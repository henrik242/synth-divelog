package no.synth.divelog.core.logbook.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.EmptyProgressMonitor
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * JGit-backed [CloudSync], shared by Android and desktop. Keeps a working copy per
 * account under [workDir], updates it from the remote before each read or write, and
 * uses HTTPS basic auth with the email as user and the account password as credential.
 */
class CloudGit(private val workDir: String) : CloudSync {

    override suspend fun pull(
        email: String,
        password: String,
        onProgress: (task: String, fraction: Float?) -> Unit,
    ): Map<String, String> =
        withContext(Dispatchers.IO) {
            val repo = cloudRepo(email)
            val dir = repoDir(email)
            val creds = UsernamePasswordCredentialsProvider(sanitizeEmail(email), password)
            val monitor = TransferProgress(onProgress)
            ensureRepo(dir, repo, creds, monitor).use { git ->
                check(syncRemote(git, repo, creds, monitor)) { "No logbook in the cloud for $email yet" }
                readTree(dir)
            }
        }

    override suspend fun push(email: String, password: String, files: Map<String, String>): Unit =
        withContext(Dispatchers.IO) {
            val repo = cloudRepo(email)
            val dir = repoDir(email)
            val creds = UsernamePasswordCredentialsProvider(sanitizeEmail(email), password)
            ensureRepo(dir, repo, creds).use { git ->
                syncRemote(git, repo, creds)
                writeTree(dir, files)
                git.add().addFilepattern(".").call()
                git.add().addFilepattern(".").setUpdate(true).call() // stage deletions
                if (!git.status().call().isClean) {
                    git.commit().setAuthor(AUTHOR).setCommitter(AUTHOR).setMessage(COMMIT_MESSAGE).call()
                }
                git.push()
                    .setRemote("origin")
                    .setCredentialsProvider(creds)
                    .setRefSpecs(RefSpec("refs/heads/${repo.branch}:refs/heads/${repo.branch}"))
                    .call()
            }
        }

    private fun repoDir(email: String): File = File(workDir, sanitizeEmail(email).ifEmpty { "default" })

    private fun ensureRepo(
        dir: File,
        repo: CloudRepo,
        creds: UsernamePasswordCredentialsProvider,
        monitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
    ): Git {
        if (File(dir, ".git").isDirectory) return Git.open(dir)
        dir.mkdirs()
        // The cloud creates the repository with the account, so a failed clone is a real
        // error (credentials, network); clear the partial copy and report it.
        return try {
            Git.cloneRepository()
                .setURI(repo.url)
                .setDirectory(dir)
                .setCredentialsProvider(creds)
                .setCloneAllBranches(true)
                .setProgressMonitor(monitor)
                .call()
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /**
     * Fetch and move the working copy to the remote branch. Fetch errors propagate, so
     * a pull never reads, and a push never overwrites, a stale copy. Returns false when
     * the account's branch does not exist in the cloud yet.
     */
    private fun syncRemote(
        git: Git,
        repo: CloudRepo,
        creds: UsernamePasswordCredentialsProvider,
        monitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
    ): Boolean {
        git.fetch().setRemote("origin").setCredentialsProvider(creds).setProgressMonitor(monitor).call()
        val remote = git.repository.findRef("refs/remotes/origin/${repo.branch}") ?: return false
        val hasLocal = git.repository.findRef("refs/heads/${repo.branch}") != null
        git.checkout()
            .setName(repo.branch)
            .setCreateBranch(!hasLocal)
            .apply { if (!hasLocal) setStartPoint("origin/${repo.branch}") }
            .call()
        git.reset().setMode(ResetType.HARD).setRef(remote.name).call()
        return true
    }

    private fun readTree(dir: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val root = dir.toPath()
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = root.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            if (rel == ".git" || rel.startsWith(".git/")) return@forEach
            out[rel] = file.readText()
        }
        return out
    }

    private fun writeTree(dir: File, files: Map<String, String>) {
        dir.listFiles()?.forEach { if (it.name != ".git") it.deleteRecursively() }
        for ((path, content) in files) {
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    /** Relays JGit's per-task progress as a fraction, once per whole percent. */
    private class TransferProgress(private val onProgress: (String, Float?) -> Unit) : EmptyProgressMonitor() {
        private var task = ""
        private var total = 0
        private var done = 0
        private var shown = -1

        override fun beginTask(title: String, totalWork: Int) {
            task = title
            total = totalWork
            done = 0
            shown = -1
            onProgress(title, if (totalWork > 0) 0f else null)
        }

        override fun update(completed: Int) {
            if (total <= 0) return
            done += completed
            val percent = done * 100 / total
            if (percent != shown) {
                shown = percent
                onProgress(task, done.toFloat() / total)
            }
        }
    }

    private companion object {
        const val COMMIT_MESSAGE = "Update dive log"
        val AUTHOR = PersonIdent("Synth Divelog", "divelog@synth.no")
    }
}
