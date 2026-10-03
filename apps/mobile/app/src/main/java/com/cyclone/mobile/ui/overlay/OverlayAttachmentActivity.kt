package com.cyclone.mobile.ui.overlay

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** References are kept only in memory and are separate from the user's authorized task goal. */
data class TaskAttachment(val text: String? = null, val imageDataUrl: String? = null)
object PendingTaskAttachment {
    private var value: TaskAttachment? = null
    private val mutablePresent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val present: kotlinx.coroutines.flow.StateFlow<Boolean> = mutablePresent
    @Synchronized fun set(attachment: TaskAttachment) { value = attachment; mutablePresent.value = true }
    @Synchronized fun take(): TaskAttachment? = value.also { value = null; mutablePresent.value = false }
}
/** Temporarily yields the overlay's window focus to a real Android picker/consent activity. */
object OverlayExternalInteraction {
    val active = kotlinx.coroutines.flow.MutableStateFlow(false)
}
class OverlayAttachmentActivity : ComponentActivity() {
    override fun onDestroy() {
        if (!isChangingConfigurations) OverlayExternalInteraction.active.value = false
        super.onDestroy()
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val type = contentResolver.getType(uri).orEmpty()
            val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytes(1_048_577) } ?: error("File unavailable")
            require(bytes.size <= 1_048_576) { "Choose a file smaller than 1 MB." }
            if (type in setOf("image/jpeg", "image/png", "image/webp")) {
                PendingTaskAttachment.set(TaskAttachment(imageDataUrl = "data:$type;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
            } else {
                require(type.startsWith("text/") && bytes.size <= 16000) { "Choose an image or a text file smaller than 16 KB." }
                PendingTaskAttachment.set(TaskAttachment(text = bytes.toString(Charsets.UTF_8)))
            }
            Toast.makeText(this, "Attached to your next request", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        finish()
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val output = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
            PendingTaskAttachment.set(TaskAttachment(imageDataUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)))
            bitmap.recycle()
            Toast.makeText(this, "Photo attached to your next request", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) runCatching {
            when {
                intent.getBooleanExtra("camera", false) -> camera.launch(null)
                intent.getBooleanExtra("photos", false) -> filePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                else -> filePicker.launch(arrayOf("text/plain", "text/markdown", "image/jpeg", "image/png", "image/webp"))
            }
        }.onFailure { Toast.makeText(this, "No compatible picker is available", Toast.LENGTH_LONG).show(); finish() }
    }
}
