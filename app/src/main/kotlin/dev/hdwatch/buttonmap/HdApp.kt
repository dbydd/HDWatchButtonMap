package dev.hdwatch.buttonmap

import android.app.Application
import android.os.VibratorManager
import dev.hdwatch.buttonmap.config.ConfigRepository
import dev.hdwatch.buttonmap.engine.MacroRunner
import dev.hdwatch.buttonmap.engine.SequenceEngine
import dev.hdwatch.buttonmap.hid.ReportRing
import dev.hdwatch.buttonmap.hid.TransportManager
import dev.hdwatch.buttonmap.input.SensorHub
import dev.hdwatch.buttonmap.ui.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Composition root: one object graph for the whole (single-activity) app. */
class HdApp : Application() {

    val ring = ReportRing()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var configRepo: ConfigRepository
        private set
    lateinit var transports: TransportManager
        private set
    lateinit var runner: MacroRunner
        private set
    lateinit var engine: SequenceEngine
        private set
    lateinit var gestures: SensorHub
        private set
    lateinit var haptics: Haptics
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val vibrator = runCatching {
            getSystemService(VibratorManager::class.java).defaultVibrator
        }.getOrNull()
        haptics = Haptics(vibrator) { msg -> ring.log("HAP  $msg") }
        configRepo = ConfigRepository(this, ring)
        transports = TransportManager(this, ring)
        runner = MacroRunner(scope, transports, { configRepo.config.value.settings }, ring)
        engine = SequenceEngine(configRepo, runner, scope, ring, onEvent = { haptics.onEvent(it) })
        gestures = SensorHub(this, configRepo, engine, ring)
        // Watch profile/settings changes to move the keep-alive service.
        scope.launch {
            configRepo.config.collect { cfg ->
                HidForegroundService.sync(this@HdApp, cfg.settings.keepAliveService)
            }
        }
    }

    companion object {
        lateinit var instance: HdApp
            private set
    }
}
