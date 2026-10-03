package com.cyclone.mobile.gateway

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

/**
 * Plan 33 (C3): a file the owner's PC made (a video from Higgsfield, say) arrives for a Command Center task in chunks
 * (`cc.media`). The phone writes them to its own cache, checks the whole file's SHA-256, and only then adds it to the
 * gallery (Movies/Cyclone, Pictures/Cyclone or Music/Cyclone), where the posting mission picks it up.
 *
 * Media only (video, image, audio), at most 500 MB, one file per task. A chunk that does not continue the file from
 * where it is restarts it at offset 0 or is refused.
 */
internal object CommandMedia {
    const val MAX_SIZE = 500L * 1024 * 1024
    const val MAX_CHUNK = 512 * 1024
    private const val MAX_PARTIALS = 3
    /** A Command Center task, or a Cyclone Ports item (plan 48 run 4: a file a plugin delivered). */
    private val TASK_ID = Regex("^(?:tsk|pt)_[A-Za-z0-9_-]{6,40}$")
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
    private val MIME = Regex("^(video|image|audio)/[a-z0-9.+-]{1,60}$")
    private val SHA = Regex("^[0-9a-f]{64}$")

    /** Where partial files live; production uses the app's cache. */
    internal var dir: () -> File = { File(checkNotNull(context).cacheDir, "cc-media") }
    /** Adds a finished file to the gallery; returns the folder it is in. Tests replace it. */
    internal var publish: (File, String, String) -> String = ::addToGallery

    @Volatile private var context: Context? = null
    fun install(context: Context) { this.context = context.applicationContext }

    fun receive(args: JSONObject): JSONObject {
        if (args.keys().asSequence().any { it !in setOf("taskId", "name", "mime", "size", "sha256", "offset", "data") }) throw invalid("Unexpected media field.")
        val taskId = args.optString("taskId")
        val name = args.optString("name")
        val mime = args.optString("mime")
        val sha = args.optString("sha256")
        val size = args.optLong("size", -1)
        val offset = args.optLong("offset", -1)
        if (!TASK_ID.matches(taskId) || !NAME.matches(name) || !MIME.matches(mime) || !SHA.matches(sha)) throw invalid("Bad media fields.")
        if (size !in 1..MAX_SIZE || offset !in 0 until size) throw invalid("Bad media size or offset.")
        val data = runCatching { Base64.getDecoder().decode(args.optString("data")) }.getOrNull() ?: throw invalid("data must be base64.")
        if (data.isEmpty() || data.size > MAX_CHUNK || offset + data.size > size) throw invalid("Bad media chunk.")

        val folder = dir().apply { mkdirs() }
        val part = File(folder, "$taskId.part")
        synchronized(this) {
            if (offset == 0L) {
                part.delete()
                folder.listFiles { f -> f.name.endsWith(".part") }?.sortedBy { it.lastModified() }?.dropLast(MAX_PARTIALS - 1)?.forEach { it.delete() }
            }
            val have = if (part.exists()) part.length() else 0L
            if (offset != have) throw GatewayProtocolException("MEDIA_OFFSET", "Expected offset $have.")
            RandomAccessFile(part, "rw").use { it.seek(offset); it.write(data) }
            val received = offset + data.size
            if (received < size) return answer(received, done = false, name = null, where = null)
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha) {
                part.delete()
                throw GatewayProtocolException("MEDIA_CORRUPT", "The file did not arrive intact; send it again.")
            }
            val where = try { publish(part, name, mime) } finally { part.delete() }
            return answer(received, done = true, name = name, where = where)
        }
    }

    private fun answer(received: Long, done: Boolean, name: String?, where: String?) = JSONObject()
        .put("received", received).put("done", done).put("name", name ?: JSONObject.NULL).put("folder", where ?: JSONObject.NULL)

    private fun addToGallery(file: File, name: String, mime: String): String {
        val resolver = checkNotNull(context).contentResolver
        val (collection, folder) = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/Cyclone"
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/Cyclone"
            else -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Music/Cyclone"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(collection, values) ?: throw GatewayProtocolException("MEDIA_STORE", "The gallery did not take the file.")
        try {
            resolver.openOutputStream(target).use { out ->
                requireNotNull(out) { "gallery output" }
                file.inputStream().use { it.copyTo(out) }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw GatewayProtocolException("MEDIA_STORE", "The gallery did not take the file.")
        }
        return folder
    }

    private fun invalid(message: String) = GatewayProtocolException("INVALID_REQUEST", message)
}
