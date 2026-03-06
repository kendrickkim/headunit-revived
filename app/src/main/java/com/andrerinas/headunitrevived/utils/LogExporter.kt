package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {

    fun saveLogToPublicFile(context: Context): File? {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d")
            val bufferedReader = process.inputStream.bufferedReader()

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "HUR_Log_$timeStamp.txt"

            // Save to external files dir (Android/data/.../files/) - Accessible via PC/File Manager
            val logDir = context.getExternalFilesDir(null)
            if (logDir != null && !logDir.exists()) {
                logDir.mkdirs()
            }

            // Clean old logs
            logDir?.listFiles { _, name -> name.startsWith("HUR_Log_") }?.forEach { it.delete() }

            val logFile = File(logDir, fileName)

            logFile.bufferedWriter().use { out ->
                bufferedReader.forEachLine { line ->
                    out.write(line)
                    out.newLine()
                }
            }
            logFile
        } catch (e: IOException) {
            AppLog.e("Failed to save logs", e)
            null
        }
    }

    fun shareLogFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share Log File")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
