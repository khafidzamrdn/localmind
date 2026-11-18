package com.localmind.app.feature.models

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.app.core.domain.model.AppSettings
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ModelLoadState
import com.localmind.app.core.domain.model.detectFormat
import com.localmind.app.core.domain.model.detectQuantization
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelsViewModel(
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    val models: StateFlow<List<LocalModel>> = modelRepository.observeModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun importModels(uris: List<Uri>) {
        if (uris.isEmpty() || _isImporting.value) return
        viewModelScope.launch {
            _isImporting.value = true
            var added = 0
            var duplicates = 0
            var unsupported = 0
            var failed = 0

            uris.forEach { uri ->
                try {
                    withContext(Dispatchers.IO) {
                        val existing = modelRepository.findByUri(uri.toString())
                        if (existing != null) {
                            duplicates++
                            return@withContext
                        }
                        persistReadPermission(uri)
                        val metadata = readMetadata(uri)
                        val displayName = metadata?.first ?: uri.lastPathSegment ?: "model.gguf"
                        val size = metadata?.second ?: 0L
                        val format = detectFormat(displayName)
                        if (format != com.localmind.app.core.domain.model.ModelFormat.GGUF) {
                            unsupported++
                        }
                        modelRepository.addModel(
                            LocalModel(
                                id = UUID.randomUUID().toString(),
                                displayName = displayName,
                                uri = uri.toString(),
                                sizeBytes = size,
                                importedAt = System.currentTimeMillis(),
                                format = format,
                                quantization = detectQuantization(displayName),
                                loadState = ModelLoadState.NOT_LOADED,
                            ),
                        )
                        added++
                    }
                } catch (error: Exception) {
                    failed++
                }
            }

            _message.value = buildString {
                append("$added model${if (added == 1) "" else "s"} imported")
                if (duplicates > 0) append(" · $duplicates already in library")
                if (unsupported > 0) append(" · $unsupported not GGUF, marked unsupported")
                if (failed > 0) append(" · $failed could not be read")
            }
            _isImporting.value = false
        }
    }

    fun removeModel(modelId: String) {
        viewModelScope.launch {
            modelRepository.removeModel(modelId)
            if (settings.value.activeModelId == modelId) {
                settingsRepository.setActiveModel(null)
            }
            _message.value = "Removed from library. The file on disk was not deleted."
        }
    }

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            val model = modelRepository.getModel(modelId) ?: return@launch
            if (!model.isUsable) {
                _message.value = "This file is not a supported GGUF model."
                return@launch
            }
            settingsRepository.setActiveModel(modelId)
            _message.value = "${model.displayName} is now the selected model."
        }
    }

    fun clearDefaultModel() {
        viewModelScope.launch { settingsRepository.setActiveModel(null) }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun readMetadata(uri: Uri): Pair<String, Long>? {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                if (name != null) return name to size
            }
        }
        return null
    }
}
