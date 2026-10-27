package no.synth.divelog

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import no.synth.divelog.core.logbook.io.BackupFolder
import no.synth.divelog.core.logbook.io.BackupFolders

/** Builds the backup folders over the system folder picker; [context] should outlive the activity. */
@Composable
internal fun rememberBackupFolders(context: Context): BackupFolders {
    val pick = rememberActivityResult(ActivityResultContracts.OpenDocumentTree())
    return DocumentTreeBackupFolders(context) { pick(null) }
}

/**
 * Backup folders picked with the system folder picker (a document tree), which keeps its
 * permission across restarts. Providers that offer no folders, Google Drive among them,
 * do not show up in the picker.
 */
private class DocumentTreeBackupFolders(
    private val context: Context,
    private val pickTree: suspend () -> Uri?,
) : BackupFolders {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun pick(): String? {
        val tree = pickTree() ?: return null
        resolver.takePersistableUriPermission(tree, ACCESS)
        return tree.toString()
    }

    override fun open(location: String): BackupFolder? {
        val tree = location.toUri()
        val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        return if (granted) DocumentTreeFolder(resolver, tree) else null
    }

    override fun label(location: String): String {
        val tree = location.toUri()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val name = runCatching {
            resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return name ?: rootId.substringAfter(':')
    }

    override fun release(location: String) {
        runCatching { resolver.releasePersistableUriPermission(location.toUri(), ACCESS) }
    }

    private companion object {
        const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

private class DocumentTreeFolder(private val resolver: ContentResolver, private val tree: Uri) : BackupFolder {
    private val treeId = DocumentsContract.getTreeDocumentId(tree)

    /** Display name to document id, for the folder's direct children. */
    private fun children(): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val projection = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID)
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }.orEmpty()
    }

    override fun list(): List<String> = children().keys.toList()

    /** A file of that name already there is kept: the provider names the new one "name (1)". */
    override fun write(name: String, bytes: ByteArray) {
        val parent = documentUri(treeId)
        // A generic type keeps the name as is; a specific one lets the provider add its own extension.
        val doc = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)) {
            "Cannot create $name in the backup folder"
        }
        val out = requireNotNull(resolver.openOutputStream(doc, "w")) { "Cannot write $name in the backup folder" }
        out.use { it.write(bytes) }
    }

    override fun delete(name: String) {
        children()[name]?.let { DocumentsContract.deleteDocument(resolver, documentUri(it)) }
    }

    private fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
}
