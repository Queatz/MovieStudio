# Movie Studio — Project Guidelines for AI Agents

These are conventions and recurring gotchas for anyone (human or AI) working on this codebase.
Read them before making UI or model changes.

## Terminology

- Use the word **"Movie"** in all user-facing copy — never "Film".
  The internal data class is still named `Film` (renaming it would churn the DB/serialization and
  the REST paths under `/api/films`), but every string shown to the user must say "Movie".

## Compose UI: clickable components MUST clip to their shape (COMMON ISSUE)

**Problem we keep hitting:** clickable/hoverable Compose components (Cards, Boxes with
`Modifier.clickable`, custom pointer-input surfaces, etc.) show a **rectangular** hover / press /
ripple highlight that does not match the component's rounded background. The corners of the
highlight "stick out" past the rounded card.

**Root cause:** the ripple/indication is drawn on the component's *layout bounds*. If the rounded
corners come only from a `shape =` parameter or a `background(color, shape)` **after** the
clickable, the indication is not clipped to that shape.

**Fix (do this for EVERY clickable component):** add a `Modifier.clip(shape)` **before** the
`.clickable { }` / `.pointerInput { }` / interaction modifier, using the *same* shape as the
background:

```kotlin
Card(
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp)) // <-- clip BEFORE clickable so hover has rounded corners
        .clickable { ... },
    shape = RoundedCornerShape(12.dp),   // keep the visual shape in sync
)
```

Rules of thumb:
- Round `IconButton`s: `Modifier.clip(CircleShape)`.
- Pills / chips / rounded buttons: `Modifier.clip(RoundedCornerShape(50))`.
- Any `Canvas`/`Box` that is drawn with rounded corners and is interactive: `.clip(sameShape)`.
- `import androidx.compose.ui.draw.clip`.

Material `Button`, `OutlinedButton`, `TextButton` already clip their indication to their `shape`,
so an extra `clip` on them is optional (we still add it on the pill-style ones for consistency).
The components that actually break are **`Card` + `clickable`** and **`Modifier.clickable` on a
`Box`/custom surface** — always clip those.

## Data model notes

- `Film.aspectRatio` (e.g. `"16:9"`) drives the player (center-crop fit via `object-fit: cover`)
  and the FFmpeg render canvas. Parse it with `aspectRatioToFloat(...)`; pick options come from
  `SUPPORTED_ASPECT_RATIOS`.
- Movies have **no pre-set length**. `Film.totalDuration` is auto-calculated from the media on the
  timeline via `FilmTimeline.calculatedDuration()` and persisted when clips change.
- Voice assets (`AssetType.VO`) auto-generate a `transcript` + `wordTimings` via Qwen
  (`AIGenerationService.generateTranscript`). Editing the transcript re-derives word timings with
  `buildWordTimings(...)`.
