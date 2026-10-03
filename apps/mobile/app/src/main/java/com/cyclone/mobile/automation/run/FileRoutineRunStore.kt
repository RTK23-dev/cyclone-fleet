package com.cyclone.mobile.automation.run

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileRoutineRunStore(
    private val directory: File,
    private val maximumRuns: Int = 100,
) : RoutineRunStore {
    init {
        require(maximumRuns in 1..1_000)
        require(directory.exists() || directory.mkdirs()) { "Could not create routine-run directory" }
        require(directory.isDirectory) { "Routine-run path must be a directory" }
    }

    @Synchronized
    override fun save(run: RoutineRunRecord) {
        val target = file(run.runId)
        val temporary = File(directory, ".${run.runId.value}.${System.nanoTime()}.tmp")
        temporary.writeText(RoutineRunCodec.encode(run), StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        listAll().drop(maximumRuns).forEach { file(it.runId).delete() }
    }

    @Synchronized
    override fun load(runId: RoutineRunId): RoutineRunRecord? = decode(file(runId))

    @Synchronized
    override fun list(limit: Int): List<RoutineRunRecord> = listAll().take(limit.coerceIn(1, maximumRuns))

    @Synchronized
    override fun delete(runId: RoutineRunId): Boolean = file(runId).let { !it.exists() || it.delete() }

    private fun listAll(): List<RoutineRunRecord> = directory.listFiles { file -> file.name.endsWith(SUFFIX) }
        .orEmpty()
        .mapNotNull(::decode)
        .sortedWith(compareByDescending<RoutineRunRecord> { it.updatedAtEpochMillis }.thenBy { it.runId })

    private fun decode(file: File): RoutineRunRecord? = if (!file.isFile) null else runCatching {
        RoutineRunCodec.decode(file.readText(StandardCharsets.UTF_8))
    }.getOrNull()

    private fun file(runId: RoutineRunId) = File(directory, runId.value + SUFFIX)

    private companion object {
        const val SUFFIX = ".run.json"
    }
}
