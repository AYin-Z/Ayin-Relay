"""Verify the pinned Authenticode release before making its bytes available."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import os
from pathlib import Path
import re
import stat
import subprocess
from typing import Any


_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_MAX_EXE_BYTES = 128 * 1024 * 1024
_CHUNK_BYTES = 1024 * 1024


@dataclass(frozen=True)
class InstallerRelease:
    path: Path
    sha256: str
    signer_sha256: str
    trust_bundle: Path
    timestamp_trust_bundle: Path | None = None

    def __post_init__(self) -> None:
        for path in (self.path, self.trust_bundle, self.timestamp_trust_bundle):
            if path is not None and (not isinstance(path, Path) or not path.is_absolute()):
                raise ValueError("installer and trust bundle paths must be absolute")
        if not isinstance(self.path, Path) or self.path.suffix.lower() != ".exe":
            raise ValueError("installer release must be an EXE")
        if not isinstance(self.trust_bundle, Path):
            raise ValueError("an installer CA trust bundle is required")
        for digest in (self.sha256, self.signer_sha256):
            if not isinstance(digest, str) or not _SHA256.fullmatch(digest):
                raise ValueError("installer and signer digests must be lowercase SHA-256")

    @classmethod
    def from_mapping(cls, value: Any) -> InstallerRelease:
        if not isinstance(value, dict):
            raise ValueError("installer_exe must be an object")
        fields = dict(value)
        for name in ("path", "trust_bundle", "timestamp_trust_bundle"):
            if name in fields and isinstance(fields[name], str):
                fields[name] = Path(fields[name])
        return cls(**fields)

    def snapshot(self, directory: Path) -> Path:
        """Copy once and verify the private copy that the HTTP service will read."""
        snapshot = directory / "Ayin-Relay-Setup-x64.exe"
        digest = hashlib.sha256()
        created = False
        try:
            fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as source:
                info = os.fstat(source.fileno())
                if (not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= _MAX_EXE_BYTES
                        or source.read(2) != b"MZ"):
                    raise ValueError("invalid installer EXE")
                source.seek(0)
                with snapshot.open("xb") as target:
                    created = True
                    size = 0
                    while chunk := source.read(_CHUNK_BYTES):
                        size += len(chunk)
                        if size > _MAX_EXE_BYTES:
                            raise ValueError("installer exceeds the size limit")
                        digest.update(chunk)
                        target.write(chunk)
            if not hmac.compare_digest(digest.hexdigest(), self.sha256):
                raise ValueError("installer SHA-256 does not match the approved release")

            # Verify a configured local artifact without following network URLs
            # that might have been embedded in the publisher's certificates.
            command = ["osslsigncode", "verify", "-in", str(snapshot), "-CAfile", str(self.trust_bundle),
                       "-require-leaf-hash", f"sha256:{self.signer_sha256}", "-ignore-cdp"]
            if self.timestamp_trust_bundle is not None:
                command.extend(("-TSA-CAfile", str(self.timestamp_trust_bundle)))
            try:
                subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, check=True, timeout=30)
            except (OSError, subprocess.SubprocessError) as error:
                raise ValueError("installer Authenticode verification failed") from error
            return snapshot
        except BaseException:
            if created:
                snapshot.unlink(missing_ok=True)
            raise
