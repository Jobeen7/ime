package com.jobeen.ime.skin.script

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manifest seam test: the JVM suite cannot launch an Activity, so the
 * closest checkable seam is the manifest text itself — it must declare
 * the management Activity, otherwise starting it crashes at runtime.
 * The walk-up lookup tolerates the suite running from the module dir
 * or the repo root.
 */
class SkinScriptManifestTest {

    @Test
    fun manifest_declaresSkinScriptActivity() {
        val manifest = findManifest()
        assertTrue(
            "AndroidManifest.xml not found under ${File(System.getProperty("user.dir")).absolutePath} ancestors",
            manifest != null,
        )
        val text = manifest!!.readText()
        assertTrue(
            "manifest must declare .skin.script.SkinScriptActivity",
            text.contains(".skin.script.SkinScriptActivity"),
        )
        assertTrue(
            "script management Activity must not be exported",
            // The entry sits between the cloud-plugins entry and the
            // permission Activity; exported=false is asserted on the
            // entry block, not on the whole file (other entries differ).
            manifestEntry(text).contains("android:exported=\"false\""),
        )
    }

    private fun manifestEntry(text: String): String {
        val start = text.indexOf(".skin.script.SkinScriptActivity")
        val open = text.lastIndexOf("<activity", start)
        val close = text.indexOf("/>", start)
        assertTrue("manifest entry block not found", open >= 0 && close > open)
        return text.substring(open, close)
    }

    private fun findManifest(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val direct = File(dir, "app/src/main/AndroidManifest.xml")
            if (direct.isFile) return direct
            val module = File(dir, "src/main/AndroidManifest.xml")
            if (module.isFile) return module
            dir = dir.parentFile
        }
        return null
    }
}
