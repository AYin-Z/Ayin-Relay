"""Service API authorization and transport tests; no device or production process."""
from __future__ import annotations
import asyncio
import json
import time
from unittest import IsolatedAsyncioTestCase, TestCase, mock
from . import test_bridge_http_auth as auth_tests
from plugin.tools.android_calendar import SCHEMA, android_calendar
from plugin.relay.channels.bridge import BridgeHandler, BridgeCommandRecord


class CalendarHttpTests(auth_tests.BridgeHttpAuthorizationTests):
    async def test_calendar_auth_and_denial_propagate(self) -> None:
        dispatch = mock.AsyncMock(return_value={"status": 403, "result": {"error_code": "calendar_permission_denied"}})
        self.app["server"].bridge.handle_command = dispatch
        response = await self.client.post("/calendar", json={"operation": "create", "device": "phone"})
        self.assertEqual(response.status, 401)
        dispatch.assert_not_awaited()
        session = self._session()
        response = await self.client.post("/calendar", json={"operation": "create", "device": "phone",
            "external_id": "magic:stable"}, headers=self._bearer(session.token))
        self.assertEqual(response.status, 403)
        self.assertEqual((await response.json())["error_code"], "calendar_permission_denied")
        dispatch.assert_awaited_once_with(method="POST", path="/calendar", params={},
            body={"operation": "create", "external_id": "magic:stable"}, device="phone")

    async def test_calendar_requires_active_bridge_grant(self) -> None:
        session = self._session(); session.grants.pop("bridge")
        dispatch = mock.AsyncMock(); self.app["server"].bridge.handle_command = dispatch
        response = await self.client.post("/calendar", json={"operation": "get", "device": "phone"},
            headers=self._bearer(session.token))
        self.assertEqual(response.status, 403); dispatch.assert_not_awaited()

    async def test_calendar_requires_explicit_device(self) -> None:
        session = self._session()
        dispatch = mock.AsyncMock(); self.app["server"].bridge.handle_command = dispatch
        response = await self.client.post("/calendar", json={"operation": "get"},
            headers=self._bearer(session.token))
        self.assertEqual(response.status, 400)
        self.assertEqual((await response.json())["error_code"], "explicit_device_required")
        dispatch.assert_not_awaited()

    async def test_conflict_and_cancellation_readback_pass_through(self) -> None:
        session = self._session()
        for status, result in [(409, {"error_code": "user_changed_or_deleted"}),
                               (200, {"state": "cancelled", "phone_saved": False, "user_read": None})]:
            self.app["server"].bridge.handle_command = mock.AsyncMock(return_value={"status": status, "result": result})
            response = await self.client.post("/calendar", json={"operation": "get", "device": "phone", "external_id": "magic:stable"},
                headers=self._bearer(session.token))
            self.assertEqual(response.status, status)
            self.assertEqual(await response.json(), result)


    async def test_invalid_and_expired_authority_never_dispatches(self) -> None:
        dispatch = mock.AsyncMock()
        self.app["server"].bridge.handle_command = dispatch
        expired_session = self._session()
        expired_session.expires_at = time.time() - 1
        expired_grant = self._session()
        expired_grant.grants["bridge"] = time.time() - 1
        for token, expected in [("invalid-token", 401), (expired_session.token, 401),
                                (expired_grant.token, 403)]:
            with self.subTest(expected=expected):
                response = await self.client.post("/calendar",
                    json={"operation": "get", "device": "phone", "external_id": "stable:1"},
                    headers=self._bearer(token))
                self.assertEqual(response.status, expected)
        dispatch.assert_not_awaited()

    async def test_invalid_selector_and_wrong_method_never_dispatch(self) -> None:
        session = self._session()
        dispatch = mock.AsyncMock()
        self.app["server"].bridge.handle_command = dispatch
        for selector in ["", "  ", 7, True, [], {}]:
            with self.subTest(selector=selector):
                response = await self.client.post("/calendar",
                    json={"operation": "get", "device": selector},
                    headers=self._bearer(session.token))
                self.assertEqual(response.status, 400)
        for method in ["GET", "PUT", "DELETE"]:
            response = await self.client.request(method, "/calendar?device=phone",
                headers=self._bearer(session.token))
            self.assertEqual(response.status, 405)
        dispatch.assert_not_awaited()

    async def test_reschedule_preserves_official_fields(self) -> None:
        body = {"operation": "reschedule", "external_id": "stable:1",
                "expected_revision": 2, "event_id": 17,
                "event": {"title": "Test reminder", "start_ms": 2000000000000,
                          "end_ms": 2000003600000, "timezone": "UTC", "reminder_minutes": 10}}
        dispatch = mock.AsyncMock(return_value={"status": 200, "result": {"state": "saved", "revision": 3}})
        self.app["server"].bridge.handle_command = dispatch
        session = self._session()
        response = await self.client.post("/calendar", json={**body, "device": "phone"},
            headers=self._bearer(session.token))
        self.assertEqual(response.status, 200)
        dispatch.assert_awaited_once_with(method="POST", path="/calendar", params={},
            body=body, device="phone")


class CalendarToolTests(IsolatedAsyncioTestCase):
    async def test_missing_token_fails_closed(self) -> None:
        with mock.patch("plugin.tools.android_calendar._bridge_token", return_value=None):
            result = json.loads(await android_calendar({"operation": "create", "device": "phone"}))
        self.assertEqual(result["error_code"], "bridge_auth_required")

    async def test_timeout_is_unknown_without_retry(self) -> None:
        with mock.patch("plugin.tools.android_calendar._bridge_token", return_value="test"), mock.patch(
            "plugin.tools.android_calendar.aiohttp.ClientSession.post", side_effect=asyncio.TimeoutError) as post:
            result = json.loads(await android_calendar({"operation": "create", "device": "phone", "external_id": "magic:stable"}))
        self.assertEqual(result["error_code"], "outcome_unknown")
        self.assertFalse(result["retry_create"]); post.assert_called_once()


    async def test_invalid_operation_or_selector_never_posts(self) -> None:
        with mock.patch("plugin.tools.android_calendar.aiohttp.ClientSession.post") as post:
            for args in [{"operation": "grant", "device": "phone"},
                         {"operation": "create"}, {"operation": "get", "device": " "}]:
                result = json.loads(await android_calendar(args))
                self.assertIn(result["error_code"], {"invalid_operation", "explicit_device_required"})
            post.assert_not_called()

    async def test_authenticated_payload_and_http_error_are_preserved(self) -> None:
        args = {"operation": "cancel", "device": "phone", "external_id": "stable:1",
                "expected_revision": 2, "event_id": 17}
        response = mock.Mock(status=409)
        response.json = mock.AsyncMock(return_value={"error_code": "revision_conflict", "revision": 3})
        context = mock.AsyncMock()
        context.__aenter__.return_value = response
        with mock.patch("plugin.tools.android_calendar._bridge_token", return_value="unit-test-token"), mock.patch(
                "plugin.tools.android_calendar._bridge_url", return_value="http://127.0.0.1:8767/"), mock.patch(
                "plugin.tools.android_calendar.aiohttp.ClientSession.post", return_value=context) as post:
            result = json.loads(await android_calendar(args))
        post.assert_called_once_with("http://127.0.0.1:8767/calendar", json=args,
            headers={"Authorization": "Bearer unit-test-token"})
        self.assertEqual(result, {"http_status": 409, "result": {"error_code": "revision_conflict", "revision": 3}})


class CalendarRegistrationTests(TestCase):
    def test_calendar_and_existing_transfer_tools_register_together(self) -> None:
        import plugin
        ctx = mock.Mock()
        with mock.patch("plugin.slash.register_slash_commands"), mock.patch("plugin.hooks.register_hooks"), mock.patch(
                "plugin.phone_platform.register_phone_platform"), mock.patch(
                "plugin.enhancements.context_injection.register_context_sections", return_value=True):
            plugin.register(ctx)
        registrations = {call.kwargs["name"]: call.kwargs for call in ctx.register_tool.call_args_list}
        calendar = registrations["android_calendar"]
        self.assertIs(calendar["handler"], android_calendar)
        self.assertEqual(calendar["schema"], SCHEMA)
        self.assertEqual(calendar["toolset"], "android")
        self.assertTrue(calendar["check_fn"]())
        self.assertIn("desktop_push_file", registrations)
        self.assertIn("desktop_pull_file", registrations)
        self.assertEqual(SCHEMA["parameters"]["required"], ["operation", "device"])
        self.assertEqual(set(SCHEMA["parameters"]["properties"]),
            {"operation", "device", "external_id", "expected_revision", "event_id", "event"})


class CalendarAuditTests(TestCase):
    def test_private_calendar_payload_is_not_in_audit_summary(self) -> None:
        handler = BridgeHandler()
        record = BridgeCommandRecord("test", "POST", "/calendar")
        handler.recent_commands.append(record)
        handler._update_record_from_response("test", {"status": 200, "result": {
            "state": "saved", "event": {"title": "Private calendar title"},
            "marker": "private-marker", "phone_saved": True, "revision": 1}})
        self.assertEqual(json.loads(record.result_summary), {"state": "saved", "revision": 1, "phone_saved": True})
        self.assertNotIn("Private", record.result_summary)
        self.assertNotIn("private-marker", record.result_summary)


    def test_error_and_nonobject_response_do_not_leak_calendar_content(self) -> None:
        handler = BridgeHandler()
        record = BridgeCommandRecord("test", "POST", "/calendar")
        handler.recent_commands.append(record)
        handler._update_record_from_response("test", {"status": 403,
            "error": "Private calendar title", "result": ["Private calendar title"]})
        self.assertEqual(record.error, "calendar_response_error")
        self.assertEqual(json.loads(record.result_summary), {"error_code": "invalid_calendar_response"})
