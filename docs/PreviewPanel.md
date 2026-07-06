# The Preview Panel — how the movie is composited

`PreviewPanel`
(`app/shared/src/commonMain/kotlin/app/moviestudio/ui/PreviewPanel.kt`) is the "stage" where the
movie is played back live inside the editor. It is the **client-side** mirror of the final,
server-side FFmpeg render (`server/.../service/FFmpegService.kt`): whatever you see under the
playhead in the preview is what the exported movie will look like at that instant.

This document explains what the panel does, every visual content type it can show, and how those
pieces stack together to form the finished movie.

> Terminology: user-facing copy always says **"Movie"**. The internal data class is still named
> `Film`, so this doc uses `Film`/`Film.aspectRatio` for code references and "movie" in prose.

---

## 1. Responsibilities

The panel is a pure function of three inputs from `AppViewModel`:

- `timeline` — the `FilmTimeline` (tracks + clips) currently being edited.
- `playhead` — the current time, in seconds (a `Float`). The playback ticker in `AppViewModel` is
  the **master clock**; everything else (video element, audio pool) is slaved to it.
- `libraryAssets` — used to resolve each clip's `assetId` into a concrete `Asset`.

From those it must, on every recomposition:

1. Resolve **everything under the playhead** into `ActiveClip`s.
2. Composite all **visual** clips onto one aspect-constrained stage.
3. Keep the **audio** pool (music / voice / sound effects) in sync.
4. Draw **captions** for active voice clips.
5. Offer transport controls (play/pause, timecode, save-frame, fullscreen and — on the web — the
   Default/WebGL preview-method toggle) — hidden in the distraction-free `fullscreen` playback
   mode.

---

## 2. The stage

```
Column
└─ Box  (weight=1, always dark; rounded chrome unless fullscreen)
   └─ Box (fillMaxSize, padding)
      └─ Box (aspectRatio(Film.aspectRatio), black)   ← THE STAGE
         ├─ visual clips (stacked by zIndex)          ← images / video / text
         ├─ EmptyStageContent (only if nothing visual)
         └─ captions (voice clips, drawn on top)
```

- The innermost black `Box` is sized with `aspectRatio(aspectRatioToFloat(Film.aspectRatio))`, so
  the stage always has the movie's shape (`16:9`, `9:16`, `1:1`, … from `SUPPORTED_ASPECT_RATIOS`).
- Media is fit with **center-crop ("cover")**: it fills the whole stage and the overflow is
  cropped. This is deliberately identical to the FFmpeg render, which does
  `scale=…:force_original_aspect_ratio=increase` followed by a `crop`.
- The stage is **always dark** regardless of the app theme, and in `fullscreen` mode it drops the
  rounded corners, padding and transport row so only the movie is visible (ESC exits).

---

## 3. Resolving what is "under the playhead"

```kotlin
private data class ActiveClip(val clip: Clip, val asset: Asset, val trackType: TrackType, val zIndex: Int)
```

A clip is active when `playhead ∈ [timelineStart, timelineStart + (trimOut - trimIn))`. For each
active clip we look up its `Asset` and remember the owning track's `type` and `zIndex`. Everything
downstream is a filter over this `activeClips` list, so **all tracks are considered** — not just the
top one.

---

## 4. The compositing model

The timeline is multi-track. Tracks carry a `TrackType` and a `zIndex` (draw order):

| `TrackType` | Carries | Rendered by the preview as |
|-------------|---------|-----------------------------|
| `VIDEO`     | images, video clips, description-only cards | **visual** content on the stage |
| `MUSIC`     | music assets      | audio only (audible, not visible) |
| `VOICE`     | voice-over assets | audio + optional word-timed captions |
| `EFFECTS`   | sound-effect assets | audio only |

### Visual layering

All visual content lives on `VIDEO`-type tracks. The panel collects every active `VIDEO`-track clip
and sorts it **ascending by `zIndex`**, then draws them in that order so a higher-`zIndex` clip (an
overlay track) is painted **on top** of a lower one:

```kotlin
val visualClips = activeClips
    .filter { it.trackType == TrackType.VIDEO }
    .sortedBy { it.zIndex }
```

This ordering matches FFmpeg, which overlays video clips
`sortedWith(compareBy { track.zIndex }.thenBy { it.timelineStart })`.

### Audio mixing

Every active clip on a non-`VIDEO` track that has media becomes an `AudioPlayItem` and is handed to
`updateAudioPlayback(items, isPlaying)`. That reconciles a pool of `<audio>` elements: new sounds
start, finished ones stop, and each is seeked to `sourceOffsetSeconds + trimIn + (playhead -
timelineStart)`. Per-clip volume is evaluated from the clip's volume envelope at the playhead via
`EffectsConfig.volumeAt(...)`, so fades work during scrubbing and playback.

---

## 5. Visual content types

### 5.1 Still image — `AssetType.IMAGE`

Rendered with a **plain Compose component**, Coil's `AsyncImage`:

```kotlin
AsyncImage(
    model = asset.ossUrl,
    contentScale = ContentScale.Crop,               // center-crop "cover"
    alignment = BiasAlignment(                       // 0-100 offsets → -1..+1 bias
        horizontalBias = ((offsetX - 50.0) / 50.0).toFloat().coerceIn(-1f, 1f),
        verticalBias   = ((offsetY - 50.0) / 50.0).toFloat().coerceIn(-1f, 1f),
    ),
    modifier = Modifier.fillMaxSize(),
)
```

Because `AsyncImage` is a normal Compose node laid out inside the aspect-ratio `Box`:

- it is **contained** within the stage (Compose clips drawing to the node's bounds),
- it is **centered** when both offsets are `50`, and
- `ContentScale.Crop` gives the same center-crop scaling as video and as the FFmpeg render.

Coil is configured once at the app root (`App.kt`) via `setSingletonImageLoaderFactory { … add(KtorNetworkFetcherFactory()) … }`; a per-platform Ktor engine (browser fetch on web, CIO on
desktop, OkHttp on Android) lets it load the OSS URL on every target.

**Crop-offset parity.** The `offsetX`/`offsetY` (0-100, 50 = center) come from the clip's
`EffectsConfig`. FFmpeg crops at `(iw-ow) * offsetX/100`, i.e. `offsetX = 0` shows the left edge,
`100` the right edge, `50` the center. `BiasAlignment` bias `= (offset - 50) / 50` maps to exactly
the same window (`-1` = left/top edge, `0` = center, `+1` = right/bottom edge).

### 5.2 Video — `AssetType.VIDEO`

Rendered by `VideoPlayer`, which on the web is an HTML5 `<video>` element overlaid on the Compose
canvas (see "No client-side video rendering" in `Design.md`; the browser only *plays* HTML5 video,
it never decodes frames into Compose). Its playback time is driven from the master clock:

```kotlin
val mediaTime = asset.sourceOffsetSeconds + clip.trimIn + (playhead - clip.timelineStart)
```

Crop offset for video is applied to the `<video>` element's CSS `object-position` through
`setPreviewObjectPosition(offsetX, offsetY)` (the `object-fit: cover` element already center-crops).

**DPI-correct positioning.** The overlay is placed over the Compose stage from
`onGloballyPositioned` (offset + size). Compose Web lays out in *physical* pixels
(CSS px × `devicePixelRatio`), but CSS `left/top/width/height` are *logical* pixels, so
`VideoPlayer` divides the reported bounds by `LocalDensity.current.density` before writing them.
Without this the `<video>` is drawn at `devicePixelRatio`× the position and size on high-DPI (retina)
screens — overflowing the stage with the wrong scale/offset. (Still images don't need this: an
`AsyncImage` is a native Compose node laid out in the same coordinate space as the stage.)

**Element lifecycle (don't hide on every tick).** `VideoPlayer` receives the ever-advancing
`playhead`, so the src/play/seek sync lives in a `LaunchedEffect(url, isPlaying, playhead)`. The
shared `<video>` is hidden (`display:none` + pause) **only** from a separate `DisposableEffect(Unit)`
that fires when the player leaves the composition (no video clip under the playhead). Keying the hide
on `playhead` (as before) hid the element on every frame while `display:block` was only re-applied
from `onGloballyPositioned` (which runs on layout, not per tick), so the video showed for one frame
and then went black.

**Single-video constraint.** The web preview shares **one** `<video id="compose-video-preview">`
element, so only one video clip can play at a time. If several video clips overlap, the panel plays
the **top-most** (`activeVideo = visualClips.lastOrNull { it is a video }`) and skips the rest.
Simultaneous images and text on other tracks still render normally.

**Known layering limitation.** The `<video>` element is a DOM overlay drawn *above* the Compose
canvas, so a playing video visually sits on top of canvas-drawn content (images, text, captions)
regardless of `zIndex`. In practice the common cases work well — one video at a time, or images/text
as the visual layer — and the exported FFmpeg render always layers strictly by `zIndex`. When true
image-over-video layering (or several simultaneous videos) is needed in-preview, switch to the
**WebGL preview method** (section 8), which is exactly such a canvas renderer.

### 5.3 Description-only card (no media yet)

An asset with a blank `ossUrl` is `isDescriptionOnly`. These are placeholders created by the AI
skeleton generator before media exists. They render as large, centered white text
(`asset.description ?: asset.aiPrompt ?: "Untitled scene"`), so an un-generated scene still occupies
its slot on the stage. This mirrors the exported movie, where such a clip is a text card too.

### 5.4 Captions (voice clips)

For each active `VOICE` clip whose `EffectsConfig.captions` is enabled, `CaptionOverlay` shows the
word-timed transcript chunk for the current time (`asset.wordTimings`), styled by `CaptionConfig`
(font family/size/color/position). Captions are drawn **after** the visual clips so they sit on top
(subject to the video-overlay limitation in 5.2). FFmpeg burns the same captions into the export.

### 5.5 Empty stage

When no `VIDEO`-track clip is under the playhead, `EmptyStageContent` shows a prompt box that can
generate a movie skeleton in place — the same affordance as the timeline's Generate bar.

---

## 6. The playback clock

`AppViewModel` runs the ticker that advances `playhead`; it is the single source of truth:

- Video: the `<video>` element is **seeked/paused/played to match** the playhead (it re-syncs when
  it drifts more than ~0.5 s). Its own `ontimeupdate` is intentionally ignored.
- Audio: each pooled `<audio>` element is re-seeked when it drifts more than ~0.35 s.

This keeps images (instant), video (streamed) and audio (streamed) all aligned to one timeline,
whether the user is playing or scrubbing.

### 6.1 Preloading & persistence

Because the master clock jumps between clips (playing or scrubbing), media that is only fetched
*when* the playhead reaches its clip stalls the preview: a `<video>` `src` swap re-buffers, a
freshly-created `<audio>` re-downloads and an uncached image pops in late. To keep the preview
smooth, `PreviewPanel` preloads **every** media file on the timeline up front and keeps it
persistently buffered for the session:

```kotlin
val preloadItems = remember(timeline, viewModel.libraryAssets) {
    collectPreloadMedia(timeline, viewModel.libraryAssets)
}
LaunchedEffect(preloadItems) { preloadTimelineMedia(preloadItems) }
```

- `collectPreloadMedia(timeline, assets)` (in `PlatformBridge.kt`) is a pure, unit-tested function
  that walks every clip, resolves its `Asset` and returns each **distinct** media URL once (blank
  `ossUrl` description-only clips skipped), tagged with a `PreloadKind` (`VIDEO` / `IMAGE` / `AUDIO`)
  derived from the asset type. It is keyed on `timeline` + `libraryAssets`, so it recomputes only
  when the set of timeline media changes — never on a plain playhead tick.
- `preloadTimelineMedia(items)` (the platform bridge) reconciles a **session-lived pool keyed by
  URL** (`window.__msPreloadPool`) of hidden, CORS-loaded `<video>`/`<audio>` elements
  (`preload='auto'`, muted) and decoded `<img>` loaders. Warming these keeps the browser's HTTP
  cache and decode buffers primed, so the shared preview `<video>`, the `<audio>` playback pool
  (§4) and Coil image loads (§5.1) all resolve **instantly** from cache instead of loading on
  demand. URLs no longer on the timeline are released on the next reconcile. No-op on
  desktop/Android (no DOM).

This runs from `PreviewPanel` specifically (not the timeline editor), so it also warms the media for
the distraction-free `fullscreen` playback mode, where the timeline panel is not composed.

---

## 7. How it all comes together

At any playhead position the finished movie frame is:

```
(bottom)  lowest-zIndex VIDEO-track clip
          … more VIDEO-track clips, ascending zIndex …
(top)     highest-zIndex VIDEO-track clip
          + captions
          + [all MUSIC/VOICE/EFFECTS audio mixed and playing]
```

Every visual clip is center-cropped into the movie's aspect ratio and positioned by its 0-100
offsets; every audio clip is mixed at its envelope volume and correct source position. The preview
computes this composite live in Compose; FFmpeg computes the identical composite offline to produce
the exported MP4. Keeping the two in agreement — same aspect canvas, same center-crop + offset math,
same `zIndex` order, same captions/volume — is what makes the preview a faithful proxy for the final
masterpiece.

---

## 8. The WebGL preview method

The transport row offers a preview-method toggle (`🎛 Default` / `🎛 WebGL`; the label shows the
method in use). It is only shown where `isWebGLPreviewSupported()` is true (web targets with
WebGL) and is stored as `AppViewModel.previewUseWebGL`. In the WebGL method the stage's media
compositing moves off the DOM `<video>` + Compose path onto the **GPU**, and — unlike the default
`<video>` overlay — the composited frame is drawn back **inside the Compose scene graph** so
dialogs, cards and captions layer over it automatically
(`WebGLPreview.kt` expect + the `WebGLPreview.js.kt` / `WebGLPreview.wasmJs.kt` actuals):

- `PreviewPanel` converts every media-bearing visual clip under the playhead into a
  `WebGLPreviewLayer` (bottom-to-top by `zIndex`) carrying the **same** transition-in state and
  0-100 crop offsets the default renderer evaluates, then hands the stack to
  `WebGLPreviewSurface`.
- The platform actual keeps a **detached** `<canvas>` (never added to the DOM, so it can never
  cover Compose UI) and composites the layers with a WebGL shader: cover-fit crop window
  (FFmpeg's `(iw-ow) * offset/100`), cross-fade alpha, slide translate and the pixel-space
  circular reveal — all matching the default method and the FFmpeg export.
- **Rendered into Compose (Option 1).** Each Compose frame (`withFrameNanos`) the surface sizes
  the canvas to the stage (device pixels, capped), renders, `gl.readPixels(...)` the frame back
  and wraps the bytes in a Skia `Image` (`Image.makeRaster(...).toComposeImageBitmap()`). That
  `ImageBitmap` is painted in a Compose `Canvas` at the bottom of the stage, mirrored vertically
  (WebGL's `readPixels` is bottom-up). This GPU→CPU→GPU round-trip per frame is the method's main
  cost, acceptable for a preview; the compositing/transition math still runs on the GPU.
- **Textures.** Still images are fetched once per URL (CORS) and cached for the session. Each
  video layer gets a hidden, CORS-loaded `<video>` element (pooled by clip id; kept in the DOM
  only so the browser decodes/plays it — it is `display:none`, never visible) whose current frame
  is re-uploaded to its texture every frame. Videos re-seek when they drift more than 0.5 s and
  play/pause with the master clock; only the **top-most** video layer is audible (parity with the
  default method's single shared element).
- **Differences from the default method.** Several overlapping videos render simultaneously, and
  layering is strictly by `zIndex` — the DOM-overlay limitation of 5.2 does not apply. Because the
  preview is now a real Compose node, description cards and captions layer **on top** correctly and
  Material dialogs/popups are never obscured (the whole reason for Option 1). Sliding clips are
  inherently clipped to the stage (the transform happens inside the canvas), like the FFmpeg render.
- **Save frame** captures the WebGL canvas directly (created with `preserveDrawingBuffer`, so
  `toBlob` sees the last rendered frame) when this method is active. Since the canvas is no longer
  in the DOM it is reached through the compositor state (`window.__msWebGLPreview.canvas`, guarded
  by an `active` flag). With no media layers the surface paints nothing, so the Compose empty state
  / description cards show through.

---

## 9. Platform notes

- **Images** render on **all** targets (web, desktop, Android) through Coil `AsyncImage`.
- **Video and audio** use DOM `<video>`/`<audio>` bridges and are therefore fully functional on the
  **web** target (the app's primary frontend, per `Design.md`). On desktop/Android the video bridge
  is a placeholder; images and layout still work.
- There is intentionally **no custom `expect`/`actual` image player** — image rendering is done with
  standard Compose components so it behaves like the rest of the UI (correct clipping, scaling and
  alignment).
