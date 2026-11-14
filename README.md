# LocalMind — Local Multimodal AI for Android

A native Android AI workbench built with **Kotlin**, **Jetpack Compose**, **Room**, and **C++ NDK** for running Large Language Models (LLMs) and Vision-Language Models (VLMs) offline on-device—paired with a Cursor-inspired monochrome **Daylight** and **Obsidian** interface.

## Key Features

### Inference & Hardware Acceleration
- **Dual Inference Backends:** Switch seamlessly between **LiteRT / LiteRT-LM** and **`llama.cpp`** behind a unified engine interface.
- **Vision-Language (VL) Multimodal Pipeline:** Attach images from the camera or photo gallery to run visual Q&A, OCR, and scene reasoning offline:
  - **LiteRT-LM VL:** Native multimodal support for vision-capable bundles (e.g., Gemma, FastVLM).
  - **GGUF VL:** Paired `mmproj-*.gguf` multimodal projector support in `llama.cpp` (`mtmd` / CLIP vision pipeline) for open-weight VLMs (e.g., Qwen-VL, LFM-VL).
- **Hardware Acceleration & Fallback:**
  - Automatic delegate priority (**NPU → GPU → CPU**) on LiteRT / LiteRT-LM.
  - `arm64-v8a` NEON/dotprod optimizations with configurable GPU layer offloading (Vulkan/OpenCL) on `llama.cpp`.
- **Reasoning & Telemetry:** Toggle step-by-step thinking streams, inspect collapsible thought traces, view live prefill/decode tokens-per-second metrics, and run pre-load RAM compatibility checks with lifecycle-aware native buffer cleanup (`onTrimMemory`).

### Workspaces, Threads & Branching
- **Threads & Workspaces Hub:** Organize conversations across **Workspaces** (each with custom system instructions) and **All Agents** threads backed by local **Room** SQLite persistence—with search, pinning, archiving, and one-tap Markdown export.
- **Direct Chat Switcher:** Jump straight from the active chat bar into the Threads & Workspaces switcher without losing your current draft or active branch.
- **Swappable Message Branches (`< 1 / 2 >`):** Regenerating an assistant response preserves previous drafts as sibling revisions so you can step between alternatives without losing earlier answers.

### On-Device Document & PDF Chat (Local RAG)
- **Zero-Permission Document Attachment:** Import local **PDF**, **Markdown**, **plain text**, and **source code** files via Android's Storage Access Framework (`OpenDocument`).
- **Pure-Kotlin Chunking & BM25 Retrieval:** Extracts text on-device (including uncompressed and `FlateDecode` PDF content streams), indexes chunks locally in Room, retrieves top-matching passages per turn, and attaches inline source citations beneath answers.

### Model Vault & Hugging Face Hub Browser
- **In-App Model Discovery:** Browse and search curated or queried **Hugging Face** `.gguf` and `.litertlm` repositories directly inside Model Vault.
- **Resumable Downloads & Preflight Fit:** Inspect quantization variants, check live device RAM fit before downloading, pair `mmproj` projectors, and pause/resume HTTP byte-range downloads into private app storage alongside local SAF weight imports.

### Voice, Context Gauge & Slash Commands
- **Voice Dictation & Offline Read-Aloud:** Tap the composer microphone (morphing cleanly to Send/Stop) for hands-free `SpeechRecognizer` dictation, and use per-message `TextToSpeech` controls to listen to assistant replies offline.
- **Live Context Window Gauge & Trim:** Monitor KV-cache occupancy (`used / n_ctx`) in real time and non-destructively trim older turns from the active context window while keeping full chat history readable.
- **Slash Commands & Prompt Templates:** Type `/` in the floating composer to trigger built-in actions (`/think`, `/direct`, `/trim`, `/clear`), seeded prompt templates (`/summarize`, `/code`, `/explain`), or your own saved custom templates.
- **Adaptive Daylight & Obsidian UI:** Edge-to-edge monochrome editorial layout with floating capsule controls, tabular monospace telemetry badges, `NavigationRail` adaptation on tablets/foldables, and System/Light/Dark theme support.

## Tech Stack

- **UI & Architecture:** Kotlin, Jetpack Compose (Material 3), Navigation Compose (type-safe routes), Coroutines & Flow, MVVM
- **Persistence & Networking:** Room + KSP (threads, workspaces, message branches, RAG chunks, prompt templates), DataStore Preferences, OkHttp (`HuggingFaceHubRepository`), `kotlinx.serialization`
- **Runtimes:** Google LiteRT / LiteRT-LM Android SDK, `llama.cpp` (C++17 JNI bridge via CMake)
- **Media, Voice & Storage:** CameraX, Android Photo Picker, Storage Access Framework (SAF), `SpeechRecognizer`, `TextToSpeech`, Coil
- **Testing:** JUnit, Kotlin Coroutines Test, Robolectric Compose UI tests
