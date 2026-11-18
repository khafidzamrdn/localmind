// JNI bridge between LocalMind's Kotlin engine and llama.cpp.
//
// Design notes:
//  * One opaque handle per loaded model (model + context + vocab).
//  * Generation streams decoded UTF-8 pieces back to Kotlin through a
//    TokenCallback; returning false from the callback stops generation.
//  * Only complete UTF-8 sequences are emitted, so Kotlin never sees a
//    replacement character in the middle of a multi-byte code point.
//  * No global model state; the caller may load a different model while an
//    older handle is still alive (only one is used by the app at a time).

#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LM_TAG "LocalMindLlama"

namespace {

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
// aborts the current llama_decode call, which makes Stop responsive even
// during prompt prefill instead of only at the next token.
bool abort_callback(void * data) {
    auto * session = static_cast<Session *>(data);
    return session != nullptr && session->cancel_requested.load(std::memory_order_relaxed);
}

void throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
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

// Length in bytes of the longest prefix of `text` that ends on a UTF-8
// character boundary. Invalid lead bytes stop the scan so we never emit
// garbage that the Kotlin decoder would have to paper over.
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
        // ChatML is the most widely compatible fallback for instruction-tuned
        // models that ship without an embedded template.
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
    llama_backend_init();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_backendInfo(JNIEnv * env, jobject) {
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "llama.cpp");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localmind_app_core_inference_llama_LlamaNative_loadModel(
    JNIEnv * env,
    jobject,
    jstring j_path,
    jint context_length,
    jint batch_size,
    jint thread_count,
    jint gpu_layers) {
    const std::string path = to_string(env, j_path);
    if (path.empty()) {
        throw_illegal_state(env, "No model path was provided.");
        return 0;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = gpu_layers;
    model_params.check_tensors = false;

    llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        throw_illegal_state(
            env,
            "llama.cpp could not load this GGUF file. The document provider may not expose a "
            "seekable file, the quantization may be unsupported, or the device is out of memory.");
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
            "llama.cpp created the model but could not allocate a context. Try a smaller "
            "context length or a smaller quantization.");
        return 0;
    }

    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(session);
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
    // Safe to call from any thread: the flag is only read by the compute
    // threads through abort_callback and by the generation loop.
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
        // Keep the most recent turns; the system prompt is the part the model
        // can most safely lose when a conversation outgrows the context.
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

    // Decode the prompt in batches the backend can accept.
    for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(session->n_batch)) {
        if (session->cancel_requested.load(std::memory_order_relaxed)) {
            llama_sampler_free(sampler);
            return -2;
        }
        const int32_t chunk = static_cast<int32_t>(
            std::min<size_t>(static_cast<size_t>(session->n_batch), tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, chunk);
        if (llama_decode(session->ctx, batch) != 0) {
            llama_sampler_free(sampler);
            if (session->cancel_requested.load(std::memory_order_relaxed)) return -2;
            throw_illegal_state(env, "llama.cpp failed while reading the prompt (out of memory?).");
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
}
