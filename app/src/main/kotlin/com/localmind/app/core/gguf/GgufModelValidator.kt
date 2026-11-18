package com.localmind.app.core.gguf

import android.content.ContentResolver
import android.net.Uri
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ModelValidation
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a model's GGUF header through its SAF URI and folds the result back
 * into [LocalModel]. Runs on [Dispatchers.IO]; tensor data is never read.
 */
class GgufModelValidator(
    private val contentResolver: ContentResolver,
) {

    suspend fun validate(model: LocalModel): LocalModel = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(Uri.parse(model.uri))?.use { stream ->
                val metadata = GgufParser.parse(stream)
                if (metadata.architecture.isNullOrBlank()) {
                    return@withContext model.copy(
                        validation = ModelValidation.INVALID,
                        runtimeValidated = false,
                        validationMessage = "GGUF header has no general.architecture entry.",
                    )
                }
                if (metadata.isSplit) {
                    val part = (metadata.splitIndex ?: 0) + 1
                    return@withContext model.copy(
                        validation = ModelValidation.INVALID,
                        runtimeValidated = false,
                        validationMessage = "This is part $part of a ${metadata.splitCount}-part split GGUF. " +
                            "LocalMind can only load a single-file GGUF; merge the parts first.",
                    )
                }
                val trained = metadata.trainedContextLength
                val preferredContext = model.contextLength.takeIf { it > 0 }
                    ?: LocalModel.DEFAULT_CONTEXT_LENGTH
                val contextLength = if (trained != null && trained > 0) {
                    preferredContext.coerceAtMost(trained)
                } else {
                    preferredContext
                }
                model.copy(
                    validation = ModelValidation.HEADER_VALID,
                    validationMessage = null,
                    architecture = metadata.architecture,
                    parameterCount = metadata.parameterCount,
                    ggufQuantization = metadata.quantizationLabel(),
                    trainedContextLength = trained,
                    blockCount = metadata.blockCount,
                    embeddingLength = metadata.embeddingLength,
                    headCount = metadata.headCount,
                    headCountKv = metadata.headCountKv,
                    chatTemplate = metadata.chatTemplate,
                    contextLength = contextLength,
                )
            } ?: model.copy(
                validation = ModelValidation.MISSING,
                runtimeValidated = false,
                validationMessage = "The document provider returned no data. Re-import the file.",
            )
        } catch (error: GgufFormatException) {
            model.copy(
                validation = ModelValidation.INVALID,
                runtimeValidated = false,
                validationMessage = error.message ?: "The file is not a valid GGUF model.",
            )
        } catch (error: FileNotFoundException) {
            model.copy(
                validation = ModelValidation.MISSING,
                runtimeValidated = false,
                validationMessage = "The file is no longer available at its original location.",
            )
        } catch (error: SecurityException) {
            model.copy(
                validation = ModelValidation.MISSING,
                runtimeValidated = false,
                validationMessage = "Read permission was revoked. Re-import the file to restore access.",
            )
        } catch (error: IOException) {
            model.copy(
                validation = ModelValidation.INVALID,
                runtimeValidated = false,
                validationMessage = error.message ?: "Could not read the model file.",
            )
        }
    }
}
