import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

CI = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, CI / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = load("validate-release")
published = load("verify-published")


class ReleaseValidationTest(unittest.TestCase):
    def test_release_and_prerelease(self):
        for version in ("0.4.3", "0.5.0-RC.1"):
            self.assertEqual(version, release.validate_versions("v" + version, dict.fromkeys(release.MODULES, version)))

    def test_rejects_branches_snapshots_and_malformed_tags(self):
        for tag in ("master", "0.4.3", "v0.4", "v0.4.3-SNAPSHOT", "v0.4.3\n", "v0.4.3;echo hi"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.validate_versions(tag, dict.fromkeys(release.MODULES, tag[1:]))

    def test_rejects_dirty_stale_or_incomplete_versions(self):
        for bad in ("0.4.2", "0.4.3-DIRTY123", None):
            versions = dict.fromkeys(release.MODULES, "0.4.3")
            versions["zio"] = bad
            with self.assertRaises(ValueError):
                release.validate_versions("v0.4.3", versions)
        for modules in (release.MODULES[:-1], (*release.MODULES, "examples")):
            with self.assertRaises(ValueError):
                release.validate_versions("v0.4.3", dict.fromkeys(modules, "0.4.3"))

    def test_changelog_requires_exact_heading(self):
        release.validate_changelog("0.4.3", "## [0.4.3] — 2026-10-15\n")
        for text in ("## [Unreleased]", "## [0.4.30]", "Mention 0.4.3", "## [0x4x3]"):
            with self.assertRaises(ValueError):
                release.validate_changelog("0.4.3", text)


class PublishedFilesTest(unittest.TestCase):
    def test_central_never_receives_github_credentials(self):
        with patch.dict(os.environ, {"GITHUB_PACKAGES_USERNAME": "user", "GITHUB_PACKAGES_PASSWORD": "secret"}):
            self.assertEqual({}, published.request_headers(published.CENTRAL))
            self.assertIn("Authorization", published.request_headers("https://maven.pkg.github.com/worxbend/scalacv"))
        for url in ("http://maven.pkg.github.com/a/b", "https://other.example/repo", "https://maven.pkg.github.com.evil/a/b"):
            with self.assertRaises(ValueError):
                published.request_headers(url)

    def test_exact_release_payload(self):
        data = {"meta": {"group": "com.worxbend", "id": "scalacv_3", "version": "0.4.3"},
                "payload": [["ref:v0:hash:/tmp/out.jar", f"scalacv_3-0.4.3{suffix}"] for suffix in
                            (".pom", ".jar", "-sources.jar", "-javadoc.jar")]}
        files = published.artifact_files(data, "scalacv_3", "0.4.3")
        self.assertEqual(4, len(files))
        self.assertTrue(all(path == Path("/tmp/out.jar") for path, _ in files))
        with self.assertRaises(ValueError):
            published.artifact_files(data, "scalacv_3", "0.4.4")
        data["payload"].pop()
        with self.assertRaises(ValueError):
            published.artifact_files(data, "scalacv_3", "0.4.3")


if __name__ == "__main__":
    unittest.main()
