package com.deskflow.data.db

import android.content.Context
import com.deskflow.data.adapter.vivo.VivoConstants
import com.topjohnwu.superuser.Shell
import java.io.File

data class FileMeta(val owner: String, val group: String, val mode: String, val seContext: String)

sealed class RootError(val message: String) {
    object NoRoot : RootError("未获取到 Root 权限")
    data class CopyFailed(val detail: String) : RootError("复制桌面数据库失败: $detail")
    object OpenFailed : RootError("打开数据库失败，文件可能已损坏")
    data class PushFailed(val detail: String) : RootError("写回数据库失败: $detail")
    data class UnsupportedDesktopStyle(val detail: String) : RootError("桌面风格不受支持: $detail")
}

class RootDbException(val error: RootError) : Exception(error.message)

class LauncherDbAccess(private val context: Context) {

    val workFile: File by lazy { File(workDir(), "work.db") }

    private fun workDir(): File = File(context.filesDir, "launcher")

    fun rootAvailable(): Boolean = Shell.rootAccess()

    // KernelSU 给 App 的 root shell 可能处于隔离 mount namespace，看不到 /data/user_de 等路径；
    // 若可行则统一通过 nsenter 进入 init 的全局 namespace 执行。
    private val useNsenter: Boolean by lazy {
        Shell.cmd("nsenter -t 1 -m -- true").exec().code == 0
    }

    private fun quotePosix(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun exec(vararg cmds: String): Shell.Result {
        val joined = cmds.joinToString("\n")
        return if (useNsenter) {
            Shell.cmd("nsenter -t 1 -m -- sh -c ${quotePosix(joined)}").exec()
        } else {
            Shell.cmd(*cmds).exec()
        }
    }

    private fun ok(r: Shell.Result) = r.code == 0

    fun statOriginal(): FileMeta? {
        val r = exec("stat -c '%U %G %a %C' ${VivoConstants.DB_PATH}")
        if (!ok(r)) return null
        val parts = r.out.firstOrNull()?.trim()?.split(" ") ?: return null
        if (parts.size < 4) return null
        return FileMeta(parts[0], parts[1], parts[2], parts[3])
    }

    fun pathExists(): Boolean = ok(exec("test -e ${VivoConstants.DB_PATH}"))

    fun pullWork(): File {
        val dir = workDir()
        dir.mkdirs()
        val dest = workFile.absolutePath
        val uid = android.os.Process.myUid()
        val cmd = buildString {
            append("mkdir -p '${dir.absolutePath}' ")
            append("&& cp '${VivoConstants.DB_PATH}' '$dest' ")
            append("&& chown $uid:$uid '$dest' ")
            append("&& chmod 600 '$dest' ")
            append("&& CTX=\$(stat -c %C '${dir.absolutePath}') ")
            append("&& chcon \"\$CTX\" '$dest'")
        }
        if (!ok(exec(cmd))) throw RootDbException(RootError.CopyFailed("cp 到 $dest 失败"))
        return workFile
    }

    fun pushWork(source: File, meta: FileMeta?) {
        val cmd = buildString {
            append("cp '${source.absolutePath}' '${VivoConstants.DB_PATH}' ")
            append("&& rm -f '${VivoConstants.DB_PATH}-journal' '${VivoConstants.DB_PATH}-wal' '${VivoConstants.DB_PATH}-shm' ")
            if (meta != null) {
                append("&& chown ${meta.owner}:${meta.group} '${VivoConstants.DB_PATH}' ")
                append("&& chmod ${meta.mode} '${VivoConstants.DB_PATH}' ")
                append("&& chcon '${meta.seContext}' '${VivoConstants.DB_PATH}' ")
            }
            append("&& sync")
        }
        if (!ok(exec(cmd))) throw RootDbException(RootError.PushFailed(cmd))
    }

    fun reloadLauncher() {
        exec("am force-stop ${VivoConstants.LAUNCHER_PACKAGE}")
        exec(
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME " +
                "--activity-reset-task-if-needed -f 0x10000000",
        )
    }

    /** 强制停止任意包（root shell）；搜索结果快捷操作用。 */
    fun forceStop(pkg: String): Boolean = ok(exec("am force-stop '$pkg'"))
}
