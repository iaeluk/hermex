package com.iaeluk.hermex.data.repository

import com.iaeluk.hermex.core.model.DirectoryListResponse
import com.iaeluk.hermex.core.model.FileResponse
import com.iaeluk.hermex.core.model.WorkspaceRoot
import com.iaeluk.hermex.core.model.WorkspaceMutationResponse
import com.iaeluk.hermex.core.network.HermesApiClient

class WorkspaceRepository(private val client: HermesApiClient) {
    suspend fun workspaces(): List<WorkspaceRoot> {
        val response = client.workspaces()
        return response.workspaces ?: response.roots.orEmpty()
    }

    suspend fun suggestions(prefix: String): List<String> = client.workspaceSuggestions(prefix).suggestions.orEmpty()
    suspend fun add(path: String, name: String?, create: Boolean?): WorkspaceMutationResponse =
        client.addWorkspace(path, name, create)
    suspend fun remove(path: String): WorkspaceMutationResponse = client.removeWorkspace(path)
    suspend fun rename(path: String, name: String): WorkspaceMutationResponse = client.renameWorkspace(path, name)
    suspend fun reorder(paths: List<String>): WorkspaceMutationResponse = client.reorderWorkspaces(paths)
    suspend fun list(sessionId: String, path: String?): DirectoryListResponse = client.directoryList(sessionId, path)
    suspend fun file(sessionId: String, path: String): FileResponse = client.file(sessionId, path)
    suspend fun rawFile(sessionId: String, path: String): ByteArray = client.rawFile(sessionId, path)
}
