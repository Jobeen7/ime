package com.jobeen.ime.skin.script

import com.jobeen.ime.base.speech.cloud.PluginContract
import com.jobeen.ime.data.UserFiles
import com.jobeen.ime.skin.store.AtomicFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed [SkinScriptRegistry].
 *
 * Layout under [filesDir]:
 * - skin_scripts/<id>/script.js (script source, UTF-8)
 * - skin_scripts/<id>/meta.json (id, name, enabled)
 *
 * Deliberately no per-script file directory: skin scripts get no file
 * access at all (see [SkinScriptSandbox] security policy), so there is
 * nothing to sandbox on disk. Directory names come only from validated
 * ids, and every resolved dir is checked to stay under the store root
 * (defense in depth against a crafted meta/id reaching the filesystem).
 *
 * Writes are atomic via [AtomicFiles.write] (temp file + fsync +
 * rename) so a half-written meta never parses as a live script.
 * list() only reports entries whose meta parses
 * AND whose script file exists; a half-installed dir stays invisible.
 */
class SkinScriptStore(
    private val filesDir: File,
) : SkinScriptRegistry {

    override fun list(): List<SkinScriptMeta> {
        val root = scriptsDir()
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.mapNotNull { readMeta(it) }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    override fun get(id: String): SkinScript? {
        if (!SkinScriptIds.isValid(id)) return null
        val dir = scriptDir(id) ?: return null
        val meta = readMeta(dir) ?: return null
        val script = File(dir, SCRIPT_FILE)
        if (!script.isFile) return null
        val source = runCatching { script.readText(Charsets.UTF_8) }.getOrNull()
            ?: return null
        return SkinScript(meta, source)
    }

    override fun add(id: String, name: String, source: String): SkinScriptAddResult {
        if (!SkinScriptIds.isValid(id)) return SkinScriptAddResult.InvalidId
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > MAX_NAME_CHARS) {
            return SkinScriptAddResult.InvalidName
        }
        if (source.isBlank()) return SkinScriptAddResult.EmptySource
        if (source.toByteArray(Charsets.UTF_8).size > PluginContract.MAX_SCRIPT_BYTES) {
            return SkinScriptAddResult.TooLarge
        }
        val dir = scriptDir(id) ?: return SkinScriptAddResult.InvalidId
        if (dir.exists() && (File(dir, META_FILE).isFile || File(dir, SCRIPT_FILE).isFile)) {
            return SkinScriptAddResult.AlreadyExists
        }
        dir.mkdirs()
        val meta = SkinScriptMeta(id = id, name = cleanName, enabled = true)
        val ok = runCatching {
            AtomicFiles.write(
                File(dir, SCRIPT_FILE), source.toByteArray(Charsets.UTF_8),
            )
            AtomicFiles.write(
                File(dir, META_FILE), json.encodeToString(meta).toByteArray(Charsets.UTF_8),
            )
        }.isSuccess
        if (!ok) {
            runCatching { dir.deleteRecursively() }
            return SkinScriptAddResult.WriteFailed
        }
        return SkinScriptAddResult.Added(meta)
    }

    override fun remove(id: String): Boolean {
        if (!SkinScriptIds.isValid(id)) return false
        val dir = scriptDir(id) ?: return false
        if (!dir.exists()) return false
        runCatching { dir.deleteRecursively() }
        return !dir.exists()
    }

    override fun setEnabled(id: String, enabled: Boolean): Boolean {
        if (!SkinScriptIds.isValid(id)) return false
        val dir = scriptDir(id) ?: return false
        val meta = readMeta(dir) ?: return false
        if (meta.enabled == enabled) return true
        return runCatching {
            AtomicFiles.write(
                File(dir, META_FILE),
                json.encodeToString(meta.copy(enabled = enabled)).toByteArray(Charsets.UTF_8),
            )
            true
        }.getOrDefault(false)
    }

    private fun scriptsDir(): File = File(filesDir, SCRIPTS_DIR)

    /** Resolved script dir, or null when it would escape the store root. */
    private fun scriptDir(id: String): File? {
        val root = scriptsDir().canonicalFile
        val dir = File(root, id).canonicalFile
        if (dir.parentFile != root) return null
        return dir
    }

    private fun readMeta(dir: File): SkinScriptMeta? {
        val file = File(dir, META_FILE)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<SkinScriptMeta>(file.readText(Charsets.UTF_8)) }
            .getOrNull()
    }

    companion object {
        const val SCRIPTS_DIR = UserFiles.SKIN_SCRIPTS_DIR_NAME
        const val SCRIPT_FILE = "script.js"
        const val META_FILE = "meta.json"
        const val MAX_NAME_CHARS = 128

        private val json = Json { ignoreUnknownKeys = true }
    }
}
