"""Exercise sequencing and failure handling with stand-ins for native signing tools."""

from __future__ import annotations

from hashlib import sha256
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / "build-signed-windows-connector.py"
SPEC = importlib.util.spec_from_file_location("build_signed_windows_connector", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)

SOURCE = "a" * 40
PUBLISHER = "b" * 64
VERSION = "1.2.3-rc.1"
SIGNATURE = b"-fixture-signature"
STAMP = "https://timestamp.example.test/rfc3161"


class SignedConnectorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.output = self.root / "result"
        self.calls: list[str] = []
        self.bad_signature = False
        self.wrong_certificate = False
        self.wrong_publisher = False
        self.wrong_metadata = False
        self.wrong_architecture = False
        self.enterContext(patch.object(builder.subprocess, "check_output", side_effect=self.read_git))
        self.enterContext(patch.object(builder.subprocess, "run", side_effect=self.invoke))

    def read_git(self, args: list[str], **options) -> str:
        self.assertEqual(options["cwd"], self.root)
        if args == ["git", "show", f"{SOURCE}:desktop/package.json"]:
            return json.dumps({"version": VERSION})
        self.assertEqual(args, ["git", "rev-parse", "--verify", f"{SOURCE}^{{commit}}"])
        return SOURCE + "\n"

    def invoke(self, args: list[str], **options):
        self.assertTrue(options["check"])
        if args[:2] == ["git", "archive"]:
            self.calls.append("archive")
            self.assertEqual(args[-1], SOURCE)
            archive = Path(args[3].split("=", 1)[1])
            with ZipFile(archive, "w") as snapshot:
                snapshot.writestr("desktop/package.json", json.dumps({"version": VERSION}))
                snapshot.writestr("scripts/package-desktop-connector.py", "# fixture")
        elif args[0] == "npm.cmd":
            self.calls.append(args[1])
            if args[1] == "run":
                target = options["cwd"] / "dist" / "bin" / builder.NODE_NAME
                target.parent.mkdir(parents=True)
                header = b"MZ" + bytes(58) + (64).to_bytes(4, "little")
                machine = b"\x64\xaa" if self.wrong_architecture else b"\x64\x86"
                target.write_bytes(header + b"PE\0\0" + machine + b"node")
        elif args[:2] == ["signtool.exe", "sign"]:
            path = Path(args[-1])
            self.calls.append(f"sign {path.name}")
            self.assertEqual(args[2:-1], ["/s", "My", "/sha1", SOURCE, "/fd", "SHA256",
                                         "/tr", STAMP, "/td", "SHA256"])
            path.write_bytes(path.read_bytes() + SIGNATURE)
        elif args[:2] == ["signtool.exe", "verify"]:
            path = Path(args[-1])
            self.calls.append(f"verify {path.name}")
            self.assertEqual(args[2:-1], ["/pa", "/all", "/tw"])
            if self.bad_signature and path.name == builder.INSTALLER_NAME:
                raise subprocess.CalledProcessError(2, args)
            self.assertTrue(path.read_bytes().endswith(SIGNATURE))
        elif args[0] == "powershell.exe":
            if "TimeStamperCertificate" not in args[-1]:
                self.calls.append("inspect certificate")
                self.assertEqual(options["env"]["AYIN_CONNECTOR_CERT_SELECTOR"], SOURCE)
                pin = "0" * 64 if self.wrong_certificate else PUBLISHER
                return subprocess.CompletedProcess(args, 0, pin + "\n")
            path = Path(options["env"]["AYIN_SIGNED_CONNECTOR_ARTIFACT"])
            self.calls.append(f"inspect {path.name}")
            self.assertIn("TimeStamperCertificate", args[-1])
            node = path.name == builder.NODE_NAME
            report = {"publisher": ("0" * 64 if self.wrong_publisher else PUBLISHER),
                      "product": builder.NODE_PRODUCT if node else builder.INSTALLER_PRODUCT,
                      "description": (f"{builder.NODE_PRODUCT} {VERSION}" if node else
                                      f"{builder.INSTALLER_PRODUCT} Setup")}
            if self.wrong_metadata:
                report["description"] = "wrong version"
            return subprocess.CompletedProcess(args, 0, json.dumps(report))
        elif args[1].endswith("package-desktop-connector.py"):
            self.calls.append("package")
            self.assertEqual(args[args.index("--source-sha") + 1], SOURCE)
            self.assertEqual(args[args.index("--version") + 1], VERSION)
            node = Path(args[args.index("--binary") + 1])
            self.assertTrue(node.read_bytes().endswith(SIGNATURE))
            output = Path(args[args.index("--output") + 1])
            output.write_bytes(b"installer containing " + node.read_bytes())
        else:
            self.fail(f"unexpected call: {args[0]}")
        return subprocess.CompletedProcess(args, 0)

    def build(self, timestamp: str = STAMP) -> dict:
        return builder.build_release(
            root=self.root, source_sha=SOURCE, output=self.output,
            certificate_sha1=SOURCE, publisher_sha256=PUBLISHER, timestamp_url=timestamp,
            browser_portal="https://admin.example.test/relay/private/enroll",
            relay_url="wss://relay.example.test/ws", signtool="signtool.exe",
            makensis="makensis.exe", npm="npm.cmd", powershell="powershell.exe",
        )

    def signed_inputs(self) -> tuple[Path, dict[str, bytes]]:
        path = self.root / "signed-input"
        path.mkdir()
        header = b"MZ" + bytes(58) + (64).to_bytes(4, "little")
        node = header + b"PE\0\0\x64\x86node" + SIGNATURE
        artifacts = {builder.NODE_NAME: node, builder.INSTALLER_NAME: b"installer containing " + node + SIGNATURE}
        for name, data in artifacts.items():
            (path / name).write_bytes(data)
        return path, artifacts

    def import_release(self, signed_input: Path) -> dict:
        return builder.import_signed_release(
            root=self.root, source_sha=SOURCE, output=self.output, signed_input=signed_input,
            publisher_sha256=PUBLISHER, signtool="signtool.exe", powershell="powershell.exe",
        )

    def test_import_verifies_provider_bytes_against_the_selected_commit(self) -> None:
        signed_input, artifacts = self.signed_inputs()
        (signed_input / "release.json").write_text('{"version": "untrusted"}', encoding="utf-8")
        manifest = self.import_release(signed_input)
        self.assertEqual(self.calls, [
            f"verify {builder.NODE_NAME}", f"inspect {builder.NODE_NAME}",
            f"verify {builder.INSTALLER_NAME}", f"inspect {builder.INSTALLER_NAME}",
        ])
        self.assertEqual(set(item.name for item in self.output.iterdir()), {*artifacts, "release.json"})
        self.assertEqual(json.loads((self.output / "release.json").read_bytes()), manifest)
        self.assertEqual((manifest["source_sha"], manifest["signer_sha256"], manifest["version"]),
                         (SOURCE, PUBLISHER, VERSION))
        for label in ("node", "installer"):
            entry = manifest[label]
            data = artifacts[entry["filename"]]
            self.assertEqual((signed_input / entry["filename"]).read_bytes(), data)
            self.assertEqual((self.output / entry["filename"]).read_bytes(), data)
            self.assertEqual((entry["sha256"], entry["size"]), (sha256(data).hexdigest(), len(data)))

    def test_import_does_not_publish_when_the_wrapper_fails_verification(self) -> None:
        signed_input, _ = self.signed_inputs()
        self.bad_signature = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.import_release(signed_input)
        self.assertEqual(list(self.root.iterdir()), [signed_input])

    def test_import_requires_both_artifacts_to_be_regular_nonempty_files(self) -> None:
        signed_input, _ = self.signed_inputs()
        (signed_input / builder.INSTALLER_NAME).write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "regular file within the size limit"):
            self.import_release(signed_input)
        self.assertEqual(self.calls, [])
        self.assertEqual(list(self.root.iterdir()), [signed_input])

    def test_publishes_exact_final_signed_files_only_after_both_signatures(self) -> None:
        manifest = self.build()
        self.assertEqual(self.calls, [
            "inspect certificate", "archive", "run", f"sign {builder.NODE_NAME}", f"verify {builder.NODE_NAME}",
            f"inspect {builder.NODE_NAME}", "package", f"sign {builder.INSTALLER_NAME}",
            f"verify {builder.INSTALLER_NAME}", f"inspect {builder.INSTALLER_NAME}",
        ])
        self.assertEqual(json.loads((self.output / "release.json").read_bytes()), manifest)
        self.assertEqual((manifest["source_sha"], manifest["signer_sha256"]), (SOURCE, PUBLISHER))
        for label in ("node", "installer"):
            entry = manifest[label]
            data = (self.output / entry["filename"]).read_bytes()
            self.assertTrue(data.endswith(SIGNATURE))
            self.assertEqual((entry["sha256"], entry["size"]), (sha256(data).hexdigest(), len(data)))
        self.assertEqual(list(self.root.iterdir()), [self.output])

    def test_failed_or_untimestamped_wrapper_never_publishes(self) -> None:
        self.bad_signature = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.build()
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.iterdir()), [])

    def test_wrong_publisher_or_metadata_prevents_packaging(self) -> None:
        self.wrong_publisher = True
        with self.assertRaisesRegex(ValueError, "approved publisher"):
            self.build()
        self.wrong_publisher = False
        self.wrong_metadata = True
        with self.assertRaisesRegex(ValueError, "product or version"):
            self.build()
        self.assertNotIn("package", self.calls)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_arm64_payload_is_refused_before_signing(self) -> None:
        self.wrong_architecture = True
        with self.assertRaisesRegex(ValueError, "Windows x64"):
            self.build()
        self.assertEqual(self.calls, ["inspect certificate", "archive", "run"])

    def test_wrong_selected_certificate_never_asks_the_provider_to_sign(self) -> None:
        self.wrong_certificate = True
        with self.assertRaisesRegex(ValueError, "approved publisher"):
            self.build()
        self.assertEqual(self.calls, ["inspect certificate"])
        self.assertFalse(self.output.exists())

    def test_existing_output_and_invalid_timestamp_are_refused_before_signing(self) -> None:
        with self.assertRaisesRegex(ValueError, "timestamp"):
            self.build("http://timestamp.example.test")
        self.output.write_bytes(b"existing")
        with self.assertRaises(FileExistsError):
            self.build()
        self.assertEqual(self.calls, [])
        self.assertEqual(self.output.read_bytes(), b"existing")


if __name__ == "__main__":
    unittest.main()
