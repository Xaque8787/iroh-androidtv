import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import fetch_kausway_sdk as sdk


def release(tag, **fields):
    return {"tag_name": tag, "draft": False, "prerelease": False, **fields}


class SelectionTests(unittest.TestCase):
    def test_only_stable_client_releases_with_numeric_version_ordering(self):
        releases = [
            release("suite-v99.0.0"), release("container-v99.0.0"), release("v99.0.0"),
            release("client-v0.9.0"), release("client-v0.10.0"), release("client-v0.5.0"),
            release("client-v1.0.0", draft=True), release("client-v2.0.0", prerelease=True),
            release("client-v3.0.0-rc.1"), release("client-v04.0.0"), release("client-v4.0"),
        ]
        self.assertEqual(sdk.latest_version(releases), "0.10.0")

    def test_missing_client_release_fails_instead_of_using_suite(self):
        with self.assertRaisesRegex(ValueError, "No published stable"):
            sdk.latest_version([release("suite-v0.5.0")])

    def test_discovery_follows_pagination(self):
        responses = [
            [release("suite-v9.0.0")] * 99 + [release("client-v0.5.0")],
            [release("client-v0.6.0")],
        ]
        with patch.object(sdk, "open_url", side_effect=[io.BytesIO(json.dumps(r).encode()) for r in responses]) as opened:
            self.assertEqual(sdk.resolve_version("latest"), "0.6.0")
        self.assertIn("page=2", opened.call_args.args[0])

    def test_exact_version_does_not_depend_on_latest_or_api(self):
        with patch.object(sdk, "open_url") as opened:
            self.assertEqual(sdk.resolve_version("0.5.0"), "0.5.0")
            opened.assert_not_called()
        for invalid in ("client-v0.5.0", "0.5", "0.5.0-rc.1", "00.5.0", "0.5.0\nversion=1.0.0"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                sdk.resolve_version(invalid)

    def test_authentication_is_used_only_for_api_requests(self):
        with patch.dict(os.environ, {"GH_TOKEN": "test-token"}), patch("urllib.request.urlopen") as opened:
            sdk.open_url("https://api.github.com/repos/Xaque8787/Kausway/releases", api=True)
            self.assertEqual(opened.call_args.args[0].get_header("Authorization"), "Bearer test-token")
            sdk.open_url("https://github.com/Xaque8787/Kausway/releases/download/client-v0.5.0/file")
            self.assertIsNone(opened.call_args.args[0].get_header("Authorization"))


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "libs"
        self.payload = b"test SDK payload"
        self.digest = hashlib.sha256(self.payload).hexdigest()
        self.filename = "kausway-client-android-0.5.0.aar"

    def responses(self, payload=None):
        return [io.BytesIO(f"{self.digest}  {self.filename}\n".encode()),
                io.BytesIO(self.payload if payload is None else payload)]

    def test_verified_download_and_cached_reuse(self):
        with patch.object(sdk, "open_url", side_effect=self.responses()) as opened:
            artifact, checksum = sdk.fetch_sdk("0.5.0", self.directory)
        self.assertEqual(artifact.read_bytes(), self.payload)
        self.assertEqual(checksum, self.digest)
        self.assertIn("/client-v0.5.0/", opened.call_args.args[0])
        with patch.object(sdk, "open_url", return_value=self.responses()[0]) as opened:
            self.assertEqual(sdk.fetch_sdk("0.5.0", self.directory)[0], artifact)
            self.assertEqual(opened.call_count, 1)

    def test_corrupt_download_is_never_selected_or_left_as_a_valid_aar(self):
        with patch.object(sdk, "open_url", side_effect=self.responses(b"corrupt")), self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            sdk.fetch_sdk("0.5.0", self.directory)
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_corrupt_cached_aar_is_rejected(self):
        self.directory.mkdir()
        (self.directory / self.filename).write_bytes(b"corrupt")
        with patch.object(sdk, "open_url", return_value=self.responses()[0]), self.assertRaisesRegex(ValueError, "existing"):
            sdk.fetch_sdk("0.5.0", self.directory)

    def test_checksum_must_name_the_selected_asset(self):
        for invalid in (f"{self.digest}  another.aar\n", "not a checksum", f"{self.digest}\n"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                sdk.parse_checksum(invalid, self.filename)

    def test_success_records_one_selection_for_gradle_and_workflow(self):
        root = Path(self.temp.name)
        outputs = root / "outputs"
        with patch.object(sdk, "ROOT", root), patch.object(sdk, "resolve_version", return_value="0.5.0"), \
             patch.object(sdk, "open_url", side_effect=self.responses()), \
             patch.dict(os.environ, {"GITHUB_OUTPUT": str(outputs)}), contextlib.redirect_stdout(io.StringIO()):
            sdk.main()
        metadata = (root / "app/libs/kausway-sdk.properties").read_text()
        self.assertIn("version=0.5.0\nrelease_tag=client-v0.5.0\n", metadata)
        self.assertIn(f"sha256={self.digest}\n", metadata)
        self.assertIn(metadata, outputs.read_text())
        self.assertIn(f"artifact=app/libs/{self.filename}\n", outputs.read_text())


if __name__ == "__main__":
    unittest.main()
