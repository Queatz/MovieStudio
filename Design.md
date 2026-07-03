# Project Context
You are an expert Kotlin Multiplatform (KMP) and Compose Web developer. We are building the MVP for an AI-powered movie maker application. The app allows users to generate or upload media, arrange it on a multi-track timeline, and render a final video.

# Tech Stack & Constraints
- **Shared Logic:** Kotlin Multiplatform (KMP) `commonMain` for data models, DTOs, and validation.
- **Backend:** Ktor (Kotlin) running on JVM.
- **Frontend:** Compose for Web (Wasm target).
- **Database:** ArangoDB (Document store).
- **File Storage:** Alibaba Cloud OSS.
- **Video Processing:** FFmpeg (executed server-side via Kotlin `ProcessBuilder` or a wrapper).
- **AI Generation:** Stubbed for MVP (Interfaces created for Qwen/Suno integration later).

# Core Architecture & Rules
1. **NO client-side video rendering.** The browser will only playback HTML5 `<video>` elements. Final rendering MUST happen server-side via FFmpeg.
2. **Global Library Pattern:** All media (clips, audio, music) are stored as `Assets`. Assets belong to a global library but are tagged with the `film_id` they were created for.
3. **State Management:** Frontend uses a unidirectional data flow. The timeline is just a visual representation of a JSON state tree.
4. **Asynchronous Processing:** AI generation and FFmpeg rendering are long-running. The backend must use a job queue pattern (e.g., ArangoDB `jobs` collection polled by background coroutines) and notify the frontend via WebSockets or polling.

# Data Models (ArangoDB Collections)
Use ArangoDB Document collections. Define these in `commonMain` as Kotlin `data class`es with `@Serializable`.

1. **Film**: `id`, `title`, `total_duration`, `status` (draft, rendering, completed), `created_at`.
2. **Asset**: `id`, `type` (VIDEO, AUDIO, MUSIC, VO, IMAGE), `oss_url`, `duration_seconds`, `film_id` (nullable for global assets), `tags` (List<String>), `ai_prompt` (nullable).
3. **Track**: `id`, `film_id`, `type` (VIDEO, MUSIC, VO, EFFECTS), `z_index` (int).
4. **Clip**: `id`, `track_id`, `asset_id`, `timeline_start` (float), `trim_in` (float), `trim_out` (float), `effects_config` (JSON string for color grading, etc.).
5. **Job**: `id`, `film_id`, `type` (AI_GEN, FFMPEG_RENDER), `status` (PENDING, RUNNING, COMPLETED, FAILED), `payload` (JSON), `result_url` (nullable).

# Step-by-Step Execution Plan
Execute this project in the following phases. Do not move to the next phase until the current one is complete and compiling.

## Phase 1: Project Setup & KMP Structure
- Initialize a KMP project with `commonMain`, `jvmMain` (Backend), and `wasmJsMain` (Frontend).
- Set up Ktor server with ContentNegotiation (kotlinx.serialization) and WebSockets.
- Set up Compose Web (Wasm) with basic routing.
- Define all Data Models (Film, Asset, Track, Clip, Job) in `commonMain`.

## Phase 2: Database & OSS Integration (Backend)
- Implement ArangoDB connection and repositories for all models.
- Implement Alibaba OSS service for uploading files and generating pre-signed URLs for the frontend to upload directly (to save backend bandwidth).
- Create endpoints for CRUD operations on Films and Assets.

## Phase 3: Timeline & Asset Management (Backend)
- Create endpoints to manage Tracks and Clips (add, move, trim).
- Implement the "Global Library" logic: endpoints to fetch assets, filterable by `film_id` or global.
- Implement the `Job` queue logic: background coroutines that pick up PENDING jobs and update their status.

## Phase 4: Frontend UI & Timeline (Compose Web)
- **Dashboard:** A simple grid showing all Films. Clicking one opens the Editor.
- **Editor Layout:**
    - Top: Playback controls and HTML5 `<video>` preview window (use `web.dom` interop).
    - Middle: The Timeline. Build this using Compose Canvas or absolute-positioned DOM elements. It must support horizontal scrolling and zooming.
    - Bottom/Side: The "Global Library" panel to drag-and-drop Assets onto the Timeline.
- Implement state management to hold the current Film, Tracks, and Clips.

## Phase 5: FFmpeg & AI Stubs
- Create an `FFmpegService` in the backend. Write a Kotlin function that takes the Timeline JSON, downloads the assets from OSS to a temp directory, generates an FFmpeg command to stitch the video/audio tracks, applies basic color grading (via FFmpeg filters), and uploads the final MP4 back to OSS.
- Create an `AIGenerationService` interface with methods like `generateVideo(prompt)` and `generateMusic(prompt)`. Implement a `MockAIService` that just creates a dummy Asset and marks the Job as complete after a 3-second delay.

# Specific Implementation Details for the Agent
- **Frontend Timeline:** Do not use heavy UI frameworks for the timeline. Use raw Compose Canvas for drawing the clips and playhead. Use `Modifier.pointerInput` for drag-and-drop logic.
- **Video Playback:** In Compose Web Wasm, create an HTML `<video>` element via `document.createElement("video")`, set its `src` to the OSS URL, and append it to a Compose `HTMLDiv` using `ref` or `web.dom` interop.
- **FFmpeg Execution:** Ensure the backend handles FFmpeg execution asynchronously. Use `CoroutineScope(Dispatchers.IO)` to run the `ProcessBuilder` so it doesn't block the Ktor event loop. Parse the FFmpeg stderr to update the Job progress.

# First Action
Acknowledge this prompt. Then, begin **Phase 1** by generating the `build.gradle.kts` files for the KMP project, ensuring all necessary dependencies (Ktor, Compose Web, ArangoDB driver, kotlinx-serialization) are correctly configured for Kotlin 2.0+ and Wasm.
