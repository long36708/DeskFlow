package com.deskflow.data.adapter.vivo

import com.deskflow.domain.model.GridConfig

/**
 * 桌面网格的权威来源：launcher 的 `com.bbk.launcher2.layout.xml`（user_de/shared_prefs），
 * 标准与抽屉风格各一套 `newCountX/newCountY`、`newCountX_drawer/newCountY_drawer`。
 * 用户在系统「桌面布局」里改行列后这里会变，库里没有对应字段，工作区表数据只能作旁证。
 */
data class VivoLayoutPrefs(val standard: Pair<Int, Int>?, val drawer: Pair<Int, Int>?) {

    /** 按当前风格取网格；该风格的键缺失时退回默认 4×7。 */
    fun gridFor(tables: WorkspaceTables): GridConfig {
        val (cols, rows) = if (tables === VivoDesktopStyles.DRAWER) drawer ?: return GridConfig() else standard
            ?: return GridConfig()
        return GridConfig(columns = cols, rows = rows)
    }

    companion object {
        fun parse(xml: String): VivoLayoutPrefs = VivoLayoutPrefs(
            standard = readPair(xml, "newCountX", "newCountY"),
            drawer = readPair(xml, "newCountX_drawer", "newCountY_drawer"),
        )

        private fun readPair(xml: String, xKey: String, yKey: String): Pair<Int, Int>? {
            val x = readInt(xml, xKey)?.coerceIn(2, 8) ?: return null
            val y = readInt(xml, yKey)?.coerceIn(2, 12) ?: return null
            return x to y
        }

        private fun readInt(xml: String, key: String): Int? =
            Regex("""<int name="$key" value="(\d+)"""").find(xml)?.groupValues?.get(1)?.toIntOrNull()
    }
}
