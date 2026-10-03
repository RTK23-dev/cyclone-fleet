package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.mind.signup.SignupMap
import com.cyclone.mobile.mind.signup.SignupMapStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 43 (T6): the sign-up maps over the gateway. `signup.maps` lists them for Glass's Accounts (schemas only: pages,
 * field labels and kinds, checks; never a value), and `signup.forget` drops one so the app can be mapped again.
 */
internal object GatewayV5SignupAdapter {
    const val MAX_MAPS = 200
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    @Volatile private var context: Context? = null
    fun install(context: Context) { this.context = context.applicationContext }
    private fun app(): Context = checkNotNull(context) { "signup adapter not installed" }

    /** Seams for JVM tests; production reads Cyclone Brain. */
    internal var maps: () -> List<SignupMap> = { SignupMapStore.all(app()) }
    internal var forget: (String) -> Boolean = { pkg -> SignupMapStore.forget(app(), pkg) }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "signup.maps" -> {
            if (args.length() != 0) throw GatewayProtocolException("INVALID_REQUEST", "signup.maps takes no arguments.")
            val all = maps()
            JSONObject().put("maps", JSONArray(all.take(MAX_MAPS).map { it.toJson() })).put("truncated", all.size > MAX_MAPS)
        }
        "signup.forget" -> {
            if (args.keys().asSequence().toSet() != setOf("package")) throw GatewayProtocolException("INVALID_REQUEST", "signup.forget takes {package}.")
            val pkg = args.optString("package")
            if (!PACKAGE.matches(pkg)) throw GatewayProtocolException("INVALID_REQUEST", "package is an Android package name.")
            JSONObject().put("forgotten", forget(pkg))
        }
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported sign-up operation: $op")
    }
}
