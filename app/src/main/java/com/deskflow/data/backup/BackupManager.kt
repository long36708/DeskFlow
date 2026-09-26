package com.deskflow.data.backup

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupEntry(val file: File, val label: String)

class BackupManager(context: Context) {

    private val dir = File(context.filesDir, "launcher/backups").apply { mkdirs() }

    private val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun backup(source: File): File {
        val target = File(dir, "launcher-${formatter.format(Date())}.db")
        source.copyTo(target, overwrite = true)
        prune()
        return target
    }

    fun list(): List<BackupEntry> =
        dir.listFiles { f -> f.name.startsWith("launcher-") && f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupEntry(it, it.name) }
            ?: emptyList()

    fun restoreSource(name: String): File? = File(dir, name).takeIf { it.isFile }

    private fun prune() {
        val all = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        all.drop(MAX_KEEP).forEach { it.delete() }
    }

    companion object {
        const val MAX_KEEP = 10
    }
}
