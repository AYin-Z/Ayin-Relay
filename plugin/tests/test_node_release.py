"""Verify the signed Windows release delivery contract."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid
from unittest.mock import patch

from aiohttp.test_utils import TestClient, TestServer

from plugin.relay.config import RelayConfig
from plugin.relay.node_release import load_published_node_release
from plugin.relay.server import create_app


OFFER = "/desktop/rollout/release"
VERSION = "1.2.0"


class NodeReleaseTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.source = root / "node.exe"
        self.config = root / "offer.json"
        self.snapshot = root / "snapshot"
        self.snapshot.mkdir()
        self.selected = str(uuid.uuid4())
        self.legacy = str(uuid.uuid4())
        dos = bytearray(68)
        dos[:2] = b"MZ"
        dos[60:64] = (68).to_bytes(4, "little")
        self.binary = bytes(dos) + b"PE\0\0\x64\x86" + b"fixture"
        self.digest = hashlib.sha256(self.binary).hexdigest()
        self.source.write_bytes(self.binary)
        self.offer = {
            "version": VERSION,
            "device_ids": [self.selected, self.legacy],
            "artifact": {
                "path": str(self.source), "sha256": self.digest,
                "signer_sha256": "a" * 64, "trust_bundle": str(root / "publisher-ca.pem"),
            },
        }
        self.config.touch(mode=0o600)
        self.write_offer()
        self.verify = self.enterContext(patch("plugin.installer_release.subprocess.run"))

    def write_offer(self) -> None:
        self.config.write_text(json.dumps(self.offer), encoding="utf-8")

    async def client(self, enabled: bool = True) -> TestClient:
        app = create_app(RelayConfig(
            port=8767, profile_discovery_enabled=False,
            windows_release_offer=str(self.config) if enabled else None,
        ))
        client = TestClient(TestServer(app))
        self.addAsyncCleanup(client.close)
        await client.start_server()
        return client

    async def test_offer_and_artifact_are_bound_to_session_and_verified_snapshot(self) -> None:
        client = await self.client()
        sessions = client.app["server"].sessions
        selected = sessions.create_session("selected", self.selected, desktop_only=True, ttl_seconds=0)
        other = sessions.create_session("other", str(uuid.uuid4()), desktop_only=True, ttl_seconds=0)
        legacy = sessions.create_session("legacy", self.legacy, desktop_only=False, ttl_seconds=0)
        auth = {"Authorization": f"Bearer {selected.token}"}
        path = f"/desktop/rollout/releases/{VERSION}/{self.digest}"
        for endpoint in (OFFER, path):
            self.assertEqual((await client.get(endpoint)).status, 401)
            for excluded in (other, legacy):
                denied = await client.get(endpoint, headers={"Authorization": f"Bearer {excluded.token}"})
                self.assertEqual(denied.status, 403)
                self.assertEqual(denied.headers["Cache-Control"], "no-store")

        duplicated = await client.get(OFFER, headers=[*auth.items(), ("Authorization", "Bearer invalid")])
        self.assertEqual(duplicated.status, 401)
        offer = await client.get(OFFER, headers=auth)
        self.assertEqual(offer.status, 200)
        self.assertEqual(offer.headers["Cache-Control"], "no-store")
        self.assertEqual(await offer.json(), {
            "version": VERSION, "sha256": self.digest, "size": len(self.binary),
            "platform": "win32", "arch": "x64",
        })
        for bad_path in (path.replace(VERSION, "1.2.1"), path.replace(self.digest, "b" * 64)):
            self.assertEqual((await client.get(bad_path, headers=auth)).status, 404)
        self.assertEqual((await client.head(path, headers=auth)).status, 405)

        self.source.write_bytes(b"replacement")
        download = await client.get(path, headers=auth)
        self.assertEqual(download.status, 200)
        self.assertEqual(download.headers["Cache-Control"], "no-store")
        self.assertEqual(int(download.headers["Content-Length"]), len(self.binary))
        self.assertEqual(await download.read(), self.binary)
        sessions.revoke_session(selected.token)
        self.assertEqual((await client.get(OFFER, headers=auth)).status, 401)
        self.assertEqual((await client.get(path, headers=auth)).status, 401)

    async def test_bad_signature_disables_offer_but_relay_starts(self) -> None:
        self.verify.side_effect = subprocess.CalledProcessError(1, "osslsigncode")
        client = await self.client()
        self.assertEqual((await client.get(OFFER)).status, 404)
        self.assertEqual((await client.get("/desktop/health")).status, 200)
        self.verify.assert_called_once()

    async def test_offer_is_disabled_without_configuration(self) -> None:
        client = await self.client(enabled=False)
        self.assertEqual((await client.get(OFFER)).status, 404)
        self.verify.assert_not_called()

    def test_source_digest_must_match_before_signature_verification(self) -> None:
        self.source.write_bytes(self.binary + b"tampered")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            load_published_node_release(self.config, self.snapshot)
        self.verify.assert_not_called()
        self.assertEqual(list(self.snapshot.iterdir()), [])

    def test_non_x64_executable_is_rejected(self) -> None:
        arm_binary = self.binary[:72] + b"\x64\xaa" + self.binary[74:]
        self.source.write_bytes(arm_binary)
        self.offer["artifact"]["sha256"] = hashlib.sha256(arm_binary).hexdigest()
        self.write_offer()
        with self.assertRaisesRegex(ValueError, "Windows x64"):
            load_published_node_release(self.config, self.snapshot)
        self.assertEqual(list(self.snapshot.iterdir()), [])

    def test_release_config_requires_owner_only_regular_file(self) -> None:
        self.config.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "owner-only"):
            load_published_node_release(self.config, self.snapshot)
        self.verify.assert_not_called()
