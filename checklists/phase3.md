# Phase 3: Timeline & Asset Management (Backend)
*Focuses on writing the track/clip APIs, implementing the "Global Library" retrieval, setting up the backend coroutine job queue worker, and configuring WebSockets.*

## Tasks
- [x] **Track & Clip Database Schema Operations**
  - [x] Implement TrackRepository (CRUD operations for timeline tracks).
  - [x] Implement ClipRepository (CRUD operations for clips on tracks, ensuring validity of start times and offsets).
- [x] **Timeline APIs (/api/films/{filmId}/...)**
  - [x] **Get Timeline Flow**:
    - [x] GET /api/films/{filmId}/timeline - Gather and return a consolidated JSON tree containing the Film, its Track structures, and all associated Clip items.
  - [x] **Track & Clip Endpoints**:
    - [x] POST /api/films/{filmId}/tracks - Append a track.
    - [x] DELETE /api/films/{filmId}/tracks/{trackId} - Delete a track and cascade delete its clips.
    - [x] POST /api/films/{filmId}/clips - Add a clip to a track.
    - [x] PUT /api/films/{filmId}/clips/{clipId} - Modify start, end, trim, or track position of a clip.
    - [x] DELETE /api/films/{filmId}/clips/{clipId} - Delete a clip.
- [x] **Global Library Logic**
  - [x] GET /api/library - Retrieve asset list filtering by filmId and asset type/tags.
- [x] **Background Job Engine**
  - [x] Implement JobRepository for status transitions.
  - [x] Build a coroutine-based worker loop running on Ktor startup (using CoroutineScope(Dispatchers.IO)):
    - [x] Periodically polls jobs collection for PENDING items.
    - [x] Instantly locks matching jobs to RUNNING.
    - [x] Delegates job to correct handler (FFmpegService or AIGenerationService).
    - [x] Transitions status to COMPLETED or FAILED on completion.
- [x] **WebSocket Broadcast Channels**
  - [x] Open a WebSocket endpoint /api/jobs/ws.
  - [x] Maintain list of active WebSocket sessions mapped to specific filmId or jobId.
  - [x] Broadcast events whenever a job starts, updates progress, finishes, or errors.
