# Phase 4: Frontend UI & Timeline (Compose Web)
*Focuses on state management, building the Film list dashboard, implementing video player DOM bindings, and creating a canvas timeline editor with dragging and trimming controls.*

## Tasks
- [x] **Unidirectional State Management (UDF)**
  - [x] Implement a central state holder/view model structure (e.g., using MutableStateFlow or Compose mutableStateOf).
  - [x] Define states: Film list, selected active film timeline tree, job processes, and playhead tracker.
- [x] **Dashboard Screen**
  - [x] Render a responsive grid showing all films.
  - [x] Implement a "Create Film" dialog triggering the POST /api/films request.
  - [x] Clicking a grid item shifts application state to the Editor View for that Film.
- [x] **Editor Screen**
  - [x] **HTML5 Video Preview Component**:
    - [x] Embed native video DOM elements inside Compose Web via web.dom and Compose ref/HTMLDiv interop.
    - [x] Attach listeners to sync Native Video play/pause and progress with the timeline playhead.
  - [x] **Control Panel**:
    - [x] Build standard controls: Play, Pause, Seek, and current time/total duration readouts.
  - [x] **Canvas-Based Timeline Component**:
    - [x] Draw tracks and clips directly using a Compose Canvas for ultimate performance.
    - [x] Implement vertical playhead line synchronized with video playback.
    - [x] Implement canvas scroll and zoom modifiers.
    - [x] Capture touch/mouse actions with Modifier.pointerInput to support:
      - [x] **Dragging Clips**: Drag to shift timelineStart values.
      - [x] **Trimming Clips**: Hover-and-drag handles on clip boundaries to adjust trimIn or trimOut.
  - [x] **Library Asset Panel**:
    - [x] Tabs grouping library assets by VIDEO, AUDIO, MUSIC, VO, and IMAGE.
    - [x] Drag-and-drop mechanics to let users drag an asset block directly from the library panel onto a Timeline Track, triggering a clip creation event.
