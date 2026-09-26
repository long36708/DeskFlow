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
    const val LOCATION_TABLE = "location_jan"
    const val SCREEN_TABLE = "screen_jan"
    const val ATTRIBUTE_TABLE = "attribute"
}

class VivoSnapshot(val workspace: Workspace, val maxId: Long)

class VivoLauncherAdapter {

    fun read(db: SQLiteDatabase): VivoSnapshot {
        val pages = mutableListOf<ScreenPage>()
        db.rawQuery(
            "SELECT _id, screen_order FROM ${VivoConstants.SCREEN_TABLE} " +
                "WHERE screen_container = ${CONTAINER_DESKTOP} ORDER BY screen_order",
            null,
        ).use { c ->
            while (c.moveToNext()) pages += ScreenPage(c.getInt(0), c.getInt(1))
        }

        val rows = mutableListOf<ItemState>()
        db.rawQuery(
            "SELECT _id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType, iconPackage, iconResource " +
                "FROM ${VivoConstants.LOCATION_TABLE}",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val itemCode = c.getInt(9)
                val container = c.getLong(3).toLong()
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

        val maxId = db.rawQuery("SELECT MAX(_id) FROM ${VivoConstants.LOCATION_TABLE}", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        return VivoSnapshot(Workspace(GridConfig(), pages, items.toMutableList()), maxId)
    }

    fun apply(db: SQLiteDatabase, plan: DiffPlan) {
        db.beginTransaction()
        try {
            plan.ops.forEach { op ->
                when (op) {
                    is DbOp.Delete -> {
                        db.execSQL("DELETE FROM ${VivoConstants.LOCATION_TABLE} WHERE _id = ?", arrayOf(op.id))
                        db.execSQL("DELETE FROM ${VivoConstants.ATTRIBUTE_TABLE} WHERE _id = ?", arrayOf(op.id))
                    }
                    is DbOp.Insert -> insert(db, op.row)
                    is DbOp.UpdatePosition -> db.execSQL(
                        "UPDATE ${VivoConstants.LOCATION_TABLE} SET container=?, screen=?, cellX=?, cellY=?, modified=1 " +
                            "WHERE _id = ?",
                        arrayOf<Any>(op.container, op.screen, op.cellX, op.cellY, op.id),
                    )
                    // 文件夹标题：location_jan 是显示来源，attribute 同步一份（行可能不存在，0 行受影响无害）
                    is DbOp.UpdateTitle -> {
                        db.execSQL(
                            "UPDATE ${VivoConstants.LOCATION_TABLE} SET title=?, modified=1 WHERE _id = ?",
                            arrayOf<Any>(op.title, op.id),
                        )
                        db.execSQL(
                            "UPDATE ${VivoConstants.ATTRIBUTE_TABLE} SET title=? WHERE _id = ?",
                            arrayOf<Any>(op.title, op.id),
                        )
                    }
                    is DbOp.InsertScreen -> insertScreen(db, op.screenId, op.order)
                    is DbOp.DeleteScreen -> db.execSQL(
                        "DELETE FROM ${VivoConstants.SCREEN_TABLE} WHERE _id = ?",
                        arrayOf<Any>(op.screenId),
                    )
                    is DbOp.UpdateScreenOrder -> db.execSQL(
                        "UPDATE ${VivoConstants.SCREEN_TABLE} SET screen_order=? WHERE _id = ?",
                        arrayOf<Any>(op.order, op.screenId),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insert(db: SQLiteDatabase, row: InsertRow) {
        db.execSQL(
            "INSERT INTO ${VivoConstants.LOCATION_TABLE} (_id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType, rank, modified) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,0,1)",
            arrayOf<Any>(row.id, row.title, row.intent, row.container, row.screen, row.cellX, row.cellY, row.spanX, row.spanY, row.itemType),
        )
        db.execSQL(
            "INSERT INTO ${VivoConstants.ATTRIBUTE_TABLE} (_id, title, intent, itemType, modified, restored) " +
                "VALUES (?,?,?,?,1,0)",
            arrayOf<Any>(row.id, row.title, row.intent, row.itemType),
        )
    }

    /**
     * 新增页：以现有页为模板复制一整行，只覆盖 _id 与 screen_order，
     * 这样不必知道 screen_jan 的完整 schema（vivo 各版本列不同，可能有 NOT NULL 无默认值的列）。
     */
    private fun insertScreen(db: SQLiteDatabase, screenId: Int, order: Int) {
        val cols = tableColumns(db, VivoConstants.SCREEN_TABLE)
        if (cols.isEmpty()) return
        val template = db.rawQuery(
            "SELECT _id FROM ${VivoConstants.SCREEN_TABLE} WHERE screen_container = $CONTAINER_DESKTOP LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

        if (template == null) {
            db.execSQL(
                "INSERT INTO ${VivoConstants.SCREEN_TABLE} (_id, screen_container, screen_order) VALUES (?,?,?)",
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
            "INSERT INTO ${VivoConstants.SCREEN_TABLE} ($insertCols) " +
                "SELECT $selectCols FROM ${VivoConstants.SCREEN_TABLE} WHERE _id = $template",
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
        val map = HashMap<Int, Int>()
        db.rawQuery(
            "SELECT _id, screen_order FROM ${VivoConstants.SCREEN_TABLE} WHERE screen_container = $CONTAINER_DESKTOP",
            null,
        ).use { c ->
            while (c.moveToNext()) map[c.getInt(0)] = c.getInt(1)
        }
        return map
    }

    fun readPositions(db: SQLiteDatabase): Map<Long, List<Int>> {
        val map = HashMap<Long, List<Int>>()
        db.rawQuery("SELECT _id, container, screen, cellX, cellY FROM ${VivoConstants.LOCATION_TABLE}", null).use { c ->
            while (c.moveToNext()) {
                map[c.getLong(0)] = listOf(c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4))
            }
        }
        return map
    }

    fun readTitles(db: SQLiteDatabase): Map<Long, String> {
        val map = HashMap<Long, String>()
        db.rawQuery("SELECT _id, title FROM ${VivoConstants.LOCATION_TABLE}", null).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getString(1) ?: ""
        }
        return map
    }
}
