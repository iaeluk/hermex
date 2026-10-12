package com.iaeluk.hermex.ui.workspace

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.iaeluk.hermex.core.model.WorkspaceEntry
import com.iaeluk.hermex.core.model.WorkspaceRoot
import com.iaeluk.hermex.data.repository.WorkspaceRepository
import com.iaeluk.hermex.ui.chat.MarkdownText
import com.iaeluk.hermex.ui.createExportDirectory
import com.iaeluk.hermex.ui.theme.HermexCardShape
import com.iaeluk.hermex.ui.theme.HermexIconButton
import com.iaeluk.hermex.ui.theme.HermexPillButton
import com.iaeluk.hermex.ui.theme.HermexSurfaceLevel
import com.iaeluk.hermex.ui.theme.hermexGlass
import com.iaeluk.hermex.ui.theme.hermexHairline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.iaeluk.hermex.ui.localization.localizedString

@Composable
fun WorkspaceRoute(
    sessionId: String,
    viewModelKey: String = "workspace:$sessionId",
    repository: WorkspaceRepository,
    onBack: () -> Unit,
    initialPath: String? = null,
) {
    val viewModel: WorkspaceViewModel = viewModel(key = viewModelKey, factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return WorkspaceViewModel(sessionId, repository) as T
        }

        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return WorkspaceViewModel(sessionId, repository, extras.createSavedStateHandle()) as T
        }
    })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    var pendingSavePath by rememberSaveable { mutableStateOf<String?>(null) }
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val pendingFile = pendingSavePath?.let(::File)
        pendingSavePath = null
        if (destination != null && pendingFile?.isFile == true) {
            shareScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(destination)?.use { output ->
                            pendingFile.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("Could not open the selected destination.")
                    }
                }
                runCatching { pendingFile.delete() }
                Toast.makeText(
                    context,
                    result.fold(onSuccess = { "File saved." }, onFailure = { it.message ?: "Could not save file." }),
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            pendingFile?.let { runCatching { it.delete() } }
        }
    }
    val savePreviewAs: (String?, String?, BinaryPreview?) -> Unit = { title, content, binaryPreview ->
        val bytes = content?.toByteArray(Charsets.UTF_8) ?: binaryPreview?.bytes
        if (bytes == null) {
            viewModel.reportError("No file content is available to save.")
        } else {
            val sourcePath = title ?: binaryPreview?.path
            val fileName = WorkspaceFilePreviewPolicy.displayName(sourcePath)
            shareScope.launch {
                val previousPendingPath = pendingSavePath
                val pendingFile = withContext(Dispatchers.IO) {
                    runCatching {
                        previousPendingPath?.let(::File)?.takeIf { it.isFile }?.delete()
                        File.createTempFile("workspace-save-", ".tmp", context.cacheDir).also { it.writeBytes(bytes) }
                    }
                }.getOrElse { error ->
                    viewModel.reportError(error.message ?: "Could not prepare the file for saving.")
                    return@launch
                }
                pendingSavePath = pendingFile.absolutePath
                saveFileLauncher.launch(fileName)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(initialPath) {
        if (!initialPath.isNullOrBlank()) viewModel.openInitialPath(initialPath)
    }
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            WorkspaceHeader(
                onBack = onBack,
                onRefresh = viewModel::refresh,
                isLoading = state.isLoading,
            )
            WorkspaceSearchBar(
                searchText = state.searchText,
                onSearchTextChange = viewModel::updateSearchText,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
            state.error?.let {
                Text(localizedString(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            val treeRows = remember(state.roots, state.openFolders, state.folderEntries, state.searchText) {
                WorkspaceTreePolicy.flattenTree(state.roots, state.openFolders, state.folderEntries, state.searchText)
            }
            when {
                state.isLoading && state.roots.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
                state.preview != null || state.isPreviewLoading -> FilePreview(
                    isLoading = state.isPreviewLoading,
                    title = state.preview?.path,
                    content = state.preview?.content,
                    textSizeBytes = state.preview?.size,
                    textLineCount = WorkspaceFilePreviewPolicy.lineCount(state.preview?.content),
                    binaryPreview = null,
                    onClose = viewModel::closePreview,
                    onSaveAs = { savePreviewAs(state.preview?.path, state.preview?.content, null) },
                    onShare = {
                        shareScope.launch {
                            shareWorkspacePreview(
                                context = context,
                                title = state.preview?.path,
                                content = state.preview?.content,
                                binaryPreview = null,
                            ).onFailure { error ->
                                viewModel.reportError(error.message ?: "Could not share file.")
                            }
                        }
                    },
                )
                state.binaryPreview != null -> FilePreview(
                    isLoading = false,
                    title = state.binaryPreview?.path,
                    content = null,
                    textSizeBytes = null,
                    textLineCount = null,
                    binaryPreview = state.binaryPreview,
                    onClose = viewModel::closePreview,
                    onSaveAs = { savePreviewAs(state.binaryPreview?.path, null, state.binaryPreview) },
                    onShare = {
                        shareScope.launch {
                            shareWorkspacePreview(
                                context = context,
                                title = state.binaryPreview?.path,
                                content = null,
                                binaryPreview = state.binaryPreview,
                            ).onFailure { error ->
                                viewModel.reportError(error.message ?: "Could not share file.")
                            }
                        }
                    },
                )
                treeRows.isEmpty() -> EmptyWorkspace(query = state.searchText)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(treeRows.size) { index ->
                        val row = treeRows[index]
                        val rowPath = row.entry.path ?: row.entry.name.orEmpty()
                        val isOpen = state.openFolders.contains(rowPath)
                        val isLoadingFolder = state.loadingFolders.contains(rowPath)
                        val folderError = state.folderErrors[rowPath]
                        WorkspaceTreeRowItem(
                            row = row,
                            isOpen = isOpen,
                            isLoading = isLoadingFolder,
                            error = folderError,
                            onClick = { viewModel.open(row.entry) },
                            onRetry = { viewModel.retryFolder(rowPath) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(bottom = 18.dp),
    ) {
        HermexIconButton(localizedString("Back"), "‹", onBack, modifier = Modifier.align(Alignment.CenterStart))
        Text(
            localizedString("Files"),
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        HermexPillButton(
            label = if (isLoading) "…" else "⟳",
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.CenterEnd),
            enabled = !isLoading,
        )
    }
}

@Composable
private fun WorkspaceLocationHeader(
    currentPath: String?,
    roots: List<WorkspaceRoot>,
    onRoot: () -> Unit,
    onUp: () -> Unit,
    onOpenPath: (String?) -> Unit,
    onOpenRoot: (WorkspaceRoot) -> Unit,
    canGoUp: Boolean,
) {
    val breadcrumbs = remember(currentPath) { currentPath.workspaceBreadcrumbs() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(localizedString("Location"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            Text(
                currentPath ?: "Root",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HermexPillButton("⌂  ${localizedString("Root")}", onRoot, enabled = currentPath != null)
            HermexPillButton("↑  ${localizedString("Up")}", onUp, enabled = canGoUp)
            roots.forEach { root ->
                HermexPillButton(root.name ?: root.path ?: "Root", onClick = { onOpenRoot(root) })
            }
        }
        if (breadcrumbs.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                breadcrumbs.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        Text(">", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    HermexPillButton(
                        label = crumb.title,
                        onClick = { onOpenPath(crumb.path) },
                        enabled = crumb.path != currentPath,
                    )
                }
            }
        }
    }
}

internal data class WorkspaceBreadcrumb(
    val title: String,
    val path: String?,
)

internal fun String?.workspaceBreadcrumbs(): List<WorkspaceBreadcrumb> {
    val source = this.orEmpty()
    val raw = source.trim().trim('/', '\\')
    if (raw.isBlank()) return listOf(WorkspaceBreadcrumb("Root", null))
    val isUnixAbsolute = source.trimStart().startsWith('/')
    val separator = if (source.contains('\\') && !source.contains('/')) "\\" else "/"
    val parts = raw.split('/', '\\').filter { it.isNotBlank() }
    if (parts.isEmpty()) return listOf(WorkspaceBreadcrumb("Root", null))
    val crumbs = mutableListOf(WorkspaceBreadcrumb("Root", null))
    parts.forEachIndexed { index, part ->
        crumbs += WorkspaceBreadcrumb(
            title = part,
            path = parts
                .take(index + 1)
                .joinToString(separator)
                .let { joined -> if (isUnixAbsolute) "/$joined" else joined },
        )
    }
    return crumbs
}

@Composable
private fun WorkspaceSearchBar(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = searchText,
        onValueChange = onSearchTextChange,
        leadingIcon = {
            Image(
                painter = painterResource(com.iaeluk.hermex.R.drawable.ic_hermex_search),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.secondary),
            )
        },
        label = { Text(localizedString("Search files")) },
        placeholder = { Text(localizedString("Search files")) },
        singleLine = true,
        shape = HermexCardShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

@Composable
private fun WorkspaceTreeRowItem(
    row: WorkspaceTreeRow,
    isOpen: Boolean,
    isLoading: Boolean,
    error: String?,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val isDirectory = row.entry.type == "directory" || row.entry.type == "dir" || row.entry.type == "folder"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 16).dp),
    ) {
        WorkspaceEntryRow(entry = row.entry, onClick = onClick, isOpen = isOpen, isLoading = isLoading)
        if (error != null && isDirectory) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    localizedString("Retry"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceEntryRow(entry: WorkspaceEntry, onClick: () -> Unit, isOpen: Boolean = false, isLoading: Boolean = false) {
    val isDirectory = entry.type == "directory" || entry.type == "dir" || entry.type == "folder"
    val entryPath = entry.path ?: entry.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .hermexHairline(HermexCardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), HermexCardShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                WorkspaceFilePreviewPolicy.badgeLabel(entryPath, isDirectory),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.name ?: entry.path ?: "Untitled",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    entry.path,
                    WorkspaceFilePreviewPolicy.kindLabel(entryPath, isDirectory),
                    entry.size?.let(::fileSizeText),
                ).joinToString(" - ").ifBlank { "No metadata" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        if (isLoading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else if (isDirectory) {
            Text(
                if (isOpen) "▾" else ">",
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyWorkspace(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 76.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(com.iaeluk.hermex.R.drawable.ic_lucide_folder),
            contentDescription = null,
            modifier = Modifier.size(68.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.secondary),
        )
        Spacer(Modifier.height(18.dp))
        Text(localizedString(if (query.isBlank()) "No Files" else "No Matches"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (query.isBlank()) "" else localizedString("Try a different file name or path."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun FilePreview(
    isLoading: Boolean,
    title: String?,
    content: String?,
    textSizeBytes: Long?,
    textLineCount: Int?,
    binaryPreview: BinaryPreview?,
    onClose: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSavingImage by remember(binaryPreview?.path) { mutableStateOf(false) }
    var saveImageMessage by remember(binaryPreview?.path) { mutableStateOf<String?>(null) }
    val displayPath = title ?: binaryPreview?.path ?: "Preview"
    val copiedMessage = localizedString("Copied")
    val metadata = remember(content, textSizeBytes, textLineCount, binaryPreview) {
        previewMetadata(
            content = content,
            textSizeBytes = textSizeBytes,
            textLineCount = textLineCount,
            binaryPreview = binaryPreview,
        )
    }
    val saveImage: () -> Unit = {
        val image = binaryPreview
        if (image?.isImage != true) {
            saveImageMessage = "This file is not an image."
        } else {
            scope.launch {
                isSavingImage = true
                saveImageMessage = withContext(Dispatchers.IO) {
                    saveWorkspaceImageToGallery(context, image)
                }
                isSavingImage = false
            }
        }
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveImage() else saveImageMessage = "Photos permission is required to save this image."
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title ?: "Preview",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HermexPillButton(
                    localizedString("Save As"),
                    onSaveAs,
                    enabled = !isLoading && (content != null || binaryPreview?.bytes != null),
                    filled = binaryPreview?.isImage != true,
                )
                if (binaryPreview?.isImage == true) {
                    HermexPillButton(
                        if (isSavingImage) "Saving" else "Save",
                        onClick = {
                            if (
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                saveImage()
                            }
                        },
                        enabled = !isLoading && !isSavingImage,
                        filled = true,
                    )
                }
                HermexPillButton(
                    localizedString("Share"),
                    onShare,
                    enabled = !isLoading && (content != null || binaryPreview?.bytes != null),
                )
                if (content != null) {
                    HermexPillButton(
                        localizedString("Copy"),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(displayPath, content))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        },
                        enabled = !isLoading,
                    )
                }
                HermexPillButton(localizedString("Close"), onClose)
            }
        }
        saveImageMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        } else if (binaryPreview?.isImage == true && binaryPreview.bytes != null) {
            val previewBytes = binaryPreview.bytes
            val bitmap by produceState<android.graphics.Bitmap?>(null, previewBytes) {
                value = withContext(Dispatchers.IO) { decodeWorkspacePreviewBitmap(previewBytes) }
            }
            val decodedBitmap = bitmap
            if (decodedBitmap != null) {
                var imageScale by remember(previewBytes) { mutableStateOf(1f) }
                var imageOffset by remember(previewBytes) { mutableStateOf(Offset.Zero) }
                val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
                    imageScale = (imageScale * zoomChange).coerceIn(1f, 5f)
                    imageOffset = if (imageScale > 1f) imageOffset + offsetChange else Offset.Zero
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    FilePreviewHeader(displayPath, metadata)
                    if (imageScale > 1f) {
                        HermexPillButton(
                            label = localizedString("Reset zoom"),
                            onClick = {
                                imageScale = 1f
                                imageOffset = Offset.Zero
                            },
                        )
                    }
                    Image(
                        bitmap = decodedBitmap.asImageBitmap(),
                        contentDescription = title ?: localizedString("Image"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer(
                                scaleX = imageScale,
                                scaleY = imageScale,
                                translationX = imageOffset.x,
                                translationY = imageOffset.y,
                            )
                            .transformable(transformState),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                FileUnavailablePreview(
                    title = "Could Not Preview Image",
                    message = "Could not decode this image.",
                    path = displayPath,
                    metadata = metadata,
                )
            }
        } else if (binaryPreview != null) {
            val binary = binaryPreview
            var openMessage by remember(binary.path) { mutableStateOf<String?>(null) }
            val knownSize = binary.sizeBytes ?: binary.bytes?.size?.toLong()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FileUnavailablePreview(
                    title = "No Preview",
                    message = "Preview is not available for this file type.",
                    path = displayPath,
                    metadata = metadata,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HermexPillButton(
                        label = localizedString("Open"),
                        onClick = {
                            val data = binary.bytes
                            when {
                                knownSize != null && !WorkspaceFilePreviewPolicy.canOpenExternally(knownSize) ->
                                    openMessage = "File is larger than 25 MB."
                                data == null -> openMessage = "File content is not loaded."
                                else -> scope.launch {
                                    openMessage = withContext(Dispatchers.IO) {
                                        openWorkspaceFileExternally(context, displayPath, data, binary.mimeType)
                                    }
                                }
                            }
                        },
                        enabled = !isLoading && (binary.bytes != null || knownSize != null),
                        filled = true,
                    )
                }
                openMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hermexGlass(shape = HermexCardShape, castsShadow = false)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilePreviewHeader(displayPath, metadata)
                if (content != null && WorkspaceFilePreviewPolicy.isMarkdown(displayPath)) {
                    MarkdownText(
                        markdown = content,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (content != null && WorkspaceFilePreviewPolicy.codeLanguage(displayPath) != null) {
                    MarkdownText(
                        markdown = WorkspaceFilePreviewPolicy.fencedSourceBlock(
                            content,
                            WorkspaceFilePreviewPolicy.codeLanguage(displayPath),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = content ?: "Preview is not available for this file.",
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePreviewHeader(
    path: String,
    metadata: String?,
) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontFamily = FontFamily.Monospace,
            )
            metadata?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun FileUnavailablePreview(
    title: String,
    message: String,
    path: String,
    metadata: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hermexGlass(shape = HermexCardShape, castsShadow = false)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(localizedString(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(localizedString(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        FilePreviewHeader(path = path, metadata = metadata)
    }
}

private fun previewMetadata(
    content: String?,
    textSizeBytes: Long?,
    textLineCount: Int?,
    binaryPreview: BinaryPreview?,
): String? {
    val parts = mutableListOf<String>()
    val byteCount = textSizeBytes ?: binaryPreview?.bytes?.size?.toLong() ?: content?.toByteArray(Charsets.UTF_8)?.size?.toLong()
    byteCount?.let { parts += fileSizeText(it) }
    textLineCount?.let { parts += "$it lines" }
    binaryPreview?.mimeType?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" - ").takeIf { it.isNotBlank() }
}

internal fun fileSizeText(bytes: Long): String =
    when {
        bytes < 1_000 -> "$bytes bytes"
        bytes < 1_000_000 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_000.0)
        bytes < 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    }

private fun saveWorkspaceImageToGallery(
    context: Context,
    preview: BinaryPreview,
): String {
    val resolver = context.contentResolver
    val fileName = preview.galleryFileName()
    val bytes = preview.bytes ?: return "This image is not loaded."
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, preview.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Hermex")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val uri = runCatching {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return "Could not save image."

    return try {
        resolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
            ?: error("Could not open gallery item.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        "Image saved to gallery."
    } catch (error: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        "Could not save image: ${error.localizedMessage ?: "Unknown error."}"
    }
}

private fun BinaryPreview.galleryFileName(): String {
    val rawName = path
        .trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "hermex-image" }
    val safeName = rawName
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('.', '_', '-')
        .take(96)
        .ifBlank { "hermex-image" }
    if (safeName.substringAfterLast('.', missingDelimiterValue = "").isNotBlank()) return safeName
    val extension = mimeType.substringAfter('/', missingDelimiterValue = "png")
        .substringBefore(';')
        .lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
        ?: "png"
    return "$safeName.$extension"
}

private fun decodeWorkspacePreviewBitmap(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION ||
        bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION ||
        (bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight.toLong() / sampleSize) > MAX_PREVIEW_PIXELS
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

private const val MAX_PREVIEW_DIMENSION = 4_096
private const val MAX_PREVIEW_PIXELS = 8_000_000L

private suspend fun shareWorkspacePreview(
    context: Context,
    title: String?,
    content: String?,
    binaryPreview: BinaryPreview?,
): Result<Unit> = runCatching {
    val bytes = content?.toByteArray(Charsets.UTF_8) ?: binaryPreview?.bytes
        ?: error("No file content is available to share.")
    val sourcePath = title ?: binaryPreview?.path ?: "workspace-file"
    val fileName = WorkspaceFilePreviewPolicy.displayName(sourcePath)
        .ifBlank { "workspace-file" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(96)
        .takeIf { it != "." && it != ".." }
        ?: "workspace-file"
    val mimeType = when {
        content != null -> WorkspaceFilePreviewPolicy.mimeType(sourcePath, isText = true)
        binaryPreview != null -> binaryPreview.mimeType
        else -> WorkspaceFilePreviewPolicy.mimeType(sourcePath)
    }
    val uri = withContext(Dispatchers.IO) {
        val exportDir = context.createExportDirectory("workspace")
        val file = File(exportDir, fileName)
        file.writeBytes(bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share File"))
}

private fun openWorkspaceFileExternally(
    context: Context,
    sourcePath: String,
    bytes: ByteArray,
    mimeType: String,
): String {
    return try {
        val fileName = WorkspaceFilePreviewPolicy.displayName(sourcePath)
            .ifBlank { "workspace-file" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
            .takeIf { it != "." && it != ".." }
            ?: "workspace-file"
        val exportDir = context.createExportDirectory("workspace")
        val file = File(exportDir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        "Opening in another app."
    } catch (error: android.content.ActivityNotFoundException) {
        "No app can open this file."
    } catch (error: Throwable) {
        "Could not open file: ${error.localizedMessage ?: "Unknown error."}"
    }
}
