# DESIGN.md — scalacv documentation design system

The site is built on Docusaurus 3.10 (Infima CSS). This file records every deliberate design
decision with a one-sentence rationale, per the project rule that "looks modern" is not a rationale.
Decisions are grouped by area; each ends with **Why:**.

Status legend: **done** (shipped), **planned** (agreed, not yet built), **rejected** (with reason).

---

## 1. Color — `website/src/css/custom.css`  · done

Brand color is taken from the logo: Solarized red `#DC322F` over a darker `#A32B29`, drawn with a
sweep gradient. Infima drives links, buttons, and active nav from `--ifm-color-primary`.

| Token | Light | Dark | Contrast vs surface |
|---|---|---|---|
| `--ifm-color-primary` | `#bb2a22` | `#ef7b76` | 6.06:1 on `#fff` · 6.36:1 on `#1b1b1d` |

The raw brand red `#DC322F` measures only 4.63:1 on white — technically AA, but with no headroom
for hover/visited states or antialiasing. The light primary is darkened one notch to `#bb2a22`
(6.06:1); the dark-mode primary is lightened to `#ef7b76` (6.36:1). The six Infima ramp steps
(`-dark/-darker/-darkest/-light/-lighter/-lightest`) are shaded around each.

**Why:** link text is the most common colored element on a docs page, so the brand color must clear
WCAG AA (4.5:1) with margin in both themes, not sit on the threshold.

## 2. Code blocks & syntax highlighting  · done (highlighter verified)

Docusaurus 3.10 renders code through `prism-react-renderer`, loading grammars from the bundled
`prismjs`. The standing worry with Docusaurus is that Prism's Scala grammar predates Scala 3.

The bundled Scala grammar recognizes most Scala 3 keywords, including `given`, `extension`,
`enum`, `inline`, `opaque`, `infix`, and `open`. The local
`website/src/theme/prism-include-languages.js` swizzle adds the missing `then`, `end`, and
`export` keywords and the type operators `?=>`, `=>>`, `<:`, and `>:`. The patch extends the
upstream grammar instead of replacing its vocabulary, so upstream additions remain available.

`additionalLanguages: ['java', 'scala', 'bash', 'toml', 'diff']` in `docusaurus.config.ts`. `java`
is listed **before** `scala` because the Prism component load fails (`Cannot set properties of
undefined (setting 'triple-quoted-string')`) if `scala` initializes first.

Other code-block styling: `--ifm-code-font-size: 92%` (dense signatures stay readable without
dwarfing prose); inline `code` gets a muted border/background instead of the brand-red default so it
reads as code, not as a link. Horizontal overflow is constrained to the block, never the page.

**Why:** code is the dominant visual element on an API-wrapper's docs; shipping broken Scala 3
highlighting would undercut the whole site, so the highlighter was tested rather than trusted.

## 3. Measure & rhythm  · done

Body prose (`p`, `ul`, `ol`, `blockquote`) is capped at `--scv-content-measure: 46rem` (~68ch,
inside the 65–75ch band). Tables, code blocks, and diagrams are deliberately exempt so they use the
full column and scroll within themselves.

**Why:** a fixed comfortable measure aids reading, but forcing wide reference material (long
signatures, comparison tables) into it would create needless horizontal scroll.

## 4. Motion  · done

All transitions/animations collapse to ~0ms under `prefers-reduced-motion: reduce`.

**Why:** motion is decoration here; users who opt out of it should get none.

## 5. Theme flash  · verified

Docusaurus injects a blocking inline script that sets `data-theme` before first paint; no
custom work is needed and none was added. Verified the `<script>` is present in built `index.html`.

**Why:** a flash of the wrong theme is a visible defect; confirming the built-in guard means not
re-solving a solved problem.

---

## Swizzle list

- **`prism-include-languages`** · done — loads the configured upstream grammars, then applies
  the Scala 3 additions described above. **Why:** conditionals, export clauses, and type signatures
  in the guides need highlighting that the bundled grammar does not provide. Review this patch
  when upgrading Prism; remove additions when upstream supplies equivalent coverage.

- **`Admonition` (`--wrap`)** · planned — add a `memory` variant for lifetime / use-after-free
  callouts on the Memory & resources page, and verify `danger` reads as genuinely alarming since it
  carries the use-after-free content. Rationale: the native-memory warnings are the site's highest-
  stakes prose and deserve a distinct, unmissable treatment.

## Component inventory

Guide content uses Markdown and Infima. The landing page has six React components under
`website/src/components/`:

| Component | Responsibility and reason |
| --- | --- |
| `Hero` | Installation copy and primary entry points; shares release metadata with site configuration. |
| `VisionPipeline` | Canvas demonstration and its caption/control lifecycle; scene, raster, renderer, and timing helpers isolate computation from React ownership. |
| `MorphText` | Decorative rotating headline words with a stable accessible label and reduced-motion fallback. |
| `Section` | Shared headings, spacing, and width for landing sections. |
| `Capabilities` | Feature groups linking to existing guides. |
| `Paths` | Documentation routes organized by the reader's starting knowledge. |

**Why:** the landing page needs interactive demonstrations and reusable section structure; guide
prose remains in Markdown. Add components when their behavior or reuse justifies them.

Future candidates:

- Platform support matrix (install page) — **planned**, likely a plain Markdown table first.
- "Wrong vs right" paired code blocks (Memory page) — **planned**; try Markdown + a CSS class
  before reaching for a component.
- Mill | sbt | scala-cli `Tabs` with a shared `groupId` — **planned**; requires the relevant pages
  to be authored as `.mdx` (they are `.md`/CommonMark today).

## Typography  · planned

Currently the Infima system font stack (no webfont requests — fast and flash-free, but not yet a
deliberate type choice). **Planned:** self-host a variable sans (UI/body) and a monospace vetted for
Scala legibility (`l1I`/`0O` distinction; `=>`, `?=>`, `<:`, `_*` rendering; ligatures off by
default) as subset WOFF2, preloaded. Deferred so it can be done with real subsetting + a measured
bundle impact rather than a CDN link.

## Deferred / not yet decided

- Per-page OG images (satori) — SEO phase.
- Lighthouse/axe budgets in CI — perf/a11y phase.
