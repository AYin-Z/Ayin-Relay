"""Exercise desktop rollout observations over local HTTP and real Relay sockets."""

from __future__ import annotations

import uuid
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.auth import Session
from plugin.relay.config import RelayConfig
from plugin.relay.desktop_rollout import version_key
from plugin.relay.server import create_app


class DesktopRolloutTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        return create_app(RelayConfig(profile_discovery_enabled=False, port=8767))

    def new_session(self, *, desktop_only: bool = True) -> Session:
        return self.app["server"].sessions.create_session(
            "Fixture node",
            str(uuid.uuid4()),
            ttl_seconds=0,
            desktop_only=desktop_only,
        )

    async def connect(self, session: Session, **status):
        ws = await self.client.ws_connect("/ws")
        self.addAsyncCleanup(ws.close)
        await ws.send_json({"channel": "system", "type": "auth", "payload": {
            "session_token": session.token, "device_id": session.device_id,
        }})
        self.assertEqual((await ws.receive_json(timeout=2))["type"], "auth.ok")
        if status:
            await ws.send_json({"channel": "desktop", "type": "desktop.status", "payload": status})
        await ws.send_json({"channel": "system", "type": "ping", "payload": {"ts": "observation"}})
        self.assertEqual((await ws.receive_json(timeout=2))["type"], "pong")
        return ws

    async def observe(self, device_ids: list[str], version: str = "1.2.0") -> dict:
        result = await self.client.post("/desktop/rollout/preflight",
            json={"version": version, "device_ids": device_ids}, headers={"Host": "127.0.0.1:8767"})
        self.assertEqual(result.status, 200, await result.text())
        self.assertEqual(result.headers["Cache-Control"], "no-store")
        return await result.json()

    async def test_desktop_operator_routes_require_direct_loopback(self) -> None:
        local = {"Host": "127.0.0.1:8767"}
        routes = (
            ("GET", "/desktop/_ping"), ("GET", "/desktop/health"),
            ("POST", "/desktop/fixture"), ("POST", "/desktop/transfer/push"),
            ("POST", "/desktop/transfer/pull"),
        )
        for method, path in routes:
            for denied in (
                {"Host": "external.example.test"},
                {**local, "Forwarded": "for=192.0.2.1"},
                {**local, "Origin": "https://external.example.test"},
                {**local, "CF-Connecting-IP": "192.0.2.1"},
            ):
                with self.subTest(method=method, path=path, headers=denied):
                    response = await self.client.request(method, path, headers=denied)
                    self.assertEqual(response.status, 403)
        response = await self.client.get("/desktop/health", headers=local)
        self.assertEqual(response.status, 200)

    async def test_local_identity_and_selected_ids_are_required(self) -> None:
        device_id = self.new_session().device_id
        valid = {"Host": "127.0.0.1:8767"}
        body = {"version": "1.2.0", "device_ids": [device_id]}
        for headers in [
            {"Host": "other.example.test"},
            {**valid, "Origin": "https://other.example.test"},
            {**valid, "X-Forwarded-For": "192.0.2.1"},
            {**valid, "Cf-Access-Jwt-Assertion": "fixture"},
        ]:
            denied = await self.client.post("/desktop/rollout/preflight", headers=headers, json=body)
            self.assertEqual(denied.status, 403)
        for invalid in [
            {**body, "device_ids": []},
            {**body, "device_ids": [device_id, device_id]},
            {**body, "device_ids": ["fixture-node"]},
            {**body, "device_ids": [str(uuid.uuid4()) for _ in range(65)]},
            {**body, "version": "latest"},
            {**body, "version": "9.2.0-alpha.01"},
            {**body, "command": "fixture"},
        ]:
            denied = await self.client.post("/desktop/rollout/preflight", headers=valid, json=invalid)
            self.assertEqual(denied.status, 400)

    async def test_authenticated_selected_nodes_preserve_order_and_isolate_heartbeat_claims(self) -> None:
        offline = self.new_session()
        live = self.new_session()
        legacy = self.new_session(desktop_only=False)
        unselected = self.new_session()
        unknown = str(uuid.uuid4())
        await self.connect(live, platform="win32", arch="x64", version="1.2.0-rc.2",
                           node_update_protocol=1, started_at_ms=100, private_path="fixture-private")
        # This valid but unselected installation cannot make an offline peer look online.
        await self.connect(unselected, **{"device_id": offline.device_id, "platform": "win32",
                                          "arch": "x64", "version": "1.2.0", "node_update_protocol": 1})
        requested = [offline.device_id, live.device_id, unknown, legacy.device_id]
        result = await self.observe(requested)
        nodes = result["nodes"]
        self.assertEqual([item["device_id"] for item in nodes], requested)
        self.assertEqual([item["state"] for item in nodes],
                         ["offline", "update_candidate", "unregistered", "legacy_session"])
        self.assertEqual(nodes[1]["reported_version"], "1.2.0-rc.2")
        self.assertEqual(nodes[1]["reported_started_at_ms"], 100)
        self.assertNotIn("private_path", str(result))
        self.assertNotIn(live.token, str(result))
        self.assertEqual(self.app["server"].desktop.get_recent(), [])

        self.app["server"].sessions.revoke_session(live.token)
        self.assertEqual((await self.observe([live.device_id]))["nodes"][0]["state"], "unregistered")

    async def test_stale_duplicate_unsupported_and_older_observations_fail_closed(self) -> None:
        no_report, stale, duplicate, wrong, older, exact, later, invalid = [
            self.new_session() for _ in range(8)
        ]
        await self.connect(no_report)
        with patch("plugin.relay.channels.desktop.time.time", return_value=1_000.0):
            await self.connect(stale, platform="win32", arch="x64", version="1.1.0", node_update_protocol=1)
        for session, version, arch, protocol in [
            (duplicate, "1.1.0", "x64", 1),
            (wrong, "1.1.0", "arm64", 1),
            (older, "1.2.0-rc.1", "x64", True),
            (exact, "1.2.0", "x64", 1),
            (later, "1.2.1", "x64", 1),
            (invalid, {"version": "1.2.0"}, "x64", 1),
        ]:
            await self.connect(session, platform="win32", arch=arch,
                               version=version, node_update_protocol=protocol)
        await self.connect(duplicate, platform="win32", arch="x64", version="1.1.0", node_update_protocol=1)
        selected = [s.device_id for s in (no_report, stale, duplicate, wrong, older, exact, later, invalid)]
        result = await self.observe(selected)
        self.assertEqual([item["state"] for item in result["nodes"]], [
            "awaiting_status", "stale_status", "multiple_connections", "unsupported_platform",
            "update_protocol_unreported", "reported_at_target", "reported_newer", "incomplete_status",
        ])
        self.assertIsNone(result["nodes"][-1]["reported_version"])

    def test_version_order_keeps_prerelease_components(self) -> None:
        key = version_key
        self.assertLess(key("1.2.0-rc-hotfix.2"), key("1.2.0-rc-hotfix.10"))
        self.assertLess(key("1.2.0-rc.10"), key("1.2.0"))
        self.assertIsNone(key("1.2.0-rc.01"))
