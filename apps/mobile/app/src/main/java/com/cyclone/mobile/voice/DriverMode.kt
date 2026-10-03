package com.cyclone.mobile.voice

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Driver mode (plan 24 §5.1): the setting, the AI button's spot per orientation, and the live list of voice models.
 * Settings hold model ids and choices only; no audio, no transcripts, no key (the key stays in OpenRouterSecretStore).
 */
object DriverMode {
    private const val PREFS = "cyclone_drive"
    private val _settings = MutableStateFlow(DriverSettings())
    val settings: StateFlow<DriverSettings> = _settings
    @Volatile private var loaded = false

    fun load(context: Context): DriverSettings {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                _settings.value = read(prefs(context))
                loaded = true
            }
        }
        return _settings.value
    }

    fun enabled(context: Context): Boolean = load(context).enabled

    fun update(context: Context, change: (DriverSettings) -> DriverSettings) {
        val next = change(load(context))
        write(prefs(context), next)
        _settings.value = next
    }

    fun setEnabled(context: Context, on: Boolean) = update(context) { it.copy(enabled = on) }

    fun spot(context: Context, landscape: Boolean): ButtonSpot {
        val p = prefs(context)
        val k = if (landscape) "land" else "port"
        // Before alpha.71 the button snapped to a side; that side becomes the spot's edge.
        val x = if (p.contains("spot_${k}_x")) p.getFloat("spot_${k}_x", 1f) else if (p.getBoolean("spot_${k}_right", true)) 1f else 0f
        return ButtonSpot(x, p.getFloat("spot_${k}_y", 0.62f))
    }

    fun saveSpot(context: Context, landscape: Boolean, spot: ButtonSpot) {
        val k = if (landscape) "land" else "port"
        prefs(context).edit().putFloat("spot_${k}_x", spot.xFraction).putFloat("spot_${k}_y", spot.yFraction).remove("spot_${k}_right").apply()
    }

    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(p: SharedPreferences) = DriverSettings(
        enabled = p.getBoolean("enabled", false),
        buttonDp = p.getInt("button_dp", DriverSettings.DEFAULT_BUTTON_DP).takeIf { it in DriverSettings.BUTTON_SIZES } ?: DriverSettings.DEFAULT_BUTTON_DP,
        sttModel = p.getString("stt_model", null),
        ttsModel = p.getString("tts_model", null),
        fastModel = p.getString("fast_model", null),
        voice = p.getString("voice", null),
        onDeviceStt = p.getBoolean("on_device_stt", false),
        language = p.getString("language", "auto")?.takeIf { it in DriverSettings.LANGUAGES } ?: "auto",
        endSilenceMs = p.getInt("end_silence_ms", 700).takeIf { it in DriverSettings.END_SILENCE } ?: 700,
        bestVoice = p.getBoolean("best_voice", false),
        jevWatch = p.getBoolean("jev_watch", true),
        announce = p.getBoolean("announce", false),
        announceApps = p.getStringSet("announce_apps", null)?.toSet().orEmpty(),
        announceContacts = p.getStringSet("announce_contacts", null)?.toSet().orEmpty(),
        bluetoothMic = p.getBoolean("bluetooth_mic", true),
    )

    private fun write(p: SharedPreferences, s: DriverSettings) {
        p.edit()
            .putBoolean("enabled", s.enabled)
            .putInt("button_dp", s.buttonDp)
            .putString("stt_model", s.sttModel)
            .putString("tts_model", s.ttsModel)
            .putString("fast_model", s.fastModel)
            .putString("voice", s.voice)
            .putBoolean("on_device_stt", s.onDeviceStt)
            .putString("language", s.language)
            .putInt("end_silence_ms", s.endSilenceMs)
            .putBoolean("best_voice", s.bestVoice)
            .putBoolean("jev_watch", s.jevWatch)
            .putBoolean("announce", s.announce)
            .putStringSet("announce_apps", s.announceApps.toSet())
            .putStringSet("announce_contacts", s.announceContacts.toSet())
            .putBoolean("bluetooth_mic", s.bluetoothMic)
            .apply()
    }
}

/**
 * OpenRouter's live voice lists, fetched on the owner's key and kept in memory for the session (they change rarely;
 * Settings → Voice refreshes them). Empty until fetched: Drive then speaks with the phone's own voice and says why.
 */
object VoiceCatalog {
    data class Lists(val stt: List<VoiceModel> = emptyList(), val tts: List<VoiceModel> = emptyList(), val text: List<VoiceModel> = emptyList(),
                     val fetchedAtMs: Long = 0, val error: String? = null)

    private val _lists = MutableStateFlow(Lists())
    val lists: StateFlow<Lists> = _lists

    /** Fetches when missing or older than [maxAgeMs]. Network: call off the main thread. */
    fun refresh(key: String, maxAgeMs: Long = 6 * 60 * 60 * 1000L, now: Long = System.currentTimeMillis()): Lists {
        val current = _lists.value
        if (current.fetchedAtMs > 0 && now - current.fetchedAtMs < maxAgeMs && current.error == null) return current
        if (key.isBlank()) return current.copy(error = VoiceCopy.NO_KEY).also { _lists.value = it }
        val api = OpenRouterVoice(key)
        val next = try {
            Lists(api.models("transcription"), api.models("speech"), api.models("text"), now)
        } catch (error: VoiceCallException) {
            current.copy(error = error.message)
        }
        _lists.value = next
        return next
    }

    fun choice(settings: DriverSettings): VoiceChoice = _lists.value.let { VoiceModels.choose(it.stt, it.tts, it.text, settings) }
}

/**
 * JEV's running tally for this app session (alpha.52): how often it agreed with the understanding model and how fast
 * it was. In memory only; it holds request kinds and timings, never what was said.
 */
object JevWatch {
    private val _tally = MutableStateFlow(JevShadow.Tally())
    val tally: StateFlow<JevShadow.Tally> = _tally

    fun record(model: VoiceKind, jev: JevShadow.Decision?, ms: Long, error: String?) {
        _tally.value = if (jev == null) _tally.value.failed(error ?: "no answer") else _tally.value.add(JevShadow.Sample(model, jev.kind, ms, jev.confidence))
    }
}
