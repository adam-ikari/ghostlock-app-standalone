package com.ghostlock.app.runtime

import android.content.Context
import android.content.pm.PackageManager
import com.ghostlock.app.data.ManagerScanner
import java.io.File

/**
 * Detects whether the three moving parts of the root stack are in place,
 * *without* running the exploit:
 *
 * 1. **staged** — a verified component zip sits in `<filesDir>/modules/`,
 *    ready for the native root script to install.
 * 2. **manager** — a KernelSU-family manager APK is installed on the device
 *    and carries `libksud.so` (the late-load target).
 * 3. **module active** — the KernelSU kernel module is loaded right now, so
 *    the device already has (persistent) root; `su -c 'id -u'` answers `0`.
 *
 * Each leg is independent: the user may have staged a zip without a manager,
 * or have a manager without a staged zip, etc. The UI shows all four rows.
 */
object ComponentDetector {

    /** One row of the detection card. */
    data class Status(
        val key: String,
        val label: String,
        /** `null` = not checked yet; a string = the checked result. */
        val detail: String? = null,
        val ok: Boolean = false,
    )

    /**
     * The version string of a staged, verified component zip, or `null` when
     * the zip is absent / too small / fails `module.prop` verification.
     *
     * Reads `<filesDir>/modules/<fileName>`; the staging dir and naming are
     * owned by [ComponentFetcher.fetch], so this stays in lock-step with it.
     */
    fun stagedVersion(context: Context, component: ComponentFetcher.Component): String? {
        val zip = File(context.filesDir, "modules/${component.fileName}")
        return ComponentFetcher.verifyZip(zip, component.moduleId)
    }

    /**
     * The first installed manager candidate that carries `libksud.so`, or
     * `null` when none is installed. Reuses [ManagerScanner] so the candidate
     * list and the detection card never disagree.
     */
    fun installedManager(
        pm: PackageManager,
        selfPackage: String,
    ): ManagerScanner.Candidate? =
        ManagerScanner.scan(pm, selfPackage).firstOrNull { it.hasKsud }

    /**
     * Whether this boot is rooted, i.e. the KernelSU module is loaded and this
     * app is authorised to use `su`. Three independent signals, either
     * sufficient:
     *
     * - The status file the root script writes records this boot's id, which
     *   only happens after the exploit succeeded.
     * - `/proc/modules` lists `kernelsu` (module is in the kernel).
     * - `su -c 'id -u'` prints `0` (root grant works from this app context).
     *
     * The first signal is the one that works on this device. The other two are
     * kept because they are correct elsewhere, but under `untrusted_app` both
     * report nothing even while KernelSU is live: `/proc/modules` is EACCES,
     * and the `su` that KernelSU installs lives in the root mount namespace so
     * this context cannot even stat it. Relying on them alone greyed out the
     * activate step on a device that was already rooted.
     */
    fun moduleActive(context: Context): Boolean =
        rootedInThisBoot(context) || ksuInProcModules() || suReturnsZero()

    /**
     * `true` when the status file carries a `boot_id` equal to the current
     * boot's. The root script owns that file, so a match proves this boot has
     * already been rooted; a cold boot changes the id and clears the module.
     * The state field is deliberately not consulted: a supervisor that exits
     * after the handoff leaves it at `installing`, and the module is loaded
     * either way.
     */
    internal fun rootedInThisBoot(context: Context): Boolean = runCatching {
        val recorded = statusField(File(context.filesDir, ".ghostlock_status"), "boot_id")
        recorded != null && recorded == currentBootId()
    }.getOrDefault(false)

    /**
     * Value of the `key=value` line [key] in [file], or `null`. A flat
     * `key=value` list written by the root script, so it is parsed by hand
     * rather than through a properties parser that would also accept the
     * `schema=1` line as data.
     */
    internal fun statusField(file: File, key: String): String? = runCatching {
        file.useLines { lines ->
            lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** This boot's id, or `null` when unreadable (older kernels). */
    private fun currentBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }


    /**
     * Pure slice of [moduleActive]: `true` when any line of `/proc/modules`
     * names a loaded `kernelsu` module. Extracted so the parsing is testable
     * without a real `/proc` or `su`.
     */
    internal fun ksuLoadedIn(procModules: String): Boolean =
        procModules.lineSequence().any { line ->
            line.substringBefore(' ').substringBefore('\t') == "kernelsu"
        }

    private fun ksuInProcModules(): Boolean = runCatching {
        ksuLoadedIn(File("/proc/modules").bufferedReader().readText())
    }.getOrDefault(false)

    /**
     * `su -c 'id -u'` printing `0` means this app context already holds a root
     * grant. Mirrors `handoff_probe.cpp::ksu_root_owned`, so both report the
     * same thing on the same device.
     */
    private fun suReturnsZero(): Boolean = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
        val text = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        text == "0"
    }.getOrDefault(false)

    /**
     * Runs all three checks and returns a ready-to-render list, in the order
     * the card shows them: ZygiskNext, Vector, manager, module.
     */
    fun detectAll(
        context: Context,
        pm: PackageManager,
        selfPackage: String,
    ): List<Status> {
        val zygiskNext = ComponentFetcher.byKey(ComponentFetcher.ZygiskNextKey)!!
        val vector = ComponentFetcher.byKey(ComponentFetcher.VectorKey)!!
        val stagedZygiskNext = stagedVersion(context, zygiskNext)
        val stagedVector = stagedVersion(context, vector)
        val manager = installedManager(pm, selfPackage)
        val active = moduleActive(context)
        return listOf(
            Status(
                key = "zygisknext_staged",
                label = zygiskNext.label,
                detail = stagedZygiskNext,
                ok = stagedZygiskNext != null,
            ),
            Status(
                key = "vector_staged",
                label = vector.label,
                detail = stagedVector,
                ok = stagedVector != null,
            ),
            Status(
                key = "manager_installed",
                label = "KernelSU manager",
                detail = manager?.label,
                ok = manager != null,
            ),
            Status(
                key = "module_active",
                label = "KernelSU module",
                detail = if (active) "active" else "inactive",
                ok = active,
            ),
        )
    }
}
