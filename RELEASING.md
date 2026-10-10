# Releasing scalacv

## Current status: verification and drafts, not Central publication

The latest repository tag is `v0.4.1`. A tag is a source release, not evidence of registry
availability. `.github/workflows/release.yml` runs verification and creates a **draft** GitHub
release for a pushed tag. **Central upload and provenance attestation are commented out.** It does
not currently sign, stage or upload artifacts. This document makes no claim that `0.4.1` (or any
older tag) is available on Maven Central.

Installation examples use the `0.4.1` source-release coordinates consistently. To reproduce them
without assuming a registry deployment, build a clean `v0.4.1` checkout with
`./mill __.publishLocal` and enable local Ivy resolution in the consumer (`ivy2Local` in Coursier;
sbt includes local Ivy by default). Changes after that tag use the version actually reported by
`./mill show core.publishVersion`; use the same version for all four artifacts. Documentation on
master may describe unreleased fixes; those require the current checkout's artifacts.

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

This is local publication verification, not a Central-availability check.

### Historical binary compatibility

`ci/binary-compatibility.py` uses checksum-pinned **japicmp 0.23.1** to inspect real JVM bytecode.
It builds isolated local clones at these existing tags in `TMPDIR`, using each tag's own build:

| Baseline | Required commit |
|---|---|
| `v0.4.0` | `3714fc10c1272fa29c23de22b35f2cf004c64d10` |
| `v0.4.1` | `ce99927559701a72702cd887a90821fb847fdb73` |

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

## Preparing a tag

1. Verify a clean tree and green CI, including all release gates above.
2. Move `[Unreleased]` changelog entries under the intended version/date, and align installation
   snippets when that source release is ready. Do not relabel unreleased fixes as already shipped.
3. The maintainer creates and pushes the chosen `vX.Y.Z` tag. `VcsVersion` derives artifact versions
   from Git; use a fresh tagged checkout and check all four `publishVersion` values.
4. Inspect the draft GitHub release. While upload remains disabled, neither the tag nor a green
   workflow is permission to announce registry availability.

## Enabling remote publication (separate, explicit maintainer action)

The intended destination is Maven Central under `com.worxbend`, via the Sonatype Central Portal.
Before enabling anything, verify ownership of that namespace in the Portal (including its DNS
proof), arrange signing and upload credentials through repository secrets, and review the pinned
Mill publisher's current configuration. Do not infer namespace or secret readiness from this file.

Only a separately reviewed workflow change may enable signing/upload and attestation. Initially
stage as `USER_MANAGED` (`--shouldRelease false`); inspect the actual deployment's four artifacts,
sources, javadoc, signatures and dependency POMs before explicitly releasing it. Verify registry
resolution afterward before making the GitHub release public. Never announce a draft or staged
upload as a published artifact.

## Invariants

- Only `core` (`scalacv`), `vision` (`scalacv-vision`), `graphs` (`scalacv-graphs`) and `zio`
  (`scalacv-zio`) may publish. Examples, GUI, benchmarks and docs never publish.
- Classifier-less library POMs contain only their exact intended dependencies; no natives, test
  dependencies or optional sibling modules leak into core. Consumers choose platform natives.
- Keep Scala 3.3.8 / Java 17 as the consumer floor; changing the build JVM is not changing that floor.

## Documentation hosting

The Docs workflow deploys the site to GitHub Pages. Repository Settings → Pages must select
**GitHub Actions** as its source. Website deployment and library registry publication are separate.
