package com.deskflow.domain.layout

import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.Workspace
import org.json.JSONArray
import org.json.JSONObject

/** 档案里的单图标（应用 / 快捷方式 / widget）。 */
data class ArchiveEntry(
    val kind: String,
    val dbId: Long,
    val title: String,
    val intent: String,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val iconPackage: String?,
    val iconResource: String?,
)

/** 档案里的文件夹：格位 + 子项（子项格位不存，导入时在夹内重排）。 */
data class ArchiveFolder(
    val dbId: Long,
    val title: String,
    val cellX: Int,
    val cellY: Int,
    val children: List<ArchiveEntry>,
)

data class ArchivePage(val entries: List<ArchiveEntry>, val folders: List<ArchiveFolder>)

/** 语义化布局档案：人可读、可跨设备迁移，区别于 launcher.db 整库备份（那是字节级快照）。 */
data class LayoutArchive(
    val version: Int,
    val exportedAt: String,
    val grid: GridConfig,
    val pages: List<ArchivePage>,
    val dock: List<ArchiveEntry>,
)

/**
 * 布局档案编解码（org.json，保持第三方依赖为零——单测用 maven 的 org.json 替身 android.jar 空壳）。
 *
 * 同设备恢复靠 dbId 精确匹配；跨设备 / 恢复出厂迁移靠 intent 的包名兜底（见 LayoutImportPlanner）。
 * widget 的 intent 格式不定，kind 只作展示与匹配提示，档案不解释其内容。
 */
object LayoutArchiveCodec {

    const val VERSION = 1
    const val KIND_APP = "app"
    const val KIND_SHORTCUT = "shortcut"
    const val KIND_WIDGET = "widget"

    fun kindOf(item: ItemState): String = when {
        item.readOnly -> KIND_WIDGET
        item.itemCode == ItemType.SHORTCUT -> KIND_SHORTCUT
        else -> KIND_APP
    }

    fun encode(ws: Workspace, exportedAt: String): String {
        fun entry(item: ItemState): JSONObject = JSONObject()
            .put("kind", kindOf(item))
            .put("dbId", item.dbId)
            .put("title", item.title)
            .put("intent", item.intent)
            .put("x", item.cellX)
            .put("y", item.cellY)
            .put("spanX", item.spanX)
            .put("spanY", item.spanY)
            .put("iconPackage", item.iconPackage ?: JSONObject.NULL)
            .put("iconResource", item.iconResource ?: JSONObject.NULL)

        val root = JSONObject()
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put(
                "grid",
                JSONObject()
                    .put("columns", ws.grid.columns)
                    .put("rows", ws.grid.rows)
                    .put("dockCells", ws.grid.dockCells),
            )
        val pages = JSONArray()
        ws.pages.filter { !it.isDeleted }.sortedBy { it.order }.forEach { page ->
            val items = ws.pageItems(page.screenId)
            val plain = JSONArray()
            items.filter { !it.isFolder }.forEach { plain.put(entry(it)) }
            val folders = JSONArray()
            items.filter { it.isFolder }.forEach { folder ->
                val children = JSONArray()
                ws.childrenOf(folder).forEach { children.put(entry(it)) }
                folders.put(
                    JSONObject()
                        .put("dbId", folder.dbId)
                        .put("title", folder.title)
                        .put("x", folder.cellX)
                        .put("y", folder.cellY)
                        .put("children", children),
                )
            }
            pages.put(JSONObject().put("items", plain).put("folders", folders))
        }
        val dock = JSONArray()
        ws.dockItems().sortedBy { it.cellX }.forEach { dock.put(entry(it)) }
        root.put("pages", pages).put("dock", dock)
        return root.toString(2)
    }

    fun decode(text: String): Result<LayoutArchive> = runCatching {
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(version <= VERSION) { "档案版本 $version 高于当前支持的 $VERSION，请升级应用" }
        val gridJson = root.getJSONObject("grid")
        val grid = GridConfig(
            columns = gridJson.optInt("columns", GridConfig().columns),
            rows = gridJson.optInt("rows", GridConfig().rows),
            dockCells = gridJson.optInt("dockCells", GridConfig().dockCells),
        )
        fun readEntry(json: JSONObject): ArchiveEntry = ArchiveEntry(
            kind = json.optString("kind", KIND_APP),
            dbId = json.optLong("dbId", 0L),
            title = json.optString("title", ""),
            intent = json.optString("intent", ""),
            cellX = json.optInt("x", 0),
            cellY = json.optInt("y", 0),
            spanX = json.optInt("spanX", 1),
            spanY = json.optInt("spanY", 1),
            iconPackage = json.optStringOrNull("iconPackage"),
            iconResource = json.optStringOrNull("iconResource"),
        )
        val pages = root.getJSONArray("pages").let { arr ->
            (0 until arr.length()).map { i ->
                val page = arr.getJSONObject(i)
                ArchivePage(
                    entries = page.getJSONArray("items").let { e -> (0 until e.length()).map { readEntry(e.getJSONObject(it)) } },
                    folders = page.getJSONArray("folders").let { f ->
                        (0 until f.length()).map { j ->
                            val folder = f.getJSONObject(j)
                            ArchiveFolder(
                                dbId = folder.optLong("dbId", 0L),
                                title = folder.optString("title", ""),
                                cellX = folder.optInt("x", 0),
                                cellY = folder.optInt("y", 0),
                                children = folder.getJSONArray("children")
                                    .let { c -> (0 until c.length()).map { readEntry(c.getJSONObject(it)) } },
                            )
                        }
                    },
                )
            }
        }
        val dock = root.optJSONArray("dock")?.let { d ->
            (0 until d.length()).map { readEntry(d.getJSONObject(it)) }
        } ?: emptyList()
        LayoutArchive(
            version = version,
            exportedAt = root.optString("exportedAt", ""),
            grid = grid,
            pages = pages,
            dock = dock,
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
}
