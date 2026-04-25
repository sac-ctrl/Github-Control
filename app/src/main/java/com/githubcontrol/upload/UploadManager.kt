package com.githubcontrol.upload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.githubcontrol.data.api.PutFileRequest
import com.githubcontrol.data.git.JGitService
import com.githubcontrol.data.repository.GitHubRepository
import com.githubcontrol.notifications.Notifier
import com.githubcontrol.utils.GitignoreMatcher
import com.githubcontrol.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import android.util.Base64
import android.util.Base64OutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class ConflictMode { OVERWRITE, SKIP, RENAME, REPLACE_FOLDER }
enum class UploadFileState { PENDING, UPLOADING, DONE, SKIPPED, FAILED }

const val LARGE_FILE_THRESHOLD = 20L * 1024 * 1024 // 20MB

data class UploadFile(
    val id: String,
    val displayName: String,
    val targetPath: String,
    val sizeBytes: Long,
    var state: UploadFileState = UploadFileState.PENDING,
    var error: String? = null,
    var bytesDone: Long = 0,
)

data class UploadJob(
    val owner: String,
    val repo: String,
    val branch: String,
    val targetFolder: String,
    val message: String,
    val conflictMode: ConflictMode,
    val authorName: String?,
    val authorEmail: String?,
    val files: List<UploadFile>,
    val totalBytes: Long,
    val dryRun: Boolean = false,
)

data class UploadProgress(
    val job: UploadJob? = null,
    val files: List<UploadFile> = emptyList(),
    val currentFile: String = "",
    val uploaded: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesPerSec: Double = 0.0,
    val etaSeconds: Long = 0,
    val running: Boolean = false,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null
)

@Singleton
class UploadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: GitHubRepository,
    private val jgit: JGitService,
    private val notifier: Notifier
) {
    private val _state = MutableStateFlow(UploadProgress())
    val state: StateFlow<UploadProgress> = _state

    @Volatile var paused: Boolean = false
    @Volatile var cancelled: Boolean = false

    fun pause() { paused = true; _state.value = _state.value.copy(paused = true) }
    fun resume() { paused = false; _state.value = _state.value.copy(paused = false) }
    fun cancel() { cancelled = true; paused = false }

    /** Build an upload job from the given URIs (files and/or directories). */
    fun buildJob(
        uris: List<Uri>, owner: String, repo: String, branch: String, targetFolder: String,
        message: String, conflictMode: ConflictMode,
        authorName: String?, authorEmail: String?, gitignore: GitignoreMatcher? = null,
        dryRun: Boolean = false
    ): UploadJob {
        val files = mutableListOf<UploadFile>()
        var total = 0L
        for (uri in uris) {
            // OpenMultipleDocuments returns "single" document URIs; OpenDocumentTree returns "tree" URIs.
            // DocumentFile.fromTreeUri throws IllegalArgumentException on the former, so detect first.
            val df: DocumentFile? = runCatching {
                if (DocumentsContract.isTreeUri(uri)) DocumentFile.fromTreeUri(context, uri)
                else DocumentFile.fromSingleUri(context, uri)
            }.getOrNull() ?: runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()

            when {
                df == null -> {
                    Logger.w("Upload", "skip unreadable URI $uri")
                }
                df.isDirectory -> collectDir(df, targetFolder, files, gitignore)
                else -> {
                    val tname = df.name ?: "file"
                    val target = joinPath(targetFolder, tname)
                    if (gitignore?.isIgnored(target, false) != true) {
                        files += UploadFile(uri.toString(), tname, target, df.length())
                        total += df.length()
                    }
                }
            }
        }
        return UploadJob(owner, repo, branch, targetFolder, message, conflictMode, authorName, authorEmail, files, total, dryRun)
    }

    private fun collectDir(dir: DocumentFile, base: String, into: MutableList<UploadFile>, gitignore: GitignoreMatcher?) {
        val dname = dir.name ?: return
        val newBase = joinPath(base, dname)
        for (child in dir.listFiles()) {
            if (child.isDirectory) collectDir(child, newBase, into, gitignore)
            else {
                val target = joinPath(newBase, child.name ?: "file")
                if (gitignore?.isIgnored(target, false) != true) {
                    into += UploadFile(child.uri.toString(), child.name ?: "file", target, child.length())
                }
            }
        }
    }

    suspend fun runJob(job: UploadJob) = withContext(Dispatchers.IO) {
        cancelled = false; paused = false
        val start = System.currentTimeMillis()
        var bytesDone = 0L
        var done = 0
        _state.value = UploadProgress(job, job.files, "", 0, job.files.size, 0, 0.0, 0, true)
        Logger.i("Upload", "${if (job.dryRun) "DRY-RUN " else ""}start ${job.owner}/${job.repo}@${job.branch} files=${job.files.size} bytes=${job.totalBytes} mode=${job.conflictMode}")

        if (job.conflictMode == ConflictMode.REPLACE_FOLDER && !job.dryRun) {
            try {
                runReplaceFolder(job)
                done = job.files.count { it.state == UploadFileState.DONE }
                bytesDone = job.files.filter { it.state == UploadFileState.DONE }.sumOf { it.sizeBytes }
                _state.value = _state.value.copy(
                    files = job.files.toList(), uploaded = done, bytesDone = bytesDone,
                    running = false, finished = true,
                    error = if (job.files.any { it.state == UploadFileState.FAILED }) "Some files failed" else null
                )
                notifier.uploadDone(job.files.none { it.state == UploadFileState.FAILED }, "${done}/${job.files.size} files (folder replaced)")
                return@withContext
            } catch (t: Throwable) {
                Logger.e("Upload", "REPLACE_FOLDER failed", t)
                _state.value = _state.value.copy(running = false, finished = true, error = t.message ?: "Folder replace failed")
                notifier.uploadDone(false, t.message ?: "Folder replace failed")
                return@withContext
            }
        }

        for (uf in job.files) {
            if (cancelled) { Logger.w("Upload", "cancelled at ${uf.targetPath}"); break }
            while (paused && !cancelled) Thread.sleep(150)
            uf.state = UploadFileState.UPLOADING
            _state.value = _state.value.copy(currentFile = uf.targetPath, files = job.files.toList())
            try {
                if (job.dryRun) {
                    Logger.d("Upload", "PREVIEW ${uf.targetPath} (${uf.sizeBytes}B)")
                    // Preview-only: do not contact the API, just mark the file as resolved.
                    uf.state = UploadFileState.DONE
                    uf.bytesDone = uf.sizeBytes
                    bytesDone += uf.sizeBytes
                    done++
                    _state.value = _state.value.copy(files = job.files.toList(), uploaded = done, bytesDone = bytesDone)
                    continue
                }

                if (uf.sizeBytes >= LARGE_FILE_THRESHOLD) {
                    // Use JGit for large files
                    uploadWithJGit(job, uf)
                    uf.state = UploadFileState.DONE
                    uf.bytesDone = uf.sizeBytes
                    bytesDone += uf.sizeBytes
                    Logger.i("Upload", "OK   ${uf.targetPath}  ${uf.sizeBytes}B (JGit)")
                } else {
                    // Use API for small files
                    if (job.conflictMode == ConflictMode.SKIP) {
                        val exists = runCatching { repo.fileContent(job.owner, job.repo, uf.targetPath, job.branch) }.isSuccess
                        if (exists) { uf.state = UploadFileState.SKIPPED; done++; continue }
                    }
                    val targetSha = if (job.conflictMode == ConflictMode.OVERWRITE) {
                        runCatching { repo.fileContent(job.owner, job.repo, uf.targetPath, job.branch).sha }.getOrNull()
                    } else null
                    val finalPath = if (job.conflictMode == ConflictMode.RENAME) renameIfExists(job.owner, job.repo, uf.targetPath, job.branch) else uf.targetPath

                    val b64 = streamBase64(Uri.parse(uf.id)) ?: throw IllegalStateException("Cannot read")
                    val req = PutFileRequest(
                        message = job.message,
                        content = b64,
                        sha = targetSha,
                        branch = job.branch,
                        author = if (job.authorName != null && job.authorEmail != null)
                            com.githubcontrol.data.api.GhCommitAuthor(job.authorName, job.authorEmail, java.time.Instant.now().toString()) else null,
                        committer = if (job.authorName != null && job.authorEmail != null)
                            com.githubcontrol.data.api.GhCommitAuthor(job.authorName, job.authorEmail, java.time.Instant.now().toString()) else null,
                    )
                    repo.api.putFile(job.owner, job.repo, finalPath, req)
                    uf.state = UploadFileState.DONE
                    uf.bytesDone = uf.sizeBytes
                    bytesDone += uf.sizeBytes
                    Logger.i("Upload", "OK   $finalPath  ${uf.sizeBytes}B")
                }
            } catch (t: Throwable) {
                if (t is OutOfMemoryError && uf.sizeBytes < LARGE_FILE_THRESHOLD) {
                    // Retry with JGit on OOM for small files
                    Logger.w("Upload", "OOM on ${uf.targetPath}, retrying with JGit")
                    try {
                        uploadWithJGit(job, uf)
                        uf.state = UploadFileState.DONE
                        uf.bytesDone = uf.sizeBytes
                        bytesDone += uf.sizeBytes
                        Logger.i("Upload", "OK   ${uf.targetPath}  ${uf.sizeBytes}B (JGit retry)")
                    } catch (t2: Throwable) {
                        uf.state = UploadFileState.FAILED
                        uf.error = t2.message
                        Logger.e("Upload", "FAIL ${uf.targetPath} (JGit retry)", t2)
                    }
                } else {
                    uf.state = UploadFileState.FAILED
                    uf.error = t.message
                    Logger.e("Upload", "FAIL ${uf.targetPath}", t)
                }
            }
            done++
            val elapsed = (System.currentTimeMillis() - start) / 1000.0
            val rate = if (elapsed > 0) bytesDone / elapsed else 0.0
            val eta = if (rate > 0) ((job.totalBytes - bytesDone) / rate).toLong() else 0
            notifier.upload(done, job.files.size, uf.displayName)
            _state.value = _state.value.copy(
                files = job.files.toList(), uploaded = done, bytesDone = bytesDone,
                bytesPerSec = rate, etaSeconds = eta
            )
        }
        val ok = job.files.none { it.state == UploadFileState.FAILED }
        if (!job.dryRun) {
            notifier.uploadDone(ok, "${job.files.count { it.state == UploadFileState.DONE }}/${job.files.size} files")
        }
        Logger.i("Upload", "${if (job.dryRun) "DRY-RUN " else ""}done ok=$ok done=$done failed=${job.files.count { it.state == UploadFileState.FAILED }} skipped=${job.files.count { it.state == UploadFileState.SKIPPED }}")
        _state.value = _state.value.copy(running = false, finished = true, error = if (ok) null else "Some files failed")
    }

    private suspend fun renameIfExists(owner: String, repoName: String, path: String, branch: String): String {
        var candidate = path
        var i = 1
        while (runCatching { repo.fileContent(owner, repoName, candidate, branch) }.isSuccess) {
            val dot = path.lastIndexOf('.')
            candidate = if (dot > 0 && dot > path.lastIndexOf('/')) {
                path.substring(0, dot) + "-$i" + path.substring(dot)
            } else "$path-$i"
            i++
            if (i > 99) break
        }
        return candidate
    }

    suspend fun uploadZipExpanded(
        owner: String, repoName: String, branch: String, targetFolder: String,
        zipUri: Uri, message: String, authorName: String?, authorEmail: String?
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val files = mutableListOf<Pair<String, ByteArray>>()
        resolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val baos = ByteArrayOutputStream()
                        zin.copyTo(baos)
                        files += joinPath(targetFolder, entry.name) to baos.toByteArray()
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        // Atomic multi-file commit via Git data API
        repo.commitFiles(owner, repoName, branch, files.map { it.first to it.second as ByteArray? }, message, authorName, authorEmail)
    }

    private fun readUri(uri: Uri): ByteArray? = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    /**
     * Stream the URI through a Base64 encoder so we never hold the raw bytes and
     * the encoded string in memory at the same time. Required to avoid OOM on
     * large uploads (the GitHub Contents API takes base64-encoded content).
     */
    private fun streamBase64(uri: Uri): String? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { ins ->
            val baos = ByteArrayOutputStream()
            Base64OutputStream(baos, Base64.NO_WRAP).use { b64 ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    b64.write(buf, 0, n)
                }
            }
            baos.toString("US-ASCII")
        }
    }

    /**
     * Atomically replace the contents of [job.targetFolder]: deletes any existing
     * files inside that folder that aren't being uploaded, then writes all of the
     * new files in a single Git Data API commit.
     */
    private suspend fun runReplaceFolder(job: UploadJob) {
        // Always use JGit for REPLACE_FOLDER to avoid API 422 errors
        runReplaceFolderWithJGit(job)
    }

    private suspend fun runReplaceFolderWithJGit(job: UploadJob) = withContext(Dispatchers.IO) {
        try {
            val repoUrl = "https://github.com/${job.owner}/${job.repo}.git"
            val localPath = jgit.localPath(job.owner, job.repo)

            // Clone if not exists
            if (!localPath.exists()) {
                jgit.clone(job.owner, job.repo, repoUrl, shallow = true)
            }

            // Checkout branch
            jgit.checkout(job.owner, job.repo, job.branch, createIfMissing = true)

            // Pull latest
            runCatching { jgit.pull(job.owner, job.repo) }

            // List existing files under target folder
            val targetFolder = job.targetFolder.trim('/')
            val existing: List<String> = runCatching {
                val branchInfo = repo.api.branch(job.owner, job.repo, job.branch)
                val parent = repo.api.commitDetail(job.owner, job.repo, branchInfo.commit.sha)
                val ft = repo.api.gitTree(job.owner, job.repo, parent.commit.tree.sha, recursive = 1)
                ft.tree.filter { it.type == "blob" && (targetFolder.isEmpty() || it.path.startsWith("$targetFolder/")) }
                    .map { it.path }
            }.getOrElse { emptyList() }

            val newPaths = job.files.map { it.targetPath.trim('/') }.toSet()
            val toDelete = existing.filterNot { it in newPaths }

            // Delete existing files locally
            for (path in toDelete) {
                val file = File(localPath, path)
                if (file.exists()) file.delete()
            }

            // Copy new files
            for (uf in job.files) {
                try {
                    val destFile = File(localPath, uf.targetPath)
                    destFile.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(Uri.parse(uf.id))?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Cannot read file")
                    uf.state = UploadFileState.DONE
                    uf.bytesDone = uf.sizeBytes
                } catch (t: Throwable) {
                    uf.state = UploadFileState.FAILED
                    uf.error = t.message
                    Logger.e("Upload", "FAIL copy ${uf.targetPath}", t)
                }
            }

            // Stage all changes
            jgit.stage(job.owner, job.repo, (toDelete + job.files.map { it.targetPath }).toList())

            // Commit
            jgit.commit(job.owner, job.repo, job.message, job.authorName, job.authorEmail)

            // Push
            jgit.push(job.owner, job.repo)

            Logger.i("Upload", "REPLACE_FOLDER JGit committed: deleted=${toDelete.size} added=${job.files.size}")
        } catch (t: Throwable) {
            // Clean up on error
            runCatching { jgit.cleanup(job.owner, job.repo) }
            throw t
        } finally {
            // Always clean up local repo to free storage
            runCatching { jgit.cleanup(job.owner, job.repo) }
        }
    }

    private fun joinPath(base: String, name: String): String {
        val b = base.trim('/')
        val n = name.trim('/')
        return if (b.isEmpty()) n else "$b/$n"
    }

    private suspend fun uploadWithJGit(job: UploadJob, uf: UploadFile) = withContext(Dispatchers.IO) {
        try {
            val repoUrl = "https://github.com/${job.owner}/${job.repo}.git"
            val localPath = jgit.localPath(job.owner, job.repo)

            // Clone if not exists
            if (!localPath.exists()) {
                jgit.clone(job.owner, job.repo, repoUrl, shallow = true)
            }

            // Checkout branch
            jgit.checkout(job.owner, job.repo, job.branch, createIfMissing = true)

            // Pull latest
            runCatching { jgit.pull(job.owner, job.repo) }

            // Copy file without loading into memory
            val destFile = File(localPath, uf.targetPath)
            destFile.parentFile?.mkdirs()

            context.contentResolver.openInputStream(Uri.parse(uf.id))?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot read file")

            // Stage
            jgit.stage(job.owner, job.repo, listOf(uf.targetPath))

            // Commit
            jgit.commit(job.owner, job.repo, job.message, job.authorName, job.authorEmail)

            // Push
            jgit.push(job.owner, job.repo)
        } catch (t: Throwable) {
            // Clean up on error
            runCatching { jgit.cleanup(job.owner, job.repo) }
            throw t
        } finally {
            // Always clean up local repo to free storage
            runCatching { jgit.cleanup(job.owner, job.repo) }
        }
    }
}
