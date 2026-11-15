#!/usr/bin/env python3
"""Install a pinned host-native JDK using only Python 3.12's standard library."""  # noqa: EXE001

import argparse
import hashlib
import json
import shutil
import stat
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

VERSION = "21.0.13+9"
BASE_URL = (
    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9"
)
MARKER = ".setup-jdk.json"
# Official release API digests, cross-checked against each asset's .sha256.txt:
# https://api.github.com/repos/adoptium/temurin21-binaries/releases/tags/jdk-21.0.13%2B9
SHA256 = {
    (
        "linux",
        "x86_64",
    ): "d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c",
    (
        "linux",
        "aarch64",
    ): "e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578",
    (
        "linux",
        "ppc64le",
    ): "cfaedcc659213833ead9c2a50987d8dcbd0e7a2d82217abb43acb64c0dd3d8cc",
    (
        "linux",
        "riscv64",
    ): "639d712c96899ec84e95394f90e8b4cc6a0ad0154bbec2fe657f8da00b182013",
    (
        "linux",
        "s390x",
    ): "597271ef81f7e4d0e2b86aa6e4d484af194253bdf238908a8394b78970dff713",
    (
        "macos",
        "x86_64",
    ): "9aa1d1c17ae5467a022679e7e0af5c00797d94ac462ed1a46f4db07a8a34a6dd",
    (
        "macos",
        "aarch64",
    ): "bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a",
    (
        "windows",
        "x86_64",
    ): "a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9",
}
ARCH_ALIASES = {
    "amd64": "x86_64",
    "x64": "x86_64",
    "arm64": "aarch64",
    "ppc64el": "ppc64le",
}
OS_NAMES = {"linux": "Linux", "macos": "Darwin", "windows": "Windows"}


def select_asset(os_name, arch):
    arch = ARCH_ALIASES.get(arch.lower(), arch.lower())
    if (os_name, arch) not in SHA256:
        supported = ", ".join(f"{os}/{cpu}" for os, cpu in SHA256)
        raise ValueError(
            f"Unsupported target {os_name}/{arch} for Temurin {VERSION}. Supported: {supported}"
        )
    vendor_arch = "x64" if arch == "x86_64" else arch
    vendor_os = "mac" if os_name == "macos" else os_name
    extension = "zip" if os_name == "windows" else "tar.gz"
    filename = f"OpenJDK21U-jdk_{vendor_arch}_{vendor_os}_hotspot_21.0.13_9.{extension}"
    marker = {
        "os": os_name,
        "arch": arch,
        "version": VERSION,
        "sha256": SHA256[os_name, arch],
    }
    return filename, marker


def valid_home(home, target):
    """Read metadata without executing the host binary inside the tools container."""
    binary = "java.exe" if target["os"] == "windows" else "java"
    if not (home / "bin" / binary).is_file():
        return False
    try:
        release = dict(
            line.split("=", 1)
            for line in (home / "release").read_text().splitlines()
            if "=" in line
        )
        release = {key: value.strip('"') for key, value in release.items()}
    except (OSError, UnicodeError, ValueError):
        return False
    return (
        release.get("OS_NAME") == OS_NAMES[target["os"]]
        and release.get("OS_ARCH") == target["arch"]
        and release.get("JAVA_VERSION") == "21.0.13"
        and release.get("JAVA_RUNTIME_VERSION", "").removesuffix("-LTS") == VERSION
        and release.get("IMPLEMENTOR") == "Eclipse Adoptium"
    )


def valid_cache(home, target):
    if not valid_home(home, target):
        return False
    marker = home / MARKER
    if not marker.exists():  # Accept a matching pre-existing Docker extraction.
        return True
    try:
        return json.loads(marker.read_text()) == target
    except (OSError, UnicodeError, ValueError):
        return False


def download(url, archive, checksum):
    digest = hashlib.sha256()
    with (
        urllib.request.urlopen(url, timeout=60) as response,
        archive.open("wb") as output,
    ):
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != checksum:
        raise ValueError(
            "SHA256 mismatch: downloaded JDK does not match the pinned checksum"
        )


def safe_extract(archive, destination):
    def check_name(name):
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
            raise ValueError(f"Unsafe archive path: {name}")

    if archive.suffix == ".zip":
        with zipfile.ZipFile(archive) as source:
            for entry in source.infolist():
                check_name(entry.filename)
                if stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError(f"Unsafe ZIP symlink: {entry.filename}")
            source.extractall(destination)
    else:
        with tarfile.open(archive, "r:gz") as source:
            for entry in source.getmembers():
                check_name(entry.name)
            # Python 3.12's data filter also rejects escaping links and devices.
            source.extractall(destination, filter="data")


def install(root, os_name, arch):
    filename, target = select_asset(os_name, arch)
    home = Path(root) / ".tools" / "jdk-21"
    if valid_cache(home, target):
        return home
    home.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".jdk-21-", dir=home.parent) as temporary:
        staging = Path(temporary)
        archive = staging / filename
        print(
            f"Downloading Temurin {VERSION} for {os_name}/{target['arch']} ...",
            flush=True,
        )
        download(f"{BASE_URL}/{filename}", archive, target["sha256"])
        unpacked = staging / "unpacked"
        unpacked.mkdir()
        safe_extract(archive, unpacked)
        entries = list(unpacked.iterdir())
        if len(entries) != 1 or not entries[0].is_dir():
            raise ValueError("Unexpected JDK archive layout")
        candidate = (
            entries[0] / "Contents" / "Home" if os_name == "macos" else entries[0]
        )
        if not valid_home(candidate, target):
            raise ValueError(
                "Extracted JDK binary or release metadata does not match the requested target"
            )
        (candidate / MARKER).write_text(json.dumps(target, sort_keys=True) + "\n")
        # Keep the backup outside staging so even a failed rollback preserves it.
        backup = staging.with_name(staging.name + "-previous")
        had_old = home.exists() or home.is_symlink()
        if had_old:
            home.rename(backup)
        try:
            candidate.rename(home)
        except OSError:
            if had_old:
                backup.rename(home)
            raise
        if had_old:
            if backup.is_symlink() or not backup.is_dir():
                backup.unlink()
            else:
                shutil.rmtree(backup)
    return home


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--os", required=True, choices=OS_NAMES)
    parser.add_argument(
        "--arch", required=True, help="Host architecture, e.g. x86_64 or aarch64"
    )
    parser.add_argument("--root", type=Path, default=Path("/repo"))
    args = parser.parse_args(argv)
    try:
        install(args.root, args.os, args.arch)
    except (OSError, ValueError, tarfile.TarError, zipfile.BadZipFile) as error:
        parser.exit(1, f"JDK setup failed: {error}\n")
    print(f"Host JDK ready: .tools/jdk-21 (Temurin {VERSION}).")
    print(
        "Set your editor's Java home and JavaSE-21 runtime path to your host checkout's .tools/jdk-21."
    )


if __name__ == "__main__":
    main()
