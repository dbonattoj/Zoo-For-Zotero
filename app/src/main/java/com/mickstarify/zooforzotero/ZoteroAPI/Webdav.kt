package com.mickstarify.zooforzotero.ZoteroAPI

import android.content.Context
import android.util.Log
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Item
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.SingleEmitter
import io.reactivex.functions.Action
import net.lingala.zip4j.ZipFile
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*

class Webdav(
    address: String,
    val username: String,
    val password: String
) {
    var sardine: OkHttpSardine
    var address: String
    fun testConnection(): Boolean {
        return sardine.exists(address)
    }


    fun downloadFileRx(
        attachment: Item,
        context: Context,
        attachmentStorageManager: AttachmentStorageManager
    ): Observable<DownloadProgress> {
        val webpath = address + "/${attachment.ItemKey.toUpperCase(Locale.ROOT)}.zip"
        return Observable.create { emitter ->
            var inputStream: InputStream?
            try {
                inputStream = sardine.get(webpath)
            } catch (e: IllegalArgumentException) {
                Log.e("zotero", "${e}")
                throw(e)
            } catch (e: Exception) {
                Log.e("zotero", "${e}")
                throw(IOException("File not found."))
            }

            val zipFile =
                attachmentStorageManager.createTempFile(
                    "${attachment.ItemKey.toUpperCase(Locale.ROOT)}.pdf"
                )
            val downloadOutputStream = zipFile.outputStream()

            val buffer = ByteArray(32768)
            var read = inputStream.read(buffer)
            var total: Long = 0
            while (read > 0) {
                try {
                    total += read
                    emitter.onNext(DownloadProgress(total, -1))
                    downloadOutputStream.write(buffer, 0, read)
                    read = inputStream.read(buffer)
                } catch (e: Exception) {
                    Log.e("zotero", "exception downloading webdav attachment ${e.message}")
                    throw RuntimeException("Error downloading webdav attachment ${e.message}")
                }
            }
            downloadOutputStream.close()
            inputStream.close()
            if (read > 0) {
                throw RuntimeException(
                    "Error did not finish downloading ${attachment.ItemKey.toUpperCase(
                        Locale.ROOT
                    )}.zip"
                )
            }
            val zipFile2 = ZipFile(zipFile)
            val attachmentFilename =
                zipFile2.fileHeaders.firstOrNull()?.fileName
                    ?: throw Exception("Error empty zipfile.")
            ZipFile(zipFile).extractAll(context.cacheDir.absolutePath)
            zipFile.delete() // don't need this anymore.
            attachmentStorageManager.writeAttachmentFromFile(
                File(
                    context.cacheDir,
                    attachmentFilename
                ), attachment
            )
            File(context.cacheDir, attachmentFilename).delete()
            emitter.onComplete()
        }
    }

    fun uploadAttachment(attachment: Item, attachmentStorageManager: AttachmentStorageManager): Completable {
        /*Uploading will take 3 steps,
        * 1. Compress attachment into a ZIP file (using internal cache dir)
        * 2. Upload to webdav server as F3FXJF_NEW.zip and F3FXJF_NEW.info
        * 3. send a delete request and  rename request to server so we have
        *  F3FXJF.zip + F3FXJF.info resulting*/

        val observable = Single.create(
            { emitter: SingleEmitter<File> ->
                // STEP 1 CREATE ZIP
                val fileInputStream =
                    attachmentStorageManager.getItemInputStream(attachment).source()
                val filename = attachmentStorageManager.getFilenameForItem(attachment)
                val tempFile = attachmentStorageManager.createTempFile(filename)
                val sinkBuffer = tempFile.outputStream().sink().buffer()
                sinkBuffer.writeAll(fileInputStream)
                sinkBuffer.close()
                fileInputStream.close()

                val zipFile =
                    attachmentStorageManager.createTempFile("${attachment.ItemKey.toUpperCase(Locale.ROOT)}_NEW.zip")

                ZipFile(zipFile).addFile(tempFile)
                emitter.onSuccess(zipFile)
            }).map { zipFile ->

            sardine.put(address, zipFile, "application/zip")
        }

        return Completable.fromSingle(observable)
    }

    init {
        sardine = OkHttpSardine()
        sardine.allowForInsecureSSL()
        if (username != "" && password != "") {
            sardine.setCredentials(username, password)
        }

        this.address = if (address.endsWith("/zotero")) {
            address
        } else {
            if (address.endsWith("/")) { // so we don't get server.com//zotero
                address + "zotero"
            } else {
                address + "/zotero"
            }
        }
    }

}