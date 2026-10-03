package com.cyclone.mobile.places

import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.agent.contract.AgentPageCard
import java.net.URI
import java.util.Locale

/** A destination identity. Browser Places require proof from the current Chrome address bar. */
data class ResolvedPlace(val id: String, val label: String, val packageName: String? = null, val origin: String? = null)

object PlaceResolver {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val browserPackages = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    )
    private val addressBarNames = setOf("url_bar", "location_bar", "address_bar")

    fun isChromePackage(packageName: String): Boolean = packageName in browserPackages

    fun isChromeAddressBarResourceId(resourceId: String): Boolean =
        isChromePackage(resourceId.substringBefore(":id/", "")) &&
            resourceId.substringAfter(":id/", "") in addressBarNames

    fun packagePlace(packageName: String): ResolvedPlace? =
        packageName.takeIf { packagePattern.matches(it) && !isChromePackage(it) }
            ?.let { ResolvedPlace("package:$it", it, packageName = it) }

    /** Input may be a full observed URL; only its normalized origin leaves this function. */
    fun canonicalOriginFromUrl(value: String): String? {
        if (value.isBlank() || value.length > 8192 || value.any(Char::isISOControl)) return null
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.rawAuthority == null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        if (port !in -1..65535 || port == 0) return null
        val effectivePort = if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) -1 else port
        return runCatching { URI(scheme, null, host, effectivePort, null, null, null).toASCIIString() }
            .getOrNull()?.takeIf { it.length <= 505 }
    }

    /** Accept only an origin, not a URL carrying a path, query, fragment or credentials. */
    fun canonicalOrigin(value: String): String? =
        canonicalOriginFromUrl(value)?.takeIf { it == value }

    /** Chrome's unfocused, visible URL field is the authority; other page text is never consulted. */
    fun observedChromeOrigin(snapshot: UiSnapshot): String? {
        val packageName = snapshot.packageName ?: return null
        if (!isChromePackage(packageName)) return null
        val origins = snapshot.nodes.asSequence()
            .filter { it.visibleToUser && !it.focused && !it.password && it.bounds.width > 0 && it.bounds.height > 0 }
            .filter { node ->
                node.resourceId.substringBefore(":id/", "") == packageName &&
                    isChromeAddressBarResourceId(node.resourceId)
            }
            .mapNotNull { canonicalOriginFromUrl(it.text) ?: canonicalOriginFromUrl(it.contentDescription) }
            .distinct()
            .take(2)
            .toList()
        return origins.singleOrNull()
    }

    fun resolveObservedSnapshot(snapshot: UiSnapshot): ResolvedPlace? {
        val packageName = snapshot.packageName ?: return null
        if (!isChromePackage(packageName)) return packagePlace(packageName)
        val origin = observedChromeOrigin(snapshot) ?: return null
        return ResolvedPlace("chrome:$origin", origin, origin = origin)
    }

    fun resolveCurrent(page: AgentPageCard): ResolvedPlace? {
        if (!page.actionable || page.observationId.isBlank()) return null
        val packageName = page.packageName
        if (!isChromePackage(packageName)) return packagePlace(packageName)
        val evidence = page.pageEvidence
        if (evidence.optString("browserOriginSource") != "chrome-address-bar" ||
            evidence.optString("browserOriginObservationId") != page.observationId
        ) return null
        val origin = canonicalOrigin(evidence.optString("browserOrigin")) ?: return null
        return ResolvedPlace("chrome:$origin", origin, origin = origin)
    }

    fun matchesCurrent(page: AgentPageCard, placeId: String): Boolean = resolveCurrent(page)?.id == placeId
}
