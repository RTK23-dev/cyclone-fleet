package com.cyclone.mobile.runtime.update

import java.security.MessageDigest

internal object RuntimeUpdateValidation {
    const val SUPPORTED_MANIFEST_SCHEMA = 1
    const val MAX_RESOURCES = 512
    const val MAX_TOTAL_BYTES = 128L * 1024L * 1024L
    private const val MAX_PATH_LENGTH = 240
    private val updateIdPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
    private val sha256Pattern = Regex("[a-fA-F0-9]{64}")
    private val forbiddenExtensions = setOf(
        "apk", "apks", "aab", "dex", "jar", "class", "kt", "kts",
        "so", "dll", "dylib", "exe", "com", "msi",
        "sh", "bash", "zsh", "bat", "cmd", "ps1", "js", "mjs", "cjs", "wasm",
    )

    fun validateManifest(
        manifest: RuntimeUpdateManifest,
        runtimeApiVersion: RuntimeApiVersion,
    ): RuntimeUpdateFailureCode? {
        if (manifest.schemaVersion != SUPPORTED_MANIFEST_SCHEMA) {
            return RuntimeUpdateFailureCode.UNSUPPORTED_MANIFEST_SCHEMA
        }
        if (!updateIdPattern.matches(manifest.updateId)) return RuntimeUpdateFailureCode.INVALID_UPDATE_ID
        if (manifest.issuedAtEpochMillis < 0L) return RuntimeUpdateFailureCode.INVALID_RESOURCE_METADATA
        if (!manifest.compatibleRuntimeApi.supports(runtimeApiVersion)) {
            return RuntimeUpdateFailureCode.INCOMPATIBLE_RUNTIME_API
        }
        if (manifest.resources.isEmpty()) return RuntimeUpdateFailureCode.EMPTY_MANIFEST
        if (manifest.resources.size > MAX_RESOURCES) return RuntimeUpdateFailureCode.TOO_MANY_RESOURCES

        val paths = manifest.resources.map { it.path }
        if (paths.distinct().size != paths.size) return RuntimeUpdateFailureCode.DUPLICATE_RESOURCE_PATH
        var total = 0L
        manifest.resources.forEach { resource ->
            validateResource(resource)?.let { return it }
            if (Long.MAX_VALUE - total < resource.sizeBytes) return RuntimeUpdateFailureCode.UPDATE_TOO_LARGE
            total += resource.sizeBytes
            if (total > MAX_TOTAL_BYTES) return RuntimeUpdateFailureCode.UPDATE_TOO_LARGE
        }
        return null
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun validateResource(resource: RuntimeResourceDescriptor): RuntimeUpdateFailureCode? {
        if (!isSafeRelativePath(resource.path)) return RuntimeUpdateFailureCode.INVALID_RESOURCE_PATH
        val extension = resource.path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension in forbiddenExtensions) return RuntimeUpdateFailureCode.FORBIDDEN_RESOURCE
        if (!sha256Pattern.matches(resource.sha256) || resource.sizeBytes < 0L) {
            return RuntimeUpdateFailureCode.INVALID_RESOURCE_METADATA
        }
        if (resource.schemaId.isBlank() || resource.schemaVersion < 1) {
            return RuntimeUpdateFailureCode.INVALID_RESOURCE_METADATA
        }
        return null
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > MAX_PATH_LENGTH) return false
        if (path.startsWith('/') || path.startsWith('\\') || '\\' in path || ':' in path) return false
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return false
        return segments.all { segment -> segment.all { it.code in 0x21..0x7e } }
    }
}
