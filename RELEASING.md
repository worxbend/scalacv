# Releasing scalacv

## Publishing workflows

Pushing a new `vX.Y.Z` tag runs two independent workflows, following the Glyphora setup:

- `release.yml` signs and publishes all four libraries to Sonatype Maven Central, verifies every
  remote POM/binary/source/javadoc file against the built artifacts, attests the release jars, runs
  Java 17 and Scala 3.3.8 consumers, then creates a public GitHub release.
- `publish-packages.yml` publishes the same four libraries to
  `https://maven.pkg.github.com/worxbend/scalacv`, verifies their remote files and runs the same consumers.

Both workflows run the complete reusable CI graph on the tag's exact commit before giving the
publishing job credentials. They check that exactly `core`, `vision`, `graphs` and `zio` publish,
that every version equals the tag without its `v`, and that the changelog has the matching heading.
Full Git history is fetched for VCS-derived versions. Cached build outputs cannot freeze the version
because `publishVersion` tracks Git as an input.

The current release is `v0.4.5`. Registry availability is established by its successful publishing
runs, not by the tag alone; this release does not retroactively publish older source tags.
Installation examples target `0.4.5`. A local install uses a clean tagged checkout with
`./mill __.publishLocal` and local Ivy resolution (`ivy2Local` in Coursier; sbt enables it by
default), or `./mill __.publishM2Local` for Maven. Unreleased changes use the version reported
by `./mill show core.publishVersion`; all four artifact versions must match.

## Local release gates

```sh
./mill core.test + vision.test + graphs.test + zio.test + examples.test
./mill leaks.test
python3 ci/binary-compatibility.py
./mill core.test.testOnly scalacv.PublicApiTest scalacv.PublishedPomTest
./mill docs.mdocCheck
./scripts/assemble-docs.sh
(cd website && npm ci && npm run build)
```

CI additionally resolves all four locally published artifacts through a fresh Coursier cache,
compiles the Java and Scala 3.3.8 consumers in `ci/consumer-smoke`, and runs both on JDK 17.
The Scala consumer exercises imports/extensions, graphics, vision JNI and scoped ZIO use, not
just class loading. Reproduce the Linux x86-64 consumer leg with JDK 17 and `cs` on PATH:

```sh
./mill __.publishLocal
bash ci/consumer-smoke/run.sh "$(./mill show core.publishVersion | tr -d '"')"
```

The local mode uses Ivy. The publishing workflows additionally download and hash-check all sixteen
release files from their destination and run consumers with a fresh Coursier cache, without local
Ivy/Maven fallback:

```sh
bash ci/consumer-smoke/run.sh VERSION https://repo.maven.apache.org/maven2
# For GitHub Packages, also supply COURSIER_CREDENTIALS (see below).
bash ci/consumer-smoke/run.sh VERSION https://maven.pkg.github.com/worxbend/scalacv
```

Release-tooling regression checks: `python3 -m unittest discover -s ci/tests -v`.

### Historical binary compatibility

`ci/binary-compatibility.py` uses checksum-pinned **japicmp 0.23.1** to inspect real JVM bytecode.
It builds isolated local clones at these existing tags in `TMPDIR`, using each tag's own build:

| Baseline | Required commit |
|---|---|
| `v0.4.0` | `3714fc10c1272fa29c23de22b35f2cf004c64d10` |
| `v0.4.1` | `ce99927559701a72702cd887a90821fb847fdb73` |
| `v0.4.2` | `6a6e3040a890f2724a539834ea51d8e3a2b23d5c` |
| `v0.4.3` | `7f2d0e6c5130b9ab8e81984afaa937e6526259fe` |
| `v0.4.4` | `32fd13a5c4aa8e1394703182cf0f163d40ac0d06` |

Every baseline is compared to each corresponding current jar: core, vision, graphs and zio.
Missing tags/dependencies, moved tags, build failures and binary incompatibilities fail the gate;
there is no empty-baseline success, blanket exclusion or guessed published ScalaCV artifact.
Public/protected members, synthetic bridges and top-level JVM holders are checked. These are
locally built tag bytecode, not a claim about downloaded or bit-identical published jars. Each
scratch clone retains Git metadata for the versioned build and checks out the pinned commit in
detached mode. Reports and isolated checkouts remain in the printed scratch directory. The source
repository's refs, index and worktree are not changed.

Under **early-semver**, `0.x` minor changes may break compatibility, but patch releases within a
minor must retain it. Keep all released baselines in the active `0.4.x` line, including additions
introduced in later patches. A new incompatible minor requires an explicit policy/baseline review;
do not silently reset baselines or add suppressions to make a patch green. After 1.0, preserve the
supported major line across minor releases too.

The script first proves its checker with a compatible addition, a removed method and changed
method finality. It also compiles an old subclass and verifies its unchanged bytecode links to the
compatible replacement and fails against the newly-final method. Run just those controls with
`python3 ci/binary-compatibility.py --self-test` (no Mill).

API goldens are a separate review aid, now recording finality and generic bounds. They do not
replace the historical checker or guarantee Scala source/TASTy compatibility. When a facade or
extension moves source files, preserve its JVM holder and exercise an unchanged compiled client.
Regenerate goldens only after reviewing source changes, and rerun without regeneration enabled.

Open traits (`Renderer`, `TextMeasurer`, `OcrEngine`, `MotionDetector`) may be implemented by consumers;
within a minor line a new member on them always carries a default implementation, so an existing
implementation keeps compiling and linking.

## Preparing a tag

1. Verify a clean tree and green CI, including all release gates above.
2. Move `[Unreleased]` changelog entries under the intended version/date, and align installation
   snippets when that source release is ready. Do not relabel unreleased fixes as already shipped.
3. The maintainer creates and pushes the chosen `vX.Y.Z` tag. `VcsVersion` derives artifact versions
   from Git; use a fresh tagged checkout and check all four `publishVersion` values.
4. Inspect both publishing runs. The public GitHub release is created only after Central upload,
   public artifact verification and both consumer checks succeed. GitHub Packages runs independently;
   inspect its workflow before claiming availability there.

## Credentials and signing

Configure repository **Settings → Secrets and variables → Actions**:

| Repository secret | Purpose |
|---|---|
| `SONATYPE_USERNAME` | Central Portal user-token username; passed as `MILL_SONATYPE_USERNAME` |
| `SONATYPE_PASSWORD` | Central Portal user-token password; passed as `MILL_SONATYPE_PASSWORD` |
| `MILL_PGP_SECRET_BASE64` | Base64-encoded ASCII-armored signing secret key |
| `MILL_PGP_PASSPHRASE` | Passphrase protecting that key (optional for an unprotected key) |

The Sonatype secrets already use the unprefixed names above in this repository. Use a
[Central Portal token](https://central.sonatype.org/publish/generate-portal-token/), with publishing
access to the verified `com.worxbend` namespace. The workflow uses Mill 1.1.9's
`mill.javalib.SonatypeCentralPublishModule/` and explicitly enables automatic release; it does not
use the retired OSSRH staging endpoint or leave a deployment awaiting manual Portal release.

GitHub Packages uses the job's `GITHUB_TOKEN` with `packages: write`; no additional upload PAT is
needed. Downloading packages requires authentication, including public packages. For a local consumer,
use a classic PAT with `read:packages` and export
`COURSIER_CREDENTIALS="maven.pkg.github.com USER:TOKEN"` before resolving from the URL above.
See [GitHub's Maven registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).

The dedicated RSA-4096 ScalaCV signing key generated on 2026-10-15 expires on 2028-10-14.
Its fingerprint is `C573E481E4AC377B7220E445081AE183698A37B9`; its public key is
[ci/release-signing-key.asc](ci/release-signing-key.asc) and is distributed through
`hkps://keyserver.ubuntu.com`, a [Sonatype-supported keyserver](https://central.sonatype.org/publish/requirements/gpg/).
The private key and its passphrase belong in repository secrets and secure maintainer backups,
never in source control. To export an existing maintainer key when rotating credentials:

```sh
gpg --armor --export-secret-keys FINGERPRINT | base64 -w0 | \
  gh secret set MILL_PGP_SECRET_BASE64 --repo worxbend/scalacv
gh secret set MILL_PGP_PASSPHRASE --repo worxbend/scalacv
```

## Reruns and recovery

Both workflows can be dispatched manually by selecting a **tag**, and reject a selected branch.
They serialize runs for the same destination and tag without cancelling an upload in progress.
A tag must contain these workflows and the updated release tooling. Do not move an older tag to
install a workflow. Maven release coordinates are immutable: after a partial upload, inspect the
registry and Central Portal deployment before rerunning the upload job. If upload succeeded but a
later verification step failed, verify the existing deployment before deciding how to recover;
do not blindly overwrite an already published version.

## Invariants

- Only `core` (`scalacv`), `vision` (`scalacv-vision`), `graphs` (`scalacv-graphs`) and `zio`
  (`scalacv-zio`) may publish. Examples, GUI, benchmarks and docs never publish.
- Classifier-less library POMs contain only their exact intended dependencies; no natives, test
  dependencies or optional sibling modules leak into core. Consumers choose platform natives.
- Keep Scala 3.3.8 / Java 17 as the consumer floor; changing the build JVM is not changing that floor.

## Documentation hosting

The Docs workflow deploys the site to GitHub Pages. Repository Settings → Pages must select
**GitHub Actions** as its source. Website deployment and library registry publication are separate.
