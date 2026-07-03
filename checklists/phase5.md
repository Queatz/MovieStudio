# Phase 5: FFmpeg & AI Stubs
*Focuses on background process automation, stitching track components, applying complex audio/video filters, and implementing asynchronous mock generation.*

## Tasks
- [x] **FFmpeg Service Execution Engine**
  - [x] Write FFmpegService using Kotlin's JVM ProcessBuilder (executed asynchronously).
  - [x] Implement timeline compiler logic:
    - [x] Create a local scratch/temp directory.
    - [x] Download all assets included on the active timeline.
    - [x] Generate standard FFmpeg arguments (e.g., mapping tracks, overlays, offsets, volume mixers, and color balance curves using -filter_complex).
  - [x] Run the external FFmpeg binary inside Dispatchers.IO.
  - [x] Read Process stderr stream in real-time, parsing execution progress logs (time=) to continuously update Job percentage status.
  - [x] Upload final rendered video MP4 to Alibaba OSS.
  - [x] Set Job.resultUrl to the uploaded resource, change state to COMPLETED, and notify clients.
  - [x] Perform absolute cleanup of all temporary local video/audio files.
- [x] **AI Generation Service Mocking**
  - [x] Create interface AIGenerationService with methods for prompt-based video/audio synthesis.
  - [x] Implement MockAIService:
    - [x] Simulates work using a 3-second delay.
    - [x] Creates a dummy DB asset representing the generated media (referencing a preset OSS placeholder file).
    - [x] Updates job to COMPLETED once the mock delay finishes.
