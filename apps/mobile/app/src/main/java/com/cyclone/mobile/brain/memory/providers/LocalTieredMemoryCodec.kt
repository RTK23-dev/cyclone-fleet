package com.cyclone.mobile.brain.memory.providers

import com.cyclone.mobile.brain.memory.api.MemoryActor
import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryContent
import com.cyclone.mobile.brain.memory.api.MemoryProvenance
import com.cyclone.mobile.brain.memory.api.MemoryRecord
import com.cyclone.mobile.brain.memory.api.MemoryScope
import com.cyclone.mobile.brain.memory.api.MemoryScopeKind
import com.cyclone.mobile.brain.memory.api.MemorySensitivity
import com.cyclone.mobile.brain.memory.api.MemorySourceKind
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object LocalTieredMemoryCodec {
    private const val MAGIC = 0x43594D33
    private const val FORMAT_VERSION = 1
    private const val MAX_RECORDS = 100_000
    private const val MAX_COLLECTION_SIZE = 100_000
    private const val MAX_STRING_BYTES = 4 * 1024 * 1024

    fun read(path: Path): List<MemoryRecord> {
        if (!Files.exists(path)) return emptyList()
        DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
            require(input.readInt() == MAGIC) { "Memory store magic is invalid" }
            require(input.readInt() == FORMAT_VERSION) { "Memory store format is unsupported" }
            val count = input.readBoundedCount(MAX_RECORDS)
            return List(count) { input.readRecord() }
        }
    }

    fun writeAtomically(path: Path, records: Collection<MemoryRecord>) {
        require(records.size <= MAX_RECORDS) { "Memory store record limit exceeded" }
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.staging")
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeInt(records.size)
            records.sortedWith(compareBy<MemoryRecord>({ it.scope.kind.name }, { it.scope.scopeId }, { it.recordId }))
                .forEach { output.writeRecord(it) }
        }
        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun DataOutputStream.writeRecord(record: MemoryRecord) {
        writeString(record.recordId)
        writeInt(record.schemaVersion)
        writeInt(record.recordVersion)
        writeString(record.source.actorId)
        writeString(record.source.sourceKind.name)
        writeString(record.provenance.sourceSystem)
        writeInt(record.provenance.evidenceReferences.size)
        record.provenance.evidenceReferences.sorted().forEach { writeString(it) }
        writeLong(record.provenance.observedAtEpochMillis)
        writeLong(record.createdAtEpochMillis)
        writeLong(record.updatedAtEpochMillis)
        writeDouble(record.confidence)
        writeString(record.verificationState.name)
        writeString(record.scope.kind.name)
        writeString(record.scope.scopeId)
        writeString(record.sensitivity.name)
        writeString(record.memoryClass.name)
        writeInt(record.content.fields.size)
        record.content.fields.toSortedMap().forEach { (key, value) ->
            writeString(key)
            writeString(value)
        }
        writeString(record.contentFingerprint)
        writeBoolean(record.archived)
    }

    private fun DataInputStream.readRecord(): MemoryRecord {
        val recordId = readString()
        val schemaVersion = readInt()
        val recordVersion = readInt()
        val source = MemoryActor(readString(), enumValueOf<MemorySourceKind>(readString()))
        val sourceSystem = readString()
        val evidence = buildSet {
            repeat(readBoundedCount(MAX_COLLECTION_SIZE)) { add(readString()) }
        }
        val provenance = MemoryProvenance(sourceSystem, evidence, readLong())
        val createdAt = readLong()
        val updatedAt = readLong()
        val confidence = readDouble()
        val verification = enumValueOf<MemoryVerificationState>(readString())
        val scope = MemoryScope(enumValueOf<MemoryScopeKind>(readString()), readString())
        val sensitivity = enumValueOf<MemorySensitivity>(readString())
        val memoryClass = enumValueOf<MemoryClass>(readString())
        val content = linkedMapOf<String, String>()
        repeat(readBoundedCount(MAX_COLLECTION_SIZE)) {
            val key = readString()
            require(key !in content) { "Duplicate memory content key" }
            content[key] = readString()
        }
        return MemoryRecord(
            recordId,
            schemaVersion,
            recordVersion,
            source,
            provenance,
            createdAt,
            updatedAt,
            confidence,
            verification,
            scope,
            sensitivity,
            memoryClass,
            MemoryContent(content),
            readString(),
            readBoolean(),
        )
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Memory string exceeds local format limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readBoundedCount(MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readBoundedCount(maximum: Int): Int {
        val value = readInt()
        require(value in 0..maximum) { "Memory local format count is invalid" }
        return value
    }
}
