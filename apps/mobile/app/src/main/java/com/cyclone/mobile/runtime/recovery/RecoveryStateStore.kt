package com.cyclone.mobile.runtime.recovery

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface RecoveryStateStore {
    fun load(): RecoveryPersistentState
    fun save(state: RecoveryPersistentState)
}

class InMemoryRecoveryStateStore(
    initial: RecoveryPersistentState = RecoveryPersistentState(),
) : RecoveryStateStore {
    private var state = initial.normalized()

    @Synchronized
    override fun load(): RecoveryPersistentState = state.normalized()

    @Synchronized
    override fun save(state: RecoveryPersistentState) {
        this.state = state.normalized()
    }
}

class FileRecoveryStateStore(private val file: File) : RecoveryStateStore {
    init {
        val parent = requireNotNull(file.absoluteFile.parentFile) { "Recovery-state path needs a parent" }
        require(parent.exists() || parent.mkdirs()) { "Could not create recovery-state directory" }
        require(parent.isDirectory)
    }

    @Synchronized
    override fun load(): RecoveryPersistentState = if (!file.exists()) {
        RecoveryPersistentState()
    } else {
        require(file.isFile) { "Recovery-state path is not a file" }
        RecoveryStateCodec.decode(file.readText(StandardCharsets.UTF_8)).normalized()
    }

    @Synchronized
    override fun save(state: RecoveryPersistentState) {
        val parent = requireNotNull(file.absoluteFile.parentFile)
        val temporary = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        temporary.writeText(RecoveryStateCodec.encode(state.normalized()), StandardCharsets.UTF_8)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
