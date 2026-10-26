package com.deskflow.data.adapter.vivo

import android.database.sqlite.SQLiteDatabase
import com.deskflow.domain.layout.DbOp
import com.deskflow.domain.layout.DiffPlan
import com.deskflow.domain.layout.InsertRow
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace

object VivoConstants {
    const val LAUNCHER_PACKAGE = "com.bbk.launcher2"
    const val DB_PATH = "/data/user_de/0/com.bbk.launcher2/databases/launcher.db"
    /** 网格布局（列数/行数）存在 launcher 的 layout prefs，标准/抽屉各一套。 */
    const val LAYOUT_PREFS_PATH = "/data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml"
    const val ATTRIBUTE_TABLE = "attribute"
    const val DESKTOP_STYLE_TABLE = "desktopStyle"
}

/** vivo 桌面样式各有一套独立的工作区表；当前样式由 desktopStyle.currentDesktop 决定。 */
data class WorkspaceTables(val label: String, val locationTable: String, val screenTable: String)

object VivoDesktopStyles {
    val STANDARD = WorkspaceTables("标准风格", "location_jan", "screen_jan")
    val DRAWER = WorkspaceTables("抽屉风格", "location_jan_drawer", "screen_jan_drawer")

    // 值映射来自单台 X200 实测（切样式前后对比 launcher.db 备份），非官方文档语义；未知值一律拒绝写库
    fun resolve(currentDesktop: Long?): WorkspaceTables? = when (currentDesktop) {
        null -> STANDARD // 旧版 launcher.db 无 desktopStyle 表
        1000L -> STANDARD
        1002L -> DRAWER
        else -> null
    }
}

class UnsupportedDesktopStyleException(val currentDesktop: Long?) :
    Exception("不支持的桌面风格 (currentDesktop=${currentDesktop ?: "未知"})，当前仅支持标准与抽屉风格")

class VivoSnapshot(val workspace: Workspace, val maxId: Long, val tables: WorkspaceTables) {
    val styleLabel: String get() = tables.label
}

class VivoLauncherAdapter {

    /** 读当前样式的表名；未知样式直接抛错，避免把改动写进不再显示的备用表。 */
    fun resolveTables(db: SQLiteDatabase): WorkspaceTables {
        val hasStyleTable = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name = ?",
            arrayOf(VivoConstants.DESKTOP_STYLE_TABLE),
        ).use { c -> c.moveToFirst() }
        if (!hasStyleTable) return VivoDesktopStyles.STANDARD
        val value = db.rawQuery("SELECT currentDesktop FROM ${VivoConstants.DESKTOP_STYLE_TABLE} LIMIT 1", null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        return VivoDesktopStyles.resolve(value) ?: throw UnsupportedDesktopStyleException(value)
    }

    /** [grid] 来自 launcher prefs（按风格解析）；缺省时用默认 4×7。 */
    fun read(db: SQLiteDatabase, grid: GridConfig = GridConfig()): VivoSnapshot {
        val tables = resolveTables(db)
        val pages = mutableListOf<ScreenPage>()
        db.rawQuery(
            "SELECT _id, screen_order FROM ${tables.screenTable} " +
                "WHERE screen_container = ${CONTAINER_DESKTOP} ORDER BY screen_order",
            null,
        ).use { c ->
            while (c.moveToNext()) pages += ScreenPage(c.getInt(0), c.getInt(1))
        }

        val rows = mutableListOf<ItemState>()
        db.rawQuery(
            "SELECT _id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType, iconPackage, iconResource " +
                "FROM ${tables.locationTable}",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val itemCode = c.getInt(9)
                val container = c.getLong(3)
                if (container != CONTAINER_DESKTOP && container != CONTAINER_DOCK && container < 0) continue // negative-one screen etc.
                val movable = itemCode == ItemType.APP || itemCode == ItemType.SHORTCUT || itemCode == ItemType.FOLDER
                rows += ItemState(
                    dbId = c.getLong(0),
                    title = c.getString(1) ?: "",
                    intent = c.getString(2) ?: "",
                    itemCode = itemCode,
                    iconPackage = c.getString(10),
                    iconResource = c.getString(11),
                    container = container,
                    screen = c.getInt(4),
                    cellX = c.getInt(5),
                    cellY = c.getInt(6),
                    spanX = c.getInt(7).coerceAtLeast(1),
                    spanY = c.getInt(8).coerceAtLeast(1),
                    readOnly = !movable,
                )
            }
        }

        val folderIds = rows.filter { it.isFolder }.map { it.dbId }.toSet()
        val items = rows.filter { row ->
            when {
                row.container == CONTAINER_DESKTOP || row.container == CONTAINER_DOCK -> true
                // 文件夹子项：vivo 的内置"工具文件夹" _id=0，其子项 container=0，不能用 > 0 判断
                row.container >= 0 -> folderIds.contains(row.container) // orphaned folder child: leave untouched
                else -> false
            }
        }

        // _id 在所有样式表间是同一命名空间（切换样式时按原 id 复用），新行必须避开全部表的占用
        val maxId = globalMaxId(db)

        return VivoSnapshot(Workspace(grid, pages, items.toMutableList()), maxId, tables)
    }

    private fun globalMaxId(db: SQLiteDatabase): Long {
        val names = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE 'location%' OR name = ?)",
            arrayOf(VivoConstants.ATTRIBUTE_TABLE),
        ).use { c -> while (c.moveToNext()) names += c.getString(0) }
        if (names.isEmpty()) return 0L
        val union = names.joinToString(" UNION ALL ") { "SELECT _id AS id FROM `$it`" }
        return db.rawQuery("SELECT MAX(id) FROM ($union)", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    fun apply(db: SQLiteDatabase, plan: DiffPlan) {
        val tables = resolveTables(db)
        db.beginTransaction()
        try {
            plan.ops.forEach { op ->
                when (op) {
                    is DbOp.Delete -> {
                        db.execSQL("DELETE FROM ${tables.locationTable} WHERE _id = ?", arrayOf(op.id))
                        db.execSQL("DELETE FROM ${VivoConstants.ATTRIBUTE_TABLE} WHERE _id = ?", arrayOf(op.id))
                    }
                    is DbOp.Insert -> insert(db, tables, op.row)
                    is DbOp.UpdatePosition -> db.execSQL(
                        "UPDATE ${tables.locationTable} SET container=?, screen=?, cellX=?, cellY=?, modified=1 " +
                            "WHERE _id = ?",
                        arrayOf<Any>(op.container, op.screen, op.cellX, op.cellY, op.id),
                    )
                    // 文件夹标题：location 表是显示来源，attribute 同步一份（行可能不存在，0 行受影响无害）
                    is DbOp.UpdateTitle -> {
                        db.execSQL(
                            "UPDATE ${tables.locationTable} SET title=?, modified=1 WHERE _id = ?",
                            arrayOf<Any>(op.title, op.id),
                        )
                        db.execSQL(
                            "UPDATE ${VivoConstants.ATTRIBUTE_TABLE} SET title=? WHERE _id = ?",
                            arrayOf<Any>(op.title, op.id),
                        )
                    }
                    is DbOp.InsertScreen -> insertScreen(db, tables, op.screenId, op.order)
                    is DbOp.DeleteScreen -> db.execSQL(
                        "DELETE FROM ${tables.screenTable} WHERE _id = ?",
                        arrayOf<Any>(op.screenId),
                    )
                    is DbOp.UpdateScreenOrder -> db.execSQL(
                        "UPDATE ${tables.screenTable} SET screen_order=? WHERE _id = ?",
                        arrayOf<Any>(op.order, op.screenId),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insert(db: SQLiteDatabase, tables: WorkspaceTables, row: InsertRow) {
        db.execSQL(
            "INSERT INTO ${tables.locationTable} " +
                "(_id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType, iconPackage, iconResource, rank, modified) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,1)",
            arrayOf<Any?>(
                row.id, row.title, row.intent, row.container, row.screen,
                row.cellX, row.cellY, row.spanX, row.spanY, row.itemType,
                row.iconPackage, row.iconResource,
            ),
        )
        db.execSQL(
            "INSERT INTO ${VivoConstants.ATTRIBUTE_TABLE} (_id, title, intent, itemType, modified, restored) " +
                "VALUES (?,?,?,?,1,0)",
            arrayOf<Any>(row.id, row.title, row.intent, row.itemType),
        )
    }

    /**
     * 新增页：以现有页为模板复制一整行，只覆盖 _id 与 screen_order，
     * 这样不必知道 screen 表的完整 schema（vivo 各版本列不同，可能有 NOT NULL 无默认值的列）。
     */
    private fun insertScreen(db: SQLiteDatabase, tables: WorkspaceTables, screenId: Int, order: Int) {
        val cols = tableColumns(db, tables.screenTable)
        if (cols.isEmpty()) return
        val template = db.rawQuery(
            "SELECT _id FROM ${tables.screenTable} WHERE screen_container = $CONTAINER_DESKTOP LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

        if (template == null) {
            db.execSQL(
                "INSERT INTO ${tables.screenTable} (_id, screen_container, screen_order) VALUES (?,?,?)",
                arrayOf<Any>(screenId, CONTAINER_DESKTOP, order),
            )
            return
        }
        val insertCols = cols.joinToString(",") { "`$it`" }
        val selectCols = cols.joinToString(",") { c ->
            when (c) {
                "_id" -> "$screenId"
                "screen_order" -> "$order"
                else -> "`$c`"
            }
        }
        db.execSQL(
            "INSERT INTO ${tables.screenTable} ($insertCols) " +
                "SELECT $selectCols FROM ${tables.screenTable} WHERE _id = $template",
        )
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) out += c.getString(idx)
        }
        return out
    }

    fun readScreens(db: SQLiteDatabase): Map<Int, Int> {
        val tables = resolveTables(db)
        val map = HashMap<Int, Int>()
        db.rawQuery(
            "SELECT _id, screen_order FROM ${tables.screenTable} WHERE screen_container = $CONTAINER_DESKTOP",
            null,
        ).use { c ->
            while (c.moveToNext()) map[c.getInt(0)] = c.getInt(1)
        }
        return map
    }

    fun readPositions(db: SQLiteDatabase): Map<Long, List<Int>> {
        val tables = resolveTables(db)
        val map = HashMap<Long, List<Int>>()
        db.rawQuery("SELECT _id, container, screen, cellX, cellY FROM ${tables.locationTable}", null).use { c ->
            while (c.moveToNext()) {
                map[c.getLong(0)] = listOf(c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4))
            }
        }
        return map
    }

    fun readTitles(db: SQLiteDatabase): Map<Long, String> {
        val tables = resolveTables(db)
        val map = HashMap<Long, String>()
        db.rawQuery("SELECT _id, title FROM ${tables.locationTable}", null).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getString(1) ?: ""
        }
        return map
    }
}
