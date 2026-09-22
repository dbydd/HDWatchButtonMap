package dev.hdwatch.buttonmap.config

import dev.hdwatch.buttonmap.input.Symbol
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for the external config file (schema version 1).
 *
 * ```
 * {
 *   "version": 1,
 *   "settings": { "rotateThreshold": 30, "sequenceTimeoutMs": 3000, ... },
 *   "single": { "U": {"key":"W"}, "CW": {"mouse":{"wheel":1}} },
 *   "macros": [
 *     { "id": "resupply", "name": "补给仓", "seq": "U R D D D CW",
 *       "enabled": true, "repeat": 1,
 *       "steps": [ {"key":"1"}, {"delay":100}, {"click":"left"} ] }
 *   ]
 * }
 * ```
 *
 * Step forms:
 *   {"key":"W"} | {"combo":"ctrl+shift+esc"} | {"keydown":"W"} | {"keyup":"W"}
 *   {"text":"hello"} | {"delay":120}
 *   {"mouse":{"buttons":"left","dx":0,"dy":0,"wheel":-1}} | {"click":"left","count":2}
 *   {"mouseRelease":true} | {"consumer":"PLAY_PAUSE"}
 * Sequence may be a space-separated string or an array of codes.
 * Decode is strict: every problem is collected and reported together.
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
        val single = decodeSingle(root.optJSONObject("single"), problems)
        val macros = decodeMacros(root.optJSONArray("macros"), problems)

        if (problems.isNotEmpty()) throw ConfigError(problems)
        return Config(version = version, settings = settings, single = single, macros = macros)
    }

    fun encode(config: Config): String {
        val root = JSONObject()
        root.put("version", config.version)
        root.put("settings", JSONObject().apply {
            put("rotateThreshold", config.settings.rotateThreshold)
            put("invertRotation", config.settings.invertRotation)
            put("sequenceTimeoutMs", config.settings.sequenceTimeoutMs)
            put("textDelayMs", config.settings.textDelayMs)
            put("captureUnknownInput", config.settings.captureUnknownInput)
            put("forceLoggingTransport", config.settings.forceLoggingTransport)
            put("gestureSensorsEnabled", config.settings.gestureSensorsEnabled)
            put("sensorToSymbol", JSONObject(config.settings.sensorToSymbol as Map<*, *>))
        })
        root.put("single", JSONObject().apply {
            config.single.forEach { (sym, step) -> put(sym.code, stepJson(step)) }
        })
        root.put("macros", JSONArray().apply {
            config.macros.forEach { m ->
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
                    "captureUnknownInput" -> s = s.copy(captureUnknownInput = o.getBoolean(k))
                    "forceLoggingTransport" -> s = s.copy(forceLoggingTransport = o.getBoolean(k))
                    "gestureSensorsEnabled" -> s = s.copy(gestureSensorsEnabled = o.getBoolean(k))
                    "sensorToSymbol" -> {
                        val map = o.getJSONObject(k).let { j ->
                            j.keys().asSequence().associateWith { j.getString(it) }
                        }
                        s = s.copy(sensorToSymbol = map)
                    }
                    else -> problems += "settings: unknown key '$k' ignored"
                }
            } catch (e: Exception) {
                problems += "settings.$k: ${e.message}"
            }
        }
        if (s.rotateThreshold < 1) {
            s = s.copy(rotateThreshold = 1)
        }
        return s
    }

    private fun decodeSingle(o: JSONObject?, problems: MutableList<String>): Map<Symbol, Step> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<Symbol, Step>()
        o.keys().forEach { k ->
            val sym = Symbol.fromCode(k)
            if (sym == null) {
                problems += "single: unknown symbol '$k'"
                return@forEach
            }
            try {
                out[sym] = decodeStep(o.getJSONObject(k))
            } catch (e: Exception) {
                problems += "single.$k: ${e.message}"
            }
        }
        return out
    }

    private fun decodeMacros(a: JSONArray?, problems: MutableList<String>): List<Macro> {
        if (a == null) return emptyList()
        val out = mutableListOf<Macro>()
        for (i in 0 until a.length()) {
            val m = runCatching { a.getJSONObject(i) }.getOrElse {
                problems += "macros[$i]: not an object"
                continue
            }
            val id = m.optString("id").ifBlank {
                problems += "macros[$i]: missing id"
                continue
            }
            val name = m.optString("name").ifBlank { id }
            val seq = decodeSequence(m.opt("seq")) { bad ->
                problems += "macros[$i]($id).seq: $bad"
            }
            if (seq.isEmpty()) {
                problems += "macros[$i]($id): empty sequence"
                continue
            }
            val steps = mutableListOf<Step>()
            val arr = m.optJSONArray("steps")
            if (arr == null) {
                problems += "macros[$i]($id): missing steps"
            } else {
                for (j in 0 until arr.length()) {
                    try {
                        steps += decodeStep(arr.getJSONObject(j))
                    } catch (e: Exception) {
                        problems += "macros[$i]($id).steps[$j]: ${e.message}"
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
            val mods = optMods(o.optJSONArray("mods"))
            return Step.TapKey(name, mods)
        }
        o.optString("keydown").takeIf { it.isNotBlank() }?.let {
            return Step.KeyDown(it, optMods(o.optJSONArray("mods")))
        }
        o.optString("keyup").takeIf { it.isNotBlank() }?.let {
            return Step.KeyUp(it, optMods(o.optJSONArray("mods")))
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
