package com.cyclone.mobile.codes

/**
 * Plan 49 §2: may Cyclone fill a code from this phone's own texts without asking? Code decides, never a model.
 *
 * - **AUTO:** the code plainly goes to a number on this phone, in a run the owner wants it in (an Account Setup row with
 *   this phone's number, this phone's number typed into the app in this run, the code page naming this phone's number, or
 *   the owner's own words).
 * - **ASK:** anything else (the Secrets Card, as before).
 * - **NEVER:** a Lab mission, an app kept private (banking, payments) or a payment code. The owner types those.
 */
object AutoCodePolicy {
    enum class Decision { AUTO, ASK, NEVER }

    data class Result(val decision: Decision, val why: String, val number: String? = null)

    data class Facts(
        /** Settings → Codes is on and Cyclone may read texts. */
        val enabled: Boolean,
        /** This phone's numbers (per SIM, or confirmed by the owner). */
        val phoneNumbers: List<String>,
        val lab: Boolean = false,
        /** The app is kept private (`Pilot.keepOff`): banking, payments, wallets. */
        val keptPrivate: Boolean = false,
        /** An Account Setup row's phone number, when the run is Account Setup. */
        val setupNumber: String? = null,
        /** Numbers the run typed into the app in this run. */
        val typedNumbers: List<String> = emptyList(),
        /** The code page's own text. */
        val screenText: String = "",
        /** What the owner asked, in their words. */
        val goal: String = "",
    )

    private val PAYMENT = Regex("(?i)\\b(pay|payment|betal\\p{L}*|transfer|overboek\\p{L}*|ideal|purchase|aankoop|transaction|transactie|checkout|afrekenen|card ending|kaart eindigend)\\b")
    private val MY_NUMBER = Regex("(?i)\\b(my (phone )?number|my phone|this phone|this number|mijn (telefoon)?nummer|dit nummer|deze telefoon|mijn telefoon)\\b")
    private val NUMBER = Regex("\\+?\\d[\\d ()-]{6,}\\d")
    /** "•••• 4821", "**** 21", "ending in 4821", "eindigend op 4821", "+31 6 •••• 4821". */
    private val MASKED = Regex("(?i)(?:[•*●·xX]{2,}[\\s-]*|ending (?:in|with) |eindigend (?:op|met) |eindigt op )(\\d{2,4})\\b")

    fun decide(facts: Facts): Result {
        if (facts.lab) return Result(Decision.NEVER, "a Lab mission")
        if (facts.keptPrivate) return Result(Decision.NEVER, "this app stays private")
        if (PAYMENT.containsMatchIn(facts.screenText)) return Result(Decision.NEVER, "a payment code")
        val mine = facts.phoneNumbers.map(::digits).filter { it.length >= 6 }
        if (!facts.enabled) return Result(Decision.ASK, "codes from this phone's texts are off")
        if (mine.isEmpty()) return Result(Decision.ASK, "this phone's number isn't known yet")
        fun ours(number: String): String? = mine.firstOrNull { same(it, digits(number)) }

        // The code page says where it sent the code: that wins over everything else.
        val shown = shownNumbers(facts.screenText)
        if (shown.isNotEmpty()) {
            val hit = shown.firstNotNullOfOrNull { s -> if (s.masked) mine.firstOrNull { it.endsWith(s.digits) } else ours(s.digits) }
            return if (hit != null) Result(Decision.AUTO, "the code went to this phone's number", hit)
            else Result(Decision.ASK, "the code went to a number that isn't on this phone")
        }
        facts.setupNumber?.takeIf { digits(it).length >= 6 }?.let { number ->
            return ours(number)?.let { Result(Decision.AUTO, "this account uses this phone's number", it) }
                ?: Result(Decision.ASK, "this account uses a number that isn't on this phone")
        }
        facts.typedNumbers.firstNotNullOfOrNull { ours(it) }?.let { return Result(Decision.AUTO, "this phone's number was entered", it) }
        NUMBER.findAll(facts.goal).firstNotNullOfOrNull { ours(it.value) }?.let { return Result(Decision.AUTO, "you asked for this phone's number", it) }
        if (MY_NUMBER.containsMatchIn(facts.goal)) return Result(Decision.AUTO, "you asked for this phone's number", mine.first())
        return Result(Decision.ASK, "nothing says the code goes to this phone")
    }

    private data class Shown(val digits: String, val masked: Boolean)

    private fun shownNumbers(text: String): List<Shown> =
        MASKED.findAll(text).map { Shown(it.groupValues[1], true) }.toList() +
            NUMBER.findAll(MASKED.replace(text, " ")).map { digits(it.value) }.filter { it.length >= 8 }.map { Shown(it, false) }.toList()

    fun digits(number: String): String = number.filter(Char::isDigit)

    /** The same number written two ways (+31 6 1234 5678 and 06 1234 5678): the last 9 digits match. */
    fun same(a: String, b: String): Boolean {
        val x = digits(a)
        val y = digits(b)
        if (x.length < 6 || y.length < 6) return false
        val n = minOf(9, x.length, y.length)
        return x.takeLast(n) == y.takeLast(n)
    }
}
