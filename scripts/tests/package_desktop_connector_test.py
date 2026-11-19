"""Credential-free connector archive and atomic publication contracts."""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "package-desktop-connector.py"
spec = importlib.util.spec_from_file_location("package_desktop_connector", SCRIPT)
assert spec is not None and spec.loader is not None
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)
SOURCE_SHA = "a" * 40


class ConnectorPackageTests(unittest.TestCase):
    def test_flat_template_contains_only_allowlist_and_matches_archived_binary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.exe"
            payload = b"MZ" + bytes(range(256)) * 8193
            binary.write_bytes(payload)
            (root / "setup.json").write_text('{"invite":"must-not-enter-the-template"}')
            output = root / "template.zip"
            manifest = packager.package_template(binary, output, SOURCE_SHA.upper(), "1.2.3-rc.1")
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(archive.namelist()), {"Install.cmd", "install.ps1", "Status.cmd", "Stop.cmd", "Uninstall.cmd", "hermes-relay.exe", "manifest.json"})
                self.assertEqual(archive.read("hermes-relay.exe"), payload)
                self.assertEqual(json.loads(archive.read("manifest.json")), manifest)
                self.assertEqual(manifest, {"sha256": hashlib.sha256(payload).hexdigest(), "source_sha": SOURCE_SHA, "version": "1.2.3-rc.1"})
                script = archive.read("install.ps1")
                self.assertTrue(script.startswith(b"\xef\xbb\xbf"))
                for name in packager.INSTALLER_FILES:
                    data = archive.read(name)
                    self.assertNotIn(b"\r\r\n", data)
                    self.assertNotIn(b"\n", data.replace(b"\r\n", b""))
                self.assertEqual(archive.testzip(), None)
            self.assertEqual(list(root.glob(".template.zip.*.tmp")), [])

    def test_exe_stages_only_the_fixed_payload_and_never_publishes_a_failed_build(self) -> None:
        portal = "https://admin.example.test/relay/private/enroll"
        relay = "wss://relay.example.test/ws"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary, output = root / "node.exe", root / "setup.exe"
            binary.write_bytes(b"MZ test payload")
            (root / "setup.json").write_text('{"invite":"not-for-redistribution"}')

            def compile_stub(args: list[str], *, check: bool) -> None:
                self.assertTrue(check)
                script = Path(args[-1])
                staging = script.parent
                self.assertEqual(script.name, "setup.nsi")
                self.assertIn('File "launch.ps1"', script.read_text(encoding="utf-8"))
                self.assertEqual({entry.name for entry in staging.iterdir()},
                                 {"launch.ps1", "install.ps1", "Stop.cmd", "Status.cmd", "Uninstall.cmd",
                                  "hermes-relay.exe", "manifest.json", "portal.json", "setup.nsi"})
                self.assertEqual(json.loads((staging / "portal.json").read_bytes()),
                                 {"portal": portal, "relay_url": relay})
                self.assertEqual((staging / "hermes-relay.exe").read_bytes(), binary.read_bytes())
                manifest = json.loads((staging / "manifest.json").read_bytes())
                self.assertEqual(manifest["sha256"], hashlib.sha256(binary.read_bytes()).hexdigest())
                Path(args[1].split("=", 1)[1]).write_bytes(b"MZ produced executable")

            args = (binary, output, SOURCE_SHA, "1.2.3", portal, relay, "makensis")
            with patch.object(packager.subprocess, "run", side_effect=OSError("missing compiler")):
                with self.assertRaises(OSError):
                    packager.package_exe(*args)
                self.assertFalse(output.exists())
                self.assertEqual(list(root.glob(".relay-setup-*")), [])
            with patch.object(packager.subprocess, "run", side_effect=compile_stub):
                packager.package_exe(*args)
                self.assertEqual(output.read_bytes(), b"MZ produced executable")
                with self.assertRaises(FileExistsError):
                    packager.package_exe(*args)
            self.assertEqual(list(root.glob(".relay-setup-*")), [])

    def test_exe_settings_require_trusted_endpoint_shapes(self) -> None:
        portal = "https://admin.example.test/relay/private/enroll"
        relay = "wss://relay.example.test"
        for url in ("http://admin.example.test/relay/private/enroll", portal + "?proof=x",
                    portal.replace("admin.", "u:p@admin."), portal.replace("/enroll", "/download")):
            with self.subTest(portal=url), self.assertRaises(ValueError):
                packager.browser_settings(url, relay)
        for url in ("ws://relay.example.test", relay + "#fragment", "wss://relay.example.test:65536"):
            with self.subTest(relay=url), self.assertRaises(ValueError):
                packager.browser_settings(portal, url)

    def test_existing_output_and_symlink_are_never_replaced(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.exe"
            binary.write_bytes(b"MZtest")
            output = root / "template.zip"
            output.write_bytes(b"existing artifact")
            with self.assertRaises(FileExistsError):
                packager.package_template(binary, output, SOURCE_SHA, "1.2.3")
            self.assertEqual(output.read_bytes(), b"existing artifact")
            output.unlink()
            output.symlink_to(root / "missing-target")
            with self.assertRaises(FileExistsError):
                packager.package_template(binary, output, SOURCE_SHA, "1.2.3")
            self.assertTrue(output.is_symlink())

    def test_concurrent_output_creation_is_not_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.exe"
            binary.write_bytes(b"MZtest")
            output = root / "template.zip"
            original_link = packager.os.link

            def racing_link(source: Path, destination: Path) -> None:
                destination.write_bytes(b"other packager won")
                original_link(source, destination)

            with patch.object(packager.os, "link", side_effect=racing_link):
                with self.assertRaises(FileExistsError):
                    packager.package_template(binary, output, SOURCE_SHA, "1.2.3")
            self.assertEqual(output.read_bytes(), b"other packager won")
            self.assertEqual(list(root.glob(".template.zip.*.tmp")), [])

    def test_invalid_metadata_and_failed_reads_do_not_publish_partial_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.exe"
            binary.write_bytes(b"MZtest")
            output = root / "template.zip"
            for source_sha, version in [("short", "1.2.3"), (SOURCE_SHA, "not a version")]:
                with self.assertRaises(ValueError):
                    packager.package_template(binary, output, source_sha, version)
                self.assertFalse(output.exists())
            with patch.object(packager, "copy_binary", side_effect=OSError("input read failed")):
                with self.assertRaises(OSError):
                    packager.package_template(binary, output, SOURCE_SHA, "1.2.3")
            self.assertFalse(output.exists())
            self.assertEqual(list(root.glob(".template.zip.*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
