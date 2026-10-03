package com.cyclone.mobile.ui.v32

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ui.overlay.DriveIntroActivity
import com.cyclone.mobile.voice.DriverMode
import com.cyclone.mobile.voice.DriverSettings
import com.cyclone.mobile.voice.JevWatch
import com.cyclone.mobile.voice.OpenRouterVoice
import com.cyclone.mobile.voice.SpeechOut
import com.cyclone.mobile.voice.VoiceAnnounce
import com.cyclone.mobile.voice.VoiceCatalog
import com.cyclone.mobile.voice.VoiceModel
import com.cyclone.mobile.voice.VoiceModels
import com.cyclone.mobile.voice.VoiceProbe
import com.cyclone.mobile.voice.VoiceTimings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Driver mode (plan 32): the switch, the button size and the safety note. */
@Composable
internal fun DriverModeSettings(context: Context, refresh: () -> Unit) {
    val settings by DriverMode.settings.collectAsState()
    LaunchedEffect(Unit) { DriverMode.load(context) }
    val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Driver mode", style = MaterialTheme.typography.titleMedium)
                Text("The Ask bubble becomes a large voice button. Tap it and talk; Cyclone confirms in one line and tells you when it is done.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            CycloneLiquidToggle(checked = settings.enabled, onCheckedChange = { on ->
                DriverMode.setEnabled(context, on)
                // Turning it on plays the short Drive film, which asks for the microphone at its end if needed.
                if (on) DriveIntroActivity.start(context)
                refresh()
            })
        }
        if (settings.enabled && !mic) {
            Text("Cyclone needs the microphone to hear you. Allow it when Android asks, or in Set up Cyclone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text("Button size", style = MaterialTheme.typography.labelLarge)
        CycloneLiquidChoiceBar(
            options = listOf("Smaller", "Standard", "Larger"),
            selectedIndex = DriverSettings.BUTTON_SIZES.indexOf(settings.buttonDp).coerceAtLeast(0),
            onSelect = { i -> DriverMode.update(context) { it.copy(buttonDp = DriverSettings.BUTTON_SIZES[i]) } },
            modifier = Modifier.fillMaxWidth(),
        )
        DriveNote("Tap the orb to talk. Hold it for a second, then drag it anywhere; it stays there.")
        if (settings.enabled) InTheCar(context, settings)
        DriveNote("Stop is always one tap. Paying, deleting, permissions, passwords and handing the phone to you always wait until you're stopped.")
        DriveNote("Use it hands-free with the phone mounted, and keep your eyes on the road. Cyclone is not a replacement for Android Auto.")
        DriveNote("The microphone opens only when you tap. Recordings stay in memory and are never saved.")
    }
}

/** Driver mode → In the car (plan 32 D3): the Bluetooth microphone and message announcements. */
@Composable
private fun InTheCar(context: Context, settings: DriverSettings) {
    val installed = remember {
        VoiceAnnounce.CHAT_APPS.filter { (pkg, _) -> runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess }
    }
    var contacts by remember { mutableStateOf(settings.announceContacts.sorted().joinToString(", ")) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("In the car", style = MaterialTheme.typography.titleMedium)
        ToggleRow("Car microphone", "When a car kit or headset is connected over Bluetooth, Cyclone listens through it. " +
            "Its microphone is closer to you than the phone's.", settings.bluetoothMic) { on ->
            DriverMode.update(context) { it.copy(bluetoothMic = on) }
        }
        ToggleRow("Announce messages", "Cyclone reads new messages from the apps you pick: \"Louella wrote: '…'. Reply?\" " +
            "Tap the orb within a minute to answer. Codes and long messages are never read out; groups by name only.", settings.announce) { on ->
            DriverMode.update(context) { it.copy(announce = on) }
        }
        if (settings.announce) {
            if (installed.isEmpty()) DriveNote("No supported chat app is installed.")
            installed.forEach { (pkg, label) ->
                PickRow(label, selected = pkg in settings.announceApps) {
                    DriverMode.update(context) { s -> s.copy(announceApps = if (pkg in s.announceApps) s.announceApps - pkg else s.announceApps + pkg) }
                }
            }
            Text("Only from", style = MaterialTheme.typography.labelLarge)
            CycloneLiquidSearchField(
                value = contacts,
                onValueChange = { text ->
                    contacts = text
                    val names = text.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(30).toSet()
                    DriverMode.update(context) { it.copy(announceContacts = names) }
                },
                placeholder = "Everyone (or names, separated by commas)",
                modifier = Modifier.fillMaxWidth(),
            )
            DriveNote("Cyclone needs notification access for this (Set up Cyclone). Messages are never saved; a reply is read back " +
                "word for word and sent only when you say yes.")
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        CycloneLiquidToggle(checked = checked, onCheckedChange = onChange)
    }
}

/** Settings → Voice (plan 24 §5.7): the models and voice from OpenRouter's live list, language, end of speech, Test voice. */
@Composable
internal fun VoiceSettings(context: Context) {
    val settings by DriverMode.settings.collectAsState()
    val lists by VoiceCatalog.lists.collectAsState()
    val scope = rememberCoroutineScope()
    val key = remember { OpenRouterSecretStore.read(context) }
    var loading by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<VoiceProbe.Result?>(null) }
    var testing by remember { mutableStateOf(false) }
    val speech = remember { SpeechOut(context) }
    DisposableEffect(Unit) { onDispose { speech.release() } }
    LaunchedEffect(key) {
        DriverMode.load(context)
        if (key.isNotBlank()) {
            loading = true
            withContext(Dispatchers.IO) { VoiceCatalog.refresh(key) }
            loading = false
        }
    }
    val choice = VoiceModels.choose(lists.stt, lists.tts, lists.text, settings)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (key.isBlank()) {
            DriveNote("Add your OpenRouter key in Model & API. Until then Cyclone speaks with the phone's own voice and cannot understand requests.")
        }
        lists.error?.let { DriveNote("OpenRouter's model list could not be loaded: $it") }
        if (loading) DriveNote("Loading OpenRouter's voice models…")

        Text("Recognition", style = MaterialTheme.typography.labelLarge)
        CycloneLiquidChoiceBar(
            options = listOf("OpenRouter", "On this phone"),
            selectedIndex = if (settings.onDeviceStt) 1 else 0,
            onSelect = { i -> DriverMode.update(context) { it.copy(onDeviceStt = i == 1) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!settings.onDeviceStt) ModelPicker("Speech to text", lists.stt, VoiceModels.PREFERRED_STT, choice.stt) { id ->
            DriverMode.update(context) { it.copy(sttModel = id) }
        } else DriveNote("Android's own recognizer: offline and private where the phone supports it.")

        Text("Voice quality", style = MaterialTheme.typography.labelLarge)
        CycloneLiquidChoiceBar(
            options = listOf("Fast", "Best"),
            selectedIndex = if (settings.bestVoice) 1 else 0,
            onSelect = { i -> DriverMode.update(context) { it.copy(bestVoice = i == 1, ttsModel = null, voice = null) } },
            modifier = Modifier.fillMaxWidth(),
        )
        DriveNote("Fast is the quickest natural voice. Best is the most natural one OpenRouter lists, a little slower to start.")
        ModelPicker("Voice model", lists.tts, if (settings.bestVoice) VoiceModels.PREFERRED_TTS_BEST else VoiceModels.PREFERRED_TTS, choice.tts) { id ->
            DriverMode.update(context) { it.copy(ttsModel = id, voice = null) }
        }
        val voices = VoiceModels.voices(lists.tts.firstOrNull { it.id == choice.tts })
        if (voices.isNotEmpty()) {
            Text("Voice", style = MaterialTheme.typography.labelLarge)
            voices.take(8).forEach { voice ->
                PickRow(voice, selected = voice == choice.voice, trailing = "Preview", onTrailing = {
                    scope.launch {
                        speech.say("Hi, I'm Cyclone.", key.takeIf { it.isNotBlank() }?.let { OpenRouterVoice(it) }, choice.tts, voice, settings.language)
                    }
                }) { DriverMode.update(context) { it.copy(voice = voice) } }
            }
        }

        ModelPicker("Understanding (fast model)", lists.text, VoiceModels.PREFERRED_FAST, choice.fast) { id ->
            DriverMode.update(context) { it.copy(fastModel = id) }
        }

        Text("Instant decisions (JEV, watching)", style = MaterialTheme.typography.labelLarge)
        val jev by JevWatch.tally.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(jev.summary(), style = MaterialTheme.typography.bodyMedium)
                Text("JEV, a new decision model, answers each request next to the understanding model. It never decides: " +
                    "this shows how often it agreed and how fast it was, so a drive can prove whether it may take over simple requests.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            CycloneLiquidToggle(checked = settings.jevWatch, onCheckedChange = { on -> DriverMode.update(context) { it.copy(jevWatch = on) } })
        }

        Text("Language", style = MaterialTheme.typography.labelLarge)
        DriverSettings.LANGUAGES.forEach { language ->
            PickRow(if (language == "auto") "Automatic" else language, selected = settings.language == language) {
                DriverMode.update(context) { it.copy(language = language) }
            }
        }

        Text("End of speech", style = MaterialTheme.typography.labelLarge)
        CycloneLiquidChoiceBar(
            options = DriverSettings.END_SILENCE.map { VoiceTimings.seconds(it.toLong()) },
            selectedIndex = DriverSettings.END_SILENCE.indexOf(settings.endSilenceMs).coerceAtLeast(0),
            onSelect = { i -> DriverMode.update(context) { it.copy(endSilenceMs = DriverSettings.END_SILENCE[i]) } },
            modifier = Modifier.fillMaxWidth(),
        )
        DriveNote("How long Cyclone waits after you stop talking. Shorter answers sooner; longer lets you pause between words.")

        Text("Test voice", style = MaterialTheme.typography.labelLarge)
        CycloneLiquidTextAction(
            label = if (testing) "Testing…" else "Test voice",
            enabled = !testing && key.isNotBlank(),
            prominent = true,
            onClick = {
                testing = true
                scope.launch {
                    probe = withContext(Dispatchers.IO) { VoiceProbe.run(key, choice, settings.language) }
                    testing = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        probe?.let { result ->
            TimingRow("First sound", result.firstSoundMs, VoiceTimings.TARGET_FIRST_SOUND_MS)
            result.ttsCompared.forEach { run ->
                TimingRow("Also: ${run.model.substringAfter('/')}", run.ms, VoiceTimings.TARGET_FIRST_SOUND_MS)
                run.error?.let { DriveNote(it) }
            }
            TimingRow("Transcription", result.transcribeMs, VoiceTimings.TARGET_TRANSCRIBE_MS)
            TimingRow("Understanding", result.understandMs, VoiceTimings.TARGET_UNDERSTAND_MS)
            TimingRow("Confirmation after you stop", result.confirmMs, VoiceTimings.TARGET_P50_MS)
            result.sttCompared.forEach { run ->
                TimingRow("Also: ${run.model.substringAfter('/')}", run.ms, VoiceTimings.TARGET_TRANSCRIBE_MS)
                DriveNote(run.error ?: "Heard \"${run.transcript}\"")
            }
            if (result.transcript.isNotBlank()) DriveNote("Heard \"${result.transcript}\"" +
                if (result.understood) ", understood as a timer." else ", not understood as the timer it was.")
            result.error?.let { DriveNote(it) }
        }
    }
}

@Composable
private fun ModelPicker(title: String, models: List<VoiceModel>, preferred: List<String>, selected: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        val current = models.firstOrNull { it.id == selected }
        PickRow(current?.name ?: selected ?: "None listed", selected = true, trailing = if (models.size > 1) (if (open) "Close" else "Change") else null,
            onTrailing = { open = !open }) { open = !open }
        if (open) {
            val ranked = models.sortedWith(compareBy<VoiceModel>({ m ->
                preferred.indexOfFirst { m.id == it || m.id.startsWith("$it-") }.let { if (it < 0) Int.MAX_VALUE else it }
            }, { it.name.lowercase() })).take(12)
            ranked.forEach { model ->
                PickRow(model.name, selected = model.id == selected, detail = model.id) { onPick(model.id); open = false }
            }
        }
    }
}

@Composable
private fun PickRow(label: String, selected: Boolean, detail: String? = null, trailing: String? = null, onTrailing: () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        trailing?.let { CycloneLiquidTextAction(label = it, onClick = onTrailing) }
    }
}

@Composable
private fun TimingRow(label: String, ms: Long?, target: Long) {
    val ok = ms != null && ms <= target
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule, null, Modifier.size(18.dp),
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${VoiceTimings.seconds(ms)}  ·  target ${VoiceTimings.seconds(target)}", style = MaterialTheme.typography.bodySmall,
            fontWeight = if (ok) FontWeight.SemiBold else FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DriveNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
