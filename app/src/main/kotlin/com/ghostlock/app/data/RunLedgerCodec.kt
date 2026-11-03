package com.ghostlock.app.data

/**
 * Independent ledger for the two-phase run.
 *
 * Unlike [RunStateCodec], this file is a safety gate, not telemetry: it records
 * whether the exploit was already attempted on this boot, so a stage-2 retry
 * cannot accidentally run the exploit again. The exploit and stage 2 are
 * separate entries because stage 2 must be allowed to update its own status
 * without erasing the exploit attempt that makes a same-boot re-run unsafe.
 */
data class LedgerPhase(
    val status: String = RunLedgerCodec.NotStarted,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val lastStep: String? = null,
    val checks: String? = null,
)

data class RunLedger(
    val schemaVersion: Int = RunLedgerCodec.SchemaVersion,
    val bootId: String?,
    val exploit: LedgerPhase = LedgerPhase(),
    val stage2: LedgerPhase = LedgerPhase(),
    val updatedAt: Long,
)

enum class LedgerPhaseKind { Exploit, Stage2 }

sealed class RunLedgerDecision {
    object Allow : RunLedgerDecision()
    data class Block(val reason: String) : RunLedgerDecision()
}

object RunLedgerCodec {
    const val SchemaVersion = 1

    const val NotStarted = "not_started"
    const val Running = "running"
    const val Done = "done"
    const val Failed = "failed"

    const val ChecksPass = "pass"
    const val ChecksFail = "fail"
    const val ChecksUnknown = "unknown"

    fun fresh(bootId: String?, now: Long): RunLedger =
        RunLedger(bootId = bootId, updatedAt = now)

    /**
     * A fresh ledger whose exploit entry is already failed. Stage 2 uses this
     * when the previous ledger exists but cannot be decoded: stage 2 may still
     * run (it does not touch the kernel write path), but replacing the corrupt
     * record with a clean `not_started` entry would re-open the same-boot
     * exploit gate. The failed entry keeps the gate closed.
     */
    fun freshBlocked(bootId: String?, now: Long, reason: String): RunLedger =
        exploitFinished(fresh(bootId, now), now, ok = false, lastStep = reason)

    fun exploitStarted(ledger: RunLedger, now: Long): RunLedger =
        ledger.copy(
            exploit = ledger.exploit.copy(
                status = Running,
                startedAt = ledger.exploit.startedAt ?: now,
                finishedAt = null,
                lastStep = null,
            ),
            updatedAt = now,
        )

    fun exploitFinished(
        ledger: RunLedger,
        now: Long,
        ok: Boolean,
        lastStep: String? = null,
    ): RunLedger =
        ledger.copy(
            exploit = ledger.exploit.copy(
                status = if (ok) Done else Failed,
                finishedAt = now,
                lastStep = lastStep ?: ledger.exploit.lastStep,
            ),
            updatedAt = now,
        )

    fun stage2Started(ledger: RunLedger, now: Long): RunLedger =
        ledger.copy(
            stage2 = ledger.stage2.copy(
                status = Running,
                startedAt = ledger.stage2.startedAt ?: now,
                finishedAt = null,
                checks = ChecksUnknown,
            ),
            updatedAt = now,
        )

    fun stage2Finished(
        ledger: RunLedger,
        now: Long,
        ok: Boolean,
        checks: String = if (ok) ChecksPass else ChecksFail,
    ): RunLedger =
        ledger.copy(
            stage2 = ledger.stage2.copy(
                status = if (ok) Done else Failed,
                finishedAt = now,
                checks = checks,
            ),
            updatedAt = now,
        )

    /**
     * Exploit is fail-closed: a missing current boot id, a corrupt ledger, or
     * an unreadable ledger entry all refuse the run. Stage 2 is always allowed
     * because it does not touch the kernel write path and is the only safe
     * retry after a half-success.
     */
    fun decide(
        ledger: RunLedger?,
        currentBootId: String?,
        phase: LedgerPhaseKind,
    ): RunLedgerDecision {
        if (phase == LedgerPhaseKind.Stage2) return RunLedgerDecision.Allow
        if (currentBootId.isNullOrBlank()) {
            return RunLedgerDecision.Block("boot_id is unavailable; refusing the exploit (fail-closed)")
        }
        if (ledger == null) return RunLedgerDecision.Allow
        if (ledger.schemaVersion != SchemaVersion) {
            return RunLedgerDecision.Block("unsupported ledger schema ${ledger.schemaVersion}")
        }
        if (ledger.bootId.isNullOrBlank()) {
            return RunLedgerDecision.Block("ledger has no boot_id; refusing the exploit (fail-closed)")
        }
        if (ledger.bootId != currentBootId) return RunLedgerDecision.Allow
        return if (ledger.exploit.status == NotStarted) {
            RunLedgerDecision.Allow
        } else {
            RunLedgerDecision.Block(
                "exploit already attempted on this boot (${ledger.exploit.status})",
            )
        }
    }

    fun encode(ledger: RunLedger): String = buildString {
        append("{\n")
        append("  \"schema_version\": ").append(ledger.schemaVersion).append(",\n")
        append("  \"boot_id\": ").append(quoteOrNull(ledger.bootId)).append(",\n")
        append("  \"exploit\": ")
        appendPhase(ledger.exploit, indent = "  ")
        append(",\n")
        append("  \"stage2\": ")
        appendPhase(ledger.stage2, indent = "  ")
        append(",\n")
        append("  \"updated_at\": ").append(ledger.updatedAt).append('\n')
        append("}\n")
    }

    fun decode(text: String): RunLedger? {
        val root = HoconSupport.parseValue(text) as? Map<*, *> ?: return null
        val schema = (root["schema_version"] as? Number)?.toInt() ?: return null
        val bootId = root["boot_id"] as? String
        val exploit = parsePhase(root["exploit"] as? Map<*, *>) ?: return null
        val stage2 = parsePhase(root["stage2"] as? Map<*, *>) ?: return null
        val updatedAt = (root["updated_at"] as? Number)?.toLong() ?: return null
        return RunLedger(
            schemaVersion = schema,
            bootId = bootId,
            exploit = exploit,
            stage2 = stage2,
            updatedAt = updatedAt,
        )
    }

    private fun parsePhase(map: Map<*, *>?): LedgerPhase? {
        map ?: return null
        val status = map["status"] as? String ?: return null
        return LedgerPhase(
            status = status,
            startedAt = (map["started_at"] as? Number)?.toLong(),
            finishedAt = (map["finished_at"] as? Number)?.toLong(),
            lastStep = map["last_step"] as? String,
            checks = map["checks"] as? String,
        )
    }

    private fun StringBuilder.appendPhase(phase: LedgerPhase, indent: String) {
        append("{\n")
        append(indent).append("  \"status\": ").append(quote(phase.status)).append(",\n")
        append(indent).append("  \"started_at\": ").append(phase.startedAt ?: "null").append(",\n")
        append(indent).append("  \"finished_at\": ").append(phase.finishedAt ?: "null").append(",\n")
        append(indent).append("  \"last_step\": ").append(quoteOrNull(phase.lastStep)).append(",\n")
        append(indent).append("  \"checks\": ").append(quoteOrNull(phase.checks)).append('\n')
        append(indent).append('}')
    }

    private fun quoteOrNull(value: String?): String = if (value == null) "null" else quote(value)

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
