# 🎬 Movie Studio

**Movie Studio** is an ultra-simple, AI-powered movie creation platform that transforms a single text prompt into a feature-length film (up to 240 minutes and beyond) while allowing for precise, manual clip-by-clip refinement.

Acting as an "AI Director," the application orchestrates multiple generative AI models to script, storyboard, and generate video, music, and voiceover tracks. Users can then step in to manually construct and edit the movie using a multi-track timeline, with all final rendering handled seamlessly on the server.

### ✨ Key Features
* **Movie Skeletons (Prompt-to-Movie):** Describe a scene or a whole movie and Qwen plans placeholder items straight onto the timeline at the playhead — each one generatable (and regeneratable, with restorable history) with one click.
* **Multi-Track Timeline Editor:** Draggable/resizable clips, a scrubbable playhead, zoom + scroll controls, per-clip transitions (alpha, noise, voronoi, slide, circle, pixelate) over overlapping media, volume, and space-bar play/pause.
* **Generate Anything:** WAN 2.7 video (T2V / I2V / R2V picked predictably from your inputs), images, Fun-Music songs (AI lyrics + theme + instrumental), sound effects (video ➜ audio extraction + clipping), and Qwen TTS voiceovers with word-timed transcripts and captions.
* **Voice Suite:** Preset Qwen voices plus voice cloning, editable transcripts that re-run through Qwen for word offsets, a visual word-timing editor, and a caption editor with a font chooser.
* **Saved Characters & Scenes:** Reusable libraries (name, description, up to 3 reference images) that plug into generations as R2V references.
* **Global Media Library:** One holistic asset library across every movie — uploads, AI generations, sequencer tracks, frame captures, sound effects.
* **Async Everything + Live Progress:** Every generation and render runs as a server job with WebSocket progress; a background-generations panel shows all in-flight work.
* **Server-Side Rendering with History:** FFmpeg compiles the timeline (transitions, captions, images, offsets) into an MP4, uploads it to Alibaba OSS, and every render stays replayable and downloadable.
* **Auto Light/Dark Theme:** The studio follows the system theme, while the movie preview and timeline keep their cinematic dark look.

### 🛠️ Tech Stack
* **Shared Logic:** Kotlin Multiplatform (KMP) `commonMain`
* **Frontend:** Compose for Web (Wasm target)
* **Backend:** Ktor (Kotlin/JVM)
* **Database:** ArangoDB (Document Store)
* **File Storage:** Alibaba Cloud OSS
* **Media Processing:** FFmpeg (Server-side execution)

*** 

# Development

This is a Kotlin Multiplatform project targeting Android, Web, Desktop (JVM), Server.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
      folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Configuration & Secrets

Server secrets (Alibaba Cloud OSS and the Qwen / Alibaba Model Studio AI integration) live in a
single `.env` file. Never commit real credentials — copy the template and fill it in locally:

```
cp .env.example .env   # then edit .env with your real values
```

`.env` is git-ignored. It is loaded directly by the server at startup (via `dotenv-kotlin`), so there
is no need to `source` it or export anything manually — just run:

```
./gradlew :server:run
```

Recognized variables:

| Variable | Purpose |
| --- | --- |
| `ALIBABA_ACCOUNT_ID` | Alibaba Cloud account id (reference only). |
| `OSS_ENDPOINT` | OSS region endpoint (e.g. `oss-ap-southeast-1.aliyuncs.com`). |
| `OSS_BUCKET_NAME` | OSS bucket name. |
| `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` | RAM user credentials for OSS. |
| `QWEN_API_KEY` | Alibaba Model Studio (Qwen) API key. |
| `QWEN_API_HOST` | Model Studio API host. |
| `QWEN_OPENAI_BASE_URL` | OpenAI-compatible endpoint (`<host>/compatible-mode/v1`). |
| `QWEN_DASHSCOPE_BASE_URL` | DashScope endpoint (`<host>/api/v1`). |
| `QWEN_CHAT_MODEL` | LLM for prompt refinement, lyrics/themes and skeleton planning (default `qwen-plus`). |
| `QWEN_VIDEO_MODEL_T2V` | WAN text-to-video model (default `wan2.7-t2v`). |
| `QWEN_VIDEO_MODEL_I2V` | WAN image-to-video model (default `wan2.7-i2v`). |
| `QWEN_VIDEO_MODEL_R2V` | WAN reference-to-video model (default `wan2.7-r2v`). |
| `QWEN_IMAGE_MODEL` | Text-to-image model (default `wanx2.1-t2i-turbo`). |
| `QWEN_MUSIC_MODEL` | Music generation model (default `fun-music-preview`). |
| `QWEN_TTS_MODEL` | Text-to-speech model (default `qwen-tts`). |
| `QWEN_VOICE_ENROLL_MODEL` | Voice cloning enrollment model (default `voice-enrollment`). |
| `QWEN_VOICE_CLONE_TARGET` | TTS model cloned voices target (default `cosyvoice-v2`). |
| `QWEN_TRANSCRIPTION_MODEL` | Speech-to-text model for transcripts (default `paraformer-v2`). |
| `QWEN_POLL_INTERVAL_MS` | Async task poll interval in ms (default `3000`). |
| `QWEN_POLL_TIMEOUT_MS` | Async task max wait in ms (default `300000`). |

The server always uses `QwenAIService`, which calls the real Alibaba Model Studio (Qwen / DashScope) APIs to generate media, re-hosts the results on OSS, and persists them as `Asset`s. Set `QWEN_API_KEY` (and the other `QWEN_*` variables) in `.env` before running the server.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and
options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Desktop app:
    - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
    - Standard run: `./gradlew :app:desktopApp:run`
- Server: `./gradlew :server:run`
- Web app:
    - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
    - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Desktop tests: `./gradlew :app:shared:jvmTest`
- Server tests: `./gradlew :server:test`
- Web tests:
    - Wasm target: `./gradlew :app:shared:wasmJsTest`
    - JS target: `./gradlew :app:shared:jsTest`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
