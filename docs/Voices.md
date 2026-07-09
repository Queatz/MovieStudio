# Voices — the Voice Library and text-to-speech

Movie Studio turns written narration into spoken **voiceover** audio with Alibaba **Qwen3-TTS**
(preset voices) and **CosyVoice** (cloned and designed voices). Every selectable voice lives in a
single **Voice Library** with three sections — **Default Voices**, **Cloned Voices** and **Voice
Design voices** — and any of them can be previewed ("sampled") before use.

This doc is the high-level overview: what a voice is, where the three kinds come from, how a voice
gets picked and previewed, and how narration is finally synthesized. It ties together the shared
`core` models, the server (`QwenAIService` + routes) and the app UI.

> Terminology: user-facing copy always says **"Movie"** — never "Film". The internal data class is
> still named `Film`; this doc uses code names for code and "movie" in prose.

---

## 1. The three kinds of voice

| Kind          | Where it comes from                                  | Model family            | Data class     |
|---------------|------------------------------------------------------|-------------------------|----------------|
| Default       | Ships with the studio (Qwen3-TTS presets)            | `qwen3-tts-*`           | `VoicePreset`  |
| Cloned        | Enrolled from a user's reference **audio sample**    | CosyVoice               | `VoiceClone`   |
| Voice Design  | Synthesized from a natural-language **description**  | CosyVoice               | `VoiceDesign`  |

- **Default Voices** are a fixed, documented catalog — Qwen3-TTS exposes voices as an enumerated
  set, not a queryable endpoint — so they are shipped as data (see `QWEN_VOICE_CATALOG`). Only the
  standard multilingual voices are listed; the region-specific Chinese-dialect voices are omitted
  because the hosted qwen3-tts endpoint rejects them (HTTP 400 "Voice '<name>' is not supported").
- **Cloned Voices** enroll a real voice from a reference recording (Qwen voice cloning, China
  mainland) and reference the enrolled `qwenVoiceId` at synthesis time.
- **Voice Design voices** are brand-new voices *invented* from a text prompt (e.g. "a warm,
  gravelly old storyteller with a slow pace") via CosyVoice Voice Design.

Cloned and designed voices are **both CosyVoice voices** — they enroll against
`QwenConfig.voiceCloneTargetModel` and are synthesized through the same (synchronous) path (§5).

---

## 2. Data model (`core`)

Defined in `core/src/commonMain/kotlin/app/moviestudio/Models.kt` so the server and both app
targets share exactly one definition:

```kotlin
/** A built-in ("default") Qwen3-TTS voice: what a TTS request sends as `voice`, plus display info. */
@Serializable
data class VoicePreset(
    val id: String,
    val name: String,
    val languages: List<String> = emptyList(),
    val description: String = "",
    val gender: String = ""            // "female" / "male" / ""
)

/** A voice enrolled from a reference audio sample (Qwen voice cloning). */
@Serializable
data class VoiceClone(val id: String, val name: String, val qwenVoiceId: String, /* ... */)

/** A voice synthesized from a natural-language description (CosyVoice Voice Design). */
@Serializable
data class VoiceDesign(
    val id: String,
    val name: String,
    val description: String,           // the natural-language design prompt
    val qwenVoiceId: String,           // enrolled voice id TTS requests reference
    val createdAt: Long = 0
)

/** Everything selectable in the Voice Library. */
@Serializable
data class VoiceOptions(
    val presets: List<VoicePreset> = emptyList(),
    val clones: List<VoiceClone> = emptyList(),
    val designs: List<VoiceDesign> = emptyList()
)
```

Related constants in the same file:

- `QWEN_VOICE_CATALOG: List<VoicePreset>` — the Default Voices catalog (the standard multilingual
  Qwen3-TTS voices, each with their spoken languages). This is the single source of truth for the
  presets. Dialect voices the hosted endpoint rejects are intentionally excluded.
- `QWEN_VOICE_PRESETS: List<String>` — just the ids, derived from the catalog, kept for
  defaults/fallbacks (e.g. the default narration voice `"Cherry"`).
- `VOICE_INSTRUCTION_PRESETS` — quick mood chips ("Happy", "Sad", "Excited", …) for instruct TTS.
- `VOICE_SAMPLE_TEXT` — the short friendly line spoken when previewing a voice.

**Voice identity.** Everywhere a voice is referenced at synthesis time it is by the string id that
TTS sends as `voice`: for presets that is `VoicePreset.id`, for clones/designs it is `qwenVoiceId`.

---

## 3. Choosing & previewing a voice (app UI)

The Voice Library UI lives in `app/shared/.../ui/GenerateDialogs.kt`:

- `TtsDialog` shows the currently selected voice via `voiceDisplayLabel(...)` and a **"🎙 Voice
  Library"** button that opens `VoiceLibraryDialog`.
- `VoiceLibraryDialog` has three pill tabs — `DEFAULT` (🔊), `CLONED` (🧬) and `DESIGN` (🎨):
  - **Default** lists every `VoicePreset` with its languages.
  - **Cloned** lists the user's clones with an *enroll* form and per-row delete.
  - **Voice Design** lists designed voices with a *name + description* create form and per-row
    delete.
  - Each row has a **▶ / ⏸ preview** button (`toggleSample`) and is selectable.
- Selecting a row calls `onSelect(voiceId)` and closes the dialog; the id flows back into the TTS
  generation setup.

Per project guidelines, every clickable row/card in the dialog uses `Modifier.clip(shape)` **before**
`.clickable { }` so hover/press highlights follow the rounded corners.

Preview state is driven by `AppViewModel`:

- `voiceOptions: VoiceOptions` — loaded via `refreshVoices()` → `NetworkService.getVoiceOptions()`.
- `samplingVoiceId: String?` — the voice currently loading/playing (drives the ▶/⏸ icon).
- `sampleVoice(voiceId, text)` / `stopVoiceSample()` — play a preview through the shared audio pool
  (`VOICE_SAMPLE_KEY`); a blank URL (AI not configured) surfaces a friendly message.
- `createVoiceDesign(...)` / `deleteVoiceDesign(...)` — manage designed voices, then refresh.

---

## 4. Server API

`server/.../routing/LibraryRoutes.kt` exposes the Voice Library under `/api/voice`:

| Method & path              | Purpose                                                        |
|----------------------------|----------------------------------------------------------------|
| `GET /api/voice/options`   | Full library: `presets` + `clones` + `designs` (`VoiceOptions`)|
| `POST /api/voice/sample`   | Synthesize a short preview, returns a playable `url`           |
| `POST /api/voice/clones`   | Enroll a cloned voice from an audio `url`                      |
| `DELETE /api/voice/clones/{id}` | Delete a cloned voice                                     |
| `POST /api/voice/designs`  | Design a voice from a `name` + `description`                  |
| `DELETE /api/voice/designs/{id}` | Delete a designed voice                                 |

These delegate to `AIGenerationService` (`listVoicePresets`, `sampleVoice`, `createVoiceClone`,
`createVoiceDesign`). Clones and designs persist in ArangoDB via `VoiceCloneRepository` /
`VoiceDesignRepository` (`DbCollection.VOICE_CLONES` / `VOICE_DESIGNS`).

Every call **degrades gracefully** when Qwen is not configured: `listVoicePresets()` still returns
the static catalog, `sampleVoice()` returns an empty URL, and clone/design creation produces an
offline mock voice.

---

## 5. Synthesizing voiceover (`QwenAIService`)

`server/.../service/QwenAIService.kt` decides the model and endpoint per voice:

```kotlin
private fun isCosyVoiceVoice(voiceId: String): Boolean =
    VoiceCloneRepository.listAll().any { it.qwenVoiceId == voiceId } ||
        VoiceDesignRepository.listAll().any { it.qwenVoiceId == voiceId }
```

- **Preset voice** → **qwen-tts** at `services/aigc/multimodal-generation/generation` (sync),
  built by `buildTtsRequestBody(...)`. Voice **instructions** ("happy", "whispering", …) switch the
  model to `QwenConfig.ttsInstructModel` and add an `instruct` field.
- **Cloned or designed voice** (`isCosyVoiceVoice`) → **CosyVoice** at
  `services/audio/tts/generation` (**synchronous**), built by `buildClonedVoiceTtsRequestBody(...)`.
  This request sends the enrolled `voice_id` and omits the unsupported `instruct` field — routing
  here is what fixed the earlier HTTP 400 "url error" the qwen-tts endpoint returned for
  `cosyvoice-*` models. The call is intentionally **not** asynchronous: the hosted account rejects
  async CosyVoice calls with HTTP 403 "current user api does not support asynchronous calls", so the
  audio URL is read straight from the response (`extractSyncTtsAudioUrl`) instead of being polled.

`executeTts(...)` picks the path (falling back to `"Cherry"` when no voice is set); the request
builders are pure/network-free so their shape is unit-tested. Enrollment for designs uses
`buildVoiceDesignRequestBody(...)` against the `voice-enrollment` model, targeting
`QwenConfig.voiceCloneTargetModel` (`cosyvoice-v3.5-plus`).

**Speed & pitch.** `TtsDialog` exposes two sliders — **Speed** and **Pitch** — that set
`GenerationSetup.speed` / `GenerationSetup.pitch` (0.5×–2.0×, `1.0×` = the voice's natural
delivery; bounds are the `TTS_MIN_*`/`TTS_MAX_*` constants). They apply to all three voice kinds:
`buildTtsRequestBody(...)` forwards them to Qwen as the `parameters.rate` / `parameters.pitch`
synthesis parameters (only when moved off the default, so a plain voiceover's request is
unchanged), and `buildClonedVoiceTtsRequestBody(...)` forwards them to CosyVoice as
`payload.parameters.rate` / `payload.parameters.pitch`.

Relevant `QwenConfig` model names (all overridable via env):

| Setting                 | Default env                    | Used for                          |
|-------------------------|--------------------------------|-----------------------------------|
| `ttsModel`              | `qwen3-tts-flash`              | Preset TTS                        |
| `ttsInstructModel`      | `qwen3-tts-instruct-flash`     | Preset TTS with instructions      |
| `voiceEnrollModel`      | `voice-enrollment`             | Clone + design enrollment         |
| `voiceCloneTargetModel` | `cosyvoice-v3.5-plus`          | CosyVoice synthesis target        |

---

## 6. Where voiceover audio ends up

A generated voiceover becomes an `Asset` of type `AssetType.VO`. Voice assets auto-generate a
`transcript` + `wordTimings` (via `AIGenerationService.generateTranscript`); editing the transcript
re-derives word timings with `buildWordTimings(...)`. Regenerating a voiceover with a different
voice keeps clip timing in sync (covered by `TtsRegenerationClipSyncTest`).

---

## 7. Tests

- `server/.../QwenTtsRequestTest` — preset vs cloned vs designed request bodies, `instruct`
  handling, and the Default Voices catalog.
- `server/.../TtsRegenerationClipSyncTest` — voiceover regeneration keeps clips in sync.
- `server/.../Phase5IntegrationTest` — `/api/voice/options` shape.

---

## 8. Adding or changing voices

- **New default voice:** add a `VoicePreset` to `QWEN_VOICE_CATALOG` in `core/.../Models.kt` — it
  automatically appears in the library, the presets list and `listVoicePresets()`.
- **Different Qwen model / endpoint:** override the `QwenConfig` env values above; only the URL
  strings in `QwenAIService` synthesis helpers would change if a deployment exposes different paths.
- **New user voice at runtime:** clones and designs are created through the `/api/voice` endpoints —
  no code change needed.
