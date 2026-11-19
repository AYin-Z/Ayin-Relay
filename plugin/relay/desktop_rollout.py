"""Read-only Windows node release preflight based on authenticated observations."""

from __future__ import annotations

import re
from typing import Any
import uuid

from .channels.desktop import DesktopSession


# A desktop sends desktop.status every 30 seconds. Missing three reports
# prevents a stale socket from being presented as a current candidate.
_MAX_STATUS_AGE_SECONDS = 90
_MAX_TARGETS = 64
_RELEASE_VERSION = re.compile(
    r"(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})"
    r"(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?\Z",
)
VersionKey = tuple[int, int, int, bool, tuple[tuple[bool, int | str], ...]]


def version_key(value: object) -> VersionKey | None:
    if not isinstance(value, str) or len(value) > 128:
        return None
    match = _RELEASE_VERSION.fullmatch(value)
    if match is None:
        return None
    core = tuple(map(int, match.groups()[:3]))
    if max(core) > 65535:
        return None
    suffix = match.group(4)
    parts: list[tuple[bool, int | str]] = []
    if suffix is not None:
        for identifier in suffix.split("."):
            if identifier.isdigit():
                if len(identifier) > 1 and identifier[0] == "0":
                    return None
                parts.append((False, int(identifier)))
            else:
                parts.append((True, identifier))
    return core[0], core[1], core[2], suffix is None, tuple(parts)


def parse_preflight(value: object) -> tuple[str, VersionKey, list[str]]:
    if not isinstance(value, dict) or set(value) != {"version", "device_ids"}:
        raise ValueError("version and device_ids are required")
    version = value["version"]
    target = version_key(version)
    if target is None:
        raise ValueError("a published Windows release version is required")
    device_ids = value["device_ids"]
    if not isinstance(device_ids, list) or not 1 <= len(device_ids) <= _MAX_TARGETS:
        raise ValueError("provide between 1 and 64 installation IDs")
    for device_id in device_ids:
        if not isinstance(device_id, str) or len(device_id) != 36 or str(uuid.UUID(device_id)) != device_id:
            raise ValueError("device_ids must be exact installation UUIDs")
    if len(set(device_ids)) != len(device_ids):
        raise ValueError("provide each installation UUID once")
    return version, target, device_ids


def node_observation(
    device_id: str,
    registered: bool,
    connector: bool,
    connections: list[DesktopSession | None],
    target: VersionKey,
    now: float,
) -> dict[str, Any]:
    result: dict[str, Any] = {"device_id": device_id, "reported_version": None, "reported_started_at_ms": None,
                              "last_reported_at": None, "node_update_protocol": None}
    if not registered:
        return {**result, "state": "unregistered"}
    if not connector:
        return {**result, "state": "legacy_session"}
    if not connections:
        return {**result, "state": "offline"}
    if len(connections) > 1:
        return {**result, "state": "multiple_connections"}

    view = connections[0]
    if view is None or view.last_seen_at is None:
        return {**result, "state": "awaiting_status"}
    result["last_reported_at"] = view.last_seen_at
    if not 0 <= now - view.last_seen_at <= _MAX_STATUS_AGE_SECONDS:
        return {**result, "state": "stale_status"}

    status = view.client_status
    version = version_key(status.get("version"))
    if version is not None:
        result["reported_version"] = status["version"]
    started = status.get("started_at_ms")
    if type(started) is int and 0 < started <= (1 << 53) - 1:
        result["reported_started_at_ms"] = started
    platform, arch = status.get("platform"), status.get("arch")
    if not isinstance(platform, str) or not isinstance(arch, str) or version is None:
        return {**result, "state": "incomplete_status"}
    if (platform, arch) != ("win32", "x64"):
        return {**result, "state": "unsupported_platform"}
    if version == target:
        return {**result, "state": "reported_at_target"}
    if version > target:
        return {**result, "state": "reported_newer"}
    protocol = status.get("node_update_protocol")
    if type(protocol) is not int or protocol not in (1, 2):
        return {**result, "state": "update_protocol_unreported"}
    return {**result, "state": "update_candidate", "node_update_protocol": protocol}
