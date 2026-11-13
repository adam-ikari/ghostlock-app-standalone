package com.ghostlock.app.runtime

import android.content.Context
import android.content.pm.PackageManager
import com.ghostlock.app.data.ManagerScanner
import java.io.File
import java.util.concurrent.TimeUnit

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
     * none is usable. Three sources, in order:
     *
     * 1. A staged copy named `<fileName>` under `<filesDir>/modules/`.
     * 2. Any `*.zip` there whose `module.prop` id matches (the file picker keeps
     *    the original name, and the native script installs every zip in the dir).
     * 3. The bundled asset `assets/modules/<fileName>` — a fresh install must
     *    not report the shipped modules "missing" and send the user off to
     *    fetch something that is already on disk inside the APK.
     */
    fun stagedVersion(context: Context, component: ComponentFetcher.Component): String? {
        val modulesDir = File(context.filesDir, "modules")
        val exact = File(modulesDir, component.fileName)
        if (exact.isFile) {
            ComponentFetcher.verifyZip(exact, component.moduleId)?.let { return it }
        }
        if (modulesDir.isDirectory) {
            modulesDir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
                ?.forEach { zip ->
                    if (zip.name != component.fileName) {
                        ComponentFetcher.verifyZip(zip, component.moduleId)?.let { return it }
                    }
                }
        }
        return runCatching {
            context.assets.open("modules/${component.fileName}").use { input ->
                java.util.zip.ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    var version: String? = null
                    while (entry != null) {
                        if (entry.name == "module.prop") {
                            val props = zis.bufferedReader().readText()
                            val hasId = props.lineSequence()
                                .firstOrNull { it.startsWith("id=") }
                                ?.removePrefix("id=")?.trim()
                            if (hasId == component.moduleId) {
                                version = props.lineSequence()
                                    .firstOrNull { it.startsWith("version=") }
                                    ?.removePrefix("version=")?.trim()
                            }
                            break
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    version
                }
            }
        }.getOrNull()
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
     * Whether the root script recorded a finished Vector activation for this
     * boot. Three fields must agree: `state=ready`, `ready=1`, and a `boot_id`
     * equal to the current boot's.
     *
     * Unlike [rootedInThisBoot] this *does* consult `state`: the script writes
     * `ready` only on the paths that verified the injection, and a supervisor
     * that died mid-run leaves `starting`/`installing`, which must not light up
     * the step. The residual false positive — a `ready` file whose injection has
     * since been lost — is bounded by the reboot phase, which re-verifies and
     * fails closed on its own.
     */
    internal fun vectorActivated(statusFile: File, currentBootId: String?): Boolean {
        if (currentBootId == null) return false
        val recorded = statusField(statusFile, "boot_id") ?: return false
        val state = statusField(statusFile, "state") ?: return false
        val ready = statusField(statusFile, "ready") ?: return false
        return recorded == currentBootId && state == "ready" && ready == "1"
    }

    fun vectorReady(context: Context): Boolean =
        vectorActivated(File(context.filesDir, ".ghostlock_status"), currentBootId())

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
    private fun suReturnsZero(): Boolean = suGranted()

    /**
     * `true` when this app context is allowed to run a command through `su`,
     * i.e. the KernelSU manager granted GhostLock root.
     *
     * KernelSU decides that grant interactively: an unauthorised request parks
     * the process until the user answers its own prompt, so the wait is
     * bounded — a UI refresh must never hang on that dialog.
     */
    fun suGranted(): Boolean = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return@runCatching false
        }
        proc.inputStream.bufferedReader().readText().trim() == "0"
    }.getOrDefault(false)

    /**
     * Runs all checks and returns a ready-to-render list, in the order the
     * card shows them: Vector staged, manager, module active.
     */
    fun detectAll(
        context: Context,
        pm: PackageManager,
        selfPackage: String,
    ): List<Status> {
        val vector = ComponentFetcher.byKey(ComponentFetcher.VectorKey)!!
        val stagedVector = stagedVersion(context, vector)
        val manager = installedManager(pm, selfPackage)
        val active = moduleActive(context)
        return listOf(
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
