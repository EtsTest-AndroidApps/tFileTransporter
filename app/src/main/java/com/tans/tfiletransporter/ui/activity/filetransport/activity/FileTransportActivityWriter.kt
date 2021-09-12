package com.tans.tfiletransporter.ui.activity.filetransport.activity

import android.app.Activity
import android.util.Log
import com.tans.tfiletransporter.file.CommonFileLeaf
import com.tans.tfiletransporter.file.FileConstants
import com.tans.tfiletransporter.net.commonNetBufferPool
import com.tans.tfiletransporter.net.filetransporter.*
import com.tans.tfiletransporter.net.filetransporter.RequestFilesShareWriterHandle.Companion.getJsonString
import com.tans.tfiletransporter.net.model.File
import com.tans.tfiletransporter.net.model.Folder
import com.tans.tfiletransporter.net.model.ResponseFolderModel
import com.tans.tfiletransporter.ui.activity.commomdialog.showLoadingDialog
import com.tans.tfiletransporter.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime
import org.threeten.bp.ZoneId
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.streams.toList

suspend fun newRequestFolderChildrenShareWriterHandle(
    path: String
): RequestFolderChildrenShareWriterHandle {
    val pathData = path.toByteArray(Charsets.UTF_8)
    return RequestFolderChildrenShareWriterHandle(
        pathSize = pathData.size
    ) { outputStream ->
        val writer = Channels.newChannel(outputStream)
        val buffer = commonNetBufferPool.requestBuffer()
        try {
            writer.writeSuspendSize(buffer, pathData)
        } finally {
            commonNetBufferPool.recycleBuffer(buffer)
        }
    }
}

suspend fun newFolderChildrenShareWriterHandle(
    parentPath: String,
    shareFolder: Boolean
): FolderChildrenShareWriterHandle {
    val jsonData = withContext(Dispatchers.IO) {
        val path = Paths.get(FileConstants.homePathString + parentPath)
        val children = if (shareFolder && Files.isReadable(path)) {
            Files.list(path)
                    .filter { Files.isReadable(it) }
                    .map<Any> { p ->
                        val name = p.fileName.toString()
                        val lastModify = OffsetDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()), ZoneId.systemDefault())
                        val pathString = if (parentPath.endsWith(FileConstants.FILE_SEPARATOR)) parentPath + name else parentPath + FileConstants.FILE_SEPARATOR + name
                        if (Files.isDirectory(p)) {
                            Folder(
                                    name = name,
                                    path = pathString,
                                    childCount = p.let {
                                        val s = Files.list(it)
                                        val size = s.count()
                                        s.close()
                                        size
                                    },
                                    lastModify = lastModify
                            )
                        } else {
                            File(
                                    name = name,
                                    path = pathString,
                                    size = Files.size(p),
                                    lastModify = lastModify
                            )
                        }
                    }.toList()
        } else {
            emptyList()
        }
        val responseFolder = ResponseFolderModel(
            path = parentPath,
            childrenFiles = children.filterIsInstance<File>(),
            childrenFolders = children.filterIsInstance<Folder>()
        )
        FolderChildrenShareWriterHandle.getJsonString(responseFolder).toByteArray(Charsets.UTF_8)
    }

    return FolderChildrenShareWriterHandle(
        filesJsonSize = jsonData.size
    ) { outputStream ->
        val writer = Channels.newChannel(outputStream)
        val byteBuffer = commonNetBufferPool.requestBuffer()
        try {
            writer.writeSuspendSize(byteBuffer, jsonData)
        } finally {
            commonNetBufferPool.recycleBuffer(byteBuffer)
        }
    }
}

suspend fun newRequestFilesShareWriterHandle(
    files: List<File>
): RequestFilesShareWriterHandle {
    val jsonData = getJsonString(files).toByteArray(Charsets.UTF_8)
    return RequestFilesShareWriterHandle(
        filesJsonDataSize = jsonData.size
    ) { outputStream ->
        val writer = Channels.newChannel(outputStream)
        val byteBuffer = commonNetBufferPool.requestBuffer()
        try {
            writer.writeSuspendSize(byteBuffer, jsonData)
        } finally {
            commonNetBufferPool.recycleBuffer(byteBuffer)
        }
    }
}

suspend fun Activity.newFilesShareWriterHandle(
    files: List<File>,
    pathConverter: PathConverter = defaultPathConverter
): FilesShareWriterHandle {

    val dialog = withContext(Dispatchers.Main) {
        showLoadingDialog()
    }

    return FilesShareWriterHandle(
        files
    ) { filesMd5, localAddress ->

        withContext(Dispatchers.Main) {
            dialog.cancel()
            val result = kotlin.runCatching {
                startSendingFiles(
                        files = filesMd5,
                        localAddress = localAddress,
                        pathConverter = pathConverter
                ).await()
            }
            if (result.isFailure) {
                Log.e("SendingFileError", "SendingFileError", result.exceptionOrNull())
            }
        }
    }
}

suspend fun newSendMessageShareWriterHandle(
    message: String
): SendMessageShareWriterHandle {
    val messageData = message.toByteArray(Charsets.UTF_8)
    return SendMessageShareWriterHandle(
        messageSize = messageData.size
    ) { outputStream ->
        val writer = Channels.newChannel(outputStream)
        val buffer = commonNetBufferPool.requestBuffer()
        try {
            writer.writeSuspendSize(buffer, messageData)
        } finally {
            commonNetBufferPool.recycleBuffer(buffer)
        }
    }
}

fun CommonFileLeaf.toFile(): File {
    return File(
        name = name,
        path = path,
        size = size,
        lastModify = OffsetDateTime.ofInstant(Instant.ofEpochMilli(lastModified), ZoneId.systemDefault())
    )
}

fun File.toFileLeaf(): CommonFileLeaf {
    return CommonFileLeaf(
        name = name,
        path = path,
        size = size,
        lastModified = lastModify.toInstant().toEpochMilli()
    )
}
