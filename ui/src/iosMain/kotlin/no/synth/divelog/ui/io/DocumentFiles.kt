package no.synth.divelog.ui.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

private var pickerDelegate: UIDocumentPickerDelegateProtocol? = null

/**
 * Lets the user pick a file in the system document picker and returns its bytes, or null
 * when cancelled. The picker hands over a copy, so no security-scoped access is needed.
 */
suspend fun pickDocument(host: UIViewController): ByteArray? {
    val result = CompletableDeferred<NSURL?>()
    val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            result.complete(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            result.complete(null)
        }
    }
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
    picker.delegate = delegate
    // The picker holds its delegate weakly; keep it until the picker answers.
    pickerDelegate = delegate
    host.presentViewController(picker, animated = true, completion = null)
    val url = try {
        result.await()
    } finally {
        pickerDelegate = null
    }
    return url?.let { NSData.dataWithContentsOfURL(it)?.toByteArray() }
}

/** Writes [export] to a temporary file and offers it in the share sheet (Save to Files, AirDrop, mail). */
@OptIn(ExperimentalForeignApi::class)
fun shareDocument(host: UIViewController, export: ExportFile) {
    val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + export.name)
    export.bytes.toNSData().writeToURL(url, atomically = true)
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    // On iPad the sheet is a popover and needs an anchor; the middle of the screen will do.
    sheet.popoverPresentationController?.let { popover ->
        popover.sourceView = host.view
        host.view.bounds.useContents { popover.sourceRect = CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
    }
    host.presentViewController(sheet, animated = true, completion = null)
}
