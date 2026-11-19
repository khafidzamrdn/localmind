package com.localmind.app.core.inference.llama

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.inference.LoadPhase
import com.localmind.app.core.inference.LoadProgress
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Turns a SAF document URI into a path llama.cpp can open with `fopen`, and
 * checks whether that file really supports random access.
 *
 * `ContentResolver.openFileDescriptor` is not a promise of a seekable file: a
 * provider may hand out a pipe (cloud storage, some downloads providers), and
 * llama.cpp memory-maps the model and seeks across the file. Two strategies
 * are used:
 *
 *  1. If `fstat` reports a regular file, the descriptor is kept open and
 *     `/proc/self/fd/N` is handed to llama.cpp. Nothing is copied.
 *  2. Otherwise the bytes are copied into the app cache with progress,
 *     free-space checks and cancellation, and the copy is used instead. The
 *     user's original file is never modified or deleted.
 */
class ModelFileAccess(
    private val contentResolver: ContentResolver,
    cacheDir: File,
) {

    private val modelsDir = File(cacheDir, CACHE_DIR_NAME)

    init {
        // Any file here belongs to a previous process: the in-memory registry of
        // loaded models does not survive a restart, so the copies are stale.
        clearCache()
    }

    /**
     * Opens [model]. When [forceCache] is true the document is copied even if
     * it looks seekable; the engine uses that to retry after a native file
     * access failure.
     */
    suspend fun open(
        model: LocalModel,
        forceCache: Boolean = false,
        onProgress: (LoadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): OpenedModelFile {
        if (isCancelled()) throw ModelLoadCancelledException()
        onProgress(LoadProgress(LoadPhase.PREPARING, 0))

        val uri = Uri.parse(model.uri)
        val descriptor = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (error: SecurityException) {
            throw ModelAccessException(
                "Read permission for this document was revoked. Re-import the file to restore access.",
                error,
            )
        } catch (error: Exception) {
            throw ModelAccessException(
                "Android could not open this document: ${error.message ?: "unknown error"}. " +
                    "The file may have been moved or deleted.",
                error,
            )
        } ?: throw ModelAccessException(
            "The document provider returned no file descriptor. Re-import the model to restore access.",
        )

        val stat = try {
            Os.fstat(descriptor.fileDescriptor)
        } catch (error: Exception) {
            descriptor.closeQuietly()
            throw ModelAccessException("Could not inspect the document: ${error.message}", error)
        }

        val seekable = isSeekable(descriptor, stat.st_mode)
        val reportedSize = stat.st_size.takeIf { it > 0 } ?: model.sizeBytes
        onProgress(LoadProgress(LoadPhase.PREPARING, 100))

        if (seekable && !forceCache) {
            return OpenedModelFile(
                path = "/proc/self/fd/${descriptor.fd}",
                copiedToCache = false,
                sizeBytes = reportedSize,
                description = "Accessible · seekable regular file · ${reportedSize} bytes · no copy",
                providerUri = model.uri,
                cacheFile = null,
                descriptor = descriptor,
            )
        }

        // A pipe cannot be seeked or memory-mapped by llama.cpp, so copy it.
        val cacheFile = File(modelsDir, "${model.id}.gguf")
        try {
            copyToCache(descriptor, cacheFile, reportedSize, onProgress, isCancelled)
        } finally {
            descriptor.closeQuietly()
        }

        return OpenedModelFile(
            path = cacheFile.absolutePath,
            copiedToCache = true,
            sizeBytes = cacheFile.length(),
            description = if (forceCache) {
                "Copied to app cache after a native file-access failure · ${cacheFile.length()} bytes"
            } else {
                "Provider is not seekable (pipe) · copied to app cache · ${cacheFile.length()} bytes"
            },
            providerUri = model.uri,
            cacheFile = cacheFile,
            descriptor = null,
        )
    }

    /** Cheap, read-only description used by the diagnostics panel. */
    fun inspect(model: LocalModel): String = try {
        contentResolver.openFileDescriptor(Uri.parse(model.uri), "r")?.use { descriptor ->
            val stat = Os.fstat(descriptor.fileDescriptor)
            val seekable = isSeekable(descriptor, stat.st_mode)
            buildString {
                append("Document openable · fd ${descriptor.fd} · ")
                append(if (seekable) "seekable regular file" else "non-seekable stream (cache copy needed)")
                if (stat.st_size > 0) append(" · ${stat.st_size} bytes")
            }
        } ?: "Provider returned no file descriptor."
    } catch (error: Exception) {
        "Not accessible: ${error.message ?: error.javaClass.simpleName}"
    }

    /** Deletes every cached copy; only used when this process owns none. */
    fun clearCache() {
        modelsDir.listFiles()?.forEach { file -> runCatching { file.delete() } }
    }

    fun cachedBytes(): Long = modelsDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun copyToCache(
        descriptor: ParcelFileDescriptor,
        target: File,
        expectedBytes: Long,
        onProgress: (LoadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        modelsDir.mkdirs()
        if (!modelsDir.isDirectory) {
            throw ModelAccessException(
                "The app cache directory is unavailable, so a non-seekable document cannot be copied.",
            )
        }
        ensureSpace(expectedBytes, 0)

        val partial = File(modelsDir, "${target.name}.part")
        runCatching { partial.delete() }
        val input = FileInputStream(descriptor.fileDescriptor)
        var copied = 0L
        try {
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var lastPercent = -1
                while (true) {
                    if (isCancelled()) throw ModelLoadCancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (expectedBytes > 0) {
                        ensureSpace(expectedBytes, copied)
                        val percent = ((copied * 100) / expectedBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(LoadProgress(LoadPhase.COPYING, percent))
                        }
                    } else {
                        // Unknown size: report growth in 4 MiB steps so the UI
                        // still shows movement, clamped to 95% until done.
                        val percent = ((copied / (4L * 1024 * 1024)) * 5).toInt().coerceAtMost(95)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(LoadProgress(LoadPhase.COPYING, percent))
                        }
                    }
                }
                output.fd.sync()
            }
            if (copied == 0L) {
                throw ModelAccessException("The document provider returned an empty file.")
            }
            if (!partial.renameTo(target)) {
                throw ModelAccessException("Could not finalise the cached model copy.")
            }
            onProgress(LoadProgress(LoadPhase.COPYING, 100))
        } catch (error: Throwable) {
            runCatching { partial.delete() }
            runCatching { target.delete() }
            throw when (error) {
                is ModelLoadCancelledException, is ModelAccessException -> error
                else -> ModelAccessException(
                    "Copying the document into the app cache failed: ${error.message ?: "unknown error"}",
                    error,
                )
            }
        } finally {
            runCatching { input.close() }
        }
    }

    private fun ensureSpace(expectedBytes: Long, alreadyCopied: Long) {
        val available = runCatching { StatFs(modelsDir.absolutePath).availableBytes }.getOrDefault(0L)
        if (available <= 0L) return
        val remaining = (expectedBytes - alreadyCopied).coerceAtLeast(0L)
        val needed = remaining + SPACE_HEADROOM_BYTES
        if (available < needed) {
            throw ModelAccessException(
                "Not enough free app storage to cache this model: need " +
                    "${needed / (1024 * 1024)} MB, ${available / (1024 * 1024)} MB available. " +
                    "Free some space or pick a seekable file.",
            )
        }
    }

    private fun isSeekable(descriptor: ParcelFileDescriptor, mode: Int): Boolean {
        val regular = (mode and OsConstants.S_IFMT) == OsConstants.S_IFREG
        if (!regular) return false
        return runCatching {
            // A real file can be repositioned; pipes return ESPIPE.
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            true
        }.getOrDefault(false)
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val CACHE_DIR_NAME = "model-cache"
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val SPACE_HEADROOM_BYTES = 32L * 1024 * 1024
    }
}

/** A model path plus whatever must stay alive/deleted around a native load. */
class OpenedModelFile(
    val path: String,
    val copiedToCache: Boolean,
    val sizeBytes: Long,
    val description: String,
    val providerUri: String,
    val cacheFile: File?,
    private val descriptor: ParcelFileDescriptor?,
) {
    fun close() {
        runCatching { descriptor?.close() }
        cacheFile?.let { file -> runCatching { file.delete() } }
    }
}
