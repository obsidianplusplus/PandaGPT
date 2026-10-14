#!/usr/bin/env python3
"""Extract rendering resources from the SHA-1-verified official client; never execute its classes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request
import urllib.parse
import zipfile

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
HOSTS = {"piston-meta.mojang.com", "piston-data.mojang.com", "launcher.mojang.com", "launchermeta.mojang.com"}


def fetch(url, destination, max_bytes, expected_sha1=None, expected_size=None):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in HOSTS:
        raise ValueError("Only official Mojang HTTPS downloads are accepted")
    digest = hashlib.sha1()
    total = 0
    with urllib.request.urlopen(url, timeout=60) as response:
        final = urllib.parse.urlparse(response.geturl())
        if final.scheme != "https" or final.hostname not in HOSTS:
            raise ValueError("Download redirected outside the official host allowlist")
        with destination.open("wb") as output:
            while data := response.read(1024 * 1024):
                total += len(data)
                if total > max_bytes:
                    raise ValueError("Download exceeds size limit")
                digest.update(data)
                output.write(data)
    if expected_size is not None and total != expected_size:
        raise ValueError("Official client size mismatch")
    if expected_sha1 is not None and digest.hexdigest() != expected_sha1:
        raise ValueError("Official SHA-1 mismatch")
    return digest.hexdigest()


def extract(client, output, provenance):
    count = 0
    with zipfile.ZipFile(client) as source, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            name = info.filename
            if name.startswith(("assets/minecraft/blockstates/", "assets/minecraft/models/block/",
                                "assets/minecraft/textures/block/", "assets/minecraft/textures/colormap/")):
                if info.is_dir() or ".." in name.split("/") or info.file_size > 4 * 1024 * 1024:
                    continue
                target.writestr(name, source.read(info))
                count += 1
        target.writestr("pandagpt-source.json", json.dumps(provenance, indent=2))
    if count < 100:
        raise ValueError("The client does not contain the expected rendering assets")
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="26.3")
    parser.add_argument("--output", type=Path, default=Path("plugins/PandaGPT/assets/vanilla-26.3.zip"))
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="pandagpt-assets-", dir=args.output.parent) as temp:
        temp = Path(temp)
        fetch(MANIFEST, temp / "manifest.json", 8 * 1024 * 1024)
        manifest = json.loads((temp / "manifest.json").read_text())
        version = next((v for v in manifest["versions"] if v["id"] == args.version), None)
        if version is None:
            raise ValueError("Requested Minecraft version is not in the official manifest")
        fetch(version["url"], temp / "version.json", 8 * 1024 * 1024, version["sha1"])
        details = json.loads((temp / "version.json").read_text())
        client = details["downloads"]["client"]
        fetch(client["url"], temp / "client.jar", 128 * 1024 * 1024, client["sha1"], client["size"])
        provenance = {"version": args.version, "manifest": MANIFEST, "client_url": client["url"], "client_sha1": client["sha1"]}
        count = extract(temp / "client.jar", temp / "assets.zip", provenance)
        os.replace(temp / "assets.zip", args.output)
        print(f"Verified {args.version}: extracted {count} rendering resources to {args.output}")


if __name__ == "__main__":
    main()
