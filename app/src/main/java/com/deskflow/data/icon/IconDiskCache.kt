package com.deskflow.data.icon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * 解码位图的磁盘缓存（PNG，应用私有目录）。
 *
 * 冷启动 200+ 图标逐个走 PackageManager 资源解析要数秒；命中磁盘后只需解一张 128px PNG，
 * 毫秒级。key 由调用方拼「解析路径 + 包名 + 版本」，应用更新换图标后自然落到新文件，
 * 遗留旧文件由 [sweep] 按存活期清理。
 */
class IconDiskCache(private val dir: File) {

    fun get(key: String): Bitmap? {
        val f = fileOf(key)
        if (!f.isFile) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        if (bmp == null) f.delete() // 损坏文件当作未命中，下次重新解码
        return bmp
    }

    /** 先写临时文件再改名，避免并发读到半张 PNG；失败静默——缓存只是加速，不值得打扰用户。 */
    fun put(key: String, bitmap: Bitmap) {
        runCatching {
            dir.mkdirs()
            val target = fileOf(key)
            // tmp 带线程 id：两个线程同时写同一 key 不互踩，各自原子改名
            val tmp = File(dir, "${target.name}.${Thread.currentThread().id}.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    /** 删除超过 [maxAgeMs] 未更新的条目；残留的 .tmp（写入中断）无条件清。 */
    fun sweep(maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name.endsWith(".tmp") || now - f.lastModified() > maxAgeMs) f.delete()
        }
    }

    /** key 里含 / : @ 等文件名不友好字符，统一落成 MD5 十六进制。 */
    private fun fileOf(key: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".png")
    }
}
