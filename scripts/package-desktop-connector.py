#!/usr/bin/env python3
"""Build a credential-free Windows connector ZIP or an unsigned single EXE.

The ZIP receives setup.json later from the authenticated portal. The EXE embeds
only release settings and requires browser approval before installing.
"""
from __future__ import annotations

import argparse
import hashlib
from hashlib import file_digest
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
from typing import BinaryIO
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]
INSTALLER_DIR = ROOT / "desktop" / "connector"
INSTALLER_FILES = ("Install.cmd", "install.ps1", "Status.cmd", "Stop.cmd", "Uninstall.cmd")
_SOURCE_SHA = re.compile(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})\Z")
_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?\Z")
_CHUNK_SIZE = 1024 * 1024


def archive_entry(name: str) -> zipfile.ZipInfo:
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.create_system = 3
    entry.external_attr = (stat.S_IFREG | 0o644) << 16
    entry.compress_type = zipfile.ZIP_DEFLATED
    return entry


def installer_bytes(name: str) -> bytes:
    # Git checkout line endings vary by platform. Windows PowerShell 5.1 needs
    # a BOM to read the Chinese strings consistently; CMD files use CRLF too.
    text = (INSTALLER_DIR / name).read_text(encoding="utf-8-sig")
    normalized = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
    return (b"\xef\xbb\xbf" if name.endswith(".ps1") else b"") + normalized.encode("utf-8")


def copy_binary(source: BinaryIO, archive: zipfile.ZipFile) -> str:
    digest = hashlib.sha256()
    with archive.open(archive_entry("hermes-relay.exe"), "w", force_zip64=True) as target:
        while chunk := source.read(_CHUNK_SIZE):
            digest.update(chunk)
            target.write(chunk)
    return digest.hexdigest()


def package_template(binary: Path, output: Path, source_sha: str, version: str) -> dict[str, str]:
    if not _SOURCE_SHA.fullmatch(source_sha):
        raise ValueError("source-sha must be a full 40- or 64-character Git commit SHA")
    if not _VERSION.fullmatch(version):
        raise ValueError("version must be a semantic version")
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"output already exists: {output}")
    # Load the complete fixed allowlist before creating output. No directory
    # traversal, optional setup file or surrounding staging files are copied.
    installers = {name: installer_bytes(name) for name in INSTALLER_FILES}
    temporary: Path | None = None
    try:
        with binary.open("rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size == 0:
                raise ValueError("binary must be a nonempty regular file")
            with tempfile.NamedTemporaryFile(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent, delete=False) as stream:
                temporary = Path(stream.name)
                with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                    for name, payload in installers.items():
                        archive.writestr(archive_entry(name), payload)
                    binary_sha = copy_binary(source, archive)
                    manifest = {"sha256": binary_sha, "source_sha": source_sha.lower(), "version": version}
                    archive.writestr(archive_entry("manifest.json"), json.dumps(manifest, indent=2) + "\n")
                stream.flush()
                os.fsync(stream.fileno())
        # Publish atomically without replacing anything, including a concurrent
        # writer or dangling symlink. The temporary file shares the output's
        # directory/filesystem; filesystems without hard links fail closed.
        os.link(temporary, output)
        return manifest
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def browser_settings(portal: str, relay_url: str) -> dict[str, str]:
    for value, scheme in ((portal, "https"), (relay_url, "wss")):
        url = urlsplit(value)
        if (url.scheme != scheme or not url.hostname or url.username is not None
                or url.password is not None or url.query or url.fragment
                or re.search(r"[\x00-\x20\x7f\\]", value) or not value.isascii()):
            raise ValueError(f"expected a credential-free {scheme.upper()} endpoint")
        # Reading the port also rejects out-of-range and non-numeric ports.
        _ = url.port
    if re.fullmatch(r"/[a-zA-Z0-9/_-]+/private/enroll", urlsplit(portal).path) is None:
        raise ValueError("expected the private browser enrollment path")
    return {"portal": portal, "relay_url": relay_url}


def package_exe(binary: Path, output: Path, source_sha: str, version: str,
                portal: str, relay_url: str, makensis: str) -> dict[str, str]:
    if not _SOURCE_SHA.fullmatch(source_sha):
        raise ValueError("source-sha must be a full 40- or 64-character Git commit SHA")
    if not _VERSION.fullmatch(version):
        raise ValueError("version must be a semantic version")
    if output.suffix.lower() != ".exe":
        raise ValueError("browser installer output must end in .exe")
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"output already exists: {output}")
    endpoints = browser_settings(portal, relay_url)
    with tempfile.TemporaryDirectory(prefix=".relay-setup-", dir=output.parent) as temporary:
        stage = Path(temporary).resolve()
        payload = stage / "hermes-relay.exe"
        with binary.open("rb") as source, payload.open("xb") as target:
            if not stat.S_ISREG(os.fstat(source.fileno()).st_mode) or source.read(2) != b"MZ":
                raise ValueError("the Windows payload must be a PE executable")
            source.seek(0)
            shutil.copyfileobj(source, target)
        with payload.open("rb") as source:
            manifest = {"sha256": file_digest(source, "sha256").hexdigest(),
                        "source_sha": source_sha.lower(), "version": version}
        for name in ("launch.ps1", "install.ps1", "Stop.cmd", "Status.cmd", "Uninstall.cmd"):
            (stage / name).write_bytes(installer_bytes(name))
        (stage / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (stage / "portal.json").write_text(json.dumps(endpoints), encoding="utf-8")
        candidate = stage / "candidate.exe"
        switch = "/" if os.name == "nt" else "-"
        version_num = re.split(r"[-+]", version, maxsplit=1)[0] + ".0"
        # MakeNSIS resolves File entries relative to its script directory.
        setup_script = stage / "setup.nsi"
        shutil.copyfile(INSTALLER_DIR / setup_script.name, setup_script)
        subprocess.run([makensis, f"{switch}DOUTPUT={candidate}",
                        f"{switch}DVERSION_NUM={version_num}", f"{switch}DVERSION={version}", str(setup_script)],
                       check=True)
        os.link(candidate, output)
        return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path, help="compiled Windows connector CLI")
    parser.add_argument("--output", required=True, type=Path, help="new template ZIP or unsigned installer EXE")
    parser.add_argument("--source-sha", required=True, help="full Git SHA used to build the binary and installer")
    parser.add_argument("--version", help="desktop semantic version; defaults to desktop/package.json")
    parser.add_argument("--browser-portal", help="HTTPS /private/enroll URL to embed in the EXE")
    parser.add_argument("--relay-url", help="expected WSS identity for browser enrollment")
    parser.add_argument("--makensis", default="makensis", help="NSIS compiler path for EXE assembly")
    args = parser.parse_args(argv)
    try:
        version = args.version
        if version is None:
            version = json.loads((ROOT / "desktop" / "package.json").read_text(encoding="utf-8"))["version"]
        if not isinstance(version, str):
            raise ValueError("desktop package version must be a string")
        if args.browser_portal is not None or args.relay_url is not None:
            if not args.browser_portal or not args.relay_url:
                raise ValueError("EXE assembly requires both --browser-portal and --relay-url")
            manifest = package_exe(args.binary, args.output, args.source_sha, version,
                                   args.browser_portal, args.relay_url, args.makensis)
        else:
            if args.output.suffix.lower() == ".exe":
                raise ValueError("EXE assembly requires both --browser-portal and --relay-url")
            manifest = package_template(args.binary, args.output, args.source_sha, version)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"Connector template packaging failed: {exc}\n")
    print(f"Created {args.output} (binary SHA-256 {manifest['sha256']}, source {manifest['source_sha']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
