package com.deskflow.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.deskflow.data.adapter.vivo.UnsupportedDesktopStyleException
import com.deskflow.data.adapter.vivo.VivoConstants
import com.deskflow.data.adapter.vivo.VivoLauncherAdapter
import com.deskflow.data.adapter.vivo.VivoSnapshot
import com.deskflow.data.adapter.vivo.WorkspaceTables
import com.deskflow.domain.model.GridConfig
import com.deskflow.data.backup.BackupManager
import com.deskflow.domain.layout.DbOp
import com.deskflow.domain.layout.DiffCalculator
import com.deskflow.domain.layout.DiffPlan
import com.deskflow.domain.layout.WorkspaceDiff
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
    /** 编辑期间 launcher.db 被外部（桌面自身）改动，[changes] 为人读摘要。 */
    data class Conflict(val changes: String) : ApplyOutcome()
}

class SafeWriter(context: Context) {

    private val dbAccess = LauncherDbAccess(context)
    private val backupManager = BackupManager(context)
    private val adapter = VivoLauncherAdapter()

    /** load/写回成功时的库快照基线；apply 前与现读快照比对，发现桌面的外部改动。 */
    @Volatile
    private var baseline: VivoSnapshot? = null

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
                val (tables, grid) = resolveGrid(db)
                val snapshot = adapter.read(db, grid)
                baseline = snapshot
                VivoWorkspaceHolder(snapshot.workspace, snapshot.maxId)
            } finally {
                db.close()
            }
        }
    }

    suspend fun apply(edited: Workspace, ignoreExternalChanges: Boolean = false): ApplyOutcome =
        withContext(Dispatchers.IO) {
            writePlan(ignoreExternalChanges) { fresh -> DiffCalculator.plan(edited, fresh.maxId) }
        }

    /** 直接执行一组现成 ops（反向 plan 撤销用），跳过 planner。 */
    suspend fun applyPlan(plan: DiffPlan, ignoreExternalChanges: Boolean = false): ApplyOutcome =
        withContext(Dispatchers.IO) {
            writePlan(ignoreExternalChanges) { plan }
        }

    private fun writePlan(ignoreExternalChanges: Boolean, makePlan: (VivoSnapshot) -> DiffPlan): ApplyOutcome {
        var meta: FileMeta? = null
        var backup: File? = null
        try {
            if (!dbAccess.rootAvailable()) return ApplyOutcome.Failed(RootError.NoRoot)
            val work = dbAccess.pullWork()
            meta = dbAccess.statOriginal()
            backup = backupManager.backup(work)

            val db = openReadWrite(work)
            val plan: DiffPlan
            try {
                val fresh = readChecked(db)
                plan = makePlan(fresh)
                if (plan.ops.isEmpty()) {
                    backup.delete()
                    return ApplyOutcome.Success(plan, backup.name)
                }
                // 空 plan 无可覆盖的东西，不拦；有 ops 才检查编辑期间桌面是否被外部改过
                if (!ignoreExternalChanges) externalChanges(fresh)?.let {
                    backup.delete()
                    return ApplyOutcome.Conflict(it)
                }
                adapter.apply(db, plan)
            } finally {
                db.close()
            }

            return pushVerifyBaseline(work, meta, backup, plan)
        } catch (e: RootDbException) {
            return ApplyOutcome.Failed(e.error)
        } catch (e: Exception) {
            // best effort restore if we already made a backup
            backup?.let { b -> meta?.let { runCatching { dbAccess.pushWork(b, it) } } }
            return ApplyOutcome.Failed(RootError.CopyFailed(e.message ?: e.javaClass.simpleName))
        }
    }

    /** 写回 → 强启桌面 → 拉回验证 → 通过后把写后状态设为新基线（否则二次 apply 会误报外部变化）。 */
    private fun pushVerifyBaseline(work: File, meta: FileMeta?, backup: File, plan: DiffPlan): ApplyOutcome {
        dbAccess.pushWork(work, meta)
        dbAccess.reloadLauncher()

        val verify = dbAccess.pullWork()
        val db = SQLiteDatabase.openDatabase(
            verify.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            if (!verifyDB(db, plan)) {
                dbAccess.pushWork(backup, meta)
                dbAccess.reloadLauncher()
                return ApplyOutcome.RolledBack(backup.name)
            }
            // 写后状态成为新基线；网格与 load 时同源解析，保证基线与编辑器一致
            val (tables, grid) = resolveGrid(db)
            baseline = adapter.read(db, grid)
        } finally {
            db.close()
        }
        return ApplyOutcome.Success(plan, backup.name)
    }

    private fun externalChanges(fresh: VivoSnapshot): String? {
        val loaded = baseline ?: return null
        val changes = WorkspaceDiff.summarize(loaded.workspace, fresh.workspace)
        return if (changes.isEmpty()) null else changes.joinToString("；")
    }

    /** 风格表解析 + 网格解析（launcher prefs 优先，缺失退回默认 4×7）。 */
    private fun resolveGrid(db: SQLiteDatabase): Pair<WorkspaceTables, GridConfig> =
        try {
            val tables = adapter.resolveTables(db)
            tables to (dbAccess.readLayoutPrefs()?.gridFor(tables) ?: GridConfig())
        } catch (e: UnsupportedDesktopStyleException) {
            throw RootDbException(RootError.UnsupportedDesktopStyle("当前 currentDesktop=${e.currentDesktop}，仅支持标准(1000)与抽屉(1002)"))
        }

    suspend fun restoreBackup(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val src = backupManager.restoreSource(name) ?: error("备份不存在: $name")
            val meta = dbAccess.statOriginal()
            dbAccess.pushWork(src, meta)
            dbAccess.reloadLauncher()
            // 库被整体替换，旧基线失效；调用方 load() 会重建
            baseline = null
        }
    }

    private fun readChecked(db: SQLiteDatabase): VivoSnapshot =
        try {
            // 与 baseline/load 同源解析网格，否则两份快照网格不一致
            adapter.read(db, resolveGrid(db).second)
        } catch (e: UnsupportedDesktopStyleException) {
            throw RootDbException(RootError.UnsupportedDesktopStyle("当前 currentDesktop=${e.currentDesktop}，仅支持标准(1000)与抽屉(1002)"))
        }

    fun backups(): List<com.deskflow.data.backup.BackupEntry> = backupManager.list()

    /** 强制停止第三方应用（root shell，不涉及桌面库）。 */
    fun forceStopPackage(pkg: String): Boolean = dbAccess.forceStop(pkg)

    private fun openReadWrite(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun verifyDB(db: SQLiteDatabase, plan: DiffPlan): Boolean {
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
        return itemsOk && verifyTitles(db, plan) && verifyScreens(db, plan)
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
