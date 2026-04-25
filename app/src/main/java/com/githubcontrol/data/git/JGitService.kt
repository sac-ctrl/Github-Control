package com.githubcontrol.data.git

import android.content.Context
import com.githubcontrol.data.auth.AccountManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Git engine powered by JGit. Operates on local working copies stored under
 * the app's files directory. Network operations use the active account's PAT.
 */
@Singleton
class JGitService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager
) {
    val progress = MutableStateFlow<String?>(null)

    private fun reposDir(): File {
        val d = File(context.filesDir, "git-repos"); if (!d.exists()) d.mkdirs(); return d
    }

    fun localPath(owner: String, repo: String): File = File(reposDir(), "$owner-$repo")

    private suspend fun creds(): UsernamePasswordCredentialsProvider {
        val token = accountManager.activeToken() ?: ""
        // GitHub accepts PAT as the username with empty password, or with x-access-token
        return UsernamePasswordCredentialsProvider("x-access-token", token)
    }

    suspend fun clone(owner: String, repo: String, cloneUrl: String, shallow: Boolean = false): File = withContext(Dispatchers.IO) {
        val dir = localPath(owner, repo)
        if (dir.exists()) dir.deleteRecursively()
        progress.value = "Cloning $owner/$repo…"
        val cmd = Git.cloneRepository().setURI(cloneUrl).setDirectory(dir).setCredentialsProvider(creds())
        if (shallow) cmd.setDepth(1)
        cmd.call().close()
        progress.value = null
        dir
    }

    suspend fun pull(owner: String, repo: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.pull().setCredentialsProvider(creds()).call() }
    }

    suspend fun fetch(owner: String, repo: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.fetch().setCredentialsProvider(creds()).call() }
    }

    suspend fun push(owner: String, repo: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.push().setCredentialsProvider(creds()).call() }
    }

    /** Force push — DESTRUCTIVE. Used only when dangerous mode is enabled in the UI. */
    suspend fun forcePush(owner: String, repo: String, ref: String? = null) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use {
            val cmd = it.push().setCredentialsProvider(creds()).setForce(true)
            if (ref != null) cmd.setRefSpecs(org.eclipse.jgit.transport.RefSpec(ref))
            cmd.call()
        }
    }

    /** Hard reset — DESTRUCTIVE. Used to rewrite history before a force push. */
    suspend fun resetHard(owner: String, repo: String, ref: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef(ref).call() }
    }

    /** Three-way merge of two branches. Returns true if conflicts occurred. */
    suspend fun merge(owner: String, repo: String, branch: String): Boolean = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { g ->
            val ref = g.repository.findRef(branch) ?: return@withContext false
            val res = g.merge().include(ref).call()
            res.mergeStatus.toString().contains("CONFLICT", ignoreCase = true)
        }
    }

    suspend fun stage(owner: String, repo: String, paths: List<String>) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { g -> paths.forEach { g.add().addFilepattern(it).call() } }
    }

    suspend fun commit(owner: String, repo: String, message: String, name: String?, email: String?) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { g ->
            val cmd = g.commit().setMessage(message)
            if (name != null && email != null) cmd.setAuthor(PersonIdent(name, email))
            cmd.call()
        }
    }

    suspend fun amend(owner: String, repo: String, message: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.commit().setAmend(true).setMessage(message).call() }
    }

    suspend fun resetSoft(owner: String, repo: String, ref: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.reset().setMode(ResetCommand.ResetType.SOFT).setRef(ref).call() }
    }

    suspend fun stashCreate(owner: String, repo: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.stashCreate().call() }
    }

    suspend fun status(owner: String, repo: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.status().call() }
    }

    suspend fun checkout(owner: String, repo: String, branch: String, createIfMissing: Boolean = false) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use {
            it.checkout().setName(branch).setCreateBranch(createIfMissing).call()
        }
    }

    suspend fun deleteLocalBranch(owner: String, repo: String, branch: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { it.branchDelete().setBranchNames(branch).setForce(true).call() }
    }

    suspend fun localBranches(owner: String, repo: String) = withContext(Dispatchers.IO) {
        Git.open(localPath(owner, repo)).use { g -> g.branchList().call().map { it.name } }
    }

    /** Clean up local repository clone to free storage space */
    suspend fun cleanup(owner: String, repo: String) = withContext(Dispatchers.IO) {
        val dir = localPath(owner, repo)
        if (dir.exists()) {
            runCatching { dir.deleteRecursively() }
        }
    }
}
