# Contributing to scalacv

Thanks for looking. This is a small library with a narrow remit, so the bar is less
"is this a good idea" and more "does it hold up under a native debugger".

## Getting set up

You need a JDK — anything from 17 up. Nothing else: `./mill` fetches its own launcher,
and Mill provisions the build JDK itself.

```sh
./mill core.compile vision.compile graphs.compile zio.compile examples.compile
./mill core.test + vision.test + graphs.test + zio.test + examples.test
./mill leaks.test
./mill examples.runMain scalacv.smoke      # proves the natives load
```

## Before you push

```sh
./mill core.fix + vision.fix + graphs.fix + zio.fix + examples.fix  # apply scalafix
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll
./mill core.test + vision.test + graphs.test + zio.test + examples.test
./mill leaks.test
```

CI runs the check forms of those (each module’s `fix --check`, `checkFormatAll`) and they exit non-zero
on drift, so running them locally is cheaper than a red build.

Run `python3 ci/binary-compatibility.py` for historical JVM linkage checks (all four artifacts,
both pinned 0.4.x release tags). It builds isolated temporary baselines and never resolves guessed
ScalaCV release coordinates from Central. `--self-test` runs only the checker controls, without Mill.
See [RELEASING.md](RELEASING.md) for early-semver baseline maintenance. API goldens are reviewed
separately; regeneration is not an ABI waiver. CI also compiles and runs the packaged Java and
Scala 3.3.8 consumers on JDK 17 from a fresh resolver cache. The GUI examples are deliberately
excluded from these headless commands.

## The rules that are not negotiable

**Release every native object, on every path.** This library exists because OpenCV's Java
bindings do not reclaim native memory in any useful timeframe. A leak in scalacv is not a
performance issue, it is the defect the project exists to prevent. If you allocate a `Mat`,
`MatOfPoint`, `MatOfRect`, `MatOfByte` or any detector, it is released in a `finally` or it
is wrapped in `Managed`.

**Tests must be able to fail.** An assertion that would still pass if the implementation
returned a constant, or did nothing, is a false assurance and worse than no test. We have
shipped a few of these and had to go back and fix them. Ask of every assertion: what
regression does this catch?

**Never claim an OpenCV symbol you have not checked.** `javap` against the actual jar. The
Java API is not the C++ API — `GaussianBlur`, `Canny`, `Sobel` and `Laplacian` are
capitalised, `imread` returns an empty `Mat` instead of throwing, and `HoughLinesP` gives
back `CV_32SC4` integers where you would expect floats.

**No image assets.** Test fixtures are generated programmatically. This is deliberate: it
keeps image licensing out of the project entirely, which is why `Lena.png` is gone.

**Nothing GUI in `core`.** No `highgui`, no JavaFX, no AWT. `examples-gui` is the one place
a toolkit may appear, and it is never built in CI or published.

## Benchmarking

Performance work lives in the unpublished `benchmarks` module and follows one rule: **no
optimization ships without a benchmark that shows it wins and a hash that shows the output
did not change.**

```sh
./mill benchmarks.runMain scalacv.bench.ToMatBench   # and ToBufferedImageBench, GrayBlurCloneBench, …
```

`Bench` warms the JIT, measures many iterations, and reports a mean with a 95% CI (it is a
plain-JVM harness, not JMH — the annotation processor is awkward under Mill 1.1.7 + the
natives, and the wins here are large next to timer noise). `BenchImages.hash` is the
pixel-exact regression key: an optimized path must hash identically to the baseline unless a
deviation is intentional and documented. Absolute `µs` are machine-specific — quote the
delta, and put it in the commit message. The standing record of every measured win, every
optimization that was measured and rejected, and the known headroom left in place is the
[Benchmark results](docs/mdoc/benchmark-results.md) guide (published at `/benchmark-results`) —
add your numbers there in the same pull request.

## Documentation

The site is [Docusaurus](https://docusaurus.io) in `website/`, but you almost never edit that
directory. **The source of truth is `docs/mdoc/`** — plain Markdown where every ```` ```scala mdoc ````
block is type-checked (and where headless, run) against the real library by mdoc. Prose can lie; a
compiled snippet cannot, so a change that breaks an example fails the build.

`website/docs/` and `website/static/api/` are **generated and git-ignored** — do not edit them by
hand; your changes would be overwritten and never committed. Edit `docs/mdoc/` instead.

Build the site inputs, then run Docusaurus:

```sh
./scripts/assemble-docs.sh          # mdoc type-check + splice, unified Scaladoc, API-link check
cd website && npm ci && npm run build
```

For a live dev loop, two terminals: re-run `./scripts/assemble-docs.sh` after editing a page
(`SKIP_SCALADOC=1` skips the slow Scaladoc step — API links won't resolve, but pages rebuild fast),
and `npm start` in `website/` for hot reload. The full `docs.mdocCheck` also runs on every PR, so a
broken snippet is caught at review, not at deploy.

Two things Docusaurus 3 will bite you on: guide pages are CommonMark (`.md`), so bare `<:`/`=>`/`{`
in prose are fine, but a page that needs React components (e.g. `Tabs`) must be `.mdx`; and links
into the Scaladoc under `/api/` are checked by the assemble script, not by Docusaurus.

## Commits

Conventional commits (`feat:`, `fix:`, `build:`, `docs:`, `test:`, `chore:`).

Write commit messages that explain *why*. "fix: handle empty Mat" tells the next person
nothing; the reason it was broken, and what it cost, is the useful part.

## Reporting a bug

Include your platform and the classifier you depend on, your JDK, and — if it is a crash —
the `hs_err_pid*.log`. Native crashes have no Java stack trace, so that file is usually the
only evidence there is.
