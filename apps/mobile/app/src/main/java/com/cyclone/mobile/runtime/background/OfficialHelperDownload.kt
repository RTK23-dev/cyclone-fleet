package com.cyclone.mobile.runtime.background

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest

/** Version-pinned upstream APK. Neither a Play listing nor an unverified third-party mirror. */
object OfficialHelperDownload {
    const val URL = OfficialHelperInstallPolicy.URL
    const val SHA256 = OfficialHelperInstallPolicy.SHA256
    private const val MAX_BYTES = 8 * 1024 * 1024
    fun file(context: Context) = File(context.cacheDir, "setup-helper/shizuku.apk")
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun download(context: Context): File {
        check(OfficialHelperInstallPolicy.isPinnedOfficialUrl(URL))
        check(!OfficialHelperInstallPolicy.isForbiddenStoreUrl(URL))
        check(OfficialHelperInstallPolicy.PACKAGE == BackgroundSetup.SHIZUKU_PACKAGE)
        val final = file(context)
        if (final.exists() && digest(final) == SHA256) return final
        final.parentFile!!.mkdirs()
        val part = File(final.parentFile, "download.part")
        var connection: HttpURLConnection? = null
        try {
            var url = java.net.URL(URL)
            repeat(5) {
                val conn = url.openConnection() as HttpURLConnection
                connection = conn
                conn.connectTimeout = 15_000; conn.readTimeout = 20_000; conn.instanceFollowRedirects = false
                if (conn.responseCode in 300..399) {
                    val next = java.net.URL(url, conn.getHeaderField("Location") ?: error("Download redirect unavailable"))
                    check(next.protocol == "https") { "Secure download unavailable" }
                    conn.disconnect(); url = next
                } else {
                    check(conn.responseCode == 200) { "Download unavailable. Please try again." }
                    conn.inputStream.use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(8192); var total = 0
                        while (true) {
                            if (Thread.currentThread().isInterrupted) error("Download paused")
                            val count = input.read(buffer); if (count < 0) break
                            total += count; check(total <= MAX_BYTES) { "Download too large" }; output.write(buffer, 0, count)
                        }
                    } }
                    check(digest(part) == SHA256) { "Download could not be verified. Please try again." }
                    val info = context.packageManager.getPackageArchiveInfo(part.path, 0)
                    check(info?.packageName == BackgroundSetup.SHIZUKU_PACKAGE) { "Unexpected helper package" }
                    check(part.renameTo(final)) { "Could not save the helper. Free some space and retry." }
                    return final
                }
            }
            error("Download unavailable. Please try again.")
        } finally { connection?.disconnect(); part.delete() }
    }
}
