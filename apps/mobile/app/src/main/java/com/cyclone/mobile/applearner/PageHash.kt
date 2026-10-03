package com.cyclone.mobile.applearner

import java.security.MessageDigest

/** Package-level hash helper used by the V2.8 page transition store. */
internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
