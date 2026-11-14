# LocalMind Power-User Feature Suite

Upgrade LocalMind from a single-session inference workbench into a full offline AI studio while preserving its Cursor-inspired monochrome Daylight and Obsidian floating interface. This milestone adds persistent Workspaces and branching threads, on-device document RAG, an in-app Hugging Face model browser and downloader, voice dictation and read-aloud, a live context window gauge, and slash commands.

## User Review & Critical Decisions

> [!IMPORTANT]
> All major capabilities below were confirmed in Phase 1. Review the default operational choices before implementation begins.

- **Confirmed Organization & History**: Cursor-style **Workspaces** and **All Agents** thread list backed by local SQLite persistence (Room + KSP), with multi-revision message branches (`< 1 / 2 >`) created whenever responses are regenerated.
- **Confirmed Local RAG**: Zero-permission document attachment supporting plain text, Markdown, source code, and PDFs, indexed completely on-device into searchable text chunks with inline source citations.
- **Confirmed Model Acquisition**: Built-in Hugging Face Hub model browser and resumable downloader inside Model Vault with automatic device RAM preflight compatibility badges and `mmproj` projector pairing detection.
- **Confirmed Voice & QoL Additions**: Capsule microphone dictation via Android speech recognition, offline text-to-speech playback on assistant replies, a live context window gauge with turn-trimming controls, and a `/` slash-command and custom prompt template palette.
- **Default Technical Choice — Lexical/BM25 Local Retrieval**: Document RAG defaults to fast on-device chunking and BM25 term-relevance scoring rather than requiring users to download and keep a second neural embedding model resident in RAM alongside the primary LLM.
- **Default Technical Choice — Speech & Download Permissions**: Voice dictation will request the standard microphone permission only when tapped; document/model imports use the system document picker with no broad storage permissions; active model downloads run through a data-sync worker with pause/resume support.

## 1. Overview & Core Concept

- **What It Does**:
  - **Threads & Workspaces Hub**: Turns the top-left `<` navigation target into a Cursor-faithful hub featuring **Workspaces** (grouped project contexts with pinned instructions and attached documents) and **All Agents** (chronological, searchable, archivable chat threads).
  - **On-Device Document & PDF Chat**: Lets users attach PDFs, Markdown docs, or code files to a Workspace or individual chat thread. LocalMind extracts text, splits it into overlapping chunks, retrieves the top matching passages per turn, and injects concise grounded context with verifiable source pills.
  - **Hugging Face Model Browser**: Adds a `"Discover"` mode inside Model Vault to search mobile-friendly `.gguf` and `.litertlm` weights, inspect quantization variants, check live device RAM fit before downloading, and stream files directly into the private vault.
  - **Voice Dictation & Offline Read-Aloud**: Places a subtle microphone button in the floating capsule when the prompt is empty (morphing seamlessly to `↑` Send as text appears) and adds a subtle speaker icon to assistant replies for hands-free offline listening.
  - **Context Gauge, Branching & Slash Commands**: Adds a live KV-cache occupancy pill with one-tap history trimming, `< 1 / 2 >` response revision steppers, and a floating `/` menu above the composer for instant actions and saved prompt templates.
- **Target Audience / Persona**: Privacy-conscious developers, researchers, students, and mobile power users running offline LLMs and VLMs on Android phones and tablets without cloud telemetry.
- **Key Value**: Zero lost conversations, zero manual browser-to-files app juggling for weights, grounded answers over private PDFs and notes, and effortless mobile ergonomics.

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Threads & Workspaces Flow**:
     - Tap `<` from Workbench to enter the **All Agents / Workspaces** hub (with floating `<` / Search / `+ New Thread` controls, bold `32sp` heading, pinned Workspaces, and hairline-separated threads showing title, status dot, loaded model tag, and timestamp).
     - Tap `+` or type directly in the bottom capsule to start a fresh thread; swipe or long-press a thread row to rename, pin, export, or archive.
  2. **Document RAG Flow**:
     - Tap `+` inside the floating capsule or the `Docs` floating pill to pick local PDF/TXT/MD/code files via the system file picker.
     - Attached documents appear as clean hairline source pills above the composer (showing filename and indexed chunk count); assistant answers cite retrieved snippets cleanly below the prose.
  3. **Model Discovery & Download Flow**:
     - From **Model Vault**, tap the floating `Discover` pill to browse curated and searchable Hugging Face repositories.
     - Each file variant shows exact size, quantization (`Q4_K_M`, `Q8_0`, etc.), estimated memory footprint against live free RAM, and a direct `Download` / `Pause` / `Resume` control with inline progress.
  4. **Voice, Branching & Slash Commands Flow**:
     - Empty capsule shows `+  Follow up...  [Mic]`; tapping `[Mic]` pulses a subtle `"Listening..."` status indicator and streams recognized words into the input field; typing swaps `[Mic]` to the bold circular `↑` Send button.
     - Regenerating an assistant reply stores the previous draft as a sibling branch and reveals a quiet `< 1 / 2 >` revision switcher beside the copy/delete footer.
     - Typing `/` floats a crisp hairline command popover above the composer (`/think`, `/direct`, `/trim`, `/doc`, `/clear`, plus user templates) filterable as the user types.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Strict adherence to the Cursor iOS monochrome editorial design across both **Daylight** (`#FFFFFF` pure white canvas, `#F3F3F6` soft tonal pills, `#141416` bold ink primary actions) and **Obsidian** (`#0B0B0E` deep canvas, `#191920` elevated dark pills, `#F4F4F6` crisp primary actions).
  - *Color Palette & Mood*: Neutral monochrome hierarchy using subtle `0.5dp` hairline dividers and `1dp` bordered floating pills, with surgical accents only (`emerald` live indicators, `cobalt` switches/links/citations, `amber` thinking & high-context warnings, `coral` critical context / stop / delete).
  - *Typography & Hierarchy*: `Space Grotesk` bold `32sp` screen headers (`Workspaces`, `All Agents`, `Model Vault`), `Inter` readable editorial prose (`15sp` body directly on canvas for assistant responses), and `JetBrains Mono` with tabular numerals (`tnum`) for KV context percentages, RAM sizes, download speeds, and branch counters.
  - *Component Styling & Layout*: Edge-to-edge scrolling underneath soft top/bottom background gradients, `48dp` minimum touch targets on all floating circles, pills, and branch steppers, and `NavigationRail` adaptation on tablets/foldables.
- **Interactive Feedback & Motion**:
  - Critically damped spring expansion for the `/` command popover, attachment indexing progress, and active branch switches; smooth `AnimatedContent` morph between `[Mic]`, `[↑ Send]`, and `[■ Stop]`; haptic ticks on dictation start/stop, branch switches, and context trim; full reduced-motion fallback.

## 3. Key Product Decisions & Trade-Offs

- **Decision 1 — Local Room Database for Threads, Branches & Document Index**:
  - *Chosen Approach*: Persist workspaces, conversation threads, branching message nodes, document metadata, and indexed text chunks in a local Room SQLite database via KSP.
  - *Why*: Conversations, document indexes, and prompt templates are strictly device-private offline assets that must survive app restarts with fast reactive queries (`Flow`) and zero external cloud accounts.
  - *Alternatives Considered*: JSON files in internal storage or DataStore; rejected because querying across dozens of threads, branching histories, and thousands of RAG chunks requires indexed SQL queries and atomic transactions.
- **Decision 2 — On-Device BM25 Chunk Retrieval vs. Dual-Model Neural Embeddings**:
  - *Chosen Approach*: Pure-Kotlin text chunking paired with SQL-backed inverted/BM25 lexical scoring (plus page/line metadata).
  - *Why*: Mobile RAM is heavily constrained by the loaded LLM/VLM weights and KV cache; keeping a second embedding model in memory risks OS low-memory kills. Lexical BM25 executes in milliseconds with near-zero RAM overhead.
  - *Alternatives Considered*: Loading a separate TFLite/GGUF embedding model; deferred as an optional future enhancement for high-RAM devices.
- **Decision 3 — Context Window Gauge & Non-Destructive History Trimming**:
  - *Chosen Approach*: Track live KV occupancy (`used / n_ctx`) during generation and provide a `"Trim Older Turns"` prompt/action when crossing 80% capacity, preserving the system prompt, active RAG snippets, and recent turns cleanly while marking trimmed turns visually in the thread rather than deleting them from SQLite history.
  - *Why*: Users keep their full readable chat history on-device while freeing KV cache slots and resetting native engine context cleanly without silent mid-sentence crashes.
  - *Alternatives Considered*: Hard failing when KV cache fills or silently dropping old messages from UI; both hurt user trust.
- **Decision 4 — Cursor-Style Microphone-to-Send Capsule Morph**:
  - *Chosen Approach*: Show the Voice Dictation microphone button inside the floating capsule when text is empty and the engine is idle, morphing smoothly to `↑` Send as soon as text or an image is present (and `■` Stop during generation).
  - *Why*: Matches the clean two-button floating capsule silhouette from the Cursor inspiration without crowding the input bar.
  - *Alternatives Considered*: Adding a third permanent floating pill solely for voice; rejected to preserve visual restraint.

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                         Jetpack Compose UI Layer                          │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌───────────────────┐  │
│  │  Threads & Vault    │  │   Workbench Thread  │  │  Customize Sheet  │  │
│  │  - All Agents Hub   │  │   - Slash Popover   │  │  - Prompt Library │  │
│  │  - Workspaces       │  │   - Doc Citations   │  │  - Context Gauge  │  │
│  │  - HF Model Browser │  │   - Branch Stepper  │  │  - Voice & TTS    │  │
│  └──────────┬──────────┘  │   - Mic/Send Morph  │  └─────────┬─────────┘  │
│             │             └──────────┬──────────┘            │            │
└─────────────┼────────────────────────┼───────────────────────┼────────────┘
              ▼                        ▼                       ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                         LocalMindViewModel                                │
│  Thread/Branch Coordinator  │  Context Budgeter  │  Voice/TTS Controller  │
└──────┬──────────────────────┬────────────────────┬────────────────────────┘
       │                      │                    │
       ▼                      ▼                    ▼
┌─────────────────┐  ┌──────────────────┐  ┌───────────────────────────────┐
│ Room Database   │  │ On-Device RAG    │  │ Hugging Face Hub & Downloader │
│ - Workspaces    │  │ - PDF/TXT Parser │  │ - Hub API Search (OkHttp)     │
│ - Threads       │  │ - Chunk Indexer  │  │ - Resumable Range Transfers   │
│ - Msg Branches  │  │ - BM25 Retriever │  │ - RAM Preflight Compatibility │
│ - Prompt Tpls   │  └────────┬─────────┘  └──────────────┬────────────────┘
└─────────────────┘           │                           │
                              ▼                           ▼
                   ┌────────────────────────────────────────────────────────┐
                   │   InferenceEngineCoordinator (LiteRT-LM / llama.cpp)   │
                   └────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - **Workspace**: Identifier, title, pinned system instruction, default model identifier, creation/update timestamps.
  - **Thread**: Identifier, optional workspace foreign key, auto-generated or edited title, active/archived flag, pinned status, last used model ID, creation/update timestamps.
  - **Message & Branch**: Thread foreign key, turn order, branch group ID, revision index, role (`USER` / `ASSISTANT`), markdown body, thinking trace, attached image path, cited chunk summaries, and per-turn telemetry snapshot. Threads track the selected branch revision per branch group so navigating away and returning preserves exact conversation state.
  - **Indexed Document & Chunk**: Attachment ID, workspace or thread link, display filename, media type, chunk index, page/section label, raw text excerpt, and normalized token terms for fast BM25 scoring.
  - **Slash Template**: Trigger keyword (e.g., `review`, `summarize`), label, prompt body with optional placeholder cursor, built-in vs. user-created flag.
  - **Download Job State**: Repo ID, filename, target format, total bytes, downloaded bytes, status (`Queued`, `Downloading`, `Paused`, `Completed`, `Failed`), and optional paired `mmproj` filename.

- **Interactive Component & State Mapping**:
  - **Threads & Workspaces Navigation**: Switching threads persists any in-progress draft and loads the selected thread's active branch tree into UI state; creating a thread clears active context and resets the native KV session cleanly.
  - **Message Branch Stepper (`< 1 / 2 >`)**: Regenerating an assistant message inserts a new sibling revision in the same branch group and switches the active pointer to the new revision; tapping `<` or `>` replays the selected branch path and resynchronizes the engine session history on next prompt.
  - **Context Gauge & Trim**: Updates after each turn and token event from `MemoryStats`; tapping `"Trim Context"` slides the engine's active prompt window forward from a chosen turn boundary while flagging earlier messages in the UI as `"Outside active context"`.
  - **Voice Dictation & Read-Aloud**: Microphone button triggers runtime `RECORD_AUDIO` permission check if needed, streams partial and final transcripts from `SpeechRecognizer` directly into the composer state, and cleans up recognizer resources on lifecycle disposal; speaker icon on any completed assistant turn invokes offline `TextToSpeech` with active-message highlight and instant stop.
  - **Slash Command Popover**: Watches composer text starting with `/` (or mid-prompt command triggers), filters built-in actions and Room-stored templates reactively, and either executes immediate actions (`/clear`, `/think`, `/trim`) or expands the selected template cleanly into the input buffer.
  - **Hugging Face Browser & Download Manager**: Queries Hub model metadata over HTTPS, computes compatibility against `InferenceEngineCoordinator.checkRamPreflight`, initiates resumable HTTP byte-range writes into `.part` staging files inside private app storage, promotes verified files into `LocalModelRepository` on completion, and cleans up `.part` files on cancellation.

- **Key Sequences & Integrations**:
  - **Grounded Prompt Execution**: User prompt -> extract `/command` if present -> run BM25 retrieval across documents attached to current Thread/Workspace -> format concise `<context>` block + trimmed conversation window within `n_ctx` budget -> stream tokens via `InferenceEngineCoordinator` -> persist user + assistant branch nodes and telemetry to Room.
  - **System Integrations**: Android `SpeechRecognizer` and `TextToSpeech`, Storage Access Framework `OpenDocument` for PDF/text/code files, OkHttp/Moshi (or `kotlinx.serialization`) for Hugging Face API + resumable file transfers, and Room + KSP for reactive offline persistence.
