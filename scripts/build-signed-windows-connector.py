"""Prepare a signed Ayin-Relay node and private installer from one Git commit."""

from __future__ import annotations

import argparse
from hashlib import file_digest
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from typing import Any
from urllib.parse import urlsplit
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
NODE_NAME = "hermes-relay-win-x64.exe"
INSTALLER_NAME = "Ayin-Relay-Setup-x64.exe"
NODE_PRODUCT = "Ayin-Relay Windows Node"
INSTALLER_PRODUCT = "Ayin-Relay Windows Connector"
MAX_ARTIFACT_BYTES = 128 * 1024 * 1024

_PUBLISHER_HASH = r"""
$ErrorActionPreference = 'Stop'
function Get-PublisherHash($certificate) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($hash.ComputeHash($certificate.RawData))).Replace('-','').ToLowerInvariant()
    } finally {
        $hash.Dispose()
    }
}
"""

_INSPECT_CERTIFICATE = _PUBLISHER_HASH + r"""
$certificate = Get-Item -LiteralPath ('Cert:\CurrentUser\My\' + $env:AYIN_CONNECTOR_CERT_SELECTOR)
if (-not $certificate.HasPrivateKey) { throw 'The signing provider has no private key for this certificate' }
Get-PublisherHash $certificate
"""

_INSPECT_SIGNATURE = _PUBLISHER_HASH + r"""
$path = $env:AYIN_SIGNED_CONNECTOR_ARTIFACT
$signature = Get-AuthenticodeSignature -LiteralPath $path
if ($signature.Status -ne 'Valid' -or -not $signature.SignerCertificate -or -not $signature.TimeStamperCertificate) {
    throw 'A valid Authenticode signature and trusted timestamp are required'
}
$metadata = [Diagnostics.FileVersionInfo]::GetVersionInfo($path)
@{ publisher = (Get-PublisherHash $signature.SignerCertificate); product = $metadata.ProductName; description = $metadata.FileDescription } | ConvertTo-Json -Compress
"""

def _tool(name: str) -> str:
    resolved = shutil.which(name)
    if resolved is None:
        raise ValueError(f"required tool not found: {name}")
    return str(Path(resolved).resolve())


def _validate_timestamp_url(raw: str) -> None:
    endpoint = urlsplit(raw)
    if (endpoint.scheme != "https" or not endpoint.hostname or endpoint.username is not None
            or endpoint.password is not None or endpoint.query or endpoint.fragment
            or any(char.isspace() or char == "\\" for char in raw)):
        raise ValueError("use an HTTPS RFC 3161 timestamp URL without credentials, query or fragment")


def _require_x64(path: Path) -> None:
    with path.open("rb") as stream:
        header = stream.read(64)
        if len(header) != 64 or header[:2] != b"MZ":
            raise ValueError("node build is not a Windows executable")
        offset = int.from_bytes(header[60:], "little")
        if not 64 <= offset <= 1024 * 1024:
            raise ValueError("node build has an invalid Windows executable header")
        stream.seek(offset)
        if stream.read(6) != b"PE\0\0\x64\x86":
            raise ValueError("node build does not target Windows x64")


def _inspect(powershell: str, script: str, **parameters: str) -> str:
    try:
        process = subprocess.run(
            [powershell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script],
            env={**os.environ, **parameters}, check=True, text=True, capture_output=True,
        )
    except subprocess.CalledProcessError as error:
        raise ValueError("Windows rejected the signing certificate or artifact signature") from error
    return process.stdout


def _sign(
    path: Path,
    *,
    signtool: str,
    powershell: str,
    certificate_sha1: str,
    publisher_sha256: str,
    timestamp_url: str,
    product: str,
    description: str,
) -> dict[str, Any]:
    subprocess.run(
        [signtool, "sign", "/s", "My", "/sha1", certificate_sha1, "/fd", "SHA256",
         "/tr", timestamp_url, "/td", "SHA256", str(path)],
        check=True,
    )
    return _verify_signed_artifact(
        path, signtool=signtool, powershell=powershell, publisher_sha256=publisher_sha256,
        product=product, description=description,
    )


def _verify_signed_artifact(
    path: Path,
    *,
    signtool: str,
    powershell: str,
    publisher_sha256: str,
    product: str,
    description: str,
) -> dict[str, Any]:
    # Without a catalog selector, SignTool verifies the embedded signature.
    # /tw warns on a missing timestamp, and check=True also rejects warnings.
    subprocess.run([signtool, "verify", "/pa", "/all", "/tw", str(path)], check=True)
    info = json.loads(_inspect(powershell, _INSPECT_SIGNATURE, AYIN_SIGNED_CONNECTOR_ARTIFACT=str(path)))
    if info["publisher"] != publisher_sha256:
        raise ValueError("signed artifact does not match the approved publisher")
    if (info["product"], info["description"]) != (product, description):
        raise ValueError(f"signed artifact has unexpected product or version metadata: {path.name}")
    with path.open("rb") as stream:
        size = os.fstat(stream.fileno()).st_size
        if not 0 < size <= MAX_ARTIFACT_BYTES:
            raise ValueError(f"signed artifact exceeds the portal or update size limit: {path.name}")
        return {"filename": path.name, "sha256": file_digest(stream, "sha256").hexdigest(), "size": size}


def _validate_release_request(root: Path, source_sha: str, output: Path, publisher_sha256: str) -> Path:
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("source SHA must be the full lowercase Git commit SHA")
    if not re.fullmatch(r"[0-9a-f]{64}", publisher_sha256):
        raise ValueError("publisher pin must be the lowercase SHA-256 of its DER certificate")
    output = output.absolute()
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"release output already exists: {output}")

    verified = subprocess.check_output(
        ["git", "rev-parse", "--verify", f"{source_sha}^{{commit}}"], cwd=root, text=True,
    ).strip()
    if verified != source_sha:
        raise ValueError("source SHA must identify a commit")
    return output


def _publish_release(
    release: Path,
    output: Path,
    *,
    source_sha: str,
    version: str,
    publisher_sha256: str,
    node_info: dict[str, Any],
    installer_info: dict[str, Any],
) -> dict[str, Any]:
    manifest = {"source_sha": source_sha, "version": version, "signer_sha256": publisher_sha256,
                "node": node_info, "installer": installer_info}
    (release / "release.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    # Windows rename refuses an existing destination. Keep the unpublished
    # files on the same volume so publication moves the directory as a unit.
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"release output already exists: {output}")
    os.rename(release, output)
    return manifest


def import_signed_release(
    *,
    root: Path,
    source_sha: str,
    output: Path,
    signed_input: Path,
    publisher_sha256: str,
    signtool: str,
    powershell: str,
) -> dict[str, Any]:
    output = _validate_release_request(root, source_sha, output, publisher_sha256)
    package = subprocess.check_output(
        ["git", "show", f"{source_sha}:desktop/package.json"], cwd=root, text=True, encoding="utf-8",
    )
    version = json.loads(package)["version"]
    if not isinstance(version, str):
        raise ValueError("desktop source has no release version")

    with tempfile.TemporaryDirectory(prefix=".ayin-connector-release-", dir=output.parent) as temporary:
        release = Path(temporary) / "release"
        release.mkdir()
        for name in (NODE_NAME, INSTALLER_NAME):
            artifact = signed_input / name
            info = artifact.lstat()
            if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= MAX_ARTIFACT_BYTES:
                raise ValueError(f"signed input must be a regular file within the size limit: {name}")
            shutil.copyfile(artifact, release / name)

        node = release / NODE_NAME
        _require_x64(node)
        node_info = _verify_signed_artifact(
            node, signtool=signtool, powershell=powershell, publisher_sha256=publisher_sha256,
            product=NODE_PRODUCT, description=f"{NODE_PRODUCT} {version}",
        )
        installer_info = _verify_signed_artifact(
            release / INSTALLER_NAME, signtool=signtool, powershell=powershell, publisher_sha256=publisher_sha256,
            product=INSTALLER_PRODUCT, description=f"{INSTALLER_PRODUCT} Setup",
        )
        return _publish_release(
            release, output, source_sha=source_sha, version=version, publisher_sha256=publisher_sha256,
            node_info=node_info, installer_info=installer_info,
        )


def build_release(
    *,
    root: Path,
    source_sha: str,
    output: Path,
    certificate_sha1: str,
    publisher_sha256: str,
    timestamp_url: str,
    browser_portal: str,
    relay_url: str,
    signtool: str,
    makensis: str,
    npm: str,
    powershell: str,
) -> dict[str, Any]:
    if not re.fullmatch(r"[0-9a-fA-F]{40}", certificate_sha1):
        raise ValueError("certificate selector must be the 40-character Windows thumbprint")
    _validate_timestamp_url(timestamp_url)
    output = _validate_release_request(root, source_sha, output, publisher_sha256)

    certificate_pin = _inspect(
        powershell, _INSPECT_CERTIFICATE, AYIN_CONNECTOR_CERT_SELECTOR=certificate_sha1,
    ).strip()
    if certificate_pin != publisher_sha256:
        raise ValueError("selected certificate does not match the approved publisher")

    signing = {
        "signtool": signtool, "powershell": powershell, "certificate_sha1": certificate_sha1,
        "publisher_sha256": publisher_sha256, "timestamp_url": timestamp_url,
    }
    with tempfile.TemporaryDirectory(prefix=".ayin-connector-release-", dir=output.parent) as temporary:
        work = Path(temporary)
        archive = work / "source.zip"
        source = work / "source"
        release = work / "release"
        release.mkdir()
        subprocess.run(["git", "archive", "--format=zip", f"--output={archive}", source_sha],
                       cwd=root, check=True)
        with ZipFile(archive) as snapshot:
            snapshot.extractall(source)
        desktop = source / "desktop"
        version = json.loads((desktop / "package.json").read_text(encoding="utf-8"))["version"]
        if not isinstance(version, str):
            raise ValueError("desktop source has no release version")
        subprocess.run([npm, "run", "build:bin:win"], cwd=desktop, check=True)
        node = release / NODE_NAME
        shutil.copyfile(desktop / "dist" / "bin" / NODE_NAME, node)
        _require_x64(node)
        node_info = _sign(node, **signing, product=NODE_PRODUCT, description=f"{NODE_PRODUCT} {version}")

        installer = release / INSTALLER_NAME
        subprocess.run([
            sys.executable, str(source / "scripts" / "package-desktop-connector.py"),
            "--binary", str(node), "--output", str(installer), "--source-sha", source_sha,
            "--version", version, "--browser-portal", browser_portal, "--relay-url", relay_url,
            "--makensis", makensis,
        ], cwd=source, check=True)
        installer_info = _sign(installer, **signing, product=INSTALLER_PRODUCT,
                               description=f"{INSTALLER_PRODUCT} Setup")

        return _publish_release(
            release, output, source_sha=source_sha, version=version, publisher_sha256=publisher_sha256,
            node_info=node_info, installer_info=installer_info,
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-sha", required=True, help="full commit SHA selected for release")
    parser.add_argument("--output", required=True, type=Path, help="new directory under an existing parent")
    parser.add_argument("--signed-input", type=Path, help="directory containing the provider-signed node and installer")
    parser.add_argument("--certificate-sha1", help="certificate selector in CurrentUser\\My")
    parser.add_argument("--publisher-sha256", required=True, help="approved signing certificate's DER SHA-256")
    parser.add_argument("--timestamp-url", help="HTTPS RFC 3161 timestamp service")
    parser.add_argument("--browser-portal", help="HTTPS private enrollment URL for this deployment")
    parser.add_argument("--relay-url", help="WSS Relay origin expected by the installer")
    parser.add_argument("--signtool", default="signtool.exe")
    parser.add_argument("--makensis", default="makensis.exe")
    args = parser.parse_args(argv)
    local_options = (args.certificate_sha1, args.timestamp_url, args.browser_portal, args.relay_url)
    if args.signed_input is not None:
        if any(option is not None for option in local_options):
            parser.error("--signed-input cannot be combined with local signing and packaging options")
    elif not all(local_options):
        parser.error("local builds require --certificate-sha1, --timestamp-url, --browser-portal and --relay-url")
    if os.name != "nt":
        parser.exit(1, "Signed connector release preparation requires native Windows.\n")
    try:
        common = {
            "root": ROOT, "source_sha": args.source_sha, "output": args.output,
            "publisher_sha256": args.publisher_sha256, "signtool": _tool(args.signtool),
            "powershell": _tool(str(Path(os.environ["SystemRoot"]) / "System32" / "WindowsPowerShell"
                                    / "v1.0" / "powershell.exe")),
        }
        if args.signed_input is not None:
            report = import_signed_release(**common, signed_input=args.signed_input)
        else:
            report = build_release(
                **common, certificate_sha1=args.certificate_sha1, timestamp_url=args.timestamp_url,
                browser_portal=args.browser_portal, relay_url=args.relay_url,
                makensis=_tool(args.makensis), npm=_tool("npm.cmd"),
            )
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Signed connector release failed: {error}\n")
    print(f"Verified Ayin-Relay Windows {report['version']} release prepared in {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
