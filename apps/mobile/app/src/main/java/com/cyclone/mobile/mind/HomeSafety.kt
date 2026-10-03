package com.cyclone.mobile.mind

/**
 * Alpha 93: the home screen keeps banking, payment, authenticator and password apps next to the icons missions use, and
 * a tap that lands one icon off opens one of them (the stress test's operator mis-tap opened bunq). On a launcher, a tap
 * on such an app is refused unless the owner named it or spoke of such apps (in the goal, a steer or an answer). The Mind opens the app it
 * needs with open_app instead. Pure.
 */
object HomeSafety {
    private val SENSITIVE = Regex(
        "(?i)\\b(bank\\w*|bunq|ing|rabo\\w*|abn( amro)?|knab|asn|sns|triodos|revolut|paypal|wise|n26|monzo|tikkie|klarna|" +
            "venmo|cash app|zelle|wallet|portemonnee|authenticator|authy|proton auth|2fa|otp|digid|itsme|" +
            "coinbase|binance|bitvavo|kraken|crypto\\w*|bitwarden|1password|lastpass|dashlane|keepass\\w*|password\\w*|wachtwoord\\w*)\\b",
    )

    fun isLauncher(packageName: String?): Boolean =
        packageName != null && (packageName.contains("launcher", ignoreCase = true) || packageName == "com.android.systemui")

    /** True when [label] looks like a banking, payment, sign-in or password app. */
    fun sensitiveApp(label: String): Boolean = SENSITIVE.containsMatchIn(label)

    /** The refusal for a tap on [label] on [packageName], or null when the tap may go ahead. */
    fun refusal(packageName: String?, label: String, ownerWords: String): String? {
        if (!isLauncher(packageName) || !sensitiveApp(label)) return null
        val name = label.trim().lowercase()
        if (name.isNotEmpty() && ownerWords.lowercase().contains(name)) return null
        // The owner talks about such apps ("open the bank", "my authenticator"): their call, not a mis-tap.
        if (SENSITIVE.containsMatchIn(ownerWords)) return null
        return "\"${label.take(60)}\" is a banking, payment, sign-in or password app the owner didn't name, so Cyclone doesn't open " +
            "it from the home screen. If this is the wrong icon, open the app the goal needs with open_app. If the owner " +
            "really wants it, ask them (owner_ask) and name the app."
    }
}
