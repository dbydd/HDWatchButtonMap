package dev.hdwatch.buttonmap.config

import dev.hdwatch.buttonmap.input.Symbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigJsonTest {

    @Test
    fun v2RoundTripPreservesAllSettingsAndSteps() {
        val original = Config(
            settings = Settings(
                rotateThreshold = 2,
                rotaryScale = 2.25f,
                invertRotation = false,
                sequenceTimeoutMs = 4500,
                textDelayMs = 30,
                rotaryLatchDebounceMs = 500,
                keyJitterMs = 15,
                keepScreenOn = false,
                showDirectKeyLabels = false,
                theme = ThemePreset.PHOSPHOR,
                vibrationEnabled = false,
                sensorToSymbol = mapOf("imu:shake" to "G1"),
            ),
            activeProfileId = "m1",
            profiles = listOf(
                Profile(
                    id = "m1",
                    name = "Tactical",
                    kind = ProfileKind.MACRO,
                    rotateThreshold = 1,
                    single = mapOf(
                        Symbol.CROWN_CCW to Step.Hold("CTRL"),
                        Symbol.CROWN_CW to Step.Mouse(wheel = -1),
                        Symbol.STEM to Step.MouseClick("left", 2),
                        Symbol.GESTURE_1 to Step.ConsumerKey("VOL_UP"),
                    ),
                    macros = listOf(
                        Macro(
                            id = "ops",
                            name = "Orbital",
                            sequence = listOf(Symbol.RIGHT, Symbol.RIGHT, Symbol.UP, Symbol.CROWN_CW),
                            steps = listOf(
                                Step.KeyDown("CTRL"),
                                Step.TapKey("D"),
                                Step.Wait(50),
                                Step.KeyUp("CTRL"),
                            ),
                            repeat = 2,
                        ),
                    ),
                ),
            ),
        )

        val encoded = ConfigJson.encode(original)
        val decoded = ConfigJson.decode(encoded)

        assertEquals(original.activeProfileId, decoded.activeProfileId)
        assertEquals(2, decoded.settings.rotateThreshold)
        assertEquals(2.25f, decoded.settings.rotaryScale, 0.001f)
        assertFalse(decoded.settings.invertRotation)
        assertEquals(500L, decoded.settings.rotaryLatchDebounceMs)
        assertFalse(decoded.settings.keepScreenOn)
        assertFalse(decoded.settings.showDirectKeyLabels)
        assertEquals(ThemePreset.PHOSPHOR, decoded.settings.theme)
        assertEquals(original.profiles, decoded.profiles)
    }

    @Test
    fun legacyV2ConfigWithoutNewFieldsLoadsWithDefaults() {
        val legacyJson = """
            {
              "version": 2,
              "settings": { "rotateThreshold": 3, "invertRotation": true },
              "activeProfile": "keys",
              "profiles": [
                { "id": "keys", "name": "Direct", "kind": "KEYS", "single": { "U": { "key": "W" } }, "macros": [] }
              ]
            }
        """.trimIndent()

        val cfg = ConfigJson.decode(legacyJson)
        assertEquals(1.0f, cfg.settings.rotaryScale, 0.001f)
        assertTrue(cfg.settings.keepScreenOn)
        assertTrue(cfg.settings.showDirectKeyLabels)
        assertEquals(ThemePreset.OROKIN, cfg.settings.theme)
        assertEquals(Step.TapKey("W"), cfg.activeProfile.single[Symbol.UP])
    }

    @Test
    fun v1ConfigMigratesToKeysAndMacroProfiles() {
        val v1Json = """
            {
              "version": 1,
              "single": { "U": { "combo": "ctrl+c" } },
              "macros": [
                { "id": "p", "name": "Paste", "seq": "D S", "steps": [ { "combo": "ctrl+v" } ] }
              ]
            }
        """.trimIndent()

        val cfg = ConfigJson.decode(v1Json)
        assertEquals(2, cfg.profiles.size)
        assertEquals(ProfileKind.KEYS, cfg.profiles[0].kind)
        assertEquals(ProfileKind.MACRO, cfg.profiles[1].kind)
        assertEquals(Step.TapKey("c", setOf(KeyMod.CTRL)), cfg.profiles[0].single[Symbol.UP])
    }
}
