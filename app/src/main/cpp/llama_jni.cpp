#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

#define TAG "LocalMindLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kDefaultBatchSize = 512;
constexpr int kOverflowHeadroom = 16;

struct EngineSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    common_sampler * sampler = nullptr;
    common_chat_templates_ptr chat_templates;
    mtmd_context * mtmd_ctx = nullptr;

    std::vector<common_chat_msg> chat_history;
    llama_pos current_pos = 0;
    llama_pos system_prompt_pos = 0;
    llama_pos prev_turn_pos = 0;
    size_t prev_history_size = 0;
    bool can_rollback = false;
    int32_t n_ctx = 4096;
    int32_t n_threads = 4;
    int32_t n_gpu_layers = 0;
    bool system_prompt_applied = false;
    std::string active_backend_label = "CPU (NEON/dotprod)";
};

std::mutex g_engine_mutex;
std::mutex g_log_mutex;
std::mutex g_stats_mutex;
std::string g_last_error_msg;
std::atomic<bool> g_cancel_requested{false};
std::atomic<bool> g_backend_initialized{false};
std::atomic<bool> g_atomic_loaded{false};
std::atomic<bool> g_atomic_vision{false};
std::atomic<int32_t> g_atomic_kv_used{0};
std::atomic<int32_t> g_atomic_kv_max{4096};
std::atomic<uint64_t> g_atomic_model_bytes{0};
std::string g_atomic_backend_label = "CPU (ARMv8.2 NEON/dotprod)";
EngineSession g_session;

// Diagnostics ring buffer of recent llama/ggml warnings & errors for the in-app viewer.
constexpr size_t kMaxRecentLogLines = 200;
std::deque<std::string> g_recent_logs;

void record_recent_log_locked(const char * text) {
    std::string line(text);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) {
        line.pop_back();
    }
    if (!line.empty()) {
        g_recent_logs.push_back(std::move(line));
        while (g_recent_logs.size() > kMaxRecentLogLines) {
            g_recent_logs.pop_front();
        }
    }
}

// Separate lightweight session for semantic-RAG embedding models (e.g. BERT-family GGUFs).
struct EmbeddingSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int32_t n_ctx = 512;
    int32_t n_embd = 0;
};
EmbeddingSession g_embed_session;
std::mutex g_embed_mutex;

void sync_atomic_stats_locked() {
    g_atomic_loaded.store(g_session.model != nullptr, std::memory_order_relaxed);
    g_atomic_vision.store(g_session.mtmd_ctx != nullptr, std::memory_order_relaxed);
    g_atomic_kv_used.store(g_session.current_pos, std::memory_order_relaxed);
    g_atomic_kv_max.store(g_session.n_ctx, std::memory_order_relaxed);
}

void clear_last_error() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_error_msg.clear();
}

std::string get_last_error() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return g_last_error_msg;
}

void android_llama_log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: {
            LOGE("%s", text);
            std::lock_guard<std::mutex> lock(g_log_mutex);
            std::string line(text);
            while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) {
                line.pop_back();
            }
            if (!line.empty()) {
                g_last_error_msg = line;
                record_recent_log_locked(line.c_str());
            }
            break;
        }
        case GGML_LOG_LEVEL_WARN:
            LOGW("%s", text);
            {
                std::lock_guard<std::mutex> lock(g_log_mutex);
                record_recent_log_locked(text);
            }
            break;
        default:
            break;
    }
}

/**
 * Converts arbitrary UTF-8 (including 4-byte emojis/symbols and malformed trailing bytes)
 * into a JVM jstring via UTF-16 NewString(), avoiding ART's Modified UTF-8 NewStringUTF() abort.
 */
jstring new_jstring_from_utf8(JNIEnv * env, const std::string & utf8) {
    if (utf8.empty()) {
        return env->NewStringUTF("");
    }
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    const size_t len = utf8.size();
    size_t i = 0;
    while (i < len) {
        const uint8_t b0 = static_cast<uint8_t>(utf8[i]);
        if (b0 == 0x00) {
            i++;
            continue;
        }
        uint32_t cp = 0xFFFD;
        if (b0 < 0x80) {
            cp = b0;
            i += 1;
        } else if ((b0 & 0xE0) == 0xC0 && i + 1 < len) {
            const uint8_t b1 = static_cast<uint8_t>(utf8[i + 1]);
            if ((b1 & 0xC0) == 0x80) {
                cp = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
                i += 2;
            } else {
                i += 1;
            }
        } else if ((b0 & 0xF0) == 0xE0 && i + 2 < len) {
            const uint8_t b1 = static_cast<uint8_t>(utf8[i + 1]);
            const uint8_t b2 = static_cast<uint8_t>(utf8[i + 2]);
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80) {
                cp = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
                i += 3;
            } else {
                i += 1;
            }
        } else if ((b0 & 0xF8) == 0xF0 && i + 3 < len) {
            const uint8_t b1 = static_cast<uint8_t>(utf8[i + 1]);
            const uint8_t b2 = static_cast<uint8_t>(utf8[i + 2]);
            const uint8_t b3 = static_cast<uint8_t>(utf8[i + 3]);
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80) {
                cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                i += 4;
            } else {
                i += 1;
            }
        } else {
            i += 1;
        }

        if (cp <= 0xFFFF) {
            if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
            utf16.push_back(static_cast<jchar>(cp));
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            utf16.push_back(static_cast<jchar>(0xFFFD));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

bool is_complete_utf8(const std::string & s) {
    if (s.empty()) return true;
    const auto * bytes = reinterpret_cast<const unsigned char *>(s.data());
    const size_t len = s.size();
    size_t i = 0;
    while (i < len) {
        const unsigned char b = bytes[i];
        size_t need = 1;
        if ((b & 0x80) == 0x00) need = 1;
        else if ((b & 0xE0) == 0xC0) need = 2;
        else if ((b & 0xF0) == 0xE0) need = 3;
        else if ((b & 0xF8) == 0xF0) need = 4;
        else {
            // Invalid leading byte; flush immediately so conversion can emit U+FFFD
            i++;
            continue;
        }
        if (i + need > len) {
            return false;
        }
        for (size_t k = 1; k < need; ++k) {
            if ((bytes[i + k] & 0xC0) != 0x80) {
                return true;
            }
        }
        i += need;
    }
    return true;
}

std::string detect_active_backend(int32_t gpu_layers) {
    std::vector<std::string> gpu_backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        auto * reg = ggml_backend_reg_get(i);
        if (!reg) continue;
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            gpu_backends.push_back(name);
        }
    }
    if (gpu_layers > 0 && !gpu_backends.empty()) {
        std::ostringstream oss;
        for (size_t i = 0; i < gpu_backends.size(); ++i) {
            if (i > 0) oss << "+";
            oss << gpu_backends[i];
        }
        oss << " (" << gpu_layers << " layers) + CPU";
        return oss.str();
    }
#if defined(__aarch64__)
    return "CPU (ARMv8.2 NEON/dotprod)";
#else
    return "CPU";
#endif
}

void reset_session_memory_locked() {
    if (g_session.ctx) {
        llama_memory_clear(llama_get_memory(g_session.ctx), true);
    }
    if (g_session.sampler) {
        common_sampler_reset(g_session.sampler);
    }
    g_session.chat_history.clear();
    g_session.current_pos = 0;
    g_session.system_prompt_pos = 0;
    g_session.prev_turn_pos = 0;
    g_session.prev_history_size = 0;
    g_session.can_rollback = false;
    g_session.system_prompt_applied = false;
    sync_atomic_stats_locked();
}

void free_session_locked() {
    if (g_session.mtmd_ctx) {
        mtmd_free(g_session.mtmd_ctx);
        g_session.mtmd_ctx = nullptr;
    }
    if (g_session.sampler) {
        common_sampler_free(g_session.sampler);
        g_session.sampler = nullptr;
    }
    g_session.chat_templates.reset();
    if (g_session.ctx) {
        llama_free(g_session.ctx);
        g_session.ctx = nullptr;
    }
    if (g_session.model) {
        llama_model_free(g_session.model);
        g_session.model = nullptr;
    }
    g_session.chat_history.clear();
    g_session.current_pos = 0;
    g_session.system_prompt_pos = 0;
    g_session.prev_turn_pos = 0;
    g_session.prev_history_size = 0;
    g_session.can_rollback = false;
    g_session.system_prompt_applied = false;
    g_atomic_model_bytes.store(0, std::memory_order_relaxed);
    sync_atomic_stats_locked();
}

bool shift_context_locked() {
    if (!g_session.ctx || g_session.current_pos <= g_session.system_prompt_pos + 8) {
        return false;
    }
    const llama_pos n_discard = (g_session.current_pos - g_session.system_prompt_pos) / 2;
    if (n_discard <= 0) return false;
    LOGW("KV cache near capacity (%d/%d). Shifting %d tokens.",
         g_session.current_pos, g_session.n_ctx, n_discard);
    llama_memory_t mem = llama_get_memory(g_session.ctx);
    if (!llama_memory_seq_rm(mem, 0, g_session.system_prompt_pos, g_session.system_prompt_pos + n_discard)) {
        LOGW("Mid-sequence KV shift unsupported on this architecture.");
        return false;
    }
    llama_memory_seq_add(mem, 0, g_session.system_prompt_pos + n_discard, g_session.current_pos, -n_discard);
    g_session.current_pos -= n_discard;
    g_session.can_rollback = false;
    sync_atomic_stats_locked();
    return true;
}

int decode_tokens_in_batches_locked(
        const std::vector<llama_token> & tokens,
        bool compute_last_logit) {
    if (!g_session.ctx) return -1;
    common_batch batch(g_session.ctx);
    const int total = static_cast<int>(tokens.size());
    for (int i = 0; i < total; i += kDefaultBatchSize) {
        if (g_cancel_requested.load()) return -2;
        const int cur_size = std::min(total - i, kDefaultBatchSize);
        if (g_session.current_pos + cur_size >= g_session.n_ctx - kOverflowHeadroom) {
            if (!shift_context_locked()) {
                LOGW("Context window full during batch decode (%d/%d).",
                     g_session.current_pos, g_session.n_ctx);
                return -4;
            }
        }
        batch.clear();
        for (int j = 0; j < cur_size; ++j) {
            const bool want_logit = compute_last_logit && (i + j == total - 1);
            batch.add(tokens[i + j], g_session.current_pos + j, 0, want_logit);
        }
        if (llama_process(g_session.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("llama_process failed during batch decode");
            return -3;
        }
        g_session.current_pos += cur_size;
        g_atomic_kv_used.store(g_session.current_pos, std::memory_order_relaxed);
    }
    return 0;
}

bool prompt_ends_inside_thinking(
        const std::string & gen_prompt,
        const std::string & tmpl_open_tag,
        const std::vector<std::string> & tmpl_end_tags,
        std::string * out_open_tag = nullptr,
        std::string * out_close_tag = nullptr) {
    struct TagPairDef {
        std::string open;
        std::string close;
    };
    std::vector<TagPairDef> pairs = {
        {"<think>", "</think>"},
        {"<thought>", "</thought>"},
        {"<|thought|>", "<|end_thought|>"},
        {"<reasoning>", "</reasoning>"},
        {"<|channel>thought", "<channel|>"}
    };
    if (!tmpl_open_tag.empty()) {
        std::string matched_close = "</think>";
        for (const auto & end_tag : tmpl_end_tags) {
            if (!end_tag.empty()) {
                // Trim leading whitespace like "\n</think>"
                size_t first_non_ws = end_tag.find_first_not_of(" \t\r\n");
                matched_close = first_non_ws == std::string::npos ? end_tag : end_tag.substr(first_non_ws);
                break;
            }
        }
        pairs.insert(pairs.begin(), {tmpl_open_tag, matched_close});
    }

    size_t last_open_pos = std::string::npos;
    std::string matched_open;
    std::string matched_close = "</think>";
    for (const auto & p : pairs) {
        if (p.open.empty()) continue;
        size_t pos = gen_prompt.rfind(p.open);
        if (pos != std::string::npos && (last_open_pos == std::string::npos || pos > last_open_pos)) {
            last_open_pos = pos;
            matched_open = p.open;
            matched_close = p.close;
        }
    }
    if (last_open_pos == std::string::npos) {
        return false;
    }

    size_t last_close_pos = std::string::npos;
    for (const auto & p : pairs) {
        if (p.close.empty()) continue;
        size_t pos = gen_prompt.rfind(p.close);
        if (pos != std::string::npos && (last_close_pos == std::string::npos || pos > last_close_pos)) {
            last_close_pos = pos;
        }
    }
    for (const auto & end_tag : tmpl_end_tags) {
        if (end_tag.empty()) continue;
        size_t pos = gen_prompt.rfind(end_tag);
        if (pos != std::string::npos && (last_close_pos == std::string::npos || pos > last_close_pos)) {
            last_close_pos = pos;
        }
    }

    const bool unclosed = (last_close_pos == std::string::npos) || (last_open_pos > last_close_pos);
    if (unclosed) {
        if (out_open_tag) *out_open_tag = matched_open;
        if (out_close_tag) *out_close_tag = matched_close;
    }
    return unclosed;
}

std::string format_turn_locked(
        const std::string & role,
        const std::string & content,
        const std::string & system_prompt,
        bool enable_thinking,
        bool * out_starts_in_thinking = nullptr,
        std::string * out_open_tag = nullptr,
        std::string * out_close_tag = nullptr) {
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    const bool has_template = common_chat_templates_was_explicit(g_session.chat_templates.get());
    std::string formatted;

    // On the very first user turn, attach the system message alongside the user message so
    // templates that validate "user query must exist" (like Qwen3.5) succeed on Turn 1 and
    // honor enable_thinking accurately.
    std::vector<common_chat_msg> seed_history = g_session.chat_history;
    bool seeded_system = false;
    if (role == "user" && seed_history.empty() && !system_prompt.empty()) {
        common_chat_msg sys_msg;
        sys_msg.role = "system";
        sys_msg.content = system_prompt;
        seed_history.push_back(std::move(sys_msg));
        seeded_system = true;
    }

    if (has_template) {
        bool used_jinja = false;
        try {
            common_chat_templates_inputs past_inputs;
            past_inputs.use_jinja = true;
            past_inputs.enable_thinking = enable_thinking;
            past_inputs.reasoning_format = COMMON_REASONING_FORMAT_NONE;

            std::string fmt_past;
            if (!g_session.chat_history.empty()) {
                past_inputs.messages = g_session.chat_history;
                past_inputs.add_generation_prompt = false;
                fmt_past = common_chat_templates_apply(g_session.chat_templates.get(), past_inputs).prompt.str();
            }

            common_chat_templates_inputs new_inputs;
            new_inputs.use_jinja = true;
            new_inputs.enable_thinking = enable_thinking;
            new_inputs.reasoning_format = COMMON_REASONING_FORMAT_NONE;
            new_inputs.messages = seed_history;
            new_inputs.messages.push_back(msg);
            new_inputs.add_generation_prompt = (role == "user");

            const auto chat_params = common_chat_templates_apply(g_session.chat_templates.get(), new_inputs);
            const std::string fmt_new = chat_params.prompt.str();

            if (seeded_system || g_session.chat_history.empty()) {
                formatted = fmt_new;
                used_jinja = true;
            } else if (fmt_new.size() >= fmt_past.size() && fmt_new.rfind(fmt_past, 0) == 0) {
                formatted = fmt_new.substr(fmt_past.size());
                used_jinja = true;
            } else if (role == "user") {
                // Multi-turn templates (like Qwen3.5) strip <think>...</think> from prior turns
                // once a new user query is appended, causing fmt_new != fmt_past + suffix.
                // Diff against a probe user message to isolate the exact new user turn + gen prompt.
                common_chat_templates_inputs probe_inputs = new_inputs;
                probe_inputs.messages.back().content = "__LOCALMIND_TURN_PROBE__";
                const std::string fmt_probe = common_chat_templates_apply(
                        g_session.chat_templates.get(), probe_inputs).prompt.str();
                size_t diff_pos = 0;
                const size_t min_len = std::min(fmt_new.size(), fmt_probe.size());
                while (diff_pos < min_len && fmt_new[diff_pos] == fmt_probe[diff_pos]) {
                    diff_pos++;
                }
                size_t turn_start = std::string::npos;
                for (const auto & delim : chat_params.message_delimiters.delimiters) {
                    if (delim.role == COMMON_CHAT_ROLE_USER && !delim.delimiter.empty()) {
                        size_t pos = fmt_new.rfind(delim.delimiter, diff_pos);
                        if (pos != std::string::npos && (turn_start == std::string::npos || pos > turn_start)) {
                            turn_start = pos;
                        }
                    }
                }
                if (turn_start == std::string::npos) {
                    size_t pos = fmt_new.rfind("<|im_start|>user", diff_pos);
                    if (pos == std::string::npos) pos = fmt_new.rfind("<|turn>user", diff_pos);
                    turn_start = pos;
                }
                if (turn_start != std::string::npos) {
                    formatted = fmt_new.substr(turn_start);
                    used_jinja = true;
                }
            }

            if (used_jinja && out_starts_in_thinking != nullptr && role == "user") {
                const std::string & gen_prompt = chat_params.generation_prompt;
                *out_starts_in_thinking = prompt_ends_inside_thinking(
                        gen_prompt,
                        chat_params.thinking_start_tag,
                        chat_params.thinking_end_tags,
                        out_open_tag,
                        out_close_tag);
            }
        } catch (const std::exception & e) {
            LOGW("Jinja chat template fallback: %s", e.what());
        }

        if (!used_jinja) {
            formatted = common_chat_format_single(
                    g_session.chat_templates.get(),
                    seed_history,
                    msg,
                    role == "user",
                    /* use_jinja */ false);
        }
    } else {
        if (seeded_system) {
            formatted = "<<SYS>>\n" + system_prompt + "\n<</SYS>>\n\n";
        }
        if (role == "system") {
            formatted += "<<SYS>>\n" + content + "\n<</SYS>>\n\n";
        } else if (role == "user") {
            formatted += "User: " + content + "\nAssistant: ";
        } else {
            formatted += content + "\n";
        }
    }
    if (seeded_system) {
        g_session.chat_history = std::move(seed_history);
    }
    g_session.chat_history.push_back(std::move(msg));
    return formatted;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeInit(
        JNIEnv * /*env*/,
        jobject /*thiz*/) {
    if (!g_backend_initialized.exchange(true)) {
        llama_log_set(android_llama_log_callback, nullptr);
        mtmd_helper_log_set(android_llama_log_callback, nullptr);
        llama_backend_init();
        LOGI("llama_backend_init completed");
    }
}

JNIEXPORT jint JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeLoadModel(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jModelPath,
        jstring jMmprojPath,
        jint nCtx,
        jint nThreads,
        jint nGpuLayers,
        jfloat temperature,
        jfloat topP,
        jint topK) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_cancel_requested.store(false);
    clear_last_error();
    free_session_locked();

    const char * model_path = env->GetStringUTFChars(jModelPath, nullptr);
    if (!model_path) return -1;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = std::max<int32_t>(0, nGpuLayers);

    LOGI("Loading GGUF model: %s (gpu_layers=%d)", model_path, mparams.n_gpu_layers);
    llama_model * model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!model) {
        LOGE("Failed to load GGUF model");
        return -2;
    }

    const int32_t trained_ctx = llama_model_n_ctx_train(model);
    const int32_t resolved_ctx = std::max(512, std::min(static_cast<int32_t>(nCtx), trained_ctx > 0 ? trained_ctx : nCtx));
    const int32_t resolved_threads = std::max(1, static_cast<int32_t>(nThreads));

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(resolved_ctx);
    cparams.n_batch = kDefaultBatchSize;
    cparams.n_ubatch = kDefaultBatchSize;
    cparams.n_threads = resolved_threads;
    cparams.n_threads_batch = resolved_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return -3;
    }

    common_params_sampling sparams;
    sparams.temp = temperature;
    sparams.top_p = topP;
    sparams.top_k = topK;
    common_sampler * sampler = common_sampler_init(model, sparams);
    if (!sampler) {
        LOGE("common_sampler_init failed");
        llama_free(ctx);
        llama_model_free(model);
        return -4;
    }

    mtmd_context * mtmd_ctx = nullptr;
    if (jMmprojPath != nullptr) {
        const char * mmproj_path = env->GetStringUTFChars(jMmprojPath, nullptr);
        if (mmproj_path && std::strlen(mmproj_path) > 0) {
            mtmd_context_params mtmd_params = mtmd_context_params_default();
            mtmd_params.use_gpu = (nGpuLayers > 0);
            mtmd_params.n_threads = resolved_threads;
            mtmd_params.print_timings = false;
            mtmd_params.warmup = false;
            const auto mem_usage = mtmd_get_memory_usage(mmproj_path, mtmd_params);
            const int32_t n_ubatch = static_cast<int32_t>(llama_n_ubatch(ctx));
            if (mem_usage.use_non_causal && mem_usage.image_max_tokens > n_ubatch) {
                LOGW("Capping image_max_tokens (%d -> %d) to match n_ubatch for non-causal vision.",
                     mem_usage.image_max_tokens, n_ubatch);
                mtmd_params.image_max_tokens = n_ubatch;
                if (mtmd_params.image_min_tokens > n_ubatch) {
                    mtmd_params.image_min_tokens = n_ubatch;
                }
            }
            LOGI("Loading mmproj vision projector: %s", mmproj_path);
            mtmd_ctx = mtmd_init_from_file(mmproj_path, model, mtmd_params);
            if (!mtmd_ctx) {
                LOGW("Failed to load mmproj file %s; continuing in text-only mode", mmproj_path);
            }
        }
        if (mmproj_path) env->ReleaseStringUTFChars(jMmprojPath, mmproj_path);
    }

    g_session.model = model;
    g_session.ctx = ctx;
    g_session.sampler = sampler;
    g_session.chat_templates = common_chat_templates_init(model, "");
    g_session.mtmd_ctx = mtmd_ctx;
    g_session.n_ctx = resolved_ctx;
    g_session.n_threads = resolved_threads;
    g_session.n_gpu_layers = std::max<int32_t>(0, nGpuLayers);
    g_session.current_pos = 0;
    g_session.system_prompt_pos = 0;
    g_session.system_prompt_applied = false;
    g_session.active_backend_label = detect_active_backend(g_session.n_gpu_layers);

    g_atomic_model_bytes.store(llama_model_size(model), std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> slock(g_stats_mutex);
        g_atomic_backend_label = g_session.active_backend_label;
    }
    sync_atomic_stats_locked();

    LOGI("GGUF model ready. ctx=%d threads=%d vision=%s backend=%s",
         resolved_ctx, resolved_threads, mtmd_ctx ? "enabled" : "none",
         g_session.active_backend_label.c_str());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeGenerateStream(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jSystemPrompt,
        jstring jUserPrompt,
        jobjectArray jRgbPlanes,
        jintArray jImgWidths,
        jintArray jImgHeights,
        jint maxTokens,
        jboolean jEnableThinking,
        jint thinkingBudgetTokens,
        jobject jCallback) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_cancel_requested.store(false);
    clear_last_error();

    if (!g_session.model || !g_session.ctx || !g_session.sampler) {
        return -1;
    }

    const bool enable_thinking = (jEnableThinking == JNI_TRUE);

    jclass cb_cls = env->GetObjectClass(jCallback);
    jmethodID mid_prefill = env->GetMethodID(cb_cls, "onPrefillComplete", "(IJZ)V");
    jmethodID mid_token = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)V");
    jmethodID mid_done = env->GetMethodID(cb_cls, "onCompleted", "(IJ)V");
    jmethodID mid_error = env->GetMethodID(cb_cls, "onError", "(Ljava/lang/String;)V");

    const auto emit_error = [&](const char * msg) {
        std::string full_msg = msg ? msg : "Native inference error";
        const std::string detail = get_last_error();
        if (!detail.empty() && full_msg.find(detail) == std::string::npos) {
            full_msg += ": " + detail;
        }
        jstring jmsg = new_jstring_from_utf8(env, full_msg);
        env->CallVoidMethod(jCallback, mid_error, jmsg);
        env->DeleteLocalRef(jmsg);
    };

    const auto rollback_failed_turn_locked = [&]() {
        if (!g_session.ctx) return;
        llama_memory_t mem = llama_get_memory(g_session.ctx);
        if (llama_memory_seq_rm(mem, 0, g_session.prev_turn_pos, -1)) {
            g_session.current_pos = g_session.prev_turn_pos;
            if (g_session.chat_history.size() > g_session.prev_history_size) {
                g_session.chat_history.resize(g_session.prev_history_size);
            }
            if (g_session.sampler) {
                common_sampler_reset(g_session.sampler);
            }
            g_session.can_rollback = false;
            LOGI("Recovered failed turn cleanly back to KV pos=%d", g_session.current_pos);
        } else {
            LOGW("Failed-turn partial rollback rejected (hybrid/recurrent cache); resetting context cleanly.");
            reset_session_memory_locked();
        }
    };

    const auto t_prefill_start = std::chrono::steady_clock::now();
    int32_t prefill_tokens_count = 0;
    bool starts_in_thinking = false;
    std::string active_open_tag = "<think>";
    std::string active_close_tag = "</think>";

    // Capture system prompt to seed alongside the first user turn (required by Jinja
    // templates such as Qwen3.5 that reject a standalone system-only message array).
    std::string system_text;
    if (jSystemPrompt != nullptr) {
        const char * sys_cstr = env->GetStringUTFChars(jSystemPrompt, nullptr);
        if (sys_cstr) {
            system_text = sys_cstr;
            env->ReleaseStringUTFChars(jSystemPrompt, sys_cstr);
        }
    }

    g_session.prev_turn_pos = g_session.current_pos;
    g_session.prev_history_size = g_session.chat_history.size();
    g_session.can_rollback = true;

    const char * user_cstr = env->GetStringUTFChars(jUserPrompt, nullptr);
    std::string user_text = user_cstr ? std::string(user_cstr) : "";
    if (user_cstr) env->ReleaseStringUTFChars(jUserPrompt, user_cstr);

    // Unpack the (possibly multiple) packed-RGB image planes.
    std::vector<mtmd_bitmap *> owned_bitmaps;
    const bool has_image = (jRgbPlanes != nullptr && jImgWidths != nullptr && jImgHeights != nullptr);
    if (has_image) {
        const jsize nImages = env->GetArrayLength(jRgbPlanes);
        jint * widths = env->GetIntArrayElements(jImgWidths, nullptr);
        jint * heights = env->GetIntArrayElements(jImgHeights, nullptr);
        if (!widths || !heights) {
            if (widths) env->ReleaseIntArrayElements(jImgWidths, widths, JNI_ABORT);
            if (heights) env->ReleaseIntArrayElements(jImgHeights, heights, JNI_ABORT);
            emit_error("Invalid image dimension arrays");
            return -3;
        }
        for (jsize i = 0; i < nImages; ++i) {
            const jint imgWidth = widths[i];
            const jint imgHeight = heights[i];
            if (imgWidth <= 0 || imgHeight <= 0) continue;
            auto plane = static_cast<jbyteArray>(env->GetObjectArrayElement(jRgbPlanes, i));
            if (plane == nullptr) continue;
            const jsize byte_len = env->GetArrayLength(plane);
            const jsize expected_len = imgWidth * imgHeight * 3;
            if (byte_len < expected_len) {
                env->DeleteLocalRef(plane);
                continue;
            }
            auto * rgb_buf = new std::vector<unsigned char>(static_cast<size_t>(expected_len));
            env->GetByteArrayRegion(plane, 0, expected_len, reinterpret_cast<jbyte *>(rgb_buf->data()));
            env->DeleteLocalRef(plane);
            mtmd_bitmap * bitmap = mtmd_bitmap_init(
                    static_cast<uint32_t>(imgWidth),
                    static_cast<uint32_t>(imgHeight),
                    rgb_buf->data());
            delete rgb_buf;
            if (bitmap) {
                owned_bitmaps.push_back(bitmap);
            }
        }
        env->ReleaseIntArrayElements(jImgWidths, widths, JNI_ABORT);
        env->ReleaseIntArrayElements(jImgHeights, heights, JNI_ABORT);
    }

    if (!owned_bitmaps.empty() && g_session.mtmd_ctx != nullptr && mtmd_support_vision(g_session.mtmd_ctx)) {
        // Multimodal path: one <__media__> marker per image, then the user text
        const std::string marker = mtmd_default_marker();
        std::string prompt_with_marker;
        for (size_t i = 0; i < owned_bitmaps.size(); ++i) {
            prompt_with_marker += marker;
            prompt_with_marker += "\n";
        }
        prompt_with_marker += user_text;
        const std::string formatted_user = format_turn_locked(
                "user",
                prompt_with_marker,
                system_text,
                enable_thinking,
                &starts_in_thinking,
                &active_open_tag,
                &active_close_tag);

        mtmd_input_text input_text{};
        input_text.text = formatted_user.c_str();
        input_text.text_len = formatted_user.size();
        input_text.add_special = (g_session.current_pos == 0);
        input_text.parse_special = true;

        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        const int32_t tok_rc = mtmd_tokenize(
                g_session.mtmd_ctx,
                chunks,
                &input_text,
                owned_bitmaps.data(),
                static_cast<int32_t>(owned_bitmaps.size()));

        for (mtmd_bitmap * bmp : owned_bitmaps) {
            mtmd_bitmap_free(bmp);
        }
        owned_bitmaps.clear();

        if (tok_rc != 0) {
            mtmd_input_chunks_free(chunks);
            rollback_failed_turn_locked();
            emit_error("mtmd_tokenize failed for multimodal prompt");
            return -5;
        }

        const size_t chunk_tokens = mtmd_helper_get_n_tokens(chunks);
        prefill_tokens_count += static_cast<int32_t>(chunk_tokens);
        if (g_session.current_pos + static_cast<llama_pos>(chunk_tokens) >=
            g_session.n_ctx - kOverflowHeadroom) {
            if (!shift_context_locked()) {
                mtmd_input_chunks_free(chunks);
                rollback_failed_turn_locked();
                emit_error("Context window is full. Tap Trim Context to continue.");
                return -6;
            }
        }

        llama_pos new_n_past = g_session.current_pos;
        const int32_t eval_rc = mtmd_helper_eval_chunks(
                g_session.mtmd_ctx,
                g_session.ctx,
                chunks,
                g_session.current_pos,
                0,
                kDefaultBatchSize,
                /* logits_last */ true,
                &new_n_past);
        mtmd_input_chunks_free(chunks);

        if (eval_rc != 0) {
            rollback_failed_turn_locked();
            emit_error("mtmd_helper_eval_chunks failed");
            return -6;
        }
        g_session.current_pos = new_n_past;
        g_atomic_kv_used.store(g_session.current_pos, std::memory_order_relaxed);
    } else {
        if (has_image && (g_session.mtmd_ctx == nullptr || !mtmd_support_vision(g_session.mtmd_ctx))) {
            LOGW("Image(s) attached, but no usable mtmd vision projector is loaded; running text prompt only.");
        }
        for (mtmd_bitmap * bmp : owned_bitmaps) {
            mtmd_bitmap_free(bmp);
        }
        owned_bitmaps.clear();
        try {
            const std::string formatted_user = format_turn_locked(
                    "user",
                    user_text,
                    system_text,
                    enable_thinking,
                    &starts_in_thinking,
                    &active_open_tag,
                    &active_close_tag);
            const bool has_tpl = common_chat_templates_was_explicit(g_session.chat_templates.get());
            auto user_tokens = common_tokenize(g_session.ctx, formatted_user, has_tpl && g_session.current_pos == 0, has_tpl);
            prefill_tokens_count += static_cast<int32_t>(user_tokens.size());
            const int prefill_rc = decode_tokens_in_batches_locked(user_tokens, true);
            if (prefill_rc != 0) {
                rollback_failed_turn_locked();
                emit_error(prefill_rc == -4
                        ? "Context window is full. Tap Trim Context to free space."
                        : "Failed to evaluate user prompt tokens");
                return -7;
            }
        } catch (const std::exception & e) {
            rollback_failed_turn_locked();
            emit_error(e.what());
            return -7;
        } catch (...) {
            rollback_failed_turn_locked();
            emit_error("Unexpected native error while preparing prompt");
            return -7;
        }
    }

    const auto t_prefill_end = std::chrono::steady_clock::now();
    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            t_prefill_end - t_prefill_start).count();
    env->CallVoidMethod(
            jCallback,
            mid_prefill,
            static_cast<jint>(prefill_tokens_count),
            static_cast<jlong>(prefill_ms),
            static_cast<jboolean>(starts_in_thinking ? JNI_TRUE : JNI_FALSE));

    // Autoregressive decode loop
    const auto t_decode_start = std::chrono::steady_clock::now();
    int32_t decode_tokens = 0;
    const int32_t effective_max_tokens = enable_thinking
            ? std::min<int32_t>(g_session.n_ctx, std::max<int32_t>(1, maxTokens) + std::max<int32_t>(0, thinkingBudgetTokens))
            : std::max<int32_t>(1, maxTokens);
    std::string pending_utf8;
    std::string full_assistant;
    full_assistant.reserve(2048);
    common_batch decode_batch(g_session.ctx);
    const llama_vocab * vocab = llama_model_get_vocab(g_session.model);

    bool in_thinking_block = starts_in_thinking;
    bool forced_thinking_close = false;
    int32_t thought_tokens_seen = 0;
    std::string tag_window;
    bool stopped_on_kv_limit = false;

    const auto emit_utf8_chunk = [&](const std::string & text) {
        if (text.empty()) return;
        jstring jtok = new_jstring_from_utf8(env, text);
        env->CallVoidMethod(jCallback, mid_token, jtok);
        env->DeleteLocalRef(jtok);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            g_cancel_requested.store(true);
        }
    };

    const auto update_thinking_window = [&](const std::string & added) {
        tag_window += added;
        if (tag_window.size() > 64) {
            tag_window.erase(0, tag_window.size() - 64);
        }
        static const char * kOpenTags[] = {"<think>", "<thought>", "<|thought|>", "<reasoning>", "<|channel>thought"};
        static const char * kCloseTags[] = {"</think>", "</thought>", "<|end_thought|>", "</reasoning>", "<channel|>"};
        size_t last_open = std::string::npos;
        for (const char * tag : kOpenTags) {
            size_t pos = tag_window.rfind(tag);
            if (pos != std::string::npos && (last_open == std::string::npos || pos > last_open)) {
                last_open = pos;
                active_open_tag = tag;
            }
        }
        size_t last_close = std::string::npos;
        for (const char * tag : kCloseTags) {
            size_t pos = tag_window.rfind(tag);
            if (pos != std::string::npos && (last_close == std::string::npos || pos > last_close)) {
                last_close = pos;
                active_close_tag = tag;
            }
        }
        if (last_close != std::string::npos && (last_open == std::string::npos || last_close > last_open)) {
            in_thinking_block = false;
        } else if (last_open != std::string::npos) {
            in_thinking_block = true;
        }
    };

    try {
        for (int32_t step = 0; step < effective_max_tokens; ++step) {
            if (g_cancel_requested.load()) {
                LOGI("Generation cancelled by caller after %d tokens", decode_tokens);
                break;
            }
            if (g_session.current_pos >= g_session.n_ctx - kOverflowHeadroom) {
                if (!shift_context_locked()) {
                    LOGW("Context window reached capacity mid-generation (%d/%d); finishing turn gracefully.",
                         g_session.current_pos, g_session.n_ctx);
                    stopped_on_kv_limit = true;
                    break;
                }
            }

            if (enable_thinking &&
                in_thinking_block &&
                !forced_thinking_close &&
                thinkingBudgetTokens > 0 &&
                thought_tokens_seen >= thinkingBudgetTokens) {
                const std::string close_seq = (active_close_tag == "<channel|>")
                        ? "<channel|>"
                        : ("\n" + active_close_tag + "\n\n");
                auto close_tokens = common_tokenize(g_session.ctx, close_seq, false, true);
                if (!close_tokens.empty() && decode_tokens_in_batches_locked(close_tokens, true) == 0) {
                    for (const llama_token ctok : close_tokens) {
                        common_sampler_accept(g_session.sampler, ctok, false);
                    }
                    full_assistant += close_seq;
                    emit_utf8_chunk(close_seq);
                    in_thinking_block = false;
                    forced_thinking_close = true;
                    tag_window.clear();
                    LOGI("Thinking budget (%d tok) reached; closing reasoning block cleanly.", thinkingBudgetTokens);
                }
            }

            const llama_token next_tok = common_sampler_sample(g_session.sampler, g_session.ctx, -1);
            common_sampler_accept(g_session.sampler, next_tok, true);

            if (llama_vocab_is_eog(vocab, next_tok)) {
                break;
            }

            if (in_thinking_block) {
                thought_tokens_seen++;
            }

            const std::string piece = common_token_to_piece(g_session.ctx, next_tok);
            pending_utf8 += piece;
            if (is_complete_utf8(pending_utf8)) {
                full_assistant += pending_utf8;
                update_thinking_window(pending_utf8);
                emit_utf8_chunk(pending_utf8);
                pending_utf8.clear();
            }

            decode_batch.clear();
            decode_batch.add(next_tok, g_session.current_pos, 0, true);
            if (llama_process(g_session.ctx, LLAMA_PROCESS_TYPE_DECODE, decode_batch.get()) != 0) {
                if (decode_tokens > 0) {
                    LOGW("llama_process stopped at step %d; preserving %d generated tokens.", step, decode_tokens);
                    stopped_on_kv_limit = true;
                    break;
                }
                rollback_failed_turn_locked();
                emit_error("llama_process failed during token decode");
                return -8;
            }
            g_session.current_pos++;
            g_atomic_kv_used.store(g_session.current_pos, std::memory_order_relaxed);
            decode_tokens++;
        }

        // Flush any remaining UTF-8 bytes safely before completion
        if (!pending_utf8.empty()) {
            full_assistant += pending_utf8;
            emit_utf8_chunk(pending_utf8);
            pending_utf8.clear();
        }

        // Append assistant turn directly to session history without redundant Jinja re-formatting
        std::string assistant_history_text = std::move(full_assistant);
        if (starts_in_thinking &&
            !active_open_tag.empty() &&
            assistant_history_text.rfind(active_open_tag, 0) != 0) {
            assistant_history_text = active_open_tag + "\n" + assistant_history_text;
        }
        common_chat_msg assistant_msg;
        assistant_msg.role = "assistant";
        assistant_msg.content = std::move(assistant_history_text);
        g_session.chat_history.push_back(std::move(assistant_msg));

        if (stopped_on_kv_limit) {
            g_session.can_rollback = false;
        }

        const auto t_decode_end = std::chrono::steady_clock::now();
        const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                t_decode_end - t_decode_start).count();
        env->CallVoidMethod(jCallback, mid_done, static_cast<jint>(decode_tokens), static_cast<jlong>(decode_ms));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return 0;
    } catch (const std::exception & e) {
        emit_error(e.what());
        return -9;
    } catch (...) {
        emit_error("Unexpected native exception during generation");
        return -9;
    }
}

JNIEXPORT void JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeCancelGeneration(
        JNIEnv * /*env*/,
        jobject /*thiz*/) {
    g_cancel_requested.store(true);
}

JNIEXPORT jboolean JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeRollbackLastTurn(
        JNIEnv * /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_session.ctx || !g_session.can_rollback) {
        return JNI_FALSE;
    }
    llama_memory_t mem = llama_get_memory(g_session.ctx);
    if (!llama_memory_seq_rm(mem, 0, g_session.prev_turn_pos, -1)) {
        LOGW("Partial KV rollback unsupported at pos=%d (hybrid/recurrent model); caller should reset.",
             g_session.prev_turn_pos);
        return JNI_FALSE;
    }
    g_session.current_pos = g_session.prev_turn_pos;
    if (g_session.chat_history.size() > g_session.prev_history_size) {
        g_session.chat_history.resize(g_session.prev_history_size);
    }
    if (g_session.sampler) {
        common_sampler_reset(g_session.sampler);
    }
    g_session.can_rollback = false;
    sync_atomic_stats_locked();
    LOGI("Rolled back last conversation turn to KV pos=%d", g_session.current_pos);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeResetContext(
        JNIEnv * /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    reset_session_memory_locked();
}

JNIEXPORT void JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeUnloadModel(
        JNIEnv * /*env*/,
        jobject /*thiz*/) {
    g_cancel_requested.store(true);
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    free_session_locked();
    LOGI("GGUF model unloaded and native buffers released");
}

JNIEXPORT jstring JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeGetStatsJson(
        JNIEnv * env,
        jobject /*thiz*/) {
    // Read lock-free atomics so periodic polling never stalls behind an active decode turn.
    std::string backend;
    {
        std::lock_guard<std::mutex> slock(g_stats_mutex);
        backend = g_atomic_backend_label;
    }
    std::ostringstream oss;
    oss << "{"
        << "\"loaded\":" << (g_atomic_loaded.load(std::memory_order_relaxed) ? "true" : "false") << ","
        << "\"kvUsed\":" << g_atomic_kv_used.load(std::memory_order_relaxed) << ","
        << "\"kvMax\":" << g_atomic_kv_max.load(std::memory_order_relaxed) << ","
        << "\"modelBytes\":" << g_atomic_model_bytes.load(std::memory_order_relaxed) << ","
        << "\"visionEnabled\":" << (g_atomic_vision.load(std::memory_order_relaxed) ? "true" : "false") << ","
        << "\"backend\":\"" << backend << "\""
        << "}";
    return new_jstring_from_utf8(env, oss.str());
}

JNIEXPORT void JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeLoadEmbeddingModel(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jModelPath,
        jint nCtx,
        jint nThreads) {
    std::lock_guard<std::mutex> lock(g_embed_mutex);
    if (jModelPath == nullptr) {
        return;
    }
    const char * model_path = env->GetStringUTFChars(jModelPath, nullptr);
    if (!model_path) return;

    // Free the previous embedding model before probing the new one
    if (g_embed_session.ctx) {
        llama_free(g_embed_session.ctx);
        g_embed_session.ctx = nullptr;
    }
    if (g_embed_session.model) {
        llama_model_free(g_embed_session.model);
        g_embed_session.model = nullptr;
    }

    llama_model_params mparams = llama_model_default_params();
    // Embedding models are small; keep them on CPU so the main chat model can use the GPU.
    mparams.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(jModelPath, model_path);
    if (!model) {
        LOGE("Failed to load embedding model");
        return;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.embeddings = true;
    cparams.n_ctx = static_cast<uint32_t>(std::max(512, static_cast<int32_t>(nCtx)));
    cparams.n_batch = cparams.n_ctx;
    cparams.n_ubatch = std::min<uint32_t>(cparams.n_batch, 512);
    cparams.n_threads = std::max(1, static_cast<int32_t>(nThreads));
    cparams.n_threads_batch = cparams.n_threads;
    // Pooling defaults to the value advertised by the model metadata (mean for BERT family).

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model failed for embedding model");
        llama_model_free(model);
        return;
    }

    g_embed_session.model = model;
    g_embed_session.ctx = ctx;
    g_embed_session.n_ctx = static_cast<int32_t>(cparams.n_ctx);
    g_embed_session.n_embd = llama_model_n_embd(model);
    LOGI("Embedding model ready (n_embd=%d ctx=%d)", g_embed_session.n_embd, g_embed_session.n_ctx);
}

JNIEXPORT void JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeUnloadEmbeddingModel(
        JNIEnv * /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_embed_mutex);
    if (g_embed_session.ctx) {
        llama_free(g_embed_session.ctx);
        g_embed_session.ctx = nullptr;
    }
    if (g_embed_session.model) {
        llama_model_free(g_embed_session.model);
        g_embed_session.model = nullptr;
    }
    g_embed_session.n_embd = 0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeEmbedText(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jText) {
    std::lock_guard<std::mutex> lock(g_embed_mutex);
    if (!g_embed_session.model || !g_embed_session.ctx || g_embed_session.n_embd <= 0) {
        return nullptr;
    }
    if (jText == nullptr) return nullptr;
    const char * text_cstr = env->GetStringUTFChars(jText, nullptr);
    if (!text_cstr) return nullptr;
    std::string text(text_cstr);
    env->ReleaseStringUTFChars(jText, text_cstr);
    if (text.empty()) return nullptr;

    const llama_vocab * vocab = llama_model_get_vocab(g_embed_session.model);
    auto tokens = common_tokenize(g_embed_session.ctx, text, /* add_special */ true, /* parse_special */ true);
    if (tokens.empty()) return nullptr;
    if (static_cast<int32_t>(tokens.size()) > g_embed_session.n_ctx - 4) {
        tokens.resize(static_cast<size_t>(g_embed_session.n_ctx - 4));
    }

    common_batch batch(g_embed_session.ctx);
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.add(tokens[i], static_cast<int32_t>(i), 0, false);
    }
    if (llama_process(g_embed_session.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
        LOGE("Embedding decode failed");
        return nullptr;
    }

    const float * embd = llama_get_embeddings_seq(g_embed_session.ctx, 0);
    if (embd == nullptr) {
        // Fall back to the last-token embedding if pooling is unavailable
        embd = llama_get_embeddings_ith(g_embed_session.ctx, static_cast<int32_t>(tokens.size()) - 1);
    }
    if (embd == nullptr) return nullptr;

    jfloatArray result = env->NewFloatArray(g_embed_session.n_embd);
    if (result == nullptr) return nullptr;
    std::vector<float> copy(embd, embd + g_embed_session.n_embd);
    env->SetFloatArrayRegion(result, 0, g_embed_session.n_embd, copy.data());
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeIsEmbeddingModelLoaded(
        JNIEnv * /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_embed_mutex);
    return (g_embed_session.model != nullptr && g_embed_session.ctx != nullptr && g_embed_session.n_embd > 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeSaveSessionState(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jPath) {
    if (jPath == nullptr) return JNI_FALSE;
    const char * path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    jboolean ok = JNI_FALSE;
    if (g_session.ctx && g_session.current_pos > 0) {
        const size_t written = llama_state_seq_save_file(g_session.ctx, path, 0, nullptr, 0);
        ok = (written > 0) ? JNI_TRUE : JNI_FALSE;
    }
    env->ReleaseStringUTFChars(jPath, path);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeLoadSessionState(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jPath) {
    if (jPath == nullptr) return JNI_FALSE;
    const char * path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    jboolean ok = JNI_FALSE;
    if (g_session.ctx) {
        size_t token_count = 0;
        const size_t loaded = llama_state_seq_load_file(g_session.ctx, path, 0, nullptr, 0, &token_count);
        if (loaded > 0 && token_count > 0) {
            g_session.current_pos = static_cast<llama_pos>(token_count);
            g_session.can_rollback = false;
            g_session.system_prompt_applied = true;
            g_atomic_kv_used.store(g_session.current_pos, std::memory_order_relaxed);
            ok = JNI_TRUE;
        }
    }
    env->ReleaseStringUTFChars(jPath, path);
    return ok;
}

JNIEXPORT jstring JNICALL
Java_com_example_data_engine_LlamaCppNativeBridge_nativeGetRecentLogs(
        JNIEnv * env,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::ostringstream oss;
    for (const auto & line : g_recent_logs) {
        oss << line << '\n';
    }
    return new_jstring_from_utf8(env, oss.str());
}

} // extern "C"
