package com.ghostlock.app.domain.repository

import com.ghostlock.app.data.ManagerScanner
import com.ghostlock.app.domain.model.CpuPair
import com.ghostlock.app.domain.model.DebugSettings
import com.ghostlock.app.domain.model.KernelSnapshot
import com.ghostlock.app.domain.model.OffsetCandidate
import com.ghostlock.app.domain.model.OffsetImportResult
import com.ghostlock.app.domain.model.ParseResult
import com.ghostlock.app.domain.model.ProfileConfig
import com.ghostlock.app.domain.model.UserProfileFile
import com.ghostlock.app.runtime.ComponentDetector

interface GhostlockRepository {
    suspend fun snapshot(): KernelSnapshot

    fun selectCpuPair(index: Int)

    fun setSafeModeEnabled(enabled: Boolean)

    /** Skip the pre-attack KernelSU check and run the exploit as a test. */
    fun setForceAttackTest(enabled: Boolean)

    /** User-chosen manager package, or null for auto-detect. */
    fun selectedManagerPackage(): String?

    fun setSelectedManagerPackage(pkg: String?)

    /** All installed non-system apps, compatible-first ordering. */
    suspend fun installedManagers(): List<ManagerScanner.Candidate>


    /**
     * Imports one or more picked documents (file name -> text). Includes are
     * resolved against the picked files first, then the bundled assets; a
     * missing include fails the import so the user can pick it too.
     */
    suspend fun importOffsets(documents: Map<String, String>): OffsetImportResult

    suspend fun confirmImport(documents: Map<String, String>): OffsetImportResult

    suspend fun parseSource(
        input: String,
        xblPath: String? = null,
        uefiPath: String? = null,
        /** vendor_boot.img (MediaTek only); derives kernel_phys_load AND
         * kernel_phys_offset from its header. */
        vendorBootPath: String? = null,
        overwrite: Boolean = false,
        onLog: (String) -> Unit = {},
    ): ParseResult

    suspend fun readDocument(uri: String): String

    suspend fun cacheDocument(uri: String, fileName: String): String

    /**
     * Copies a picked module zip into the runtime home's modules/ directory.
     *
     * The bundled stack is the ReZygisk + Vector pair, shipped inside the APK
     * under `assets/modules/`; the user may also pick a zip manually, and the
     * native root script installs whatever is in the staging dir.
     */
    suspend fun stageModuleZip(uri: String, name: String): String =
        cacheDocument(uri, name)

    /**
     * Copies the bundled KernelSU manager APK from `assets/` into the app cache
     * and returns its path, or null when the bundled copy is missing or not a
     * valid APK. The UI hands the path to the package installer: the manager is
     * the one component that must be an installed app, because the root script
     * loads `libksud.so` from it.
     */
    suspend fun fetchKernelSuManager(onLog: (String) -> Unit): String?

    /**
     * Extracts the Vector manager APK from the bundled `assets/modules/vector.zip`
     * into the app cache and returns its path, or null when the entry is missing
     * or not a valid APK. The UI hands the path to the package installer so the
     * user can install the Vector companion app without activating the module first.
     */
    suspend fun fetchVectorManager(onLog: (String) -> Unit): String?

    /** Whether the Vector manager companion app is installed. */
    suspend fun vectorManagerInstalled(): Boolean

    /**
     * Checks, without running the exploit, which parts of the root stack are
     * already in place: staged component zips, an installed manager, and a
     * loaded KernelSU module. See `ComponentDetector.Status`.
     */
    suspend fun detectComponents(): List<ComponentDetector.Status>

    /**
     * Whether the device already holds root right now.
     *
     * This chain grants *temporary* root and refuses to start when a persistent
     * one is present, because the KernelSU module it would load is already
     * loaded. The probe prefers `/proc/modules` and falls back to `su -c 'id -u'`.
     */
    /** Whether this app context holds a KernelSU `su` grant. */
    suspend fun suGranted(): Boolean

    suspend fun deviceHasRoot(): Boolean

    /**
     * Whether the root script's status file records a verified Vector activation
     * for this boot. The app's in-memory flag cannot survive a restart, so the
     * persisted file is the only cold-start evidence that step 2 is already done.
     */
    suspend fun vectorReady(): Boolean

    suspend fun publishOffsets(candidate: OffsetCandidate): String

    /** Verbatim user-imported documents, newest first. */
    suspend fun userProfiles(): List<UserProfileFile>

    suspend fun deleteUserProfile(name: String): Boolean

    /** Returns the resulting file name, or null when the rename failed. */
    suspend fun renameUserProfile(name: String, newName: String): String?

    /** Renders a stored document as HOCON, writes it to Downloads and shares it. */
    suspend fun exportUserProfile(name: String): String

    /**
     * Converts a legacy stored document into the current layout, stores the
     * result as a new user document and returns its file name.
     */
    suspend fun convertUserProfile(name: String): String?

    /**
     * Opens an isolated editing session for the saved profile [name] (null for
     * the current builtin source) and returns its resolved configuration. The
     * session never touches the live attack controller until committed.
     */
    suspend fun beginEditSession(name: String?): ProfileConfig?

    /** Controller of the open editing session, null when none is open. */
    fun editSessionController(): ProfileConfigController?

    /** True when the session edits the profile the attack controller loads. */
    fun editSessionIsLive(): Boolean

    /** File name edited by the open session; null when it edits the builtin. */
    fun editSessionTarget(): String?

    /** Copies the session overrides into the live controller. */
    suspend fun commitEditSession(): Boolean

    /** Updates the edited document in place with the session result. */
    suspend fun saveEditSessionInPlace(): Boolean

    /** Stores the session result as a new saved profile; returns its name. */
    suspend fun saveEditSessionAsNew(): String?

    /** Renders the session result as HOCON, writes it to Downloads, shares it. */
    suspend fun exportEditSession(): String

    /** Release the editing session targets; a stored document uses its own. */
    fun editSessionRelease(): String?

    fun endEditSession()

    /** Single authority for loading, editing and exporting profile config. */
    fun profileController(): ProfileConfigController

    suspend fun debugSettings(): DebugSettings

    fun setDebugExportEnabled(enabled: Boolean)

    fun setDebugExportLocation(location: String)

    fun setDebugKernelLogEnabled(enabled: Boolean)

    suspend fun runExploit(pair: CpuPair, onLog: (String) -> Unit, phase: String = "root"): Int

    /**
     * The step left `in_progress` by the previous run (usually a kernel panic),
     * or null when the last run completed/failed cleanly.
     */
    suspend fun lastRunStuckStep(): String?

    fun close()
}
