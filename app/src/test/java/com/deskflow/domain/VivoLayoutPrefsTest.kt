package com.deskflow.domain

import com.deskflow.data.adapter.vivo.VivoDesktopStyles
import com.deskflow.data.adapter.vivo.VivoLayoutPrefs
import com.deskflow.domain.model.GridConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VivoLayoutPrefsTest {

    private val sampleXml = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <int name="newCountY" value="9" />
            <int name="launcher_newCountX_2" value="4" />
            <int name="newCountX" value="5" />
            <int name="launcher_newCountY_2" value="7" />
            <int name="launcher_newCountY_drawer_2" value="7" />
            <int name="newCountY_drawer" value="7" />
            <int name="newCountX_drawer" value="4" />
        </map>
    """.trimIndent()

    @Test
    fun parseReadsPerStyleGrid() {
        val prefs = VivoLayoutPrefs.parse(sampleXml)
        assertEquals(5 to 9, prefs.standard)
        assertEquals(4 to 7, prefs.drawer)
    }

    @Test
    fun gridForUsesStyleSpecificValues() {
        val prefs = VivoLayoutPrefs.parse(sampleXml)
        assertEquals(5, prefs.gridFor(VivoDesktopStyles.STANDARD).columns)
        assertEquals(9, prefs.gridFor(VivoDesktopStyles.STANDARD).rows)
        assertEquals(4, prefs.gridFor(VivoDesktopStyles.DRAWER).columns)
        assertEquals(7, prefs.gridFor(VivoDesktopStyles.DRAWER).rows)
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        val prefs = VivoLayoutPrefs.parse("<map><int name=\"newCountX\" value=\"5\" /></map>")
        assertNull(prefs.standard) // 缺 newCountY，整组放弃
        // 真机验证：抽屉布局改过 5×9 后 standard 键应变成 5×9；缺键则回退 4×7
        assertEquals(GridConfig(), prefs.gridFor(VivoDesktopStyles.STANDARD))
        assertEquals(GridConfig(), prefs.gridFor(VivoDesktopStyles.DRAWER))
    }

    @Test
    fun parseToleratesGarbageAndClamps() {
        assertNull(VivoLayoutPrefs.parse("not xml at all").standard)
        val clamped = VivoLayoutPrefs.parse(
            "<map><int name=\"newCountX\" value=\"99\" /><int name=\"newCountY\" value=\"1\" /></map>",
        )
        assertEquals(8 to 2, clamped.standard)
    }
}
