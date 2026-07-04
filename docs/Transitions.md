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

/** The incoming clip's opacity + fractional slide offset at a given progress. */
data class TransitionVisual(val alpha: Float, val translateXFraction: Float, val translateYFraction: Float)
fun TransitionSpec.visualAt(progress: Float): TransitionVisual
```

- `progressAt(...)` clamps the window to `[TRANSITION_MIN_SECONDS, clipDuration]` and returns the
  normalized progress; it is the shared window math.
- `visualAt(...)` maps a transition to a **visual transform**: `SLIDE` translates the clip in from
  its `direction` at full opacity (`translate = ±(1 - progress)` on X or Y); every other type is a
  **cross-fade** (`alpha = progress`). The grain/pixelate/voronoi overlays FFmpeg layers on top of
  the fade can't be reproduced with Compose modifiers, so the preview approximates them as the
  dominant alpha fade.
- The slide offset signs in `visualAt(...)` are defined to match the FFmpeg overlay expressions in
  §4, so a slide looks the same in the preview and the export.

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
| `NOISE`          | alpha fade-in **+** `noise=alls=48:allf=t:enable='between(t,0,dur)'`                                     |
| `VORONOI`        | alpha fade-in **+** `pixelize=width=42:height=42:enable='between(t,0,dur)'`                              |
| `PIXELATE`       | alpha fade-in **+** `pixelize=width=16:height=16:enable='between(t,0,dur)'`                              |
| `CIRCLE`         | alpha fade-in only (see the gap below)                                                                   |

Notes / current gaps in the render:

- **`CIRCLE` has no dedicated implementation.** It falls through to the alpha-fade branch and adds
  no extra filter, so today a "Circle reveal" renders identically to an "Alpha fade". A real
  implementation would need e.g. a masked/`geq` circular wipe.
- All effects are keyed with `enable='between(...)'`/`fade ... st=0:d=dur`, i.e. they only run
  during the window and the clip plays clean afterwards — reinforcing that these are
  transition-**in** effects only.

---

## 5. The preview now applies transitions

`PreviewPanel` (`app/shared/.../ui/PreviewPanel.kt`) is the client-side mirror of the FFmpeg render
(*"what the exported movie will look like at that instant"*). It now reads `effects.transition` and
applies the shared `visualAt(progressAt(...))` to every visual clip under the playhead:

- For each clip it computes `clipLocal = playhead - clip.timelineStart`,
  `clipDuration = clip.trimOut - clip.trimIn`, then `transition.visualAt(transition.progressAt(...))`
  (`ActiveClip.transitionVisual(playhead)`).
- **Images** (`AssetType.IMAGE`) apply the resulting `TransitionVisual` via a `graphicsLayer`
  (`alpha`, and `translationX/Y = fraction * size`), so they fade / slide in over the media beneath.
- **Video** passes `alpha` / `offsetXFraction` / `offsetYFraction` into `VideoPlayer` (below).
- **Description-only cards** are *not* transitioned, matching FFmpeg (those items skip the
  transition block in the render).

Because a transitioning clip becomes partly transparent / offset, the lower-`zIndex` clips (or the
black stage) show through exactly as the FFmpeg overlay reveals `currentVideoTag`.

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
    alpha: Float = 1f,              // cross-fade opacity of the incoming clip
    offsetXFraction: Float = 0f,    // slide offset, fraction of the player size (+ = right)
    offsetYFraction: Float = 0f,    // slide offset, fraction of the player size (+ = down)
)
```

How each `actual` honors them:

- **JVM / Android** (placeholder players): a `graphicsLayer` applies `alpha` and
  `translationX/Y = fraction * size`.
- **Web (`VideoPlayer.wasmJs.kt` / `VideoPlayer.js.kt`).** The player is a single shared
  `<video id="compose-video-preview">` DOM element drawn *on top of* the Compose canvas (see
  `docs/PreviewPanel.md` §5.2), so a Compose `Modifier.alpha` can't fade it. Instead the element's
  own `style.opacity` and `style.left`/`style.top` are driven from the transition: the un-transformed
  stage bounds are stored on the element (`dataset.baseX/Y/W/H`) whenever layout changes, and a
  separate `LaunchedEffect(alpha, offsetX, offsetY)` re-applies opacity + `base + fraction * size`
  offset every tick as the progress advances.

---

## 7. Remaining gaps

- **`CIRCLE` still has no dedicated implementation** in either the render or the preview — it renders
  as a plain alpha fade in both (kept consistent between them). A real version needs a masked/`geq`
  circular wipe on the render side and an equivalent clip mask in the preview.
- **Grain / pixelate detail is fade-only in the preview.** `NOISE`, `VORONOI` and `PIXELATE` add
  their FFmpeg grain/pixelize on top of the fade in the export; the preview approximates them as the
  dominant alpha fade (the timing and reveal match; the texture doesn't).
- **Transition-out** is still unsupported (see the note at the top).

---

## 8. Summary

- Transitions are per-clip, **transition-in only**, stored in `EffectsConfig.transition`
  (`TransitionSpec`), authored in `ClipInspector` for `VIDEO`-track clips.
- `TransitionSpec` now carries a **parameter** — `direction` (`SlideDirection`) for `SLIDE` — and is
  the template for future per-type parameters.
- The window math (`progressAt`) and the effect mapping (`visualAt` → `TransitionVisual`) live in
  **shared `core` code**, so the preview and FFmpeg stay in lock-step.
- The **live `PreviewPanel` now applies transitions** to both images (`graphicsLayer`) and video
  (via the extended `VideoPlayer`, which honors `alpha`/offset — on web by driving the DOM overlay's
  `style.opacity`/position), matching the FFmpeg export.
- Remaining gaps: `CIRCLE` renders as a plain fade everywhere; the preview approximates
  noise/pixelate/voronoi as their dominant fade; and there is still no transition-**out**.
