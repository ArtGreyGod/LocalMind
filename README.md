# LocalMind — Local Multimodal AI for Android

A native Android AI workbench built with **Kotlin**, **Jetpack Compose**, **Room**, and **C++ NDK** for running Large Language Models (LLMs) and Vision-Language Models (VLMs) offline on-device—paired with a Cursor-inspired monochrome **Daylight** and **Obsidian** interface.

## Key Features

### Inference & Hardware Acceleration
- **Dual Inference Backends:** Switch seamlessly between **LiteRT / LiteRT-LM** and **`llama.cpp`** behind a unified engine interface.
- **Vision-Language (VL) Multimodal Pipeline:** Attach up to **4 images** per turn from the camera or photo gallery for visual Q&A, OCR, comparison, and scene reasoning offline:
  - **LiteRT-LM VL:** Native multimodal support for vision-capable bundles (e.g., Gemma, FastVLM).
  - **GGUF VL:** Paired `mmproj-*.gguf` multimodal projector support in `llama.cpp` (`mtmd` / CLIP vision pipeline) for open-weight VLMs (e.g., Qwen-VL, LFM-VL).
- **Hardware Acceleration & Fallback:**
  - Automatic delegate priority (**NPU → GPU → CPU**) on LiteRT / LiteRT-LM.
  - `arm64-v8a` NEON/dotprod optimizations with **Vulkan GPU layer offloading** on `llama.cpp` (built via `ggml-vulkan`; requires `glslc` on the build host, and gracefully falls back to CPU-only when unavailable).
- **Reasoning & Telemetry:** Toggle step-by-step thinking streams, inspect collapsible thought traces, view live prefill/decode tokens-per-second metrics, and run pre-load RAM compatibility checks with lifecycle-aware native buffer cleanup (`onTrimMemory`).
- **GGUF Metadata:** Architecture, parameter count, and trained context length are parsed from the GGUF header at import time and surfaced in the Vault.

### Workspaces, Threads & Branching
- **Threads & Workspaces Hub:** Organize conversations across **Workspaces** (each with custom system instructions) and **All Agents** threads backed by local **Room** SQLite persistence—with title search, **full-text message search (FTS4)**, pinning, archiving, and one-tap Markdown export.
- **Direct Chat Switcher:** Jump straight from the active chat bar into the Threads & Workspaces switcher without losing your current draft or active branch. Composer drafts and staged images are **scoped per thread**.
- **Swappable Message Branches (`< 1 / 2 >`):** Regenerating an assistant response preserves previous drafts as sibling revisions so you can step between alternatives without losing earlier answers.
- **Full-Fidelity Export / Import:** Export any thread as JSON (branches, thought traces, telemetry, citations) and re-import it on another device; Markdown export shares via `FileProvider` so long transcripts never overflow binder limits.
- **Fast Thread Resume:** llama.cpp KV-cache state is snapshotted per completed turn and restored when switching back, skipping the full re-prefill.

### On-Device Document & PDF Chat (Local RAG)
- **Zero-Permission Document Attachment:** Import local **PDF**, **Markdown**, **plain text**, and **source code** files via Android's Storage Access Framework (`OpenDocument`).
- **Pure-Kotlin Chunking & BM25 Retrieval:** Extracts text on-device (uncompressed and `FlateDecode` PDF content streams, with garbled custom-encoding streams detected and skipped), indexes chunks locally in Room, retrieves top-matching passages per turn, and attaches inline source citations beneath answers.
- **Optional Semantic Rerank:** Enable **Semantic Retrieval** and pick a BERT-family embedding GGUF in the Customize sheet — BM25 candidates are reranked by cosine similarity for paraphrase-tolerant retrieval.

### Model Vault & Hugging Face Hub Browser
- **In-App Model Discovery:** Browse and search curated or queried **Hugging Face** `.gguf` and `.litertlm` repositories directly inside Model Vault, with a **"Recommended for this device"** shelf sized against live free RAM.
- **Resumable, Verified Downloads:** Inspect quantization variants, check live device RAM fit before downloading, pair `mmproj` projectors, and pause/resume HTTP byte-range downloads. Completed byte counts are validated, Hub LFS `sha256` checksums are verified when advertised, and downloads keep running under a **foreground service** with progress notifications.

### Voice, Context Gauge & Slash Commands
- **Voice Dictation & Offline Read-Aloud:** Tap the composer microphone (morphing cleanly to Send/Stop) for hands-free `SpeechRecognizer` dictation, and use per-message `TextToSpeech` controls to listen to assistant replies offline.
- **Live Context Window Gauge & Trim:** Monitor KV-cache occupancy (`used / n_ctx`) in real time and non-destructively trim older turns from the active context window while keeping full chat history readable. Trimmed turns are **auto-summarized into a rolling conversation memory** the model sees after resync.
- **Slash Commands & Prompt Templates:** Type `/` in the floating composer to trigger built-in actions (`/think`, `/direct`, `/trim`, `/clear`), seeded prompt templates (`/summarize`, `/code`, `/explain`), or your own saved custom templates.
- **Share-Sheet & Shortcuts:** Send text into LocalMind from any app (`ACTION_SEND`) or long-press the launcher icon for **New chat**.
- **Privacy:** Chat history, RAG index, and model registry are **excluded from cloud backup & device transfer**; an optional **biometric app lock** gates the whole workbench; native engine warnings/errors are viewable in the in-app Diagnostics panel.
- **Adaptive Daylight & Obsidian UI:** Edge-to-edge monochrome editorial layout with floating capsule controls, tabular monospace telemetry badges, `NavigationRail` adaptation on tablets/foldables, and System/Light/Dark theme support.

## Tech Stack

- **UI & Architecture:** Kotlin, Jetpack Compose (Material 3), Navigation Compose (type-safe routes), Coroutines & Flow, MVVM
- **Persistence & Networking:** Room + KSP (threads, workspaces, message branches, RAG chunks, prompt templates, FTS4 message search; schemas exported to `app/schemas`), DataStore Preferences, OkHttp (`HuggingFaceHubRepository`), `kotlinx.serialization`
- **Runtimes:** Google LiteRT / LiteRT-LM Android SDK, `llama.cpp` (C++17 JNI bridge via CMake, pinned to a release tag)
- **Media, Voice & Storage:** CameraX, Android Photo Picker, Storage Access Framework (SAF), `SpeechRecognizer`, `TextToSpeech`, Coil, BiometricPrompt
- **Testing:** JUnit, Kotlin Coroutines Test, Robolectric Compose UI tests

## Building with Vulkan GPU offload

`ggml-vulkan` is enabled by default for `arm64-v8a`. The build host needs `glslc`
(shaderc) to precompile the GPU shaders; if it is missing, CMake prints a warning and
produces a CPU-only build rather than failing. On Ubuntu CI: `apt-get install glslc`.
On macOS: `brew install shaderc`.
