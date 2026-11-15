"""Offline tests; all installations and synthetic archives live in temporary roots."""

import contextlib
import hashlib
import io
import json
import stat
import tarfile
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest.mock import patch

try:
    from . import setup_jdk as jdk
except ImportError:
    import setup_jdk as jdk


def release_text(os_name="linux", arch="x86_64", **changes):
    fields = {
        "IMPLEMENTOR": "Eclipse Adoptium",
        "JAVA_VERSION": "21.0.13",
        "JAVA_RUNTIME_VERSION": "21.0.13+9-LTS",
        "OS_NAME": jdk.OS_NAMES[os_name],
        "OS_ARCH": arch,
    }
    fields.update(changes)
    return "".join(f'{key}="{value}"\n' for key, value in fields.items())


def archive_bytes(os_name, arch="x86_64", **changes):
    prefix = "jdk-21.0.13+9/" + ("Contents/Home/" if os_name == "macos" else "")
    binary = "java.exe" if os_name == "windows" else "java"
    files = {
        prefix + "release": release_text(os_name, arch, **changes).encode(),
        prefix + "bin/" + binary: b"synthetic native java; never execute",
    }
    output = io.BytesIO()
    if os_name == "windows":
        with zipfile.ZipFile(output, "w") as archive:
            for name, content in files.items():
                archive.writestr(name, content)
    else:
        with tarfile.open(fileobj=output, mode="w:gz") as archive:
            for name, content in files.items():
                entry = tarfile.TarInfo(name)
                entry.size = len(content)
                entry.mode = 0o755 if name.endswith("/java") else 0o644
                archive.addfile(entry, io.BytesIO(content))
    return output.getvalue()


class JdkTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.home = self.root / ".tools" / "jdk-21"

    def make_home(self, os_name="linux", arch="x86_64"):
        (self.home / "bin").mkdir(parents=True)
        binary = "java.exe" if os_name == "windows" else "java"
        (self.home / "bin" / binary).write_bytes(b"old java")
        (self.home / "release").write_text(release_text(os_name, arch))
        return jdk.select_asset(os_name, arch)[1]

    def make_old_install(self):
        self.make_home()
        (self.home / jdk.MARKER).write_text('{"version": "old"}')
        (self.home / "keep").write_text("previous installation")

    def assert_old_preserved(self):
        self.assertEqual((self.home / "keep").read_text(), "previous installation")
        self.assertEqual((self.home / "bin/java").read_bytes(), b"old java")
        self.assertEqual((self.home / jdk.MARKER).read_text(), '{"version": "old"}')
        self.assertEqual(list(self.home.parent.iterdir()), [self.home])

    def test_platform_mapping_and_aliases(self):
        for os_name, arch in jdk.SHA256:
            with self.subTest(os=os_name, arch=arch):
                filename, marker = jdk.select_asset(os_name, arch)
                vendor_os = "mac" if os_name == "macos" else os_name
                vendor_arch = "x64" if arch == "x86_64" else arch
                extension = ".zip" if os_name == "windows" else ".tar.gz"
                self.assertIn(f"_{vendor_arch}_{vendor_os}_", filename)
                self.assertTrue(filename.endswith("21.0.13_9" + extension))
                self.assertEqual(marker["version"], "21.0.13+9")
                self.assertRegex(marker["sha256"], r"^[0-9a-f]{64}$")
        for alias, canonical in jdk.ARCH_ALIASES.items():
            self.assertEqual(
                jdk.select_asset("linux", alias.upper()),
                jdk.select_asset("linux", canonical),
            )

    def test_unsupported_targets_never_fall_back_or_create_directories(self):
        for os_name, arch in (
            ("windows", "arm64"),
            ("macos", "s390x"),
            ("linux", "mips"),
        ):
            with (
                self.subTest(os=os_name, arch=arch),
                patch.object(jdk.urllib.request, "urlopen") as request,
            ):
                with self.assertRaisesRegex(
                    ValueError, "Unsupported target.*Supported:.*windows/x86_64"
                ):
                    jdk.install(self.root, os_name, arch)
                request.assert_not_called()
                self.assertFalse(self.home.parent.exists())

    def test_matching_unmarked_legacy_install_is_reused_without_network(self):
        self.make_home()
        with patch.object(jdk.urllib.request, "urlopen") as request:
            self.assertEqual(jdk.install(self.root, "linux", "amd64"), self.home)
        request.assert_not_called()
        self.assertFalse((self.home / jdk.MARKER).exists())

    def test_linux_cache_cannot_satisfy_other_native_targets(self):
        self.make_home()
        for os_name, arch in (
            ("macos", "x86_64"),
            ("windows", "x86_64"),
            ("linux", "aarch64"),
        ):
            with self.subTest(os=os_name, arch=arch):
                target = jdk.select_asset(os_name, arch)[1]
                (self.home / jdk.MARKER).write_text(json.dumps(target))
                self.assertFalse(jdk.valid_cache(self.home, target))
                (self.home / jdk.MARKER).unlink()
                self.assertFalse(jdk.valid_cache(self.home, target))

    def test_wrong_or_corrupt_marker_invalidates_otherwise_matching_home(self):
        target = self.make_home()
        for content in (
            "not JSON",
            "[]",
            json.dumps({**target, "sha256": "wrong"}),
            json.dumps({**target, "os": "macos"}),
            json.dumps({**target, "arch": "aarch64"}),
        ):
            with self.subTest(content=content):
                (self.home / jdk.MARKER).write_text(content)
                self.assertFalse(jdk.valid_cache(self.home, target))
        (self.home / jdk.MARKER).write_text(json.dumps(target))
        self.assertTrue(jdk.valid_cache(self.home, target))

    def test_release_fields_are_checked_even_with_matching_marker(self):
        target = self.make_home()
        (self.home / jdk.MARKER).write_text(json.dumps(target))
        for field, value in (
            ("OS_NAME", "Darwin"),
            ("OS_ARCH", "aarch64"),
            ("JAVA_VERSION", "21.0.12"),
            ("JAVA_RUNTIME_VERSION", "21.0.13+8-LTS"),
            ("IMPLEMENTOR", "other vendor"),
        ):
            with self.subTest(field=field):
                (self.home / "release").write_text(release_text(**{field: value}))
                self.assertFalse(jdk.valid_cache(self.home, target))
        (self.home / "release").unlink()
        self.assertFalse(jdk.valid_cache(self.home, target))

    def test_windows_cache_requires_java_exe_file(self):
        target = self.make_home("windows")
        (self.home / jdk.MARKER).write_text(json.dumps(target))
        self.assertTrue(jdk.valid_cache(self.home, target))
        (self.home / "bin/java.exe").rename(self.home / "bin/java")
        self.assertFalse(jdk.valid_cache(self.home, target))
        (self.home / "bin/java.exe").mkdir()
        self.assertFalse(jdk.valid_cache(self.home, target))

    def test_tar_zip_and_flattened_mac_install_for_every_supported_target(self):
        for index, (os_name, arch) in enumerate(jdk.SHA256):
            with self.subTest(os=os_name, arch=arch):
                root = self.root / str(index)
                payload = archive_bytes(os_name, arch)
                digest = hashlib.sha256(payload).hexdigest()
                with (
                    patch.dict(jdk.SHA256, {(os_name, arch): digest}),
                    patch.object(
                        jdk.urllib.request, "urlopen", return_value=io.BytesIO(payload)
                    ) as request,
                ):
                    home = jdk.install(root, os_name, arch)
                    filename, target = jdk.select_asset(os_name, arch)
                    request.assert_called_once_with(
                        f"{jdk.BASE_URL}/{filename}", timeout=60
                    )
                    self.assertEqual(
                        json.loads((home / jdk.MARKER).read_text()), target
                    )
                    self.assertTrue(jdk.valid_cache(home, target))
                    self.assertFalse((home / "Contents").exists())
                    if os_name != "windows":
                        self.assertTrue(
                            (home / "bin/java").stat().st_mode & stat.S_IXUSR
                        )
                    request.reset_mock()
                    self.assertEqual(jdk.install(root, os_name, arch), home)
                    request.assert_not_called()
                self.assertEqual(list(home.parent.iterdir()), [home])

    def test_checksum_failure_preserves_old_installation(self):
        self.make_old_install()
        with (
            patch.object(
                jdk.urllib.request, "urlopen", return_value=io.BytesIO(b"corrupted")
            ),
            patch.object(jdk, "safe_extract") as extract,
        ):
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                jdk.install(self.root, "linux", "x86_64")
            extract.assert_not_called()
        self.assert_old_preserved()

    def test_verified_mac_install_replaces_unmarked_linux_home(self):
        self.make_home()
        (self.home / "keep").write_text("old linux installation")
        payload = archive_bytes("macos", "aarch64")
        digest = hashlib.sha256(payload).hexdigest()
        with (
            patch.dict(jdk.SHA256, {("macos", "aarch64"): digest}),
            patch.object(
                jdk.urllib.request, "urlopen", return_value=io.BytesIO(payload)
            ),
        ):
            jdk.install(self.root, "macos", "arm64")
            target = jdk.select_asset("macos", "aarch64")[1]
            self.assertTrue(jdk.valid_cache(self.home, target))
        self.assertFalse((self.home / "keep").exists())
        self.assertFalse((self.home / "Contents").exists())
        self.assertEqual(list(self.home.parent.iterdir()), [self.home])

    def test_download_failure_preserves_old_installation(self):
        self.make_old_install()
        with (
            patch.object(
                jdk.urllib.request,
                "urlopen",
                side_effect=urllib.error.URLError("offline"),
            ),
            self.assertRaises(urllib.error.URLError),
        ):
            jdk.install(self.root, "linux", "x86_64")
        self.assert_old_preserved()

    def test_bad_archive_or_metadata_preserves_old_installation(self):
        self.make_old_install()
        for payload in (b"not a tar archive", archive_bytes("linux", OS_NAME="Darwin")):
            with self.subTest(payload=payload[:20]):
                digest = hashlib.sha256(payload).hexdigest()
                with (
                    patch.dict(jdk.SHA256, {("linux", "x86_64"): digest}),
                    patch.object(
                        jdk.urllib.request, "urlopen", return_value=io.BytesIO(payload)
                    ),
                    self.assertRaises((ValueError, tarfile.TarError)),
                ):
                    jdk.install(self.root, "linux", "x86_64")
                self.assert_old_preserved()

    def test_failed_replacement_rename_rolls_back(self):
        self.make_old_install()
        payload = archive_bytes("linux")
        original_rename = Path.rename

        def fail_candidate(path, destination):
            if path.parent.name == "unpacked":
                raise OSError("simulated replacement failure")
            return original_rename(path, destination)

        with (
            patch.dict(
                jdk.SHA256, {("linux", "x86_64"): hashlib.sha256(payload).hexdigest()}
            ),
            patch.object(
                jdk.urllib.request, "urlopen", return_value=io.BytesIO(payload)
            ),
            patch.object(Path, "rename", fail_candidate),
            self.assertRaisesRegex(OSError, "replacement failure"),
        ):
            jdk.install(self.root, "linux", "x86_64")
        self.assert_old_preserved()

    def test_tar_and_zip_reject_traversal_and_foreign_absolute_paths(self):
        for extension in ("tar.gz", "zip"):
            for name in ("../escaped", "/escaped", "C:/escaped", "..\\escaped"):
                with self.subTest(extension=extension, name=name):
                    archive = self.root / ("bad." + extension)
                    destination = self.root / "unpacked"
                    destination.mkdir(exist_ok=True)
                    if extension == "zip":
                        with zipfile.ZipFile(archive, "w") as source:
                            source.writestr(name, b"bad")
                    else:
                        with tarfile.open(archive, "w:gz") as source:
                            entry = tarfile.TarInfo(name)
                            entry.size = 3
                            source.addfile(entry, io.BytesIO(b"bad"))
                    with self.assertRaisesRegex(ValueError, "Unsafe archive path"):
                        jdk.safe_extract(archive, destination)
                    self.assertEqual(list(destination.iterdir()), [])
                    self.assertFalse((self.root / "escaped").exists())

    def test_tar_rejects_escaping_links_and_devices(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE):
            with self.subTest(kind=kind):
                archive = self.root / "bad.tar.gz"
                with tarfile.open(archive, "w:gz") as source:
                    entry = tarfile.TarInfo("jdk/link")
                    entry.type = kind
                    entry.linkname = "../../escaped"
                    source.addfile(entry)
                with self.assertRaises(tarfile.FilterError):
                    jdk.safe_extract(archive, self.root / "unpacked")
                self.assertFalse((self.root / "escaped").exists())

    def test_zip_rejects_symlinks(self):
        archive = self.root / "bad.zip"
        with zipfile.ZipFile(archive, "w") as source:
            entry = zipfile.ZipInfo("jdk/link")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            source.writestr(entry, "../../escaped")
        with self.assertRaisesRegex(ValueError, "Unsafe ZIP symlink"):
            jdk.safe_extract(archive, self.root / "unpacked")

    def test_cli_defaults_to_container_root(self):
        output = io.StringIO()
        with (
            patch.object(jdk, "install") as install,
            contextlib.redirect_stdout(output),
        ):
            jdk.main(["--os", "windows", "--arch", "x86_64"])
        install.assert_called_once_with(Path("/repo"), "windows", "x86_64")

    def test_cli_root_override_preserves_editor_settings(self):
        self.make_home("windows")
        settings = self.root / ".vscode" / "settings.json"
        settings.parent.mkdir()
        settings.write_text('{"existing": true}')
        output = io.StringIO()
        with (
            contextlib.redirect_stdout(output),
            patch.object(jdk.urllib.request, "urlopen") as request,
        ):
            jdk.main(["--os", "windows", "--arch", "x86_64", "--root", str(self.root)])
        request.assert_not_called()
        self.assertIn("Host JDK ready: .tools/jdk-21", output.getvalue())
        self.assertEqual(settings.read_text(), '{"existing": true}')


if __name__ == "__main__":
    unittest.main()
