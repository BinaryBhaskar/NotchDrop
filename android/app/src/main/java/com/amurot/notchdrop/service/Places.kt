package com.amurot.notchdrop.service

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap

/** Intents that open received files and the folder they're saved in. */
object Places {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** The save folder as a document the Files app can show. */
    private fun folderDocument(): Uri {
        Node.saveTree()?.let { return DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }
        val mac = Node.store.peer?.displayName?.replace('/', '-') ?: "Mac"
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Download/From $mac")
    }

    /** Opens the save folder in the Files app; falls back to the Downloads view. */
    fun openFolder(ctx: Context) {
        runCatching { ctx.startActivity(folderIntent(ctx)) }
    }

    /** The save folder in the Files app, or the Downloads view where nothing can show a folder. */
    fun folderIntent(ctx: Context): Intent {
        val folder = Intent(Intent.ACTION_VIEW).setDataAndType(folderDocument(), DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (folder.resolveActivity(ctx.packageManager) != null) folder
        else Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun mimeOf(ctx: Context, uri: Uri, name: String? = null): String =
        runCatching { ctx.contentResolver.getType(uri) }.getOrNull()?.takeIf { it != "application/octet-stream" }
            ?: name?.substringAfterLast('.', "")?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"

    /** Opens a file in the app that handles its type. Throws [ActivityNotFoundException] if none does. */
    fun open(ctx: Context, uri: Uri, mime: String) {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun share(ctx: Context, uri: Uri?, mime: String?, text: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            if (uri != null) {
                type = mime ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
