"""Serve one operator-approved signed Windows node release to selected installations."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from ..installer_release import InstallerRelease, read_owner_json
from .desktop_rollout import parse_preflight


NODE_FILENAME = "hermes-relay-win-x64.exe"


@dataclass
class PublishedNodeRelease:
    version: str
    device_ids: frozenset[str]
    path: Path
    sha256: str
    size: int
    active_downloads: int = 0


def load_published_node_release(config: Path, directory: Path) -> PublishedNodeRelease:
    value = read_owner_json(config, 8192)
    if set(value) != {"version", "device_ids", "artifact"}:
        raise ValueError("release offer must contain version, device_ids and artifact")
    version, _, device_ids = parse_preflight({"version": value["version"], "device_ids": value["device_ids"]})
    artifact = InstallerRelease.from_mapping(value["artifact"])
    snapshot = artifact.snapshot(directory, filename=NODE_FILENAME)
    try:
        with snapshot.open("rb") as source:
            dos = source.read(64)
            if len(dos) != 64 or dos[:2] != b"MZ":
                raise ValueError("malformed Windows PE")
            offset = int.from_bytes(dos[60:], "little")
            if not 64 <= offset <= 1024 * 1024:
                raise ValueError("malformed Windows PE")
            source.seek(offset)
            if source.read(6) != b"PE\0\0\x64\x86":
                raise ValueError("release offer requires a Windows x64 node executable")
        return PublishedNodeRelease(version, frozenset(device_ids), snapshot, artifact.sha256, snapshot.stat().st_size)
    except BaseException:
        snapshot.unlink(missing_ok=True)
        raise
