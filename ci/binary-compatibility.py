#!/usr/bin/env python3
"""Compare actual release-tag bytecode, never guessed registry coordinates.

Requires git, curl, a JDK (java/javac/jar) and the checked-in Mill launcher.
Run from any directory. --self-test exercises the checker without invoking Mill.
Temporary checkouts/reports live under TMPDIR and are retained for diagnosis.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASELINES = {
    "v0.4.0": "3714fc10c1272fa29c23de22b35f2cf004c64d10",
    "v0.4.1": "ce99927559701a72702cd887a90821fb847fdb73",
}
MODULES = ("core", "vision", "graphs", "zio")
VERSION = "0.23.1"
SHA256 = "f2300a8531b68e25b678247874a1eae13a07d6842a4a1236845481fc90c5c6c7"


def run(*args, cwd=ROOT, **kwargs):
    return subprocess.run([str(a) for a in args], cwd=cwd, check=True, **kwargs)


def checker(work):
    jar = work / "japicmp.jar"
    run("curl", "--fail", "--location", "--retry", "3", "--output", jar,
        f"https://repo.maven.apache.org/maven2/com/github/siom79/japicmp/japicmp/{VERSION}/"
        f"japicmp-{VERSION}-jar-with-dependencies.jar")
    if hashlib.sha256(jar.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("japicmp checksum mismatch")
    return jar


def command(tool, old, new, old_cp="", new_cp=""):
    # Include synthetic bridges and JVM holder classes: Scala source imports alone
    # cannot prove that a client compiled before a source-file move still links.
    return ["java", "-jar", str(tool), "--old", str(old), "--new", str(new),
            "--old-classpath", old_cp, "--new-classpath", new_cp,
            "-a", "protected", "--include-synthetic", "--only-modified",
            "--error-on-binary-incompatibility"]


def self_test(tool, work):
    def compile_jar(name, source):
        folder = work / name
        folder.mkdir()
        (folder / "Api.java").write_text(source)
        run("javac", "--release", "17", "-d", folder, folder / "Api.java")
        jar = work / f"{name}.jar"
        run("jar", "cf", jar, "-C", folder, "Api.class")
        return jar

    old = compile_jar("old", "public class Api { public int value() { return 1; } }")
    good = compile_jar("good", "public class Api { public int value() { return 2; } public int added() { return 3; } }")
    final = compile_jar("final", "public class Api { public final int value() { return 1; } }")
    removed = compile_jar("removed", "public class Api { }")
    run(*command(tool, old, good))
    for bad, reason in ((final, "METHOD_NOW_FINAL"), (removed, "METHOD_REMOVED")):
        result = subprocess.run(command(tool, old, bad), capture_output=True, text=True)
        report = result.stdout + result.stderr
        (work / f"{bad.stem}-negative.log").write_text(report)
        if result.returncode == 0 or reason not in report:
            raise RuntimeError(f"checker failed negative control {reason}: {report}")
    # Compile a real old client, then run it unchanged with each implementation.
    client = work / "client"
    client.mkdir()
    (client / "Client.java").write_text(
        "public class Client extends Api { public int value() { return 4; } "
        "public static void main(String[] args) { System.out.println(new Client().value()); } }")
    run("javac", "--release", "17", "-cp", old, "-d", client, client / "Client.java")
    run("java", "-cp", os.pathsep.join((str(client), str(good))), "Client")
    bad = subprocess.run(["java", "-cp", os.pathsep.join((str(client), str(final))), "Client"],
                         capture_output=True, text=True)
    if bad.returncode == 0 or "IncompatibleClassChangeError" not in bad.stderr:
        raise RuntimeError("old-client finality control did not fail as expected")
    print("ABI-CHECKER-SELF-TEST-OK", flush=True)


def pathref(value):
    return value.split(":", 3)[3]


def outputs(checkout):
    # Resolve classpaths explicitly so missing dependencies fail the comparison;
    # never use --ignore-missing-classes or a blanket exclusion filter.
    targets = [f"{m}.{t}" for m in MODULES for t in ("jar", "runClasspath")]
    selectors = [part for index, target in enumerate(targets) for part in (("+", target) if index else (target,))]
    run(checkout / "mill", "--no-server", "-j", "1", *selectors, cwd=checkout)
    result = {}
    for module in MODULES:
        out = checkout / "out" / module
        jar = pathref(json.loads((out / "jar.json").read_text())["value"])
        shown = run(checkout / "mill", "--no-server", "-j", "1", "show", f"{module}.runClasspath",
                    cwd=checkout, capture_output=True, text=True)
        cp = [pathref(p) for p in json.loads(shown.stdout)]
        result[module] = (jar, os.pathsep.join(cp))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    work = Path(tempfile.mkdtemp(prefix="scalacv-abi-"))
    print(f"ABI evidence: {work}", flush=True)
    tool = checker(work)
    self_test(tool, work)
    if args.self_test:
        return
    current = outputs(ROOT)
    failed = []
    for tag, expected in BASELINES.items():
        actual = run("git", "rev-parse", f"{tag}^{{commit}}", capture_output=True, text=True).stdout.strip()
        if actual != expected:
            raise RuntimeError(f"baseline {tag}: expected {expected}, got {actual}")
        checkout = work / tag
        # A separate local clone preserves VcsVersion's required Git metadata without
        # changing the user's checkout, refs, index or worktree registration.
        run("git", "clone", "--shared", "--no-checkout", ROOT, checkout)
        run("git", "checkout", "--detach", expected, cwd=checkout)
        baseline = outputs(checkout)
        for module in MODULES:
            old, old_cp = baseline[module]
            new, new_cp = current[module]
            report = work / f"{tag}-{module}.log"
            with report.open("w") as log:
                result = subprocess.run(command(tool, old, new, old_cp, new_cp), stdout=log, stderr=subprocess.STDOUT)
            print(f"{tag} -> current {module}: exit {result.returncode}; {report}", flush=True)
            if result.returncode:
                print(report.read_text(), flush=True)
                failed.append(f"{tag}/{module}")
    if failed:
        raise SystemExit("Binary compatibility failed: " + ", ".join(failed))
    print("BINARY-COMPATIBILITY-OK: all four artifacts against both 0.4.x release tags")


if __name__ == "__main__":
    main()
