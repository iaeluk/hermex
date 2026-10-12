package com.iaeluk.hermex.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.iaeluk.hermex.ui.SavedStatePolicy
import com.iaeluk.hermex.ui.setBoundedString
import com.iaeluk.hermex.core.runSuspendCatching
import com.iaeluk.hermex.core.model.FileResponse
import com.iaeluk.hermex.core.model.WorkspaceEntry
import com.iaeluk.hermex.core.model.WorkspaceRoot
import com.iaeluk.hermex.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val roots: List<WorkspaceRoot> = emptyList(),
    val searchText: String = "",
    val openFolders: Set<String> = emptySet(),
    val folderEntries: Map<String, List<WorkspaceEntry>> = emptyMap(),
    val folderErrors: Map<String, String> = emptyMap(),
    val loadingFolders: Set<String> = emptySet(),
    val preview: FileResponse? = null,
    val binaryPreview: BinaryPreview? = null,
    val isLoading: Boolean = true,
    val isPreviewLoading: Boolean = false,
    val error: String? = null,
)

data class BinaryPreview(
    val path: String,
    val bytes: ByteArray?,
    val isImage: Boolean,
    val mimeType: String,
    val sizeBytes: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryPreview) return false
        return path == other.path &&
            isImage == other.isImage &&
            mimeType == other.mimeType &&
            sizeBytes == other.sizeBytes &&
            when {
                bytes == null -> other.bytes == null
                other.bytes == null -> false
                else -> bytes.contentEquals(other.bytes)
            }
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + isImage.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (sizeBytes?.hashCode() ?: 0)
        return result
    }
}

class WorkspaceViewModel(
    private val sessionId: String,
    private val repository: WorkspaceRepository,
    private val savedStateHandle: SavedStateHandle? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(
        WorkspaceUiState(
            searchText = savedStateHandle?.get<String>(SAVED_SEARCH_TEXT)
                ?.let { SavedStatePolicy.boundedInput(it, SavedStatePolicy.MaximumSearchCharacters) }
                .orEmpty(),
        ),
    )
    val state: StateFlow<WorkspaceUiState> = _state
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var previewJob: Job? = null
    private var previewGeneration = 0L
    private var pendingRevealPath: String? = null

    init {
        refresh()
    }

    fun refresh() {
        val generation = ++refreshGeneration
        previewJob?.cancel()
        previewGeneration += 1
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, preview = null, binaryPreview = null) }
            val roots = runSuspendCatching { repository.workspaces() }.getOrNull()
            if (generation != refreshGeneration) return@launch
            if (roots != null) _state.update { it.copy(roots = roots) }
            revealPendingPath()
            val open = _state.value.openFolders.toList()
            coroutineScope {
                open.map { folder -> async { listFolder(folder, generation) } }.awaitAll()
            }
            if (generation != refreshGeneration) return@launch
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun toggleFolder(path: String?) {
        val folder = path?.takeIf { it.isNotBlank() } ?: return
        val isOpen = _state.value.openFolders.contains(folder)
        if (isOpen) {
            _state.update {
                it.copy(
                    openFolders = it.openFolders - folder,
                    folderEntries = it.folderEntries - folder,
                    folderErrors = it.folderErrors - folder,
                    loadingFolders = it.loadingFolders - folder,
                )
            }
            return
        }
        _state.update { it.copy(openFolders = it.openFolders + folder, folderErrors = it.folderErrors - folder) }
        if (_state.value.folderEntries[folder] == null) {
            viewModelScope.launch { listFolder(folder, refreshGeneration) }
        }
    }

    fun retryFolder(path: String?) {
        val folder = path?.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(folderErrors = it.folderErrors - folder) }
        viewModelScope.launch { listFolder(folder, refreshGeneration) }
    }

    fun open(entry: WorkspaceEntry) {
        val path = entry.path ?: entry.name ?: return
        val isDirectory = entry.type == "directory" || entry.type == "dir" || entry.type == "folder"
        if (isDirectory) {
            if (_state.value.folderErrors[path] != null) retryFolder(path) else toggleFolder(path)
        } else {
            preview(path, entry.size)
        }
    }

    fun openRoot(root: WorkspaceRoot) {
        toggleFolder(root.path)
    }

    fun openInitialPath(path: String?) {
        val file = path?.takeIf { it.isNotBlank() } ?: return
        pendingRevealPath = file
        preview(file)
        if (_state.value.roots.isNotEmpty()) {
            revealPendingPath()
            _state.value.openFolders.toList().forEach { folder ->
                if (_state.value.folderEntries[folder] == null && !_state.value.loadingFolders.contains(folder)) {
                    viewModelScope.launch { listFolder(folder, refreshGeneration) }
                }
            }
        }
    }

    private fun revealPendingPath() {
        val file = pendingRevealPath ?: return
        val roots = _state.value.roots.mapNotNull { it.path }.filter { it.isNotBlank() }
        val base = roots.firstOrNull { root -> file == root || file.startsWith(root.trimEnd('/') + "/") }
            ?: return
        pendingRevealPath = null
        val chain = mutableListOf<String>()
        var current = file.substringBeforeLast('/', missingDelimiterValue = "")
        while (current.isNotBlank() && current != base && current.startsWith(base)) {
            chain += current
            current = current.substringBeforeLast('/', missingDelimiterValue = "")
        }
        if (base.isNotBlank()) chain += base
        if (chain.isEmpty()) return
        _state.update { it.copy(openFolders = it.openFolders + chain) }
    }

    private suspend fun listFolder(folder: String, generation: Long) {
        _state.update { it.copy(loadingFolders = it.loadingFolders + folder) }
        runSuspendCatching { repository.list(sessionId, folder) }
            .onSuccess { list ->
                if (generation != refreshGeneration) return@onSuccess
                _state.update {
                    it.copy(
                        folderEntries = it.folderEntries + (folder to sortWorkspaceEntries(list.entries.orEmpty())),
                        folderErrors = it.folderErrors - folder,
                        loadingFolders = it.loadingFolders - folder,
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (generation != refreshGeneration) return@onFailure
                _state.update {
                    it.copy(
                        folderErrors = it.folderErrors + (folder to (error.message ?: "Could not list folder.")),
                        loadingFolders = it.loadingFolders - folder,
                    )
                }
            }
    }

    fun updateSearchText(value: String) {
        val boundedValue = SavedStatePolicy.boundedInput(value, SavedStatePolicy.MaximumSearchCharacters)
        savedStateHandle?.setBoundedString(SAVED_SEARCH_TEXT, boundedValue, SavedStatePolicy.MaximumSearchCharacters)
        _state.update { it.copy(searchText = boundedValue) }
    }

    fun reportError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun closePreview() {
        previewJob?.cancel()
        previewGeneration += 1
        _state.update { it.copy(preview = null, binaryPreview = null, isPreviewLoading = false) }
    }

    private fun preview(path: String, sizeBytes: Long? = null) {
        previewJob?.cancel()
        val generation = ++previewGeneration
        previewJob = viewModelScope.launch {
            _state.update { it.copy(isPreviewLoading = true, error = null, preview = null, binaryPreview = null) }
            if (WorkspaceFilePreviewPolicy.isKnownUnsupportedBinary(path)) {
                if (sizeBytes != null && WorkspaceFilePreviewPolicy.shouldOpenExternally(sizeBytes)) {
                    if (generation != previewGeneration) return@launch
                    _state.update {
                        it.copy(
                            binaryPreview = BinaryPreview(
                                path = path,
                                bytes = null,
                                isImage = false,
                                mimeType = WorkspaceFilePreviewPolicy.mimeType(path),
                                sizeBytes = sizeBytes,
                            ),
                            isPreviewLoading = false,
                        )
                    }
                    return@launch
                }
                loadBinaryPreview(path, previewGeneration, sizeBytes)
                return@launch
            }
            if (WorkspaceFilePreviewPolicy.shouldLoadRawPreview(path)) {
                loadBinaryPreview(path, previewGeneration, sizeBytes)
                return@launch
            }
            runSuspendCatching { repository.file(sessionId, path) }
                .onSuccess { preview ->
                    if (generation != previewGeneration) return@onSuccess
                    if (shouldRenderWorkspaceTextPreview(preview.content)) {
                        _state.update { it.copy(preview = preview, isPreviewLoading = false) }
                    } else {
                        _state.update {
                            it.copy(
                                binaryPreview = BinaryPreview(
                                    path = path,
                                    bytes = null,
                                    isImage = false,
                                    mimeType = WorkspaceFilePreviewPolicy.mimeType(path),
                                ),
                                isPreviewLoading = false,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (generation != previewGeneration) return@onFailure
                    if (WorkspaceFilePreviewPolicy.isRasterImage(path)) {
                        loadBinaryPreview(path, generation)
                    } else {
                        _state.update { it.copy(isPreviewLoading = false, error = error.message ?: "Could not open file.") }
                    }
                }
        }
    }

    private suspend fun loadBinaryPreview(path: String, generation: Long = previewGeneration, sizeBytes: Long? = null) {
        runSuspendCatching { repository.rawFile(sessionId, path) }
            .onSuccess { bytes ->
                if (generation != previewGeneration) return@onSuccess
                _state.update {
                    it.copy(
                        binaryPreview = BinaryPreview(
                            path = path,
                            bytes = bytes,
                            isImage = WorkspaceFilePreviewPolicy.isRasterImage(path),
                            mimeType = WorkspaceFilePreviewPolicy.mimeType(path),
                            sizeBytes = sizeBytes ?: bytes.size.toLong(),
                        ),
                        isPreviewLoading = false,
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (generation == previewGeneration) {
                    _state.update { it.copy(isPreviewLoading = false, error = error.message ?: "Could not open file.") }
                }
            }
    }

    suspend fun loadRawBytes(path: String): ByteArray = repository.rawFile(sessionId, path)

    private companion object {
        const val SAVED_SEARCH_TEXT = "workspace_search_text"
    }
}

internal fun sortWorkspaceEntries(entries: List<WorkspaceEntry>): List<WorkspaceEntry> {
    val comparator = Comparator<WorkspaceEntry> { left, right ->
        val dirOrder = compareValues(
            if (left.type == "directory" || left.type == "dir" || left.type == "folder") 0 else 1,
            if (right.type == "directory" || right.type == "dir" || right.type == "folder") 0 else 1,
        )
        if (dirOrder != 0) {
            dirOrder
        } else {
            WorkspaceTreePolicy.compareNatural(left.name ?: left.path.orEmpty(), right.name ?: right.path.orEmpty())
        }
    }
    return entries.sortedWith(comparator)
}

internal fun shouldRenderWorkspaceTextPreview(content: String?): Boolean {
    val text = content ?: return false
    if ('\u0000' in text) return false
    if (text.isEmpty()) return true
    val suspicious = text.count { character ->
        (character.code < 32 && character != '\n' && character != '\r' && character != '\t') ||
            character == '\uFFFD'
    }
    return suspicious <= maxOf(8, text.length / 100)
}
