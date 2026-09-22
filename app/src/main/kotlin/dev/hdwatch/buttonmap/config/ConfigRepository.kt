package dev.hdwatch.buttonmap.config

import android.app.Application
import android.content.Context
import android.net.Uri
import dev.hdwatch.buttonmap.R
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

    private val _status = MutableStateFlow(app.getString(R.string.set_status_not_loaded))
    val status: StateFlow<String> = _status.asStateFlow()

    private val _statusIsError = MutableStateFlow(false)

    /** True when [status] reports a read/parse/write failure; drives the danger color. */
    val statusIsError: StateFlow<Boolean> = _statusIsError.asStateFlow()

    /**
     * Sole writer of [status]: the flag travels with the text so screens never have to
     * re-derive "is this an error" from the localized string they are rendering.
     */
    private fun setStatus(text: String, isError: Boolean = false) {
        _status.value = text
        _statusIsError.value = isError
    }

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
            setStatus(app.getString(R.string.set_status_import_read_failed, uri), isError = true)
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
            setStatus(app.getString(R.string.set_status_export_failed, it.message), isError = true)
            false
        }
    }

    fun copyToExternal(): File? {
        val target = externalFile ?: return null
        return runCatching {
            target.writeText(ConfigJson.encode(_config.value))
            prefs.edit().putLong(KEY_EXT_STAMP, target.lastModified()).apply()
            setStatus(app.getString(R.string.set_status_exported_to, target.absolutePath))
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
        val ok = storeAndActivate(text, app.getString(R.string.set_status_external_file, ext.name))
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
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        setStatus(app.getString(R.string.set_status_saved, stamp))
    }

    fun updateSettings(transform: (Settings) -> Settings) = update { it.copy(settings = transform(it.settings)) }

    fun setSingle(symbol: Symbol, step: Step?) = update { cfg ->
        cfg.mapActive { p ->
            val map = LinkedHashMap(p.single)
            if (step == null) map.remove(symbol) else map[symbol] = step
            p.copy(single = map)
        }
    }

    fun setMacroEnabled(id: String, enabled: Boolean) = update { cfg ->
        cfg.mapActive { p ->
            p.copy(macros = p.macros.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    fun deleteMacro(id: String) = update { cfg ->
        cfg.mapActive { p -> p.copy(macros = p.macros.filterNot { it.id == id }) }
    }

    /** Point all on-watch editors and the engine at another profile. */
    fun setActiveProfile(id: String) = update { it.withActive(id) }

    /** Pad center-card gesture: hop to the next profile, wrapping. */
    fun cycleActiveProfile() = update { cfg ->
        if (cfg.profiles.size < 2) return@update cfg
        val idx = cfg.profiles.indexOfFirst { it.id == cfg.activeProfile.id }
        cfg.withActive(cfg.profiles[(idx + 1) % cfg.profiles.size].id)
    }

    fun updateProfile(id: String, transform: (Profile) -> Profile) = update { cfg ->
        cfg.copy(profiles = cfg.profiles.map { if (it.id == id) transform(it) else it })
    }

    /**
     * Install a bundled profile pack (the five-tap easter egg): a profile with
     * the same id is replaced, and the pack becomes the active surface.
     */
    fun installBundledPack(assetName: String): Boolean = try {
        val text = app.assets.open(assetName).bufferedReader().use { it.readText() }
        val profile = ConfigJson.decode(text).profiles.firstOrNull()
            ?: throw IllegalStateException("pack has no profile")
        val packCfg = ConfigJson.decode(text)
        update { cfg ->
            cfg.copy(
                profiles = cfg.profiles.filterNot { it.id == profile.id } + profile,
                activeProfileId = profile.id,
                // Take the pack's gesture wiring, keep the user's own calibration.
                settings = cfg.settings.copy(
                    gestureSensorsEnabled = packCfg.settings.gestureSensorsEnabled ||
                        cfg.settings.gestureSensorsEnabled,
                    sensorToSymbol = cfg.settings.sensorToSymbol + packCfg.settings.sensorToSymbol,
                ),
            )
        }
        setStatus(app.getString(R.string.set_status_pack_unlocked, profile.name, profile.macros.size))
        ring.log("CFG  pack $assetName installed (${profile.macros.size} macros)")
        true
    } catch (e: Exception) {
        setStatus(app.getString(R.string.set_status_unlock_failed, e.message), isError = true)
        false
    }

    // -------------------------------------------------------------- internals

    /**
     * Earlier builds seeded a handful of demo macros (the game-name ones).
     * They are ours, not the user's: strip them wherever they survive so the
     * shipped default is the neutral set.
     */
    private fun stripBundledDemoMacros(cfg: Config): Config {
        val legacy = setOf("demo1", "demo2", "demo3", "demo4", "resupply", "eagle", "shield", "mine")
        if (cfg.profiles.none { p -> p.macros.any { it.id in legacy } }) return cfg
        ring.log("CFG  dropped bundled demo macros")
        return cfg.copy(
            profiles = cfg.profiles.map { p -> p.copy(macros = p.macros.filterNot { it.id in legacy }) },
        )
    }

    private fun seedFromAssets() {
        val text = runCatching {
            app.assets.open(assetSeedName).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        // The seed carries language-neutral names for its two built-in
        // profiles; give them the local names before they ever reach the UI.
        val localized = runCatching {
            val cfg = ConfigJson.decode(text)
            ConfigJson.encode(
                cfg.copy(
                    profiles = cfg.profiles.map { p ->
                        when (p.id) {
                            "keys" -> p.copy(name = app.getString(R.string.app_profile_direct))
                            "macro" -> p.copy(name = app.getString(R.string.app_profile_macro))
                            else -> p
                        }
                    },
                ),
            )
        }.getOrDefault(text)
        runCatching { configFile.writeText(localized) }
        ring.log("CFG  seeded defaults")
    }

    private fun loadFile(source: String) {
        val text = runCatching { configFile.readText() }.getOrNull()
        if (text == null) {
            setStatus(app.getString(R.string.set_status_config_missing), isError = true)
            seedFromAssets()
            runCatching { _config.value = ConfigJson.decode(configFile.readText()) }
            return
        }
        try {
            val decoded = stripBundledDemoMacros(ConfigJson.decode(text))
            _config.value = decoded
            persist(decoded)
            setStatus(app.getString(R.string.set_status_loaded, source))
            ring.log("CFG  loaded from $source: ${_config.value.profiles.size} profiles, " +
                "active=${_config.value.activeProfile.name}")
        } catch (e: ConfigJson.ConfigError) {
            setStatus(
                app.getString(R.string.set_status_config_error, e.problems.take(3).joinToString(" | ")),
                isError = true,
            )
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
            setStatus(app.getString(R.string.set_status_imported, label))
            ring.log("CFG  imported ($label): ${decoded.profiles.size} profiles")
            return true
        } catch (e: ConfigJson.ConfigError) {
            setStatus(
                app.getString(R.string.set_status_import_rejected, e.problems.take(4).joinToString(" | ")),
                isError = true,
            )
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
