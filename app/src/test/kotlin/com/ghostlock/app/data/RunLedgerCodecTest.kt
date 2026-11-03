package com.ghostlock.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RunLedgerCodecTest {
    private val boot = "507b645b-81fc-4999-8690-6bca31f23e32"

    @Test
    fun `round trips exploit and stage2 entries`() {
        val ledger = RunLedgerCodec.stage2Finished(
            RunLedgerCodec.exploitFinished(
                RunLedgerCodec.exploitStarted(RunLedgerCodec.fresh(boot, 10L), 10L),
                20L,
                ok = true,
                lastStep = "w3d",
            ),
            30L,
            ok = true,
        )
        val decoded = RunLedgerCodec.decode(RunLedgerCodec.encode(ledger))
        assertEquals(ledger, decoded)
    }

    @Test
    fun `missing ledger allows the exploit`() {
        assertEquals(
            RunLedgerDecision.Allow,
            RunLedgerCodec.decide(null, boot, LedgerPhaseKind.Exploit),
        )
    }

    @Test
    fun `same boot exploit attempt is refused`() {
        val ledger = RunLedgerCodec.exploitFinished(
            RunLedgerCodec.exploitStarted(RunLedgerCodec.fresh(boot, 10L), 10L),
            20L,
            ok = true,
        )
        assertTrue(
            RunLedgerCodec.decide(ledger, boot, LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
    }

    @Test
    fun `failed exploit on the same boot is also refused`() {
        val ledger = RunLedgerCodec.exploitFinished(
            RunLedgerCodec.exploitStarted(RunLedgerCodec.fresh(boot, 10L), 10L),
            20L,
            ok = false,
        )
        assertTrue(
            RunLedgerCodec.decide(ledger, boot, LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
    }

    @Test
    fun `a new boot allows the exploit`() {
        val ledger = RunLedgerCodec.exploitFinished(
            RunLedgerCodec.exploitStarted(RunLedgerCodec.fresh(boot, 10L), 10L),
            20L,
            ok = true,
        )
        assertEquals(
            RunLedgerDecision.Allow,
            RunLedgerCodec.decide(ledger, "new-boot-id", LedgerPhaseKind.Exploit),
        )
    }

    @Test
    fun `missing current boot id fails the exploit closed`() {
        assertTrue(
            RunLedgerCodec.decide(null, null, LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
        assertTrue(
            RunLedgerCodec.decide(null, "  ", LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
    }

    @Test
    fun `ledger without a boot id fails the exploit closed`() {
        val ledger = RunLedger(bootId = null, updatedAt = 1L)
        assertTrue(
            RunLedgerCodec.decide(ledger, boot, LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
    }

    @Test
    fun `unsupported schema fails the exploit closed`() {
        val ledger = RunLedger(schemaVersion = 99, bootId = boot, updatedAt = 1L)
        assertTrue(
            RunLedgerCodec.decide(ledger, boot, LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
    }

    @Test
    fun `stage2 is allowed after a same boot exploit attempt`() {
        val ledger = RunLedgerCodec.exploitFinished(
            RunLedgerCodec.exploitStarted(RunLedgerCodec.fresh(boot, 10L), 10L),
            20L,
            ok = true,
        )
        assertEquals(
            RunLedgerDecision.Allow,
            RunLedgerCodec.decide(ledger, boot, LedgerPhaseKind.Stage2),
        )
    }

    @Test
    fun `stage2 update does not erase the exploit attempt`() {
        val ledger = RunLedgerCodec.stage2Finished(
            RunLedgerCodec.exploitFinished(
                RunLedgerCodec.exploitStarted(RunLedgerCodec.fresh(boot, 10L), 10L),
                20L,
                ok = true,
            ),
            30L,
            ok = false,
        )
        assertEquals(RunLedgerCodec.Done, ledger.exploit.status)
        assertEquals(RunLedgerCodec.Failed, ledger.stage2.status)
        assertEquals(RunLedgerCodec.ChecksFail, ledger.stage2.checks)
        assertTrue(
            RunLedgerCodec.decide(ledger, boot, LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
    }

    @Test
    fun `stage2 after an unreadable ledger keeps the exploit blocked`() {
        val ledger = RunLedgerCodec.stage2Finished(
            RunLedgerCodec.freshBlocked(boot, 10L, "ledger_unreadable"),
            20L,
            ok = true,
        )
        assertEquals(RunLedgerCodec.Failed, ledger.exploit.status)
        assertEquals("ledger_unreadable", ledger.exploit.lastStep)
        assertTrue(
            RunLedgerCodec.decide(ledger, boot, LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
    }

    @Test
    fun `unknown exploit status on the same boot is refused`() {
        val ledger = RunLedger(
            bootId = boot,
            exploit = LedgerPhase(status = "mystery"),
            updatedAt = 1L,
        )
        assertTrue(
            RunLedgerCodec.decide(ledger, boot, LedgerPhaseKind.Exploit) is RunLedgerDecision.Block,
        )
    }

    @Test
    fun `corrupt documents do not decode`() {
        assertNull(RunLedgerCodec.decode(""))
        assertNull(RunLedgerCodec.decode("not a config"))
        assertNull(RunLedgerCodec.decode("{\"schema_version\": 1}"))
    }

    @Test
    fun `store reads missing as null and round trips through a file`() {
        val dir = Files.createTempDirectory("run-ledger").toFile()
        try {
            val store = RunLedgerStore(dir.resolve("run_ledger.json"))
            assertNull(store.read())
            val ledger = RunLedgerCodec.exploitStarted(RunLedgerCodec.fresh(boot, 10L), 10L)
            store.write(ledger)
            assertEquals(ledger, store.read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `store rejects a corrupt file instead of treating it as missing`() {
        val dir = Files.createTempDirectory("run-ledger").toFile()
        try {
            val file = dir.resolve("run_ledger.json")
            file.writeText("corrupt")
            val store = RunLedgerStore(file)
            assertTrue(runCatching { store.read() }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }
}
