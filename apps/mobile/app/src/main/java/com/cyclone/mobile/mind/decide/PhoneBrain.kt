package com.cyclone.mobile.mind.decide

import android.content.Context
import com.cyclone.mobile.mind.MindMemory
import com.cyclone.mobile.mind.modes.PhoneDecider
import java.io.File

/**
 * The phone side of Cyclone's decisions (alpha 89): the lessons on this phone, the phone model trained from them, the
 * actions it has earned, and the numbers for Glass and the Lab. Everything stays on this phone; the lessons never leave
 * it (the health report carries counts and times only).
 */
object PhoneBrain {
    private const val FILE = "decide/lessons.jsonl"
    /** Retrain after this many new lessons (training takes a few milliseconds). */
    private const val RETRAIN_EVERY = 10

    private val lock = Any()
    @Volatile private var lessons: List<Lesson>? = null
    @Volatile private var model: PhoneModel? = null
    @Volatile private var earned: Set<String> = emptySet()
    private var sinceTraining = 0
    private var requests = 0L

    /** The router's view of the phone model for one request, or null when it is off. */
    fun decider(context: Context, use: PhoneModelUse, bar: Double): PhoneDecider? {
        if (use == PhoneModelUse.OFF) return null
        val current = ready(context, bar)
        val audit = synchronized(lock) { ++requests % Earning.AUDIT_EVERY == 0L }
        return PhoneDecider(current, earned, mayAct = use == PhoneModelUse.EARNED, audit = audit)
    }

    /** Keeps one routed request (not when it looks like it carries a secret). */
    fun record(context: Context, lesson: Lesson, bar: Double) {
        val kept = Lessons.keepable(lesson) { MindMemory.looksSecret(it) } ?: return
        synchronized(lock) {
            val all = (load(context) + kept).takeLast(Lessons.MAX_KEPT)
            lessons = all
            runCatching {
                val target = file(context)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(Lessons.encode(all))
                temp.renameTo(target)
            }
            if (++sinceTraining >= RETRAIN_EVERY || model == null) train(all, bar)
        }
    }

    /** The numbers for the health report, Glass and the Lab. */
    fun stats(context: Context, bar: Double, use: PhoneModelUse): org.json.JSONObject =
        DecisionStats.summary(synchronized(lock) { load(context) }, bar, Decisions.active().label, use.wire, System.currentTimeMillis())

    /** Forgets every lesson (Settings), and the phone model starts again from its built-in examples. */
    fun forget(context: Context, bar: Double) = synchronized(lock) {
        runCatching { file(context).delete() }
        lessons = emptyList()
        train(emptyList(), bar)
    }

    private fun ready(context: Context, bar: Double): PhoneModel = synchronized(lock) {
        model ?: train(load(context), bar)
    }

    private fun train(all: List<Lesson>, bar: Double): PhoneModel {
        val trained = PhoneModel.train(all, bar)
        model = trained
        earned = Earning.earned(all, bar)
        sinceTraining = 0
        return trained
    }

    private fun load(context: Context): List<Lesson> = lessons ?: Lessons.decode(runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull())
        .also { lessons = it }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)
}
