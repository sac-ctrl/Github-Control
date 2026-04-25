package com.githubcontrol.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.githubcontrol.data.api.DeleteFileRequest
import com.githubcontrol.data.api.GhBranch
import com.githubcontrol.data.api.GhContent
import com.githubcontrol.data.api.PutFileRequest
import com.githubcontrol.data.git.JGitService
import com.githubcontrol.data.repository.GitHubRepository
import com.githubcontrol.utils.fromBase64
import com.githubcontrol.utils.toBase64
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilesState(
    val loading: Boolean = false,
    val owner: String = "",
    val name: String = "",
    val path: String = "",
    val ref: String = "",
    val items: List<GhContent> = emptyList(),
    val branches: List<GhBranch> = emptyList(),
    val selection: Set<String> = emptySet(),
    val multiSelect: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repo: GitHubRepository,
    private val jgit: JGitService
) : ViewModel() {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state

    fun load(owner: String, name: String, path: String, ref: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, owner = owner, name = name, path = path, ref = ref, error = null)
            try {
                val items = repo.contents(owner, name, path, ref.ifBlank { null })
                    .sortedWith(compareByDescending<GhContent> { it.type == "dir" }.thenBy { it.name.lowercase() })
                val branches = if (_state.value.branches.isEmpty()) runCatching { repo.branches(owner, name) }.getOrDefault(emptyList()) else _state.value.branches
                _state.value = _state.value.copy(loading = false, items = items, branches = branches)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = t.message)
            }
        }
    }

    fun toggleMultiSelect() { _state.value = _state.value.copy(multiSelect = !_state.value.multiSelect, selection = emptySet()) }
    fun toggleSelect(path: String) {
        val cur = _state.value.selection
        _state.value = _state.value.copy(selection = if (cur.contains(path)) cur - path else cur + path)
    }
    fun clearSelection() { _state.value = _state.value.copy(selection = emptySet()) }

    fun setRef(branch: String) {
        val s = _state.value
        load(s.owner, s.name, s.path, branch)
    }

    fun deletePath(path: String, sha: String, message: String, onDone: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            try {
                repo.api.deleteFile(s.owner, s.name, path, DeleteFileRequest(message, sha, s.ref.ifBlank { null }))
                load(s.owner, s.name, s.path, s.ref); onDone()
            } catch (t: Throwable) { _state.value = _state.value.copy(error = t.message) }
        }
    }

    fun renamePath(oldPath: String, sha: String, newPath: String, message: String, contentBytes: ByteArray, onDone: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            try {
                repo.api.putFile(s.owner, s.name, newPath, PutFileRequest(message, contentBytes.toBase64(), null, s.ref.ifBlank { null }))
                repo.api.deleteFile(s.owner, s.name, oldPath, DeleteFileRequest(message, sha, s.ref.ifBlank { null }))
                load(s.owner, s.name, s.path, s.ref); onDone()
            } catch (t: Throwable) { _state.value = _state.value.copy(error = t.message) }
        }
    }

    /**
     * Convenience overload: fetches the file's bytes from GitHub itself, then commits a
     * new path + deletes the old one. Used by the swipe-rename UI in [FilesScreen].
     */
    fun renamePath(item: GhContent, newPath: String, message: String, onDone: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            try {
                val full = repo.fileContent(s.owner, s.name, item.path, s.ref.ifBlank { null })
                val bytes = full.content?.fromBase64() ?: ByteArray(0)
                repo.api.putFile(s.owner, s.name, newPath, PutFileRequest(message, bytes.toBase64(), null, s.ref.ifBlank { null }))
                repo.api.deleteFile(s.owner, s.name, item.path, DeleteFileRequest(message, item.sha, s.ref.ifBlank { null }))
                load(s.owner, s.name, s.path, s.ref); onDone()
            } catch (t: Throwable) { _state.value = _state.value.copy(error = t.message) }
        }
    }

    fun deleteSelected(message: String, onDone: () -> Unit) {
        val s = _state.value
        val selectedItems = s.items.filter { s.selection.contains(it.path) }
        if (selectedItems.isEmpty()) { onDone(); return }
        viewModelScope.launch {
            try {
                // Expand any folders so directory selection wipes their full subtree
                val paths = expandPaths(s.owner, s.name, s.ref, selectedItems)
                if (paths.size == 1) {
                    // Single file: use API
                    val item = selectedItems.first()
                    repo.api.deleteFile(s.owner, s.name, item.path, DeleteFileRequest(message, item.sha, s.ref.ifBlank { null }))
                } else {
                    // Multiple files/folders: use JGit to avoid API 422
                    val repoUrl = "https://github.com/${s.owner}/${s.name}.git"
                    val localPath = jgit.localPath(s.owner, s.name)

                    // Clone if not exists
                    if (!localPath.exists()) {
                        jgit.clone(s.owner, s.name, repoUrl, shallow = true)
                    }

                    // Checkout branch
                    val branchName = s.ref.ifBlank { "main" }
                    jgit.checkout(s.owner, s.name, branchName, createIfMissing = true)

                    // Pull latest
                    runCatching { jgit.pull(s.owner, s.name) }

                    // Delete files locally
                    for (path in paths) {
                        val file = java.io.File(localPath, path)
                        if (file.exists()) file.delete()
                    }

                    // Stage deletions
                    jgit.stage(s.owner, s.name, paths)

                    // Commit
                    jgit.commit(s.owner, s.name, message, null, null)

                    // Push
                    jgit.push(s.owner, s.name)
                }
                clearSelection()
                load(s.owner, s.name, s.path, s.ref); onDone()
            } catch (t: Throwable) {
                // Clean up on error too
                runCatching { jgit.cleanup(s.owner, s.name) }
                _state.value = _state.value.copy(error = t.message)
            } finally {
                // Always clean up local repo to free storage
                runCatching { jgit.cleanup(s.owner, s.name) }
            }
        }
    }

    /**
     * Delete a folder and everything inside it as one atomic commit.
     * Uses JGit to avoid API 422 errors when deleting many files.
     */
    fun deleteFolder(folderPath: String, message: String, onDone: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            try {
                val repoUrl = "https://github.com/${s.owner}/${s.name}.git"
                val localPath = jgit.localPath(s.owner, s.name)

                // Clone if not exists
                if (!localPath.exists()) {
                    jgit.clone(s.owner, s.name, repoUrl, shallow = true)
                }

                // Checkout branch
                val branchName = s.ref.ifBlank { "main" }
                jgit.checkout(s.owner, s.name, branchName, createIfMissing = true)

                // Pull latest
                runCatching { jgit.pull(s.owner, s.name) }

                // List all files under the folder
                val branchInfo = repo.api.branch(s.owner, s.name, branchName)
                val parent = repo.api.commitDetail(s.owner, s.name, branchInfo.commit.sha)
                val ft = repo.api.gitTree(s.owner, s.name, parent.commit.tree.sha, recursive = 1)
                val prefix = folderPath.trim('/') + "/"
                val toDelete = ft.tree
                    .filter { it.type == "blob" && it.path.startsWith(prefix) }
                    .map { it.path }

                if (toDelete.isEmpty()) {
                    _state.value = _state.value.copy(error = "Folder is empty or already deleted")
                    onDone(); return@launch
                }

                // Delete files locally
                for (path in toDelete) {
                    val file = java.io.File(localPath, path)
                    if (file.exists()) file.delete()
                }

                // Stage deletions
                jgit.stage(s.owner, s.name, toDelete)

                // Commit
                jgit.commit(s.owner, s.name, message, null, null)

                // Push
                jgit.push(s.owner, s.name)

                load(s.owner, s.name, s.path, s.ref); onDone()
            } catch (t: Throwable) {
                // Clean up on error too
                runCatching { jgit.cleanup(s.owner, s.name) }
                _state.value = _state.value.copy(error = t.message)
            } finally {
                // Always clean up local repo to free storage
                runCatching { jgit.cleanup(s.owner, s.name) }
            }
        }
    }

    /**
     * Resolve a mixed selection of files + directories into a flat list of
     * blob paths (so a single tree commit can delete everything atomically).
     */
    private suspend fun expandPaths(
        owner: String, name: String, ref: String, items: List<GhContent>
    ): List<String> {
        val files = items.filter { it.type == "file" }.map { it.path }
        val dirs = items.filter { it.type == "dir" }
        if (dirs.isEmpty()) return files
        val branchInfo = repo.api.branch(owner, name, ref.ifBlank { "HEAD" })
        val parent = repo.api.commitDetail(owner, name, branchInfo.commit.sha)
        val ft = repo.api.gitTree(owner, name, parent.commit.tree.sha, recursive = 1)
        val dirPaths = dirs.map { it.path.trim('/') + "/" }
        val nested = ft.tree.filter { it.type == "blob" && dirPaths.any { p -> it.path.startsWith(p) } }
            .map { it.path }
        return (files + nested).distinct()
    }
}
