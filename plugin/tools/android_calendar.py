"""Optional native CalendarContract service. No UI automation or cloud calendar API."""
from __future__ import annotations

import asyncio
import json
from typing import Any

import aiohttp

from .android_tool import _bridge_token, _bridge_url


async def android_calendar(args: dict[str, Any], **kwargs: Any) -> str:
    """Forward one explicit request; timeouts NEVER trigger automatic retry."""
    if args.get("operation") not in {"calendars", "create", "get", "reschedule", "cancel"}:
        return json.dumps({"error_code": "invalid_operation"})
    if not isinstance(args.get("device"), str) or not args["device"].strip():
        return json.dumps({"error_code": "explicit_device_required"})
    token = _bridge_token()
    if not token:
        return json.dumps({"error_code": "bridge_auth_required"})
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=35)) as session:
            async with session.post(
                f"{_bridge_url().rstrip('/')}/calendar", json=args,
                headers={"Authorization": f"Bearer {token}"},
            ) as response:
                try:
                    result = await response.json()
                except (aiohttp.ContentTypeError, ValueError):
                    # Relay auth and absent-route responses can be plain text.
                    # Keep definite denials distinct from uncertain mutations.
                    denial = {401: "bridge_auth_required", 403: "bridge_permission_denied",
                              404: "calendar_unavailable"}.get(response.status)
                    if denial is None:
                        raise
                    result = {"error_code": denial}
                return json.dumps({"http_status": response.status, "result": result})
    except (asyncio.TimeoutError, aiohttp.ClientError, ValueError):
        return json.dumps({"error_code": "outcome_unknown", "retry_create": False,
                           "instruction": "Query get with the same device and external_id. Never create a new ID."})


SCHEMA: dict[str, Any] = {
    "name": "android_calendar",
    "description": (
        "Optional phone-native calendar service. Requires phone-local calendar capability, explicit "
        "calendar selection and user-granted READ/WRITE_CALENDAR; independent of screen control. "
        "Stable external_id is mandatory for event operations. Query after timeout; never retry with a new ID. "
        "Reschedule/cancel require expected_revision from get. phone_saved does not prove a notification or reading."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "operation": {"type": "string", "enum": ["calendars", "create", "get", "reschedule", "cancel"]},
            "device": {"type": "string"},
            "external_id": {"type": "string", "maxLength": 160},
            "expected_revision": {"type": "integer", "minimum": 1},
            "event_id": {"type": "integer"},
            "event": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "maxLength": 200},
                    "start_ms": {"type": "integer"}, "end_ms": {"type": "integer"},
                    "timezone": {"type": "string"},
                    "reminder_minutes": {"type": "integer", "minimum": 0, "maximum": 40320},
                },
                "required": ["title", "start_ms", "end_ms", "timezone", "reminder_minutes"],
                "additionalProperties": False,
            },
        },
        "required": ["operation", "device"],
        "additionalProperties": False,
    },
}
