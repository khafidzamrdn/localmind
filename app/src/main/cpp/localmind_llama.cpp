// JNI bridge between LocalMind's Kotlin engine and llama.cpp.
//
// Design notes:
//  * One opaque handle per loaded model (model + context + vocab).
//  * Model loading reports progress back to Kotlin and aborts when Kotlin
//    returns false (used for cancellation and for the memory guard).
//  * llama.cpp / ggml log lines are captured in a bounded ring buffer so the
//    UI can show the real failure reason instead of a generic message.
//  * Probe loads the vocabulary only, which validates the architecture and
//    tensor layout without reading the weights.
//  * Only complete UTF-8 sequences are streamed to Kotlin.

#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char * kTag = "LocalMindLlama";
constexpr size_t kMaxLogBytes = 16 * 1024;

// ---------------------------------------------------------------------------
// Log capture
// ---------------------------------------------------------------------------

std::mutex g_log_mutex;
std::string g_log_tail;

int android_priority(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN: return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        case GGML_LOG_LEVEL_CONT: return ANDROID_LOG_DEBUG;
        default: return ANDROID_LOG_INFO;
    }
}

void log_callback(enum ggml_log_level level, const char * text, void *) {
    if (text == nullptr || *text == '\0') return;
    __android_log_write(android_priority(level), kTag, text);

    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_tail.append(text);
    if (g_log_tail.size() > kMaxLogBytes) {
        const size_t overflow = g_log_tail.size() - kMaxLogBytes;
        const size_t newline = g_log_tail.find('\n', overflow);
        g_log_tail.erase(0, newline == std::string::npos ? overflow : newline + 1);
    }
}

std::string log_tail() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return g_log_tail;
}

void clear_log() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_tail.clear();
}

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

// Values shared with Kotlin's NativeErrorKind.
enum ErrorKind : int {
    kErrorUnknown = 0,
    kErrorUnsupportedArchitecture = 1,
    kErrorFileAccess = 2,
    kErrorOutOfMemory = 3,
    kErrorCancelled = 4,
};

int classify_error(const std::string & log) {
    const std::string text = lower_copy(log);
    if (text.find("cancelled model load") != std::string::npos) return kErrorCancelled;
    if (text.find("unsupported model architecture") != std::string::npos ||
        text.find("unknown model architecture") != std::string::npos ||
        text.find("unsupported architecture") != std::string::npos) {
        return kErrorUnsupportedArchitecture;
    }
    if (text.find("failed to allocate") != std::string::npos ||
        text.find("out of memory") != std::string::npos ||
        text.find("cannot allocate memory") != std::string::npos) {
        return kErrorOutOfMemory;
    }
    if (text.find("failed to open") != std::string::npos ||
        text.find("failed to load model from") != std::string::npos ||
        text.find("failed to map") != std::string::npos ||
        text.find("mmap") != std::string::npos ||
        text.find("no such file") != std::string::npos ||
        text.find("permission denied") != std::string::npos ||
        text.find("no such device or address") != std::string::npos) {
        return kErrorFileAccess;
    }
    return kErrorUnknown;
}

void throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
}

// Adds the native log tail to an error message so the UI can show the reason.
std::string with_log(const std::string & message) {
    std::string log = log_tail();
    constexpr size_t kMaxDetail = 1800;
    if (log.size() > kMaxDetail) {
        log.erase(0, log.size() - kMaxDetail);
    }
    while (!log.empty() && (log.back() == '\n' || log.back() == ' ')) log.pop_back();
    if (log.empty()) return message;
    return message + "\n\n" + log;
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t n_ctx = 0;
    int32_t n_batch = 0;
    std::atomic<bool> cancel_requested{false};
    std::mutex mutex;
};

// Called by llama.cpp from compute threads while decoding. Returning true
// aborts the current llama_decode call, so Stop also interrupts prefill.
bool abort_callback(void * data) {
    auto * session = static_cast<Session *>(data);
    return session != nullptr && session->cancel_requested.load(std::memory_order_relaxed);
}

struct ProgressBridge {
    JNIEnv * env = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    int last_percent = -1;
};

bool load_progress(float progress, void * user_data) {
    auto * bridge = static_cast<ProgressBridge *>(user_data);
    if (bridge == nullptr || bridge->callback == nullptr || bridge->env == nullptr) return true;

    int percent = static_cast<int>(progress * 100.0f);
    if (percent < 0) percent = 0;
    if (percent > 100) percent = 100;
    if (percent == bridge->last_percent) return true;
    bridge->last_percent = percent;

    const jboolean keep_going = bridge->env->CallBooleanMethod(
        bridge->callback, bridge->method, static_cast<jint>(percent));
    if (bridge->env->ExceptionCheck() == JNI_TRUE) return false;
    return keep_going == JNI_TRUE;
}

std::string to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::vector<std::string> to_strings(JNIEnv * env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;
    const jsize size = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(size));
    for (jsize i = 0; i < size; ++i) {
        auto * element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(to_string(env, element));
        if (element != nullptr) env->DeleteLocalRef(element);
    }
    return out;
}

size_t complete_utf8_prefix(const std::string & text) {
    size_t index = 0;
    size_t last = 0;
    while (index < text.size()) {
        const auto lead = static_cast<unsigned char>(text[index]);
        size_t length = 0;
        if (lead < 0x80) {
            length = 1;
        } else if ((lead & 0xE0) == 0xC0) {
            length = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            length = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            length = 4;
        } else {
            break;
        }
        if (index + length > text.size()) break;
        index += length;
        last = index;
    }
    return last;
}

bool emit_text(JNIEnv * env, jobject callback, jmethodID method, const std::string & text) {
    if (text.empty()) return true;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes == nullptr) return false;
    env->SetByteArrayRegion(
        bytes,
        0,
        static_cast<jsize>(text.size()),
        reinterpret_cast<const jbyte *>(text.data()));
    const jboolean keep_going = env->CallBooleanMethod(callback, method, bytes);
    env->DeleteLocalRef(bytes);
    return keep_going == JNI_TRUE;
}

std::string build_prompt(
    llama_model * model,
    const std::vector<std::string> & roles,
    const std::vector<std::string> & contents) {
    std::vector<llama_chat_message> messages;
    messages.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) {
        messages.push_back({ roles[i].c_str(), contents[i].c_str() });
    }

    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr || *tmpl == '\0') {
        tmpl = "chatml";
    }

    int32_t needed = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), true, nullptr, 0);
    if (needed > 0) {
        std::string formatted(static_cast<size_t>(needed), '\0');
        int32_t written = llama_chat_apply_template(
            tmpl, messages.data(), messages.size(), true, formatted.data(), needed);
        if (written > 0) {
            formatted.resize(static_cast<size_t>(written));
            return formatted;
        }
    }

    std::string fallback;
    for (size_t i = 0; i < roles.size(); ++i) {
        fallback += roles[i];
        fallback += ": ";
        fallback += contents[i];
        fallback += '\n';
    }
    fallback += "assistant:";
    return fallback;
}

void free_session(Session * session) {
    if (session == nullptr) return;
    if (session->ctx != nullptr) llama_free(session->ctx);
    if (session->model != nullptr) llama_model_free(session->model);
    session->ctx = nullptr;
    session->model = nullptr;
    delete session;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_backendInfo(JNIEnv * env, jobject) {
    try {
        const char * version = llama_version();
        const char * system = llama_print_system_info();

        std::string info = "llama.cpp ";
        info += version != nullptr ? version : "unknown";
        info += "\n";
        info += system != nullptr ? system : "unknown";

        const size_t backend_count = ggml_backend_reg_count();
        info += "\nbackends=" + std::to_string(backend_count);
        info += "\ndevices=" + std::to_string(ggml_backend_dev_count());

        bool gpu = false;
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (dev == nullptr) continue;
            if (ggml_backend_dev_type(dev) != GGML_BACKEND_DEVICE_TYPE_CPU) {
                gpu = true;
                const char * name = ggml_backend_dev_name(dev);
                if (name != nullptr) {
                    info += " ";
                    info += name;
                }
            }
        }
        info += std::string("\ngpu=") + (gpu ? "true" : "false");
        return env->NewStringUTF(info.c_str());
    } catch (const std::exception & error) {
        throw_illegal_state(env, std::string("backend info failed: ") + error.what());
    } catch (...) {
        throw_illegal_state(env, "backend info failed: unknown native error");
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_lastLog(JNIEnv * env, jobject) {
    return env->NewStringUTF(log_tail().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_clearLog(JNIEnv *, jobject) {
    clear_log();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_lastErrorKind(JNIEnv *, jobject) {
    return classify_error(log_tail());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_probeModel(
    JNIEnv * env,
    jobject,
    jstring j_path,
    jobject progress_callback) {
    const std::string path = to_string(env, j_path);
    if (path.empty()) {
        throw_illegal_state(env, "No model path was provided.");
        return nullptr;
    }

    ProgressBridge bridge;
    jmethodID progress_method = nullptr;
    if (progress_callback != nullptr) {
        jclass cls = env->GetObjectClass(progress_callback);
        progress_method = env->GetMethodID(cls, "onProgress", "(I)Z");
        env->DeleteLocalRef(cls);
        bridge.env = env;
        bridge.callback = progress_callback;
        bridge.method = progress_method;
    }

    clear_log();

    try {
        llama_model_params params = llama_model_default_params();
        params.vocab_only = true;
        params.load_mode = LLAMA_LOAD_MODE_NONE;
        params.n_gpu_layers = 0;
        params.check_tensors = false;
        if (progress_method != nullptr) {
            params.progress_callback = load_progress;
            params.progress_callback_user_data = &bridge;
        }

        llama_model * model = llama_model_load_from_file(path.c_str(), params);
        if (model == nullptr) {
            throw_illegal_state(
                env,
                with_log(
                    "llama.cpp could not read this GGUF file. The file may be unreadable, "
                    "split into multiple parts, or use an architecture this build does not support."));
            return nullptr;
        }

        char arch[128] = {0};
        char desc[256] = {0};
        llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));
        llama_model_desc(model, desc, sizeof(desc));

        std::string info;
        info += "arch=";
        info += arch;
        info += "\ndesc=";
        info += desc;
        info += "\nparams=" + std::to_string(llama_model_n_params(model));
        info += "\nctx_train=" + std::to_string(llama_model_n_ctx_train(model));

        llama_model_free(model);
        return env->NewStringUTF(info.c_str());
    } catch (const std::exception & error) {
        throw_illegal_state(env, with_log(std::string("llama.cpp probe failed: ") + error.what()));
    } catch (...) {
        throw_illegal_state(env, with_log("llama.cpp probe failed: unknown native error"));
    }
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_loadModel(
    JNIEnv * env,
    jobject,
    jstring j_path,
    jint context_length,
    jint batch_size,
    jint thread_count,
    jint gpu_layers,
    jobject progress_callback) {
    const std::string path = to_string(env, j_path);
    if (path.empty()) {
        throw_illegal_state(env, "No model path was provided.");
        return 0;
    }

    ProgressBridge bridge;
    jmethodID progress_method = nullptr;
    if (progress_callback != nullptr) {
        jclass cls = env->GetObjectClass(progress_callback);
        progress_method = env->GetMethodID(cls, "onProgress", "(I)Z");
        env->DeleteLocalRef(cls);
        bridge.env = env;
        bridge.callback = progress_callback;
        bridge.method = progress_method;
    }

    clear_log();

    try {
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = gpu_layers;
        model_params.check_tensors = false;
        if (progress_method != nullptr) {
            model_params.progress_callback = load_progress;
            model_params.progress_callback_user_data = &bridge;
        }

        llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
        if (model == nullptr) {
            const int kind = classify_error(log_tail());
            if (kind == kErrorCancelled) {
                throw_illegal_state(env, "The model load was cancelled.");
            } else {
                throw_illegal_state(
                    env,
                    with_log(
                        "llama.cpp could not load this GGUF file. The native log above shows the "
                        "exact reason (unsupported architecture, unreadable file, or out of memory)."));
            }
            return 0;
        }

        const int32_t trained_ctx = llama_model_n_ctx_train(model);
        int32_t n_ctx = context_length > 0 ? context_length : trained_ctx;
        if (trained_ctx > 0 && n_ctx > trained_ctx) n_ctx = trained_ctx;
        if (n_ctx < 256) n_ctx = 256;

        int32_t n_batch = batch_size > 0 ? batch_size : 512;
        n_batch = std::max(32, std::min(n_batch, 2048));
        const int32_t threads = std::max(1, static_cast<int>(thread_count));

        auto * session = new Session();
        session->model = model;
        session->n_batch = n_batch;

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = static_cast<uint32_t>(n_ctx);
        ctx_params.n_batch = static_cast<uint32_t>(n_batch);
        ctx_params.n_ubatch = static_cast<uint32_t>(std::min(n_batch, 512));
        ctx_params.n_threads = threads;
        ctx_params.n_threads_batch = threads;
        ctx_params.no_perf = true;
        ctx_params.abort_callback = abort_callback;
        ctx_params.abort_callback_data = session;

        llama_context * ctx = llama_init_from_model(model, ctx_params);
        if (ctx == nullptr) {
            delete session;
            llama_model_free(model);
            throw_illegal_state(
                env,
                with_log(
                    "llama.cpp loaded the weights but could not create a context. "
                    "Reduce the context length or batch size."));
            return 0;
        }

        session->ctx = ctx;
        session->vocab = llama_model_get_vocab(model);
        session->n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception & error) {
        throw_illegal_state(env, with_log(std::string("llama.cpp load failed: ") + error.what()));
    } catch (...) {
        throw_illegal_state(env, with_log("llama.cpp load failed: unknown native error"));
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_freeModel(
    JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    {
        // Wait for an in-flight generation to finish before tearing down the
        // context, so unloading cannot race the decode loop.
        std::lock_guard<std::mutex> lock(session->mutex);
    }
    free_session(session);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_contextSize(
    JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    return session != nullptr ? session->n_ctx : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_cancel(
    JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    session->cancel_requested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_generate(
    JNIEnv * env,
    jobject,
    jlong handle,
    jobjectArray roles_array,
    jobjectArray contents_array,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jobject callback) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) {
        throw_illegal_state(env, "The model is not loaded.");
        return -1;
    }
    if (callback == nullptr) {
        throw_illegal_state(env, "No token callback was provided.");
        return -1;
    }

    try {
        std::lock_guard<std::mutex> lock(session->mutex);
        session->cancel_requested.store(false, std::memory_order_relaxed);

        const std::vector<std::string> roles = to_strings(env, roles_array);
        const std::vector<std::string> contents = to_strings(env, contents_array);
        if (roles.empty() || roles.size() != contents.size()) {
            throw_illegal_state(env, "The chat history is empty or malformed.");
            return -1;
        }

        const std::string prompt = build_prompt(session->model, roles, contents);
        if (prompt.empty()) {
            throw_illegal_state(env, "The chat template produced an empty prompt.");
            return -1;
        }

        const llama_vocab * vocab = session->vocab;

        int32_t token_capacity = -llama_tokenize(
            vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
        if (token_capacity <= 0) {
            throw_illegal_state(env, "The prompt could not be tokenized by this model.");
            return -1;
        }

        std::vector<llama_token> tokens(static_cast<size_t>(token_capacity));
        const int32_t token_count = llama_tokenize(
            vocab,
            prompt.data(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            token_capacity,
            true,
            true);
        if (token_count <= 0) {
            throw_illegal_state(env, "The prompt could not be tokenized by this model.");
            return -1;
        }
        tokens.resize(static_cast<size_t>(token_count));

        int32_t max_new = max_tokens > 0 ? max_tokens : 512;
        max_new = std::min(max_new, session->n_ctx - 8);
        if (max_new < 1) {
            throw_illegal_state(env, "The context window is too small to generate tokens.");
            return -1;
        }

        const int32_t budget = session->n_ctx - max_new - 4;
        if (budget > 0 && static_cast<int32_t>(tokens.size()) > budget) {
            const size_t drop = tokens.size() - static_cast<size_t>(budget);
            tokens.erase(tokens.begin(), tokens.begin() + static_cast<long>(drop));
        }

        llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
        if (sampler == nullptr) {
            throw_illegal_state(env, "The sampler could not be created.");
            return -1;
        }
        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), 64, repeat_penalty > 0.0f ? repeat_penalty : 1.0f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
        llama_sampler_chain_add(
            sampler, llama_sampler_init_top_p(top_p > 0.0f ? top_p : 0.9f, 1));
        llama_sampler_chain_add(
            sampler, llama_sampler_init_temp(temperature > 0.0f ? temperature : 0.7f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        jclass callback_class = env->GetObjectClass(callback);
        jmethodID on_token = env->GetMethodID(callback_class, "onToken", "([B)Z");
        env->DeleteLocalRef(callback_class);
        if (on_token == nullptr) {
            llama_sampler_free(sampler);
            throw_illegal_state(env, "The token callback has an unexpected shape.");
            return -1;
        }

        int32_t result = 0;
        std::string pending;

        for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(session->n_batch)) {
            if (session->cancel_requested.load(std::memory_order_relaxed)) {
                llama_sampler_free(sampler);
                return -2;
            }
            const int32_t chunk = static_cast<int32_t>(
                std::min<size_t>(static_cast<size_t>(session->n_batch), tokens.size() - offset));
            llama_batch batch = llama_batch_get_one(tokens.data() + offset, chunk);
            if (llama_decode(session->ctx, batch) != 0) {
                if (session->cancel_requested.load(std::memory_order_relaxed)) {
                    llama_sampler_free(sampler);
                    return -2;
                }
                llama_sampler_free(sampler);
                throw_illegal_state(
                    env, with_log("llama.cpp failed while reading the prompt. This is usually out of memory."));
                return -1;
            }
        }

        int32_t produced = 0;
        int32_t n_past = static_cast<int32_t>(tokens.size());
        for (; produced < max_new; ++produced) {
            if (session->cancel_requested.load(std::memory_order_relaxed)) {
                llama_sampler_free(sampler);
                return -2;
            }
            if (n_past + 1 >= session->n_ctx) break;

            const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            llama_sampler_accept(sampler, token);

            char piece[256];
            const int32_t piece_length = llama_token_to_piece(
                vocab, token, piece, sizeof(piece), 0, false);
            if (piece_length > 0) {
                pending.append(piece, static_cast<size_t>(piece_length));
                const size_t emit = complete_utf8_prefix(pending);
                if (emit > 0) {
                    const bool keep_going = emit_text(env, callback, on_token, pending.substr(0, emit));
                    pending.erase(0, emit);
                    if (env->ExceptionCheck() == JNI_TRUE) {
                        llama_sampler_free(sampler);
                        return -1;
                    }
                    if (!keep_going) {
                        result = produced;
                        llama_sampler_free(sampler);
                        return result;
                    }
                }
            }

            llama_token next = token;
            llama_batch batch = llama_batch_get_one(&next, 1);
            if (llama_decode(session->ctx, batch) != 0) {
                const bool cancelled = session->cancel_requested.load(std::memory_order_relaxed);
                llama_sampler_free(sampler);
                return cancelled ? -2 : produced;
            }
            ++n_past;
        }

        if (!pending.empty()) {
            emit_text(env, callback, on_token, pending);
        }

        llama_sampler_free(sampler);
        return produced;
    } catch (const std::exception & error) {
        throw_illegal_state(env, with_log(std::string("llama.cpp generation failed: ") + error.what()));
    } catch (...) {
        throw_illegal_state(env, with_log("llama.cpp generation failed: unknown native error"));
    }
    return -1;
}
