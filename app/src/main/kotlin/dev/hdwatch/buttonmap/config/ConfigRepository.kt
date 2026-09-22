package dev.hdwatch.buttonmap.config

import android.app.Application
import android.content.Context
import android.net.Uri
import dev.hdwatch.buttonmap.input.Symbol
import dev.hdwatch.buttonmap.hid.ReportRing
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Config lifecycle:
 *  - canonical file: filesDir/config.json (survives updates, edited on-watch)
 *  - seed: assets/default_config.json on first launch
 *  - external drop: <app external files>/hdmap.json — adb-pushable without
 *    any permission; auto-imported whenever its timestamp is newer than the
 *    last import stamp
 *  - SAF import/export for non-dev users
 */
class ConfigRepository(
    private val app: Application,
    private val ring: ReportRing,
) {

    private val prefs = app.getSharedPreferences("hdmap_cfg", Context.MODE_PRIVATE)

    private val configFile = File(app.filesDir, "config.json")
    private val assetSeedName = "default_config.json"

    val externalFile: File?
        get() = app.getExternalFilesDir(null)?.let { File(it, "hdmap.json") }

    private val _config = MutableStateFlow(Config.EMPTY)
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _status = MutableStateFlow("未加载")
    val status: StateFlow<String> = _status.asStateFlow()

    init {
        if (!configFile.exists()) {
            seedFromAssets()
        }
        loadFile("startup")
        checkExternalUpdate(silent = true)
    }

    // ------------------------------------------------------------- public API

    /** Reload canonical file (menu action). */
    fun reload() = loadFile("manual")

    /** Import from a SAF-picked document; keeps no persistent URI (import is manual). */
    fun importFrom(uri: Uri) {
        val text = runCatching {
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            _status.value = "导入失败：无法读取 $uri"
            ring.log("CFG  import failed uri=$uri")
            return
        }
        storeAndActivate(text, "SAF ${uri.lastPathSegment ?: "document"}")
    }

    fun exportTo(uri: Uri): Boolean {
        val text = ConfigJson.encode(_config.value)
        return runCatching {
            app.contentResolver.openOutputStream(uri, "wt").use { out ->
                out!!.write(text.toByteArray())
            }
            true
        }.getOrElse {
            _status.value = "导出失败：${it.message}"
            false
        }
    }

    fun copyToExternal(): File? {
        val target = externalFile ?: return null
        return runCatching {
            target.writeText(ConfigJson.encode(_config.value))
            prefs.edit().putLong(KEY_EXT_STAMP, target.lastModified()).apply()
            _status.value = "已导出到 ${target.absolutePath}"
            target
        }.getOrNull()
    }

    /** adb-pushed hdmap.json detection; returns true when it was pulled in. */
    fun checkExternalUpdate(silent: Boolean = false): Boolean {
        val ext = externalFile ?: return false
        if (!ext.exists()) return false
        val seen = prefs.getLong(KEY_EXT_STAMP, 0L)
        if (ext.lastModified() <= seen) return false
        val text = runCatching { ext.readText() }.getOrNull() ?: return false
        val ok = storeAndActivate(text, "外部文件 ${ext.name}")
        // Only consume the timestamp on success: a rejected file must be
        // retried after the user fixes it in place.
        if (ok) {
            prefs.edit().putLong(KEY_EXT_STAMP, ext.lastModified()).apply()
            if (!silent) ring.log("CFG  external import applied")
        }
        return ok
    }

    fun update(transform: (Config) -> Config) {
        val next = transform(_config.value)
        persist(next)
        _config.value = next
        _status.value = "已保存 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"
    }

    fun updateSettings(transform: (Settings) -> Settings) = update { it.copy(settings = transform(it.settings)) }

    fun setSingle(symbol: Symbol, step: Step?) = update { cfg ->
        val map = LinkedHashMap(cfg.single)
        if (step == null) map.remove(symbol) else map[symbol] = step
        cfg.copy(single = map)
    }

    fun setMacroEnabled(id: String, enabled: Boolean) = update { cfg ->
        cfg.copy(macros = cfg.macros.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun deleteMacro(id: String) = update { cfg ->
        cfg.copy(macros = cfg.macros.filterNot { it.id == id })
    }

    // -------------------------------------------------------------- internals
    private fun seedFromAssets() {
        val text = runCatching {
            app.assets.open(assetSeedName).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        runCatching { configFile.writeText(text) }
        ring.log("CFG  seeded defaults")
    }

    private fun loadFile(source: String) {
        val text = runCatching { configFile.readText() }.getOrNull()
        if (text == null) {
            _status.value = "配置文件缺失，使用内置默认"
            seedFromAssets()
            runCatching { _config.value = ConfigJson.decode(configFile.readText()) }
            return
        }
        try {
            _config.value = ConfigJson.decode(text)
            _status.value = "已加载 ($source)"
            ring.log("CFG  loaded from $source: ${_config.value.macros.size} macros, " +
                "${_config.value.single.size} singles")
        } catch (e: ConfigJson.ConfigError) {
            _status.value = "配置错误：${e.problems.take(3).joinToString(" | ")}"
            ring.log("CFG  decode failed: ${e.problems}")
            // Keep running on defaults so the watch stays usable while editing.
            if (_config.value === Config.EMPTY) {
                seedFromAssets()
                runCatching { _config.value = ConfigJson.decode(configFile.readText()) }
            }
        }
    }

    private fun storeAndActivate(text: String, label: String): Boolean {
        try {
            val decoded = ConfigJson.decode(text)
            runCatching { configFile.writeText(text) }
            _config.value = decoded
            _status.value = "已导入：$label"
            ring.log("CFG  imported ($label): ${decoded.macros.size} macros")
            return true
        } catch (e: ConfigJson.ConfigError) {
            _status.value = "导入拒绝：${e.problems.take(4).joinToString(" | ")}"
            ring.log("CFG  import rejected ($label): ${e.problems}")
            return false
        }
    }

    private fun persist(cfg: Config) {
        runCatching { configFile.writeText(ConfigJson.encode(cfg)) }
            .onFailure { ring.log("CFG  persist failed: ${it.message}") }
    }

    private companion object {
        const val KEY_EXT_STAMP = "ext_stamp"
    }
}
