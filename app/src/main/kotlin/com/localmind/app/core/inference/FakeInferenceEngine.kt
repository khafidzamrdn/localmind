package com.localmind.app.core.inference

import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ModelFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Milestone 1 backend. It produces deterministic, streamed Markdown so the
 * whole chat pipeline (persistence, cancellation, scroll behaviour, Markdown
 * rendering) can be exercised without an Android native inference library.
 *
 * This engine never reads the selected model file and makes no network calls.
 * The UI labels it as a demo backend so generated text is never mistaken for
 * real model output.
 */
class FakeInferenceEngine : InferenceEngine {

    override val id: String = "demo"
    override val displayName: String = "Demo engine"
    override val isDemo: Boolean = true

    private val loadedModelId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    override fun capabilities() = EngineCapabilities(
        streaming = true,
        nativeToolCalling = false,
        gpuAcceleration = false,
        configurableContext = false,
    )

    override suspend fun loadModel(model: LocalModel): Result<Unit> {
        if (model.format != ModelFormat.GGUF) {
            return Result.failure(IllegalArgumentException("Unsupported model format: ${model.displayName}"))
        }
        delay(250)
        loadedModelId.set(model.id)
        return Result.success(Unit)
    }

    override suspend fun unload() {
        loadedModelId.set(null)
    }

    override fun generate(request: InferenceRequest): Flow<InferenceEvent> = flow {
        val prompt = request.messages.lastOrNull { it.isUser }?.content.orEmpty()
        val response = buildResponse(prompt)

        // Simulated model warm-up / prefill latency.
        delay(450)
        currentCoroutineContext().ensureActive()

        val chunks = chunkForStreaming(response)
        var emitted = 0
        val maxChunks = (request.params.maxTokens * 3).coerceAtLeast(24)

        for (chunk in chunks) {
            currentCoroutineContext().ensureActive()
            emit(InferenceEvent.Token(chunk))
            emitted++
            if (emitted >= maxChunks) break
            delay(STREAM_DELAY_MS)
        }

        val promptTokens = estimateTokens(request.systemPrompt) +
            request.messages.sumOf { estimateTokens(it.content) }
        emit(
            InferenceEvent.Completed(
                TokenUsage(
                    promptTokens = promptTokens,
                    generatedTokens = estimateTokens(response),
                ),
            ),
        )
    }

    private fun chunkForStreaming(text: String): List<String> =
        TOKEN_REGEX.findAll(text).map { it.value }.toList()

    private fun buildResponse(prompt: String): String {
        val normalized = prompt.lowercase()
        val indonesian = INDONESIAN_HINTS.any { normalized.contains(it) }
        return when {
            normalized.isBlank() -> if (indonesian) EMPTY_ID else EMPTY_EN
            GREETING_HINTS.any { normalized.trim().startsWith(it) } ->
                if (indonesian) GREETING_ID else GREETING_EN
            normalized.contains("```") || CODE_HINTS.any { normalized.contains(it) } ->
                if (indonesian) CODE_ID else CODE_EN
            MARKDOWN_HINTS.any { normalized.contains(it) } ->
                if (indonesian) MARKDOWN_ID else MARKDOWN_EN
            else -> if (indonesian) GENERAL_ID else GENERAL_EN
        }
    }

    private companion object {
        const val STREAM_DELAY_MS = 16L

        val TOKEN_REGEX = Regex("""\s*\S+""")

        val GREETING_HINTS = listOf("hi", "hello", "hey", "halo", "hai", "pagi", "siang", "malam")
        val CODE_HINTS = listOf("code", "kode", "kotlin", "compose", "function", "fungsi", "program", "bug")
        val MARKDOWN_HINTS = listOf("markdown", "format", "table", "tabel")
        val INDONESIAN_HINTS = listOf(
            "apa", "bagaimana", "tolong", "buat", "jelaskan", "saya", "kamu", "dan", "yang",
            "adalah", "bisa", "tidak", "untuk", "dengan", "contoh",
        )

        val EMPTY_EN = """
            I did not receive any content. Send a question or a task description and I will respond.
        """.trimIndent()

        val EMPTY_ID = """
            Saya tidak menerima isi pesan. Kirim pertanyaan atau deskripsi tugas, lalu saya akan merespons.
        """.trimIndent()

        val GREETING_EN = """
            ## Hello 👋

            I'm **LocalMind**, a local-first assistant. In this build I'm running as a **demo engine**: the chat,
            storage, streaming and cancellation paths are real, but the words are simulated.

            Here is what already works:

            - Conversations are stored locally with Room.
            - Streaming responses can be stopped at any time.
            - Messages render Markdown and code blocks.
            - Editing a prompt truncates later messages and regenerates.

            Import a `.gguf` model from the **Models** tab to prepare for the native inference backend.
        """.trimIndent()

        val GREETING_ID = """
            ## Halo 👋

            Saya **LocalMind**, asisten lokal-first. Di build ini saya berjalan sebagai **demo engine**: alur chat,
            penyimpanan, streaming, dan stop sudah nyata, tetapi teks jawabannya masih simulasi.

            Yang sudah berfungsi:

            - Percakapan disimpan lokal dengan Room.
            - Respons streaming bisa dihentikan kapan saja.
            - Pesan mendukung Markdown dan blok kode.
            - Mengedit prompt akan memotong pesan setelahnya lalu regenerasi.

            Impor model `.gguf` dari tab **Models** untuk menyiapkan backend inferensi native.
        """.trimIndent()

        val CODE_EN = """
            ## A small Kotlin example

            A typical Compose state holder keeps UI state immutable and exposes events as functions:

            ```kotlin
            data class CounterUiState(
                val count: Int = 0,
                val isSaving: Boolean = false,
            )

            class CounterViewModel(
                private val repository: CounterRepository,
            ) : ViewModel() {
                private val _state = MutableStateFlow(CounterUiState())
                val state: StateFlow<CounterUiState> = _state.asStateFlow()

                fun increment() {
                    _state.update { current ->
                        current.copy(count = current.count + 1)
                    }
                }
            }
            ```

            ### Why this shape works

            1. The UI only observes `state`.
            2. Side effects stay inside the ViewModel.
            3. `copy` keeps recomposition predictable.

            > This answer is generated by the demo engine. Once a real GGUF backend is loaded, the same pipeline will
            > stream model tokens instead.
        """.trimIndent()

        val CODE_ID = """
            ## Contoh Kotlin singkat

            State holder Compose yang rapi menyimpan state immutable dan mengekspos aksi sebagai fungsi:

            ```kotlin
            data class CounterUiState(
                val count: Int = 0,
                val isSaving: Boolean = false,
            )

            class CounterViewModel(
                private val repository: CounterRepository,
            ) : ViewModel() {
                private val _state = MutableStateFlow(CounterUiState())
                val state: StateFlow<CounterUiState> = _state.asStateFlow()

                fun increment() {
                    _state.update { current ->
                        current.copy(count = current.count + 1)
                    }
                }
            }
            ```

            ### Kenapa pola ini berguna

            1. UI hanya mengamati `state`.
            2. Efek samping tetap di dalam ViewModel.
            3. `copy` membuat recomposition lebih mudah diprediksi.

            > Jawaban ini dihasilkan demo engine. Setelah backend GGUF asli dimuat, pipeline yang sama akan
            > men-stream token dari model.
        """.trimIndent()

        val MARKDOWN_EN = """
            ## Markdown support

            LocalMind renders the subset that matters for technical chat:

            | Element | Example |
            | --- | --- |
            | Bold | **important** |
            | Inline code | `rememberSaveable` |
            | Code fences | fenced blocks with copy |
            | Lists | ordered and unordered |

            - Nested paragraphs stay readable.
            - Long lines in code blocks scroll horizontally.
            - Every code block has a copy action.

            ```json
            {
              "name": "localmind",
              "offline": true,
              "backend": "demo"
            }
            ```
        """.trimIndent()

        val MARKDOWN_ID = """
            ## Dukungan Markdown

            LocalMind merender bagian yang penting untuk chat teknis:

            | Elemen | Contoh |
            | --- | --- |
            | Tebal | **penting** |
            | Kode inline | `rememberSaveable` |
            | Blok kode | blok berpagar dengan tombol salin |
            | Daftar | berurut dan tidak berurut |

            - Paragraf tetap nyaman dibaca.
            - Baris panjang di blok kode bisa digeser horizontal.
            - Setiap blok kode punya aksi salin.

            ```json
            {
              "name": "localmind",
              "offline": true,
              "backend": "demo"
            }
            ```
        """.trimIndent()

        val GENERAL_EN = """
            ## Working through it

            Here is a practical way to approach the request in an offline Android app:

            1. **Clarify the constraint.** Local models have a fixed context window, so decide what must be in the
               prompt versus what can be retrieved on demand.
            2. **Keep data local.** Store conversations in Room and settings in DataStore; avoid logging prompt text.
            3. **Stream early.** Show the first token quickly, and keep a visible stop action.
            4. **Handle failure.** A model can fail to load because of RAM pressure, a corrupt file, or an
               unsupported quantization.

            ```text
            request -> context builder -> engine -> stream -> persist
                                          |
                                          +--> cancel / error path
            ```

            If you tell me which part you want to build first, I can outline the concrete classes and files.

            > Note: this reply comes from the demo engine. Native GGUF inference is not enabled in Milestone 1.
        """.trimIndent()

        val GENERAL_ID = """
            ## Cara mendekati tugas ini

            Berikut pendekatan praktis untuk aplikasi Android offline:

            1. **Tentukan batasannya.** Model lokal punya context window terbatas, jadi pisahkan apa yang wajib masuk
               prompt dan apa yang diambil saat dibutuhkan.
            2. **Jaga data tetap lokal.** Simpan percakapan di Room dan pengaturan di DataStore; jangan mencatat isi
               prompt ke log.
            3. **Streaming lebih awal.** Tampilkan token pertama secepat mungkin dan sediakan tombol stop.
            4. **Tangani kegagalan.** Model bisa gagal dimuat karena RAM, file rusak, atau kuantisasi yang tidak
               didukung.

            ```text
            request -> context builder -> engine -> stream -> persist
                                          |
                                          +--> cancel / error path
            ```

            Sebutkan bagian mana yang ingin dibangun lebih dulu, dan saya bisa jabarkan class serta file konkretnya.

            > Catatan: jawaban ini berasal dari demo engine. Inferensi GGUF native belum aktif di Milestone 1.
        """.trimIndent()
    }
}
