package com.cyclone.mobile.brain

import android.content.Context
import com.cyclone.mobile.fastpath.FastPathLanding
import com.cyclone.mobile.fastpath.FastPathLandingHint
import java.io.File

/** Optional personal direction sheet. The live task agent never writes this. */
object UserMdRuntime {
    const val PREFS = "cyclone_user_md"
    const val ENABLED_KEY = "enabled"
    private const val PENDING_KEY = "pending_compress"

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var document: UserMdDocument = UserMdDocument.empty()
        private set

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(ENABLED_KEY, false)
        document = UserMdDocument.parse(file(context).takeIf { it.isFile }?.readText().orEmpty())
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, on).apply()
        if (on && !file(context).isFile) save(context, UserMdDocument.empty().render())
    }

    fun save(context: Context, markdown: String) {
        document = UserMdDocument.parse(markdown)
        val target = file(context)
        target.parentFile?.mkdirs()
        target.writeText(document.render())
    }

    fun markdown(): String = document.render()

    fun replace(next: UserMdDocument, context: Context? = null) {
        document = next
        context?.let { save(it, next.render()) }
    }

    fun resetForTest(on: Boolean = false, doc: UserMdDocument = UserMdDocument.empty()) {
        enabled = on
        document = doc
    }

    fun slice(goal: String): UserMdSlice? {
        if (!enabled) return null
        return document.slice(goal).takeIf { it.useful }
    }

    fun landing(goal: String): FastPathLandingHint? {
        if (!enabled) return null
        if (FastPathLanding.namedApp(goal) != null) return null
        val slice = document.slice(goal)
        if (slice.askWhich.isNotEmpty()) return null
        val app = slice.preferredApp ?: return null
        val packageName = FastPathLanding.packageForName(app) ?: return null
        return FastPathLandingHint(
            tool = "phone.open_app",
            packageName = packageName,
            reason = "$app is the app saved for this person in user.md.",
        )
    }

    fun pendingCompress(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PENDING_KEY, 0)

    fun bumpPending(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(PENDING_KEY, (prefs.getInt(PENDING_KEY, 0) + 1).coerceAtMost(20)).apply()
    }

    fun clearPending(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PENDING_KEY, 0).apply()
    }

    fun file(context: Context): File = File(File(context.applicationContext.filesDir, "Cyclone Brain"), "user.md")
}
