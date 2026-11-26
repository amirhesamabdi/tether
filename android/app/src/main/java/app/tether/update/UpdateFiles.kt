package app.tether.update

import android.content.Context
import android.net.Uri
import app.tether.report.ReportProvider
import java.io.File
import java.io.FileNotFoundException

internal object UpdateFiles {
    const val PATH = "update"
    const val APK_MIME = "application/vnd.android.package-archive"

    fun directory(context: Context) = File(context.cacheDir, "app-updates")

    fun uri(context: Context, file: File): Uri = Uri.Builder().scheme("content")
        .authority(ReportProvider.authority(context)).appendPath(PATH).appendPath(file.name).build()

    fun openableFile(context: Context, uri: Uri): File {
        val name = uri.pathSegments.getOrNull(1) ?: throw FileNotFoundException()
        if (uri.pathSegments.size != 2 || !GatewayRelease.APK_NAME.matches(name)) throw FileNotFoundException()
        return File(directory(context), name).takeIf { it.isFile } ?: throw FileNotFoundException()
    }
}
