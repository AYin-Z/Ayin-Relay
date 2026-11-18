"""Persist one explicitly activated, verified Windows release rollout."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
import json
import os
from pathlib import Path
import re
import stat
import tempfile
from typing import Any
import uuid

from ..installer_release import read_owner_json
from .desktop_rollout import parse_preflight, version_key
from .node_release import PublishedNodeRelease


_MAX_BYTES = 32 * 1024
_ID = re.compile(r"[0-9a-f]{32}\Z")
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def _matches(pattern: re.Pattern[str], value: object) -> bool:
    return isinstance(value, str) and pattern.fullmatch(value) is not None


@dataclass(frozen=True)
class RolloutReceipt:
    rollout_id: str
    version: str
    sha256: str
    attempt_id: str
    state: str
    completed_at: int

    @classmethod
    def parse(cls, value: object) -> RolloutReceipt:
        if not isinstance(value, dict) or set(value) != set(cls.__annotations__):
            raise ValueError("invalid rollout receipt fields")
        if (not _matches(_ID, value["rollout_id"]) or not _matches(_ID, value["attempt_id"])
                or not _matches(_SHA256, value["sha256"]) or version_key(value["version"]) is None
                or value["state"] not in ("applied", "rolled_back")
                or type(value["completed_at"]) is not int or not 0 < value["completed_at"] < (1 << 53)):
            raise ValueError("invalid rollout receipt")
        return cls(**value)

    def json(self) -> dict[str, Any]:
        return vars(self)


@dataclass(frozen=True)
class ManagedRollout:
    rollout_id: str
    version: str
    sha256: str
    device_ids: tuple[str, ...]
    receipts: dict[str, RolloutReceipt]

    def matches(self, receipt: RolloutReceipt) -> bool:
        return (receipt.rollout_id, receipt.version, receipt.sha256) == (self.rollout_id, self.version, self.sha256)

    @classmethod
    def load(cls, path: Path, release: PublishedNodeRelease) -> ManagedRollout | None:
        try:
            value = read_owner_json(path, _MAX_BYTES)
        except FileNotFoundError:
            return None
        if set(value) != set(cls.__annotations__):
            raise ValueError("invalid persisted rollout fields")
        version, _, device_ids = parse_preflight({"version": value["version"], "device_ids": value["device_ids"]})
        if not _matches(_ID, value["rollout_id"]) or not _matches(_SHA256, value["sha256"]):
            raise ValueError("invalid persisted rollout identity")
        if (version, value["sha256"]) != (release.version, release.sha256) or not set(device_ids) <= release.device_ids:
            return None
        rollout = cls(value["rollout_id"], version, value["sha256"], tuple(device_ids), {})
        receipts = value["receipts"]
        if not isinstance(receipts, dict) or not set(receipts) <= set(device_ids):
            raise ValueError("invalid persisted rollout receipts")
        for device, value in receipts.items():
            receipt = RolloutReceipt.parse(value)
            if not rollout.matches(receipt):
                raise ValueError("persisted receipt is for another rollout")
            rollout.receipts[device] = receipt
        return rollout

    def json(self) -> dict[str, Any]:
        return {**vars(self), "receipts": {key: receipt.json() for key, receipt in self.receipts.items()}}


def _save(path: Path, rollout: ManagedRollout) -> None:
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(rollout.json(), stream, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


class RolloutStore:
    def __init__(self, path: Path, release: PublishedNodeRelease) -> None:
        if not path.is_absolute() or path.is_symlink():
            raise ValueError("rollout state path must be absolute and local")
        parent = path.parent.stat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid() or parent.st_mode & 0o022:
            raise ValueError("rollout state parent must be controlled by the Relay service user")
        self.path = path
        self.current = ManagedRollout.load(path, release)
        self.lock = asyncio.Lock()

    async def _persist(self, rollout: ManagedRollout) -> None:
        writer = asyncio.create_task(asyncio.to_thread(_save, self.path, rollout))
        try:
            await asyncio.shield(writer)
        except asyncio.CancelledError:
            await writer
            self.current = rollout
            raise
        self.current = rollout

    async def start(self, release: PublishedNodeRelease, devices: tuple[str, ...]) -> ManagedRollout:
        async with self.lock:
            if self.current:
                if frozenset(self.current.device_ids) != frozenset(devices):
                    raise ValueError("already activated for another selection")
                return self.current
            rollout = ManagedRollout(uuid.uuid4().hex, release.version, release.sha256, devices, {})
            await self._persist(rollout)
            return rollout

    async def record(self, device_id: str, receipt: RolloutReceipt) -> None:
        async with self.lock:
            rollout = self.current
            if rollout is None or device_id not in rollout.device_ids or not rollout.matches(receipt):
                raise LookupError("receipt has no active assignment")
            prior = rollout.receipts.get(device_id)
            if prior == receipt:
                return
            if prior is not None:
                raise ValueError("assignment already has a terminal receipt")
            await self._persist(replace(rollout, receipts={**rollout.receipts, device_id: receipt}))
