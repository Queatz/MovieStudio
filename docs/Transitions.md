# Transitions — how clip transitions are implemented

A **transition** blends a visual clip *in* over whatever plays underneath it at the start of the
clip. Transitions are a per-clip effect stored in the clip's `EffectsConfig`, edited in the
`ClipInspector`, applied live in the **`PreviewPanel`** while editing, and rendered to real pixels by
the **server-side FFmpeg render** (`server/.../service/FFmpegService.kt`). Both the preview and the
export derive their behavior from the **same shared logic in `core`** so they stay in lock-step.

> **Transition-in only.** Every transition currently supported is a transition *in*: it runs from
> 0% → 100% across a window that starts at the clip's start. There is **no transition-out** (no
> fade/slide/dissolve at the *end* of a clip) today. Wherever this doc says "transition" it means
> "transition-in".

> Terminology: user-facing copy always says **"Movie"**. The internal data class is still named
> `Film`, so this doc uses `Film` for code references and "movie" in prose.

---

## 1. Data model

Defined in `core/src/commonMain/kotlin/app/moviestudio/Models.kt`:

```kotlin
enum class TransitionType { NONE, ALPHA, NOISE, VORONOI, SLIDE, CIRCLE, PIXELATE }

/** Which edge a SLIDE transition enters from (FROM_RIGHT reproduces the original behavior). */
enum class SlideDirection { FROM_LEFT, FROM_RIGHT, FROM_TOP, FROM_BOTTOM }

@Serializable
data class TransitionSpec(
    val type: TransitionType = TransitionType.NONE,
    val durationSeconds: Double = 1.0,                       // transition window from the clip start
    val direction: SlideDirection = SlideDirection.FROM_RIGHT, // SLIDE only
)
```

- The `TransitionSpec` lives inside a clip's `EffectsConfig.transition` (nullable), which is
  serialized to JSON in `Clip.effectsConfig` alongside captions, volume and crop offsets.
- `durationSeconds` is the **transition window**: the transition progresses from 0% to 100% over
  `[clipStart, clipStart + durationSeconds)`. After the window the clip plays normally.
- `direction` is a **transition parameter** that only applies to `SLIDE`; it picks which edge the
  clip slides in from. `FROM_RIGHT` is the default and reproduces the original slide (enter from the
  right, move left). New parameters for other types can be added to `TransitionSpec` the same way
  (unknown keys already parse safely, so old clips keep working).
- `TransitionType.displayName()` / `SlideDirection.displayName()` provide the human labels used by
  the UI:

  | `TransitionType` | Label           |
  |------------------|-----------------|
  | `NONE`           | None            |
  | `ALPHA`          | Alpha fade      |
  | `NOISE`          | Noise dissolve  |
  | `VORONOI`        | Voronoi cells   |
  | `SLIDE`          | Slide           |
  | `CIRCLE`         | Circle reveal   |
  | `PIXELATE`       | Pixelate        |

---

## 2. Authoring a transition (`ClipInspector`)

The transition editor (`app/shared/.../ui/ClipInspector.kt`) is shown **only for `VIDEO`-type
tracks** — the tracks that carry images, video and description-only cards:

- A `DropdownSelector` picks the `TransitionType`. Choosing `None` sets `transition = null`;
  choosing anything else creates a `TransitionSpec(type, duration)` (the duration defaults to
  `1.0s`, clamped to the clip length).
- When a non-`None` type is selected, a **"Window"** slider controls `durationSeconds`
  (`0.1s .. clipLength`), displayed as both seconds and a **percentage of the clip length**.
- When the type is `SLIDE`, a **"Direction"** `DropdownSelector` sets `TransitionSpec.direction`
  (From left / right / top / bottom) — this is the per-transition parameter.

On the timeline, `TimelinePanel` draws a small **`⇄`** badge in front of the clip's label whenever
the clip has a non-`NONE` transition, so authored transitions are visible at a glance.

---

## 3. Shared transition logic (`core`) — the single source of truth

The transition semantics live in `core/.../Models.kt` and are consumed by **both** the preview and
the FFmpeg render, so the two can't drift:

```kotlin
/** Progress 0f..1f of the transition at clip-local time; 1f = settled / no effect. */
fun TransitionSpec?.progressAt(clipLocalSeconds: Double, clipDuration: Double): Float

/**
 * The incoming clip's visual at a given progress, described with a small set of orthogonal,
 * interpretable *primitives* — not a hard-coded transform per transition. Each renderer applies the
 * primitives it can express.
 */
data class TransitionVisual(
    val alpha: Float = 1f,               // cross-fade opacity
    val translateXFraction: Float = 0f,  // slide offset, fraction of stage (+ = right)
    val translateYFraction: Float = 0f,  // slide offset, fraction of stage (+ = down)
    val revealRadiusFraction: Float = 1f,// centered circular reveal (1 = no mask, 0 = nothing)
    val pixelateFraction: Float = 0f,    // mosaic amount (0 = crisp, 1 = maximally blocky)
    val noiseFraction: Float = 0f,       // grain/dissolve amount (0 = clean, 1 = fully speckled)
    val voronoiFraction: Float = 0f,     // voronoi-cell amount (0 = crisp, 1 = coarse cells)
)
fun TransitionSpec.visualAt(progress: Float): TransitionVisual
```

- `progressAt(...)` clamps the window to `[TRANSITION_MIN_SECONDS, clipDuration]` and returns the
  normalized progress; it is the shared window math.
- `visualAt(...)` maps a transition to a set of **primitives** so preview and export agree:
  - `SLIDE` → translate the clip in from its `direction` at full opacity (`translate = ±(1 - p)`).
  - `CIRCLE` → a centered circular reveal that grows from nothing to full (`revealRadiusFraction = p`)
    at full opacity — **no** cross-fade.
  - `PIXELATE` → the clip resolves out of large mosaic blocks (`pixelateFraction = 1 - p`) while it
    cross-fades in (`alpha = p`).
  - `NOISE` → the clip emerges from grain/dissolve speckle (`noiseFraction = 1 - p`) while it
    cross-fades in (`alpha = p`).
  - `VORONOI` → the clip resolves out of voronoi cells (`voronoiFraction = 1 - p`) while it
    cross-fades in (`alpha = p`).
  - `ALPHA` → a plain cross-fade (`alpha = p`).
- The reveal fraction and slide-offset signs in `visualAt(...)` are defined to match the FFmpeg
  expressions in §4, so those transitions look the same in the preview and the export.
- **Not every primitive is reproducible everywhere.** The GPU-composited **WebGL preview** runs a
  fragment shader over the frame, so it *can* apply `pixelateFraction` / `noiseFraction` /
  `voronoiFraction` (see §5). The **default DOM `<video>` + Compose preview** can only express
  `alpha`, `translate` and a circular clip (`revealRadiusFraction`) — *not* an arbitrary mosaic /
  grain / cell warp — so there the textured transitions fall back to their accompanying alpha fade.
  This asymmetry is exactly why a primitive set is a **bridge, not the endgame** — see §7.

---

## 4. Rendering (server-side FFmpeg)

All transition pixels are produced by `FFmpegService.executeRenderJob(...)`. Every visual clip is
built as its own filter chain and then **overlaid** onto the accumulating video
(`currentVideoTag` — the black canvas plus all lower clips), so the transition always blends the
incoming clip *over the media beneath it*:

```
[currentVideoTag][clip]overlay=eof_action=pass:enable='between(t,start,end)'<overlayExtra>[next]
```

The transition window is clamped to `transitionDur = durationSeconds.coerceIn(0.05, clipDuration)`
and applied as follows (clip-local time `0..transitionDur`):

| `TransitionType` | FFmpeg filters added to the clip chain                                                                 |
|------------------|---------------------------------------------------------------------------------------------------------|
| `SLIDE`          | Overlay `x`/`y` slides the clip in from `direction` (e.g. FROM_RIGHT: `x='if(lt(t-start,dur), W-W*p, 0)'`, FROM_TOP: `y='...-H+H*p...'`), `p=(t-start)/dur` |
| `ALPHA`          | `format=yuva420p`, `fade=t=in:st=0:d=dur:alpha=1` (alpha fade-in)                                        |
| `NOISE`          | alpha fade-in **+** `noise=c3s=48:c3f=t:enable='between(t,0,dur)'` (grain on the alpha plane only)       |
| `VORONOI`        | alpha fade-in **+** **animated voronoi cells** via `geq` (see below)                                    |
| `PIXELATE`       | alpha fade-in **+** **animated mosaic** (see below)                                                     |
| `CIRCLE`         | `format=yuva420p` **+** growing circular alpha mask via `geq` (see below) — no fade                     |

The textured/masked transitions now have real, animated implementations:

- **`VORONOI` — animated voronoi cells.** On `format=gbrp` (full-res RGB planes), a per-pixel `geq`
  samples the frame at the nearest random cell seed over a 3×3 grid of cells whose size shrinks from
  60px to 1px as the window ends, then `format=yuva420p` + the alpha fade cross-fade it in:
  ```
  geq=r='<voronoi>':g='<voronoi>':b='<voronoi>':enable='between(t,0,dur)'
  // cellPx = max(1, 60 * (1 - min(T/dur, 1)));  seed = cell + hash22(cell);
  // sample the nearest seed's pixel — the exact analog of the WebGL voronoi shader
  ```
  The `geq` expression (`FFmpegService.voronoiGeqExpression`) replicates the shader's `hash22` and
  nearest-seed search within FFmpeg's 10 `st()`/`ld()` slots, so preview and export match. It is
  `enable`-gated to the window, so the clip plays crisp afterwards.
- **`CIRCLE` — growing circular reveal.** After `format=yuva420p`, a `geq` sets the alpha plane to
  opaque only inside a centered circle whose radius grows over the window:
  ```
  geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':
      a='if(lte(hypot(X-W/2,Y-H/2), hypot(W/2,H/2)*min(T/dur,1)), 255, 0)'
  ```
  `hypot(W/2,H/2)` is the center-to-corner distance, so at `T=dur` the circle covers the whole
  frame. Outside the circle the clip is transparent, so the `overlay` shows the media beneath — a
  true iris-in. This is the exact analog of the preview's `CircleRevealShape`
  (`revealRadiusFraction = min(T/dur, 1)`), so preview and export match.
- **`PIXELATE` — animated mosaic.** Instead of a constant `pixelize=16`, the block size is animated:
  the clip is downscaled with nearest-neighbor to a *time-varying* tiny size and scaled back up, so
  the blocks start large (`maxBlock` px at `t=0`) and shrink to 1px (crisp) as the window ends:
  ```
  scale=w='max(2,2*floor(W/blockPx/2))':h='max(2,2*floor(H/blockPx/2))':eval=frame:flags=neighbor,
  scale=W:H:flags=neighbor            // blockPx = max(1, maxBlock*(1 - min(t/dur, 1)))
  ```
  This is the real animation of `pixelateFraction = 1 - progress`; the accompanying alpha fade is
  what the preview approximates.
- All effects are keyed with `enable='between(...)'` / `fade ... st=0:d=dur`, or (for the scales)
  become the identity transform after the window, so the clip plays clean afterwards — reinforcing
  that these are transition-**in** effects only.

---

## 5. The preview now applies transitions

`PreviewPanel` (`app/shared/.../ui/PreviewPanel.kt`) is the client-side mirror of the FFmpeg render
(*"what the exported movie will look like at that instant"*). It now reads `effects.transition` and
applies the shared `visualAt(progressAt(...))` to every visual clip under the playhead:

- For each clip it computes `clipLocal = playhead - clip.timelineStart`,
  `clipDuration = clip.trimOut - clip.trimIn`, then `transition.visualAt(transition.progressAt(...))`
  (`ActiveClip.transitionVisual(playhead)`).
- **Images** (`AssetType.IMAGE`) apply the resulting `TransitionVisual` via a `graphicsLayer`
  (`alpha`, and `translationX/Y = fraction * size`) plus, when `revealRadiusFraction < 1`, a
  `Modifier.clip(CircleRevealShape(...))` — so they fade / slide / iris in over the media beneath.
- **Video** passes `alpha` / `offsetXFraction` / `offsetYFraction` / `revealRadiusFraction` into
  `VideoPlayer` (below).
- **Description-only cards** are *not* transitioned, matching FFmpeg (those items skip the
  transition block in the render).
- **Pixelate / Noise / Voronoi** show only their alpha fade in the **default DOM preview** (Compose
  can't mosaic/grain the content); the real textured animation appears in the export and in the
  **WebGL preview** (below).

Because a transitioning clip becomes partly transparent / offset, the lower-`zIndex` clips (or the
black stage) show through exactly as the FFmpeg overlay reveals `currentVideoTag`.

### 5.1 The WebGL preview applies the textured transitions too

The optional **WebGL preview** (`app/shared/.../WebGLPreview.{wasmJs,js}.kt`, chosen from the
renderer dropdown) composites every layer on the GPU and reads the frame back into the Compose
scene graph. Because it runs a real fragment shader, it applies the textured primitives the DOM
overlay can't, so it matches the FFmpeg export far more closely:

- **`PIXELATE`** — the sampling position is quantized into square blocks whose size shrinks from
  ~48px to 1px as the transition completes (`blockPx = max(1, 48 * pixelateFraction)`), mirroring
  the FFmpeg nearest-neighbor down/up-scale.
- **`VORONOI`** — each pixel is sampled at the nearest random cell seed found over a 3×3 grid of
  cells whose size shrinks to per-pixel (crisp) as the transition completes
  (`cellPx = max(1, 60 * voronoiFraction)`), so the clip resolves out of cells.
- **`NOISE`** — the clip's alpha is speckled with a per-pixel hash grain that fades out as the
  transition completes (`a = clamp(alpha + (rand - 0.5) * 2 * noiseFraction, 0, 1)`); a per-frame
  seed animates the grain.

The shader uses `precision highp float` (guaranteed by the preferred WebGL2 context) so the
hash-based grain/cells keep precision. The three amounts travel to the shader through the per-frame
CSV bridge alongside the existing alpha / translate / reveal values.

---

## 6. `VideoPlayer` carries the transition

The shared video abstraction (`app/shared/.../VideoPlayer.kt`) now has the transition hooks it was
missing:

```kotlin
@Composable
expect fun VideoPlayer(
    url: String,
    isPlaying: Boolean,
    playhead: Float,
    onTimeUpdate: (Float) -> Unit,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,               // cross-fade opacity of the incoming clip
    offsetXFraction: Float = 0f,     // slide offset, fraction of the player size (+ = right)
    offsetYFraction: Float = 0f,     // slide offset, fraction of the player size (+ = down)
    revealRadiusFraction: Float = 1f,// circular reveal (1 = no mask, 0 = nothing shown)
)
```

How each `actual` honors them:

- **JVM / Android** (placeholder players): a `graphicsLayer` applies `alpha` and
  `translationX/Y = fraction * size`, plus `Modifier.clip(CircleRevealShape(revealRadiusFraction))`
  when the reveal is partial.
- **Web (`VideoPlayer.wasmJs.kt` / `VideoPlayer.js.kt`).** The player is a single shared
  `<video id="compose-video-preview">` DOM element drawn *on top of* the Compose canvas (see
  `docs/PreviewPanel.md` §5.2), so Compose modifiers can't transform it. Instead the element's own
  CSS is driven from the transition: the un-transformed stage bounds are stored on the element
  (`dataset.baseX/Y/W/H`) whenever layout changes, and a separate
  `LaunchedEffect(alpha, offsetX, offsetY, reveal)` re-applies `style.opacity`, the
  `base + fraction * size` position, and a `style.clipPath = circle(<reveal * cornerDist>px at 50% 50%)`
  every tick. The circle radius uses the same center-to-corner distance as the FFmpeg `geq` mask, so
  the web iris matches the export. `pixelateFraction` has no CSS analog on a `<video>`, so pixelate
  on web video is fade-only in the preview (see §7).

---

## 7. Scaling to many transitions — is the primitive model the right direction?

**Short answer:** the *shared, progress-driven definition* consumed by both the preview and the
export is the right idea and worth keeping; encoding it as a fixed set of Compose/FFmpeg primitives
(`alpha` / `translate` / `revealRadiusFraction` / `pixelateFraction`) is a good **bridge for the
handful of transitions we ship today, but it will not scale to the "hundreds" we want.**

Why the primitive set doesn't scale:

- Each new primitive must be hand-implemented **twice** (a Compose modifier *and* an FFmpeg filter
  chain) and every renderer must be able to express it. We already have an unavoidable asymmetry —
  Compose can't mosaic content, and the web `<video>` is a DOM overlay we can only tweak with a few
  CSS properties — so `pixelateFraction` is export-only. Arbitrary wipes / irises / dissolves /
  morphs each add another special case and another asymmetry.

The scalable endgame — **one GLSL shader per transition (the [GL Transitions](https://gl-transitions.com)
model):** a transition becomes a fragment shader `transition(vec2 uv, float progress)` that mixes a
`from` and a `to` texture. There are 80+ open-source ones, and authoring a new transition is *data*
(a shader), not code changes in two renderers. It maps cleanly onto our stack:

- **Preview (GPU):** run the shader over the two frames — Android `RuntimeShader` (AGSL) and desktop
  Skia `RuntimeEffect` (`org.jetbrains.skia.RuntimeEffect`) already exist in Compose Multiplatform.
  On **web** this means promoting the preview from a DOM `<video>` overlay to a `<canvas>` that draws
  the decoded video frame and runs the shader in WebGL — the biggest single piece of work. The
  optional **WebGL preview** (§5.1) is already this canvas-and-shader path and shows why it removes
  the DOM overlay's transform limitations (pixelate / noise / voronoi run there today); generalizing
  it to arbitrary GL-Transitions shaders is the remaining step.
- **Export (FFmpeg):** the same GLSL runs via the `gl-transition` filter, or we lean on FFmpeg's
  built-in **`xfade`** filter, which already ships ~50 transitions (`fade`, `wipe*`, `slide*`,
  `circleopen`/`circleclose`/`circlecrop`, `pixelize`, `dissolve`, `radial`, …) plus a `custom=`
  expression. `xfade` blends two equal-length streams, whereas we currently *overlay each clip onto
  an accumulator*, so adopting it is a render-pipeline change (build a `from`/`to` pair per
  transition window) — the main reason it isn't wired up yet.

**Recommended path:** (1) keep the shared `progressAt` window math; (2) evolve `visualAt` into a
`TransitionRecipe` that can also name a **shader id + uniforms**; (3) add the GPU shader preview and
the `xfade`/`gl-transition` export path; (4) migrate the existing primitive transitions onto shaders
so there is a single implementation per transition. Until then, the primitive set keeps preview and
export in lock-step for the transitions we ship.

---

## 8. Remaining gaps

- **Pixelate / Noise / Voronoi are fade-only in the *default* DOM preview.** Compose can't mosaic /
  grain content and the web video is a DOM overlay, so the default preview shows only the
  accompanying alpha fade. The **WebGL preview** (§5.1) and the FFmpeg export both apply the real
  textured animation; switching the renderer to WebGL closes the gap.
- **Transition-out** is still unsupported (see the note at the top).
- **`CIRCLE`'s `geq` mask is per-pixel per-frame**, so it's the most expensive transition to render;
  the `xfade=circleopen` route in §7 would be cheaper.

---

## 9. Summary

- Transitions are per-clip, **transition-in only**, stored in `EffectsConfig.transition`
  (`TransitionSpec`), authored in `ClipInspector` for `VIDEO`-track clips.
- `TransitionSpec` carries a **parameter** — `direction` (`SlideDirection`) for `SLIDE` — and is the
  template for future per-type parameters.
- The window math (`progressAt`) and the effect mapping (`visualAt` → `TransitionVisual`) live in
  **shared `core` code** as a small set of orthogonal **primitives** (alpha / translate / circular
  reveal / pixelate / noise / voronoi), so the preview and FFmpeg stay in lock-step.
- **`CIRCLE`** is a real growing circular reveal (FFmpeg `geq` alpha mask; preview
  `CircleRevealShape` / web `clip-path`), **`PIXELATE`** **animates** the mosaic (time-varying
  nearest-neighbor down/up-scale), and **`NOISE`** / **`VORONOI`** add real grain / voronoi cells.
- The **live `PreviewPanel` applies transitions** to images (`graphicsLayer` + circle clip) and
  video (via the extended `VideoPlayer`, which honors `alpha` / offset / reveal — on web by driving
  the DOM overlay's `style.opacity` / position / `clipPath`). The **WebGL preview** additionally
  runs the `pixelate` / `noise` / `voronoi` primitives in its fragment shader (§5.1).
- The path to **hundreds** of transitions (GL-Transitions shaders + `xfade` / `gl-transition`) is in
  §7; the *default* DOM preview still shows the textured transitions as their dominant fade, and
  there is no transition-**out**.
