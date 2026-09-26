package com.deskflow.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.deskflow.data.adapter.vivo.UnsupportedDesktopStyleException
import com.deskflow.data.adapter.vivo.VivoConstants
import com.deskflow.data.adapter.vivo.VivoLauncherAdapter
import com.deskflow.data.adapter.vivo.VivoSnapshot
import com.deskflow.data.backup.BackupManager
import com.deskflow.domain.layout.DbOp
import com.deskflow.domain.layout.DiffCalculator
import com.deskflow.domain.layout.DiffPlan
import com.deskflow.domain.model.Workspace
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class ApplyOutcome {
    data class Success(val plan: DiffPlan, val backupName: String) : ApplyOutcome()
    data class Failed(val error: RootError) : ApplyOutcome()
    data class RolledBack(val backupName: String) : ApplyOutcome()
}

class SafeWriter(context: Context) {

    private val dbAccess = LauncherDbAccess(context)
    private val backupManager = BackupManager(context)
    private val adapter = VivoLauncherAdapter()

    fun rootAvailable(): Boolean = dbAccess.rootAvailable()

    suspend fun load(): Result<VivoWorkspaceHolder> = withContext(Dispatchers.IO) {
        runCatching {
            if (!dbAccess.rootAvailable()) throw RootDbException(RootError.NoRoot)
            if (!dbAccess.pathExists()) {
                throw RootDbException(RootError.CopyFailed("未找到 ${VivoConstants.DB_PATH}"))
            }
            val work = dbAccess.pullWork()
            val db = openReadWrite(work)
            try {
                val snapshot = readChecked(db)
                VivoWorkspaceHolder(snapshot.workspace, snapshot.maxId)
            } finally {
                db.close()
            }
        }
    }

    private fun readChecked(db: SQLiteDatabase): VivoSnapshot =
        try {
            adapter.read(db)
        } catch (e: UnsupportedDesktopStyleException) {
            throw RootDbException(RootError.UnsupportedDesktopStyle("当前 currentDesktop=${e.currentDesktop}，仅支持标准(1000)与抽屉(1002)"))
        }

    suspend fun apply(edited: Workspace): ApplyOutcome = withContext(Dispatchers.IO) {
        var meta: FileMeta? = null
        try {
            if (!dbAccess.rootAvailable()) return@withContext ApplyOutcome.Failed(RootError.NoRoot)
            val work = dbAccess.pullWork()
            meta = dbAccess.statOriginal()
            val backup = backupManager.backup(work)

            val db = openReadWrite(work)
            val plan: DiffPlan
            try {
                val fresh = readChecked(db)
                plan = DiffCalculator.plan(edited, fresh.maxId)
                if (plan.ops.isEmpty()) {
                    backup.delete()
                    return@withContext ApplyOutcome.Success(plan, backup.name)
                }
                adapter.apply(db, plan)
            } finally {
                db.close()
            }

            dbAccess.pushWork(work, meta)
            dbAccess.reloadLauncher()

            val verify = dbAccess.pullWork()
            val ok = verifyDB(verify, edited, plan)
            if (!ok) {
                dbAccess.pushWork(backup, meta)
                dbAccess.reloadLauncher()
                return@withContext ApplyOutcome.RolledBack(backup.name)
            }
            ApplyOutcome.Success(plan, backup.name)
        } catch (e: RootDbException) {
            ApplyOutcome.Failed(e.error)
        } catch (e: Exception) {
            // best effort restore if we already made a backup
            meta?.let { runCatching { backupManager.list().firstOrNull()?.let { b -> dbAccess.pushWork(b.file, it) } } }
            ApplyOutcome.Failed(RootError.CopyFailed(e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun restoreBackup(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val src = backupManager.restoreSource(name) ?: error("备份不存在: $name")
            val meta = dbAccess.statOriginal()
            dbAccess.pushWork(src, meta)
            dbAccess.reloadLauncher()
        }
    }

    fun backups(): List<com.deskflow.data.backup.BackupEntry> = backupManager.list()

    private fun openReadWrite(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun verifyDB(copy: File, edited: Workspace, plan: DiffPlan): Boolean {
        val db = SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        return try {
            val actual = adapter.readPositions(db)
            val touched = mutableMapOf<Long, List<Int>>()
            plan.ops.forEach { op ->
                when (op) {
                    is DbOp.UpdatePosition -> touched[op.id] = listOf(op.container.toInt(), op.screen, op.cellX, op.cellY)
                    is DbOp.Insert -> touched[op.row.id] = listOf(op.row.container.toInt(), op.row.screen, op.row.cellX, op.row.cellY)
                    is DbOp.Delete -> touched[op.id] = emptyList()
                    is DbOp.UpdateTitle, is DbOp.InsertScreen, is DbOp.DeleteScreen, is DbOp.UpdateScreenOrder -> Unit
                }
            }
            val itemsOk = touched.all { (id, expected) ->
                if (expected.isEmpty()) !actual.containsKey(id) else actual[id] == expected
            }
            itemsOk && verifyTitles(db, plan) && verifyScreens(db, plan)
        } finally {
            db.close()
        }
    }

    /** 重命名/新建的文件夹标题要真的落在 location_jan 里，否则桌面显示的还是旧名。 */
    private fun verifyTitles(db: SQLiteDatabase, plan: DiffPlan): Boolean {
        val expected = LinkedHashMap<Long, String>()
        plan.ops.forEach { op ->
            when (op) {
                is DbOp.UpdateTitle -> expected[op.id] = op.title
                is DbOp.Insert -> if (op.row.itemType == ItemType.FOLDER) expected[op.row.id] = op.row.title
                else -> Unit
            }
        }
        if (expected.isEmpty()) return true
        val actual = adapter.readTitles(db)
        return expected.all { (id, title) -> actual[id] == title }
    }

    private fun verifyScreens(db: SQLiteDatabase, plan: DiffPlan): Boolean {
        val screenOps = plan.ops.filter {
            it is com.deskflow.domain.layout.DbOp.InsertScreen ||
                it is com.deskflow.domain.layout.DbOp.DeleteScreen ||
                it is com.deskflow.domain.layout.DbOp.UpdateScreenOrder
        }
        if (screenOps.isEmpty()) return true
        val actual = adapter.readScreens(db)
        return screenOps.all { op ->
            when (op) {
                is com.deskflow.domain.layout.DbOp.InsertScreen -> actual.containsKey(op.screenId)
                is com.deskflow.domain.layout.DbOp.DeleteScreen -> !actual.containsKey(op.screenId)
                is com.deskflow.domain.layout.DbOp.UpdateScreenOrder -> actual[op.screenId] == op.order
                else -> true
            }
        }
    }
}

class VivoWorkspaceHolder(val workspace: Workspace, val maxId: Long)
