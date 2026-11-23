#!/usr/bin/env python3
"""Resolve a stable client release and verify its SDK before selecting it for Gradle."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Xaque8787/Kausway"
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


def open_url(url, *, api=False):
    headers = {"User-Agent": "iroh-androidtv-sdk-fetch"}
    if api:
        headers["Accept"] = "application/vnd.github+json"
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if token:
            headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    for attempt in range(3):
        try:
            return urllib.request.urlopen(request, timeout=30)
        except urllib.error.URLError as error:
            if isinstance(error, urllib.error.HTTPError) and error.code not in (429, 500, 502, 503, 504):
                raise
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def latest_version(releases):
    candidates = []
    for release in releases:
        tag = release.get("tag_name", "")
        if release.get("draft") or release.get("prerelease") or not tag.startswith("client-v"):
            continue
        version = tag.removeprefix("client-v")
        match = VERSION.fullmatch(version)
        if match:
            candidates.append((tuple(map(int, match.groups())), version))
    if not candidates:
        raise ValueError("No published stable client-v<major.minor.patch> release found")
    return max(candidates)[1]


def resolve_version(requested):
    if requested != "latest":
        if not VERSION.fullmatch(requested):
            raise ValueError("KAUSWAY_SDK_VERSION must be 'latest' or a stable version such as 0.5.0")
        return requested
    releases = []
    page = 1
    while True:
        url = f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100&page={page}"
        with open_url(url, api=True) as response:
            batch = json.load(response)
        if not isinstance(batch, list):
            raise ValueError("GitHub returned an invalid release list")
        releases.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return latest_version(releases)


def parse_checksum(content, filename):
    match = re.fullmatch(r"([0-9a-fA-F]{64})[ \t]+\*?" + re.escape(filename) + r"\n?", content)
    if not match:
        raise ValueError(f"Invalid SHA-256 file for {filename}")
    return match.group(1).lower()


def sha256(path):
    with path.open("rb") as source:
        digest = hashlib.sha256()
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_sdk(version, directory):
    filename = f"kausway-client-android-{version}.aar"
    base_url = f"https://github.com/{REPOSITORY}/releases/download/client-v{version}"
    with open_url(f"{base_url}/{filename}.sha256") as response:
        checksum = parse_checksum(response.read(512).decode("ascii"), filename)
    directory.mkdir(parents=True, exist_ok=True)
    artifact = directory / filename
    if artifact.exists():
        if sha256(artifact) != checksum:
            raise ValueError(f"SHA-256 mismatch for existing {artifact}; remove it and fetch again")
        return artifact, checksum
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=directory, prefix=filename + ".", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            with open_url(f"{base_url}/{filename}") as response:
                shutil.copyfileobj(response, output)
        if sha256(temporary) != checksum:
            raise ValueError(f"SHA-256 mismatch for downloaded {filename}")
        temporary.replace(artifact)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return artifact, checksum


def write_selection(directory, version, checksum):
    metadata = directory / "kausway-sdk.properties"
    temporary = metadata.with_suffix(".properties.tmp")
    temporary.write_text(f"version={version}\nrelease_tag=client-v{version}\nsha256={checksum}\n")
    temporary.replace(metadata)


def main():
    version = resolve_version(os.environ.get("KAUSWAY_SDK_VERSION", "latest").strip() or "latest")
    directory = ROOT / "app/libs"
    artifact, checksum = fetch_sdk(version, directory)
    write_selection(directory, version, checksum)
    print(f"Kausway SDK: {REPOSITORY} client-v{version}", flush=True)
    print(f"SHA-256: {checksum}", flush=True)
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a") as stream:
            stream.write(f"version={version}\nrelease_tag=client-v{version}\nsha256={checksum}\n")
            stream.write(f"artifact={artifact.relative_to(ROOT).as_posix()}\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        raise SystemExit(f"Kausway SDK selection failed: {error}") from None
