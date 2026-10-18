# API-REFERENCE.md — how the API reference is built, and why

The API reference must feel like part of the site (same header, theme toggle, search). Three
approaches were considered; this records the spike, the decision, the **verified** Scaladoc flag
list, and the integration seams that remain.

## The spike (A / B / C)

**A — Publish and theme Scaladoc under `/api/`.** Scaladoc 3 generates the API site; restyle to
match. Cheapest, always correct, stays current automatically. Ceiling set by Scaladoc's theming
hooks.

**B — Hybrid.** Scaladoc under `/api/`, plus hand-authored mdoc-checked API guide pages per concern
that deep-link into it. More connective tissue, more pages to maintain.

**C — Generate MDX API pages from TASTy** (`tasty-inspector` / `tasty-query`). Full visual control;
API becomes first-class site content. But it carries a custom extractor that must stay correct
across `given`/`extension`/`opaque`/`enum`/`inline`/union types and, above all, **doc comments** —
the awkward part. A generator that silently drops members on the next refactor is worse than themed
Scaladoc.

## Decision: A (unified, themed Scaladoc into `static/api/`)

Chosen A. The library already ships hand-written guide pages for every concern (the "B" value is
present without Scaladoc-injection fragility), so the extra maintenance of B/C buys little here.
C's TASTy extractor is a standing liability against a library whose Scala-3 surface is heavy in
`extension` methods and `enum`s — exactly what a custom generator mangles — and the payoff (native
theming/search) is largely recovered by Pagefind indexing the built Scaladoc HTML in the same pass
as the guides. So: generate real Scaladoc, unify it, drop it into `static/api/`, and connect it to
the guides with links rather than by re-rendering it.

**Unification detail.** The published modules are separate artifacts (core, vision, graphs, zio) but
the guides link every type under `/api/core/scalacv/*.html`, treating `scalacv` as one namespace. So
an unpublished `apidocs` Mill module documents **core + vision + graphs together** into `/api/core`;
`zio` (package `scalacv.zio`, effect dependency) is documented separately into `/api/zio`. This
makes all in-content API deep-links resolve to real files and gives users one API reference instead
of three.

**Integration seams (stated, not papered over).** Scaladoc is static HTML outside Docusaurus's
router, so: (1) links into it use the `pathname://` protocol and are validated by a **separate**
check in `assemble-docs.sh`, not by `onBrokenLinks`; (2) it renders with Scaladoc's own chrome, not
the Docusaurus header — a visible seam when crossing from a guide into `/api/`. Closing that seam
(shared header via post-build DOM injection, unified search via Pagefind) is follow-up work, tracked as
follow-up issues. Bidirectional guide↔API linking (every guide links forward on first mention; every
documented type links back) is likewise a follow-up.

## Verified Scaladoc flags (Scala 3.3.8, the version in use)

Checked by running `scaladoc -help` from `org.scala-lang:scaladoc_3:3.3.8` — not assumed. Present as
**standard options**:

| Flag | Present | Use here |
|---|---|---|
| `-external-mappings` | yes | link JDK / Bytedeco types in signatures instead of dead text |
| `-source-links` | yes | every member → its exact GitHub line |
| `-snippet-compiler` | yes | compile examples inside Scaladoc comments |
| `-social-links` | yes | GitHub icon in the Scaladoc header |
| `-project-logo` | yes | brand the API header |
| `-project-version` / `-revision` | yes | version label + source revision |
| `-groups` | yes | group members by `@group` rather than alphabetically |
| `-siteroot` / `-doc-root-content` | yes | static pages + root package doc |
| `-versions-dictionary-url` | yes | multi-version docs switch (deferred; pre-1.0) |
| `-doc-canonical-base-url` | yes | canonical URLs for SEO |

Not standard options (so **not relied on**): `-Ygenerate-inkuire` and `-Yapi-subdirectory` are
private `-Y` options, absent from the standard `-help`; type-based Inkuire search is treated as out
of scope rather than assumed to exist. `-doc-source-url` / `-doc-external-doc` exist only as
deprecated Scala-2 aliases (`-source-links` / `-external-mappings` are the current forms).

## Status of the API build

**Done:** unified Scaladoc (core+vision+graphs) generated and served at `/api/core`; every inbound
`/api/` link verified by `assemble-docs.sh`; `-source-links` (every member → its exact GitHub line,
verified resolving in production), `-social-links`, `-project`/`-project-version` wired into the
`apidocs`/`zio` docJar; **every unresolved Scaladoc `@link` fixed** (cold build warns 0).

**Follow-up (tracked issue), in dependency order:** `-external-mappings` (JDK + Bytedeco — deferred
because its regexes match classpath entries and a wrong URL yields broken links, so it needs its own
verified change); `-snippet-compiler`, `-groups`; align Scaladoc CSS tokens with `DESIGN.md`; inject
the site header post-build to close the guide→API chrome seam; add guide↔API back-links.
