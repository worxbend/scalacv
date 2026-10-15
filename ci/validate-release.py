#!/usr/bin/env python3
"""Require an exact release tag, changelog entry and four matching artifact versions."""

import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("core", "vision", "graphs", "zio")


def validate_versions(tag: str, versions: dict[str, str]) -> str:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?", tag):
        raise ValueError(f"invalid release tag: {tag!r}")
    expected = tag[1:]
    if expected.upper().endswith("-SNAPSHOT"):
        raise ValueError("release tags cannot publish snapshots")
    if set(versions) != set(MODULES):
        raise ValueError("release versions must cover exactly core, vision, graphs and zio")
    for module, version in versions.items():
        if version != expected:
            raise ValueError(f"{module}.publishVersion {version!r} does not match {tag!r}")
    return expected


def validate_changelog(version: str, changelog: str) -> None:
    if not re.search(rf"^## \[{re.escape(version)}\](?:\s|$)", changelog, re.MULTILINE):
        raise ValueError(f"CHANGELOG.md needs a '## [{version}]' release heading")


def mill(*args: str) -> str:
    return subprocess.check_output([str(ROOT / "mill"), *args], cwd=ROOT, text=True, timeout=900).strip()


def main() -> None:
    if os.environ.get("GITHUB_REF_TYPE") != "tag":
        raise ValueError("select a release tag, not a branch, when dispatching publication")
    tag = os.environ["GITHUB_REF_NAME"]
    version = validate_versions(tag, dict.fromkeys(MODULES, tag[1:]))
    subprocess.run(["git", "diff", "--exit-code", "HEAD"], cwd=ROOT, check=True)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tagged = subprocess.check_output(["git", "rev-parse", f"refs/tags/{tag}^{{commit}}"], cwd=ROOT, text=True).strip()
    if head != tagged or head != os.environ["GITHUB_SHA"]:
        raise ValueError("checkout must be the exact event tag commit")
    modules = {line.removesuffix(".publishArtifacts") for line in
               mill("resolve", "__:PublishModule.publishArtifacts").splitlines()}
    if modules != set(MODULES):
        raise ValueError(f"unexpected publishable modules: {sorted(modules)}")
    versions = {module: json.loads(mill("show", f"{module}.publishVersion")) for module in MODULES}
    validate_versions(tag, versions)
    validate_changelog(version, (ROOT / "CHANGELOG.md").read_text())
    print(f"Validated {tag} at {head}: exactly four libraries, version {version}")


if __name__ == "__main__":
    main()
