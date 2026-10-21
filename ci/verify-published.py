#!/usr/bin/env python3
"""Compare all sixteen remote release files with the artifacts built for publication."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = {"core": "scalacv_3", "vision": "scalacv-vision_3",
             "graphs": "scalacv-graphs_3", "zio": "scalacv-zio_3"}
CENTRAL = "https://repo.maven.apache.org/maven2"


def request_headers(repository: str) -> dict[str, str]:
    if repository == CENTRAL:
        return {}
    if not re.fullmatch(r"https://maven\.pkg\.github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("expected the Maven Central or GitHub Packages HTTPS repository")
    credentials = f"{os.environ['GITHUB_PACKAGES_USERNAME']}:{os.environ['GITHUB_PACKAGES_PASSWORD']}"
    return {"Authorization": "Basic " + base64.b64encode(credentials.encode()).decode()}


def artifact_files(data: dict, artifact: str, version: str) -> list[tuple[Path, str]]:
    if data["meta"] != {"group": "com.worxbend", "id": artifact, "version": version}:
        raise ValueError(f"wrong published coordinates for {artifact}")
    expected = {f"{artifact}-{version}{suffix}" for suffix in
                (".pom", ".jar", "-sources.jar", "-javadoc.jar")}
    payload = data["payload"]
    if len(payload) != 4 or {name for _, name in payload} != expected:
        raise ValueError(f"{artifact}: expected POM, binary, source and javadoc jars")
    return [(Path(ref.split(":", 3)[3]), f"com/worxbend/{artifact}/{version}/{name}")
            for ref, name in payload]


def fetch_bytes(request, deadline, *, open_url, clock, sleep) -> bytes:
    while True:
        try:
            with open_url(request, timeout=30) as response:
                downloaded = response.read()
            return downloaded
        except urllib.error.HTTPError as error:
            if error.code not in (404, 429, 500, 502, 503, 504) or clock() >= deadline:
                raise
            sleep(5)
        except urllib.error.URLError:
            if clock() >= deadline:
                raise
            sleep(5)


def verify_files(files, repository, headers, *, open_url, clock, sleep) -> None:
    # One shared propagation deadline, not fifteen minutes per missing file.
    deadline = clock() + (900 if repository == CENTRAL else 120)
    for local, relative in files:
        request = urllib.request.Request(f"{repository}/{relative}", headers=headers)
        downloaded = fetch_bytes(request, deadline, open_url=open_url, clock=clock, sleep=sleep)
        if hashlib.sha256(downloaded).digest() != hashlib.sha256(local.read_bytes()).digest():
            raise ValueError(f"remote release file differs from the built artifact: {relative}")
        print(f"Verified {relative}", flush=True)



def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    args = parser.parse_args()
    repository = args.repository.rstrip("/")
    headers = request_headers(repository)
    tag = os.environ["GITHUB_REF_NAME"]
    if os.environ.get("GITHUB_REF_TYPE") != "tag" or not tag.startswith("v"):
        raise ValueError("remote verification requires a release tag")
    version = tag[1:]
    files = []
    for module, artifact in ARTIFACTS.items():
        output = subprocess.check_output([str(ROOT / "mill"), "show", f"{module}.publishArtifacts"],
                                         cwd=ROOT, text=True, timeout=900)
        files.extend(artifact_files(json.loads(output), artifact, version))
    verify_files(files, repository, headers, open_url=urllib.request.urlopen,
                 clock=time.monotonic, sleep=time.sleep)


if __name__ == "__main__":
    main()
