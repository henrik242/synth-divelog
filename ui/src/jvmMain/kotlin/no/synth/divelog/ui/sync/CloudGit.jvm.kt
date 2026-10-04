package no.synth.divelog.ui.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * JGit-backed cloud client. Keeps a working copy per account under [workDir],
 * updates it from the remote before each read or write, and uses HTTPS basic auth
 * with the email as user and the account password as credential.
 */
actual class CloudGit actual constructor(private val workDir: String) {

    actual suspend fun pull(email: String, password: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val repo = cloudRepo(email)
            val dir = repoDir(email)
            val creds = UsernamePasswordCredentialsProvider(sanitizeEmail(email), password)
            ensureRepo(dir, repo, creds).use { git ->
                syncRemote(git, repo, creds)
                readTree(dir)
            }
        }

    actual suspend fun push(email: String, password: String, files: Map<String, String>): Unit =
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
                Unit
            }
        }

    private fun repoDir(email: String): File = File(workDir, sanitizeEmail(email).ifEmpty { "default" })

    private fun ensureRepo(dir: File, repo: CloudRepo, creds: UsernamePasswordCredentialsProvider): Git {
        if (File(dir, ".git").isDirectory) return Git.open(dir)
        dir.mkdirs()
        return try {
            Git.cloneRepository()
                .setURI(repo.url)
                .setDirectory(dir)
                .setCredentialsProvider(creds)
                .setCloneAllBranches(true)
                .call()
        } catch (e: Exception) {
            // A new account has no stored repository yet; start a local one wired
            // to the remote and create the branch on the first push.
            val git = Git.init().setDirectory(dir).setInitialBranch(repo.branch).call()
            git.remoteAdd().setName("origin").setUri(URIish(repo.url)).call()
            git
        }
    }

    private fun syncRemote(git: Git, repo: CloudRepo, creds: UsernamePasswordCredentialsProvider) {
        try {
            git.fetch().setRemote("origin").setCredentialsProvider(creds).call()
        } catch (e: Exception) {
            return // offline or empty remote: keep the local copy as is
        }
        val remote = git.repository.findRef("refs/remotes/origin/${repo.branch}") ?: return
        val hasLocal = git.repository.findRef("refs/heads/${repo.branch}") != null
        git.checkout()
            .setName(repo.branch)
            .setCreateBranch(!hasLocal)
            .apply { if (!hasLocal) setStartPoint("origin/${repo.branch}") }
            .call()
        git.reset().setMode(ResetType.HARD).setRef(remote.name).call()
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

    private companion object {
        const val COMMIT_MESSAGE = "Update dive log"
        val AUTHOR = PersonIdent("Synth Divelog", "divelog@synth.no")
    }
}
