package com.ghostlock.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/** Reads and writes [RunLedger] with an fsync before the atomic rename. */
class RunLedgerStore(private val file: File) {
    /**
     * Returns null when the ledger has never been written. A file that exists
     * but cannot be decoded is an error, not "missing": the exploit must not
     * silently treat a corrupt safety record as a clean boot.
     */
    fun read(): RunLedger? {
        if (!file.isFile) return null
        val text = file.readText()
        return RunLedgerCodec.decode(text)
            ?: throw IllegalStateException("run ledger is corrupt: ${file.absolutePath}")
    }

    fun write(ledger: RunLedger) {
        val parent = file.parentFile
            ?: throw IllegalStateException("run ledger has no parent: ${file.absolutePath}")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("cannot create ${parent.absolutePath}")
        }
        val temp = File(parent, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(RunLedgerCodec.encode(ledger).toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                temp.delete()
                throw IllegalStateException("cannot replace ${file.absolutePath}")
            }
        }
    }
}

/** Boot identity for the ledger; uptime is deliberately not used. */
object BootId {
    fun current(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
