package com.cyclone.mobile.mind.signup

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Plan 43 (T6): the sign-up maps this phone learned, one file per app in Cyclone Brain. Schemas only (a map never
 * holds a value), so they are kept as plain JSON and sent to the PC as they are.
 */
object SignupMapStore {
    private const val FOLDER = "Cyclone Brain/Signup"
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private fun dir(context: Context): File = File(context.applicationContext.filesDir, FOLDER).apply { mkdirs() }

    fun save(context: Context, map: SignupMap) {
        require(PACKAGE.matches(map.packageName)) { "package" }
        val file = File(dir(context), "${map.packageName}.json")
        val tmp = File(file.path + ".tmp")
        tmp.writeText(map.toJson().toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun load(context: Context, packageName: String): SignupMap? =
        if (!PACKAGE.matches(packageName)) null
        else File(dir(context), "$packageName.json").takeIf { it.isFile }?.let { runCatching { SignupMap.fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun all(context: Context): List<SignupMap> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { SignupMap.fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedBy { it.appLabel.lowercase() }

    fun forget(context: Context, packageName: String): Boolean =
        PACKAGE.matches(packageName) && File(dir(context), "$packageName.json").delete()
}
