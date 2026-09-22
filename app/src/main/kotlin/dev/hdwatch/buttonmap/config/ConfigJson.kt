package dev.hdwatch.buttonmap.config

import dev.hdwatch.buttonmap.input.Symbol
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec, schema version 2 (also reads v1 and migrates).
 *
 * ```
 * {
 *   "version": 2,
 *   "settings": { ... },
 *   "activeProfile": "keys",
 *   "profiles": [
 *     { "id": "keys", "name": "直控", "kind": "KEYS",
 *       "rotateThreshold": 3,
 *       "single": { "U": {"key":"W"}, "CW": {"mouse":{"wheel":-1}} },
 *       "macros": [] },
 *     { "id": "macro", "name": "宏", "kind": "MACRO",
 *       "single": { "U": {"key":"W"} },
 *       "macros": [ { "id":"resupply","name":"补给仓","seq":"U R D D D CW",
 *                     "steps":[{"key":"1"},{"delay":120},{"click":"left"}] } ] }
 *   ]
 * }
 * ```
 *
 * v1 files (top-level single/macros) are accepted and wrapped into a KEYS
 * profile plus a MACRO profile named 宏; writing always emits v2.
 * Step/sequence grammar is documented in decodeStep/decodeSequence.
 */
object ConfigJson {

    class ConfigError(val problems: List<String>) :
        Exception(problems.joinToString("; "))

    fun decode(text: String): Config {
        val problems = mutableListOf<String>()
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw ConfigError(listOf("root is not a JSON object: ${it.message}")) }

        val version = root.optInt("version", 1)
        if (version > Config.CURRENT_VERSION) {
            problems += "unsupported config version $version (app supports <= ${Config.CURRENT_VERSION})"
        }
        val settings = decodeSettings(root.optJSONObject("settings"), problems)

        val profiles = when {
            root.has("profiles") -> decodeProfiles(root.optJSONArray("profiles"), problems)
            version == 1 -> migrateV1(root, problems)
            else -> {
                problems += "no profiles section"
                emptyList()
            }
        }

        var activeId = root.optString("activeProfile", "")
        if (profiles.none { it.id == activeId }) {
            activeId = profiles.firstOrNull()?.id ?: ""
        }

        if (problems.isNotEmpty()) throw ConfigError(problems)
        if (profiles.isEmpty()) throw ConfigError(listOf("config has no profiles"))
        return Config(version = Config.CURRENT_VERSION, settings = settings, profiles = profiles, activeProfileId = activeId)
    }

    fun encode(config: Config): String {
        val root = JSONObject()
        root.put("version", Config.CURRENT_VERSION)
        root.put("settings", JSONObject().apply {
            put("rotateThreshold", config.settings.rotateThreshold)
            put("invertRotation", config.settings.invertRotation)
            put("sequenceTimeoutMs", config.settings.sequenceTimeoutMs)
            put("textDelayMs", config.settings.textDelayMs)
            put("keyJitterMs", config.settings.keyJitterMs)
            put("rotaryLatchDebounceMs", config.settings.rotaryLatchDebounceMs)
            put("captureUnknownInput", config.settings.captureUnknownInput)
            put("forceLoggingTransport", config.settings.forceLoggingTransport)
            put("gestureSensorsEnabled", config.settings.gestureSensorsEnabled)
            put("keepAliveService", config.settings.keepAliveService)
            put("vibrationEnabled", config.settings.vibrationEnabled)
            put("sensorToSymbol", JSONObject(config.settings.sensorToSymbol as Map<*, *>))
        })
        root.put("activeProfile", config.activeProfileId)
        root.put("profiles", JSONArray().apply {
            config.profiles.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("kind", p.kind.name)
                    p.rotateThreshold?.let { put("rotateThreshold", it) }
                    put("single", JSONObject().apply {
                        p.single.forEach { (sym, step) -> put(sym.code, stepJson(step)) }
                    })
                    if (p.ringSteps.isNotEmpty()) {
                        put("ring", JSONObject().apply {
                            p.ringSteps.forEach { (slot, step) -> put(slot, stepJson(step)) }
                        })
                    }
                    put("macros", JSONArray().apply {
                        p.macros.forEach { m ->
                            put(JSONObject().apply {
                                put("id", m.id)
                                put("name", m.name)
                                put("seq", JSONArray(m.sequence.map { it.code }))
                                put("enabled", m.enabled)
                                put("repeat", m.repeat)
                                put("steps", JSONArray().apply { m.steps.forEach { put(stepJson(it)) } })
                            })
                        }
                    })
                })
            }
        })
        return root.toString(2)
    }

    // ------------------------------------------------------------------ decode

    private fun decodeSettings(o: JSONObject?, problems: MutableList<String>): Settings {
        if (o == null) return Settings()
        var s = Settings()
        o.keys().forEach { k ->
            try {
                when (k) {
                    "rotateThreshold" -> s = s.copy(rotateThreshold = o.getInt(k))
                    "invertRotation" -> s = s.copy(invertRotation = o.getBoolean(k))
                    "sequenceTimeoutMs" -> s = s.copy(sequenceTimeoutMs = o.getLong(k))
                    "textDelayMs" -> s = s.copy(textDelayMs = o.getLong(k))
                    "keyJitterMs" -> s = s.copy(keyJitterMs = o.getLong(k))
                    "rotaryLatchDebounceMs" -> s = s.copy(rotaryLatchDebounceMs = o.getLong(k))
                    "captureUnknownInput" -> s = s.copy(captureUnknownInput = o.getBoolean(k))
                    "forceLoggingTransport" -> s = s.copy(forceLoggingTransport = o.getBoolean(k))
                    "gestureSensorsEnabled" -> s = s.copy(gestureSensorsEnabled = o.getBoolean(k))
                    "keepAliveService" -> s = s.copy(keepAliveService = o.getBoolean(k))
                    "vibrationEnabled" -> s = s.copy(vibrationEnabled = o.getBoolean(k))
                    "sensorToSymbol" -> {
                        val j = o.getJSONObject(k)
                        s = s.copy(sensorToSymbol = j.keys().asSequence().associateWith { j.getString(it) })
                    }
                    else -> problems += "settings: unknown key '$k' ignored"
                }
            } catch (e: Exception) {
                problems += "settings.$k: ${e.message}"
            }
        }
        if (s.rotateThreshold < 1) s = s.copy(rotateThreshold = 1)
        return s
    }

    private fun decodeProfiles(a: JSONArray?, problems: MutableList<String>): List<Profile> {
        if (a == null) return emptyList()
        val out = mutableListOf<Profile>()
        for (i in 0 until a.length()) {
            val o = runCatching { a.getJSONObject(i) }.getOrElse {
                problems += "profiles[$i]: not an object"
                continue
            }
            val id = o.optString("id").ifBlank {
                problems += "profiles[$i]: missing id"
                continue
            }
            val name = o.optString("name").ifBlank { id }
            val kind = when (o.optString("kind", "KEYS").uppercase()) {
                "KEYS" -> ProfileKind.KEYS
                "MACRO" -> ProfileKind.MACRO
                else -> {
                    problems += "profiles[$i]($id): unknown kind, using KEYS"
                    ProfileKind.KEYS
                }
            }
            val threshold = if (o.has("rotateThreshold")) o.optInt("rotateThreshold").takeIf { it >= 1 } else null
            val single = decodeSingleMap(o.optJSONObject("single"), "profiles[$i]($id)", problems)
            val macros = decodeMacros(o.optJSONArray("macros"), "profiles[$i]($id)", problems)
            val ring = LinkedHashMap<String, Step>()
            o.optJSONObject("ring")?.keys()?.forEach { slot ->
                val obj = runCatching { o.getJSONObject("ring").getJSONObject(slot) }.getOrNull()
                if (obj == null) {
                    problems += "profiles[$i]($id) ring.$slot: not an object"
                } else {
                    runCatching { decodeStep(obj) }
                        .onSuccess { ring[slot] = it }
                        .onFailure { problems += "profiles[$i]($id) ring.$slot: ${it.message}" }
                }
            }
            out += Profile(id, name, kind, single, macros, threshold, ring)
        }
        return out
    }

    private fun decodeSingleMap(o: JSONObject?, where: String, problems: MutableList<String>): Map<Symbol, Step> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<Symbol, Step>()
        o.keys().forEach { k ->
            val sym = Symbol.fromCode(k)
            if (sym == null) {
                problems += "$where.single: unknown symbol '$k'"
                return@forEach
            }
            try {
                out[sym] = decodeStep(o.getJSONObject(k))
            } catch (e: Exception) {
                problems += "$where.single.$k: ${e.message}"
            }
        }
        return out
    }

    private fun decodeMacros(a: JSONArray?, where: String, problems: MutableList<String>): List<Macro> {
        if (a == null) return emptyList()
        val out = mutableListOf<Macro>()
        for (i in 0 until a.length()) {
            val m = runCatching { a.getJSONObject(i) }.getOrElse {
                problems += "$where.macros[$i]: not an object"
                continue
            }
            val id = m.optString("id").ifBlank {
                problems += "$where.macros[$i]: missing id"
                continue
            }
            val name = m.optString("name").ifBlank { id }
            val seq = decodeSequence(m.opt("seq")) { bad ->
                problems += "$where.macros[$i]($id).seq: $bad"
            }
            if (seq.isEmpty()) {
                problems += "$where.macros[$i]($id): empty sequence"
                continue
            }
            val steps = mutableListOf<Step>()
            val arr = m.optJSONArray("steps")
            if (arr == null) {
                problems += "$where.macros[$i]($id): missing steps"
            } else {
                for (j in 0 until arr.length()) {
                    try {
                        steps += decodeStep(arr.getJSONObject(j))
                    } catch (e: Exception) {
                        problems += "$where.macros[$i]($id).steps[$j]: ${e.message}"
                    }
                }
            }
            out += Macro(
                id = id,
                name = name,
                sequence = seq,
                steps = steps,
                enabled = m.optBoolean("enabled", true),
                repeat = m.optInt("repeat", 1).coerceAtLeast(1),
            )
        }
        return out
    }

    private fun migrateV1(root: JSONObject, problems: MutableList<String>): List<Profile> {
        val single = decodeSingleMap(root.optJSONObject("single"), "v1", problems)
        val macros = decodeMacros(root.optJSONArray("macros"), "v1", problems)
        val keys = Profile("keys", "直控", ProfileKind.KEYS, single = single)
        return if (macros.isEmpty()) listOf(keys)
        else listOf(keys, Profile("macros", "宏", ProfileKind.MACRO, single = single, macros = macros))
    }

    private fun decodeSequence(node: Any?, report: (String) -> Unit): List<Symbol> {
        val tokens: List<String> = when (node) {
            is JSONArray -> (0 until node.length()).map { node.optString(it) }
            is String -> node.split(' ', ',', '|').map { it.trim() }.filter { it.isNotEmpty() }
            null -> emptyList()
            else -> {
                report("must be a string or an array")
                emptyList()
            }
        }
        return tokens.mapNotNull { t ->
            Symbol.fromCode(t) ?: run {
                report("unknown symbol '$t'")
                null
            }
        }
    }

    private fun decodeStep(o: JSONObject): Step {
        o.optString("combo").takeIf { it.isNotBlank() }?.let { raw ->
            val parts = raw.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "empty combo" }
            val mods = parts.dropLast(1).map { modOf(it) }.toSet()
            return Step.TapKey(parts.last(), mods)
        }
        o.optString("key").takeIf { it.isNotBlank() }?.let { name ->
            return Step.TapKey(name, optMods(o.optJSONArray("mods")))
        }
        o.optString("keydown").takeIf { it.isNotBlank() }?.let {
            return Step.KeyDown(it, optMods(o.optJSONArray("mods")))
        }
        o.optString("keyup").takeIf { it.isNotBlank() }?.let {
            return Step.KeyUp(it, optMods(o.optJSONArray("mods")))
        }
        o.optString("hold").takeIf { it.isNotBlank() }?.let { name ->
            return Step.Hold(name, optMods(o.optJSONArray("mods")))
        }
        o.optString("text").takeIf { it.isNotEmpty() }?.let {
            return Step.TypeText(it)
        }
        if (o.has("delay")) return Step.Wait(o.getLong("delay"))
        if (o.has("hold")) return Step.Wait(o.getLong("hold"))
        if (o.optBoolean("mouseRelease")) return Step.MouseRelease
        o.optString("click").takeIf { it.isNotBlank() }?.let {
            return Step.MouseClick(it, o.optInt("count", 1).coerceAtLeast(1))
        }
        o.optJSONObject("mouse")?.let { m ->
            val buttons = m.optString("buttons").split('+').map { it.trim() }.filter { it.isNotEmpty() }
            return Step.Mouse(
                buttons = buttons.toSet(),
                dx = m.optInt("dx", 0),
                dy = m.optInt("dy", 0),
                wheel = m.optInt("wheel", 0),
            )
        }
        o.optString("consumer").takeIf { it.isNotBlank() }?.let {
            return Step.ConsumerKey(it)
        }
        throw IllegalArgumentException("unrecognized step: $o")
    }

    private fun optMods(a: JSONArray?): Set<KeyMod> =
        a?.let { (0 until it.length()).map { i -> modOf(it.getString(i)) }.toSet() } ?: emptySet()

    private fun modOf(raw: String): KeyMod = when (raw.lowercase()) {
        "ctrl", "control" -> KeyMod.CTRL
        "shift" -> KeyMod.SHIFT
        "alt", "option" -> KeyMod.ALT
        "gui", "win", "meta", "cmd", "super" -> KeyMod.GUI
        else -> throw IllegalArgumentException("unknown modifier '$raw'")
    }

    // ------------------------------------------------------------------ encode

    private fun stepJson(step: Step): JSONObject = when (step) {
        is Step.TapKey -> JSONObject().apply {
            put("key", step.key)
            if (step.mods.isNotEmpty()) put("mods", JSONArray(step.mods.map { it.name.lowercase() }))
        }
        is Step.KeyDown -> JSONObject().apply {
            put("keydown", step.key)
            if (step.mods.isNotEmpty()) put("mods", JSONArray(step.mods.map { it.name.lowercase() }))
        }
        is Step.KeyUp -> JSONObject().apply {
            put("keyup", step.key)
            if (step.mods.isNotEmpty()) put("mods", JSONArray(step.mods.map { it.name.lowercase() }))
        }
        is Step.Hold -> JSONObject().apply {
            put("hold", step.key)
            if (step.mods.isNotEmpty()) put("mods", JSONArray(step.mods.map { it.name.lowercase() }))
        }
        is Step.TypeText -> JSONObject().put("text", step.text)
        is Step.Wait -> JSONObject().put("delay", step.ms)
        is Step.Mouse -> JSONObject().apply {
            put("mouse", JSONObject().apply {
                if (step.buttons.isNotEmpty()) put("buttons", step.buttons.joinToString("+"))
                if (step.dx != 0) put("dx", step.dx)
                if (step.dy != 0) put("dy", step.dy)
                if (step.wheel != 0) put("wheel", step.wheel)
            })
        }
        is Step.MouseClick -> JSONObject().apply {
            put("click", step.button)
            put("count", step.count)
        }
        Step.MouseRelease -> JSONObject().put("mouseRelease", true)
        is Step.ConsumerKey -> JSONObject().put("consumer", step.usage)
    }
}
