package com.ghostlock.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.R
import com.ghostlock.app.data.ManagerScanner
import com.ghostlock.app.domain.model.CpuPair
import com.ghostlock.app.domain.model.ExecutionFieldValue
import com.ghostlock.app.domain.model.ProfileFieldNode
import com.ghostlock.app.domain.model.RunCheck
import com.ghostlock.app.domain.model.UserProfileFile
import com.ghostlock.app.runtime.ComponentDetector
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

data class GhostlockUiState(
    val deviceName: String = "",
    val kernelRelease: String = "",
    val socName: String = "",
    val kernelSupported: Boolean = false,
    val running: Boolean = false,
    val cpuPairLabels: List<String> = emptyList(),
    val cpuPairIndex: Int = 0,
    /** CPU pair from the resolved profile when it differs from the device pick. */
    val customCpuPair: CpuPair? = null,
    val safeModeEnabled: Boolean = false,
    val forceAttackTestEnabled: Boolean = false,
    val tcpRouteEnabled: Boolean = true,
    val compact: Boolean = false,
    val executionSheetVisible: Boolean = false,
    val executionSheetDismissible: Boolean = false,
    val dialogVisible: Boolean = false,
    val dialogType: DialogType = DialogType.NONE,
    val dialogTitleRes: Int = 0,
    val dialogMessage: String = "",
    val dialogMessageRes: Int = 0,
    val dialogItems: List<String> = emptyList(),
    val dialogItemResIds: List<Int> = emptyList(),
    val dialogCurrentItemIndex: Int = -1,
    val dialogInput: String = "",
    val dialogConfirmLabelRes: Int = R.string.parse_start,
    /** Documentation URL shown as an extra button on a NOTICE dialog. */
    val dialogDocUrl: String? = null,
    val overwriteDialogVisible: Boolean = false,
    val overwriteMessage: String = "",
    val logLines: List<GhostlockLogLine> = emptyList(),
    val executionRelease: String = "",
    val executionHasProfile: Boolean = false,
    val executionFields: List<ExecutionFieldValue> = emptyList(),
    val executionEditing: Map<String, String> = emptyMap(),
    val advancedScreenVisible: Boolean = false,
    val debugExportEnabled: Boolean = true,
    val debugExportLocation: String = "",
    val debugKernelLogEnabled: Boolean = true,
    val aboutVisible: Boolean = false,
    /** Open legal document on top of About; null when none is open. */
    val legalDocument: LegalDocument? = null,
    val parametersVisible: Boolean = false,
    val profileOverrideVisible: Boolean = false,
    val advancedOverrideVisible: Boolean = false,
    /** Stored document edited by the open session; null for the builtin. */
    val editTargetName: String? = null,
    val profileOverrideRelease: String = "",
    val profileOverrideRoots: List<ProfileFieldNode> = emptyList(),
    val profileOverrideEditing: Map<String, String> = emptyMap(),
    /** Controller-reported geometry violations, dotted paths. */
    val profileInvalidPaths: Set<String> = emptySet(),
    /** Explicit route from the profile; null means geometry inference. */
    val profileRoute: String? = null,
    /** Declared fallback route; null/"none" means disabled. */
    val profileFallback: String? = null,
    /** Manually selected builtin source; null means automatic matching. */
    val activeBuiltinProfile: String? = null,
    val builtinScreenVisible: Boolean = false,
    /** Unfilled reference templates, listed separately on the builtin picker. */
    val builtinTemplates: List<String> = emptyList(),
    /** Builtin releases sorted by similarity to the device kernel. */
    val builtinProfiles: List<String> = emptyList(),
    val loadConfigVisible: Boolean = false,
    /** Verbatim documents in the user profile folder, newest first. */
    val userProfiles: List<UserProfileFile> = emptyList(),
    /** Loaded user document feeding the imported layer; null means none. */
    val activeUserProfile: String? = null,
    /** File name of the open user-profile detail screen, null when closed. */
    val userProfileDetail: String? = null,
    val userProfileRenameTarget: String? = null,
    val userProfileDeleteTarget: String? = null,
    val managerCandidates: List<ManagerScanner.Candidate> = emptyList(),
    val selectedManagerPackage: String? = null,
    val managerPickerVisible: Boolean = false,
    /** Pre-flight requirements of the one-tap run. */
    val runChecks: List<com.ghostlock.app.domain.model.RunCheck> = emptyList(),
    /** Companion managers the setup flow installs from bundled assets. */
    val kernelSuManagerInstalled: Boolean = false,
    val vectorManagerInstalled: Boolean = false,
    /** This app context holds a KernelSU `su` grant (needed by activate). */
    val suGranted: Boolean = true,
    /** Per-component detection rows; empty until the user taps "检测". */
    val componentStatuses: List<ComponentDetector.Status> = emptyList(),
    val componentDetecting: Boolean = false,
    /** Temporary root is present (this session or carried over). */
    val rootActive: Boolean = false,
    /** Vector passed its post-activation verification. */
    val vectorActive: Boolean = false,
    /** A setup pass (root / activate / reboot) owns the native binary. */
    val setupBusy: Boolean = false,
    /** Human-readable phase of the running setup pass. */
    val setupState: String = "",
    val setupError: String? = null,
)

enum class DialogType { NONE, LIST, INPUT, NOTICE, MANAGERS, SU_AUTH }

data class GhostlockLogLine(val text: String, val color: Int)

interface GhostlockActions {
    fun onGetRoot()
    fun onActivateFramework()
    fun onProfileInvalid()
    fun onCloseExecutionSheet()
    fun onCopyLogs()
    fun onImportOffsetsHocon()
    fun onImportOffsetsJson()
    fun onDocumentsResult(request: DocumentRequest, uris: List<String>)
    fun onParseOta()
    fun onParseImage()
    fun onCpuPairSelected(index: Int)
    fun onSafeModeChanged(enabled: Boolean)
    fun onForceAttackTestChanged(enabled: Boolean)
    fun onPickModuleZip()
    fun onFetchKernelSuManager()
    fun onDetectComponents()
    fun onFetchVectorManager()
    fun onOpenKernelSuManager()
    fun onRetrySuAuthorization()

    fun onOpenManagerPicker()
    fun onCloseManagerPicker()
    fun onManagerSelected(pkg: String?)
    fun onDialogItemSelected(index: Int)
    fun onDialogInputChange(value: String)
    fun onDialogConfirm(value: String)
    fun onDialogDismiss()
    fun onDialogDismissFinished()
    fun onOverwriteConfirm()
    fun onOverwriteDismiss()
    fun onExecutionFieldChanged(path: String, value: String)
    fun onRouteChanged(index: Int)
    fun onFallbackChanged(index: Int)
    fun onExportProfile()
    fun onSaveProfileEdits()
    fun onSaveProfileAs()
    fun onExportProfileEdits()
    fun onRevertProfileEdits()
    fun onOpenAdvanced()
    fun onCloseAdvanced()
    fun onShowAbout()
    fun onCloseAbout()
    fun onOpenLegalDocument(document: LegalDocument)
    fun onCloseLegalDocument()
    fun onDebugExportChanged(enabled: Boolean)
    fun onDebugExportLocationPick()
    fun onDebugKernelLogChanged(enabled: Boolean)
    fun onOpenParameters()
    fun onCloseParameters()
    fun onOpenLoadConfig()
    fun onCloseLoadConfig()
    fun onOpenUserProfileDetail(name: String)
    fun onCloseUserProfileDetail()
    fun onLoadUserProfile(name: String)
    fun onUnloadUserProfile()
    fun onEditUserProfile(name: String)
    fun onUserProfileRename(name: String)
    fun onUserProfileExport(name: String)
    fun onConvertUserProfile(name: String)
    fun onUserProfileDelete(name: String)
    fun onUserProfileDeleteConfirm()
    fun onUserProfileDeleteDismiss()
    fun onOpenBuiltinProfiles()
    fun onCloseBuiltinProfiles()
    fun onSelectBuiltinProfile(release: String?)
    fun onOpenProfileOverrides()
    fun onCloseProfileOverrides()
    fun onOpenAdvancedOverrides()
    fun onCloseAdvancedOverrides()
    fun onProfileOverrideChanged(path: String, value: String)
}

internal sealed interface GhostlockScreen : NavKey {
    data object Main : GhostlockScreen
    data object Advanced : GhostlockScreen
    data object About : GhostlockScreen
    data class Legal(val document: LegalDocument) : GhostlockScreen
    data object Parameters : GhostlockScreen
    data object LoadConfig : GhostlockScreen
    data object Builtin : GhostlockScreen
    data class UserProfileDetail(val name: String) : GhostlockScreen
    data object ProfileOverride : GhostlockScreen
    data object AdvancedOverride : GhostlockScreen
}

internal fun navigationPath(state: GhostlockUiState): List<GhostlockScreen> {
    val path = mutableListOf<GhostlockScreen>(GhostlockScreen.Main)
    if (!state.advancedScreenVisible) return path
    path += GhostlockScreen.Advanced
    if (state.aboutVisible) {
        path += GhostlockScreen.About
        state.legalDocument?.let { path += GhostlockScreen.Legal(it) }
        return path
    }
    if (!state.parametersVisible) return path
    path += GhostlockScreen.Parameters
    if (state.loadConfigVisible) {
        path += GhostlockScreen.LoadConfig
        when {
            state.builtinScreenVisible -> path += GhostlockScreen.Builtin
            state.userProfileDetail != null ->
                path += GhostlockScreen.UserProfileDetail(state.userProfileDetail)
        }
    }
    if (state.profileOverrideVisible) {
        path += GhostlockScreen.ProfileOverride
        if (state.advancedOverrideVisible) path += GhostlockScreen.AdvancedOverride
    }
    return path
}

internal fun syncNavigationPath(backStack: NavBackStack, desired: List<GhostlockScreen>) {
    var common = 0
    while (common < backStack.size && common < desired.size &&
        backStack[common] == desired[common]
    ) {
        common++
    }
    while (backStack.size > common) backStack.removeAt(backStack.lastIndex)
    backStack.addAll(desired.drop(common))
}

private fun closeScreen(screen: GhostlockScreen, actions: GhostlockActions) {
    when (screen) {
        GhostlockScreen.Main -> Unit
        GhostlockScreen.Advanced -> actions.onCloseAdvanced()
        GhostlockScreen.About -> actions.onCloseAbout()
        is GhostlockScreen.Legal -> actions.onCloseLegalDocument()
        GhostlockScreen.Parameters -> actions.onCloseParameters()
        GhostlockScreen.LoadConfig -> actions.onCloseLoadConfig()
        GhostlockScreen.Builtin -> actions.onCloseBuiltinProfiles()
        is GhostlockScreen.UserProfileDetail -> actions.onCloseUserProfileDetail()
        GhostlockScreen.ProfileOverride -> actions.onCloseProfileOverrides()
        GhostlockScreen.AdvancedOverride -> actions.onCloseAdvancedOverrides()
    }
}

@Composable
internal fun GhostlockApp(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        val desiredPath = navigationPath(state)
        val backStack = remember {
            navBackStackOf(GhostlockScreen.Main).apply { addAll(desiredPath.drop(1)) }
        }
        LaunchedEffect(desiredPath) { syncNavigationPath(backStack, desiredPath) }
        val swipeBack = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
            NavSwipeDirection.RightToLeft
        } else {
            NavSwipeDirection.LeftToRight
        }
        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                    onBack = {
                        when {
                            state.executionSheetVisible -> {
                                if (state.executionSheetDismissible) actions.onCloseExecutionSheet()
                            }

                            state.overwriteDialogVisible -> actions.onOverwriteDismiss()
                            state.dialogVisible -> actions.onDialogDismiss()
                            state.userProfileDeleteTarget != null -> actions.onUserProfileDeleteDismiss()
                            else -> {
                                val screen = backStack.lastOrNull() as? GhostlockScreen
                                if (screen != null && screen != GhostlockScreen.Main) {
                                    closeScreen(screen, actions)
                                    backStack.removeAt(backStack.lastIndex)
                                }
                            }
                        }
                    },
                ) {
                    entry<GhostlockScreen.Main>(swipeDismiss = NavSwipeDirection.None) {
                        MainScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.Advanced>(swipeDismiss = swipeBack) {
                        AdvancedScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.About>(swipeDismiss = swipeBack) {
                        AboutScreen(
                            onBack = actions::onCloseAbout,
                            onOpenLegal = actions::onOpenLegalDocument,
                        )
                    }
                    entry<GhostlockScreen.Legal>(swipeDismiss = swipeBack) { screen ->
                        LegalScreen(document = screen.document, onBack = actions::onCloseLegalDocument)
                    }
                    entry<GhostlockScreen.Parameters>(swipeDismiss = swipeBack) {
                        ParameterScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.LoadConfig>(swipeDismiss = swipeBack) {
                        LoadConfigScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.Builtin>(swipeDismiss = swipeBack) {
                        BuiltinProfileScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.UserProfileDetail>(swipeDismiss = swipeBack) { screen ->
                        UserProfileDetailScreen(state = state, actions = actions, name = screen.name)
                    }
                    entry<GhostlockScreen.ProfileOverride>(swipeDismiss = swipeBack) {
                        ProfileOverrideScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.AdvancedOverride>(swipeDismiss = swipeBack) {
                        AdvancedOverrideScreen(state = state, actions = actions)
                    }
                }
                GhostlockDialog(state = state, actions = actions)
                GhostlockOverwriteDialog(state = state, actions = actions)
                GhostlockExecutionSheet(state = state, actions = actions)
                GhostlockManagerPickerSheet(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = "GhostLock",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        MainContent(
            state = state,
            actions = actions,
            scrollBehavior = scrollBehavior,
            scaffoldPadding = paddingValues,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun pageContentPadding(
    scaffoldPadding: PaddingValues,
    top: Dp = 8.dp,
    bottom: Dp = 12.dp,
    horizontalMin: Dp = 12.dp,
): PaddingValues {
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val horizontal = ((windowWidth - 800.dp) / 2).coerceAtLeast(horizontalMin)
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = scaffoldPadding.calculateTopPadding() + top,
        bottom = scaffoldPadding.calculateBottomPadding() + bottom,
    )
}

@Composable
private fun GhostlockExecutionSheet(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayBottomSheet(
        show = state.executionSheetVisible,
        title = stringResource(R.string.log_title),
        allowDismiss = state.executionSheetDismissible,
        onDismissRequest = actions::onCloseExecutionSheet,
        startAction = {
            IconButton(onClick = actions::onCopyLogs) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
        endAction = {
            IconButton(
                enabled = state.executionSheetDismissible,
                onClick = actions::onCloseExecutionSheet,
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.action_close),
                )
            }
        },
        content = {
            LogPanel(
                lines = state.logLines,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 520.dp)
                    .navigationBarsPadding(),
            )
        },
    )
}

@Composable
private fun GhostlockManagerPickerSheet(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayBottomSheet(
        show = state.managerPickerVisible,
        title = stringResource(R.string.manager_picker_title),
        onDismissRequest = actions::onCloseManagerPicker,
        content = {
            var showAll by remember { mutableStateOf(false) }
            // Auto stays first so a saved selection can always be cleared,
            // even when no manager app was found at all.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .navigationBarsPadding()
                    .selectableGroup(),
            ) {
                item {
                    ManagerPickerRow(
                        title = stringResource(R.string.manager_summary_auto),
                        subtitle = null,
                        icon = null,
                        selected = state.selectedManagerPackage == null,
                    ) { actions.onManagerSelected(null) }
                }
                if (state.managerCandidates.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.manager_picker_empty),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                } else {
                    val compatible = state.managerCandidates.filter { it.hasKsud }
                    val incompatible = state.managerCandidates.filterNot { it.hasKsud }
                    val visibleIncompatible = if (showAll) incompatible else emptyList()
                    items(compatible, key = { it.packageName }) { candidate ->
                        ManagerPickerRow(
                            title = candidate.label,
                            subtitle = candidate.packageName,
                            icon = candidate,
                            selected = state.selectedManagerPackage == candidate.packageName,
                        ) { actions.onManagerSelected(candidate.packageName) }
                    }
                    items(visibleIncompatible, key = { it.packageName }) { candidate ->
                        ManagerPickerRow(
                            title = candidate.label,
                            subtitle = stringResource(R.string.manager_no_ksud),
                            icon = candidate,
                            selected = state.selectedManagerPackage == candidate.packageName,
                            muted = true,
                        ) { actions.onManagerSelected(candidate.packageName) }
                    }
                    if (incompatible.isNotEmpty()) {
                        item {
                            TextButton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                text = stringResource(
                                    if (showAll) R.string.manager_hide_all else R.string.manager_show_all,
                                ),
                                onClick = { showAll = !showAll },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ManagerPickerRow(
    title: String,
    subtitle: String?,
    icon: ManagerScanner.Candidate?,
    selected: Boolean,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val imageBitmap = remember(icon) {
        icon?.let { candidate ->
            runCatching {
                val drawable = pm.getApplicationIcon(candidate.packageName)
                val width = maxOf(drawable.intrinsicWidth, 1)
                val height = maxOf(drawable.intrinsicHeight, 1)
                android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(android.graphics.Canvas(bitmap))
                }
            }.getOrNull()
        }?.asImageBitmap()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 0.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (muted) MiuixTheme.colorScheme.onBackgroundVariant else MiuixTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.CheckCircleOutline,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GhostlockDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayDialog(
        show = state.dialogVisible,
        title = if (state.dialogType == DialogType.NONE) null else stringResource(state.dialogTitleRes),
        onDismissRequest = actions::onDialogDismiss,
        onDismissFinished = actions::onDialogDismissFinished,
        content = {
            when (state.dialogType) {
                DialogType.LIST -> {
                    val items = state.dialogItems.ifEmpty { state.dialogItemResIds.map { stringResource(it) } }
                    items.forEachIndexed { index, item ->
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            text = if (index == state.dialogCurrentItemIndex) {
                                stringResource(R.string.export_current_marker, item)
                            } else {
                                item
                            },
                            onClick = { actions.onDialogItemSelected(index) },
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.cancel),
                        onClick = actions::onDialogDismiss,
                    )
                }

                DialogType.INPUT -> {
                    TextField(
                        value = state.dialogInput,
                        onValueChange = actions::onDialogInputChange,
                        label = stringResource(state.dialogMessageRes),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.cancel),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(state.dialogConfirmLabelRes),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm(state.dialogInput) },
                        )
                    }
                }


                DialogType.NOTICE -> {
                    Text(
                        text = stringResource(state.dialogMessageRes),
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        state.dialogDocUrl?.let { docUrl ->
                            val uriHandler = LocalUriHandler.current
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.dialog_open_guide),
                                onClick = { uriHandler.openUri(docUrl) },
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.dialog_dismiss),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = actions::onDialogDismiss,
                        )
                    }
                }

                DialogType.SU_AUTH -> {
                    Text(
                        text = stringResource(R.string.su_auth_message),
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 12.dp),
                        text = stringResource(R.string.su_auth_open_manager),
                        onClick = {
                            actions.onDialogDismiss()
                            actions.onOpenKernelSuManager()
                        },
                    )
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        text = stringResource(R.string.su_auth_retry),
                        onClick = {
                            actions.onDialogDismiss()
                            actions.onRetrySuAuthorization()
                        },
                    )
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.manager_prompt_later),
                        onClick = actions::onDialogDismiss,
                    )
                }

                DialogType.MANAGERS -> {
                    if (!state.kernelSuManagerInstalled) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            text = stringResource(R.string.manager_guide_kernelsu),
                            onClick = {
                                actions.onDialogDismiss()
                                actions.onFetchKernelSuManager()
                            },
                        )
                    }
                    if (!state.vectorManagerInstalled) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            text = stringResource(R.string.manager_guide_vector),
                            onClick = {
                                actions.onDialogDismiss()
                                actions.onFetchVectorManager()
                            },
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.manager_prompt_later),
                        onClick = actions::onDialogDismiss,
                    )
                }

                DialogType.NONE -> Unit
            }
        },
    )
}

@Composable
private fun GhostlockOverwriteDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayDialog(
        show = state.overwriteDialogVisible,
        title = stringResource(R.string.overwrite_title),
        summary = stringResource(R.string.overwrite_message, state.overwriteMessage),
        onDismissRequest = actions::onOverwriteDismiss,
        content = {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.cancel),
                    onClick = actions::onOverwriteDismiss,
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.overwrite_yes),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = actions::onOverwriteConfirm,
                )
            }
        },
    )
}

@Composable
private fun MainContent(
    state: GhostlockUiState,
    actions: GhostlockActions,
    scrollBehavior: ScrollBehavior,
    scaffoldPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .scrollEndHaptic()
            .overScrollVertical()
            .scrollEndHaptic()
            .fillMaxHeight()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        contentPadding = pageContentPadding(scaffoldPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "controls") {
            ControlPanel(
                state = state,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ControlPanel(
    state: GhostlockUiState,
    actions: GhostlockActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ActivationStatusCard(
            supported = state.kernelSupported,
            profileAvailable = state.executionHasProfile,
            profileValid = state.profileInvalidPaths.isEmpty(),
            onParametersClick = actions::onOpenParameters,
            modifier = Modifier.fillMaxWidth(),
        )
        SetupFlowCard(
            state = state,
            actions = actions,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        if (!state.kernelSuManagerInstalled || !state.vectorManagerInstalled) {
            ManagerSetupCard(
                kernelSuInstalled = state.kernelSuManagerInstalled,
                vectorInstalled = state.vectorManagerInstalled,
                onInstallKernelSu = actions::onFetchKernelSuManager,
                onInstallVector = actions::onFetchVectorManager,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
        RunCheckCard(
            checks = state.runChecks,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        ComponentDetectCard(
            statuses = state.componentStatuses,
            detecting = state.componentDetecting,
            onDetect = actions::onDetectComponents,
            modifier = Modifier.fillMaxWidth(),
        )
        Card(modifier = modifier.padding(top = 12.dp)) {
            ArrowPreference(
                title = stringResource(R.string.advanced_settings),
                summary = stringResource(R.string.advanced_settings_summary),
                onClick = actions::onOpenAdvanced,
            )
        }
        DeviceInfoCard(
            deviceName = state.deviceName,
            socName = state.socName,
            kernelRelease = state.kernelRelease,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

/**
 * Three ordered actions, each gated on the previous one's result.
 *
 * The steps are deliberately separate instead of one button: getting root,
 * installing the framework and restarting zygote fail for different reasons and
 * a single tap hides which of them went wrong. The order is also load-bearing —
 * soft reboot only preserves a Vector stack that is already running.
 */
@Composable
private fun SetupFlowCard(
    state: GhostlockUiState,
    actions: GhostlockActions,
    modifier: Modifier = Modifier,
) {
    val busy = state.running || state.setupBusy
    val profileBlocked = state.runChecks.any { !it.ok && it.blocking }
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.setup_flow_hint),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (state.setupBusy && state.setupState == "activate") {
                Text(
                    text = stringResource(R.string.setup_activate_progress),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            SetupStepButton(
                index = 1,
                labelRes = R.string.action_get_root,
                subtitleRes = R.string.step_get_root_desc,
                done = state.rootActive,
                enabled = !busy && !profileBlocked,
                current = !state.rootActive,
                onClick = actions::onGetRoot,
                onBlockedClick = actions::onProfileInvalid,
            )
            SetupStepButton(
                index = 2,
                labelRes = R.string.action_activate_framework,
                subtitleRes = R.string.step_activate_desc,
                done = state.vectorActive,
                enabled = !busy && !profileBlocked && state.rootActive,
                current = state.rootActive && !state.vectorActive,
                onClick = actions::onActivateFramework,
                onBlockedClick = actions::onProfileInvalid,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.setupError?.let { error ->
                Text(
                    text = error,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Prompts for the two companion managers the flow depends on.
 *
 * Only the missing ones are listed: the KernelSU manager supplies `ksud` for
 * the late-load and the su handoff, and the Vector manager is the app that
 * talks to the injected framework. Both APKs ship inside this APK, so the
 * action is a single tap — no download.
 */
@Composable
private fun ManagerSetupCard(
    kernelSuInstalled: Boolean,
    vectorInstalled: Boolean,
    onInstallKernelSu: () -> Unit,
    onInstallVector: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.manager_guide_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!kernelSuInstalled) {
                ManagerInstallRow(
                    name = stringResource(R.string.manager_guide_kernelsu),
                    detail = stringResource(R.string.manager_guide_kernelsu_hint),
                    onClick = onInstallKernelSu,
                )
            }
            if (!vectorInstalled) {
                ManagerInstallRow(
                    name = stringResource(R.string.manager_guide_vector),
                    detail = stringResource(R.string.manager_guide_vector_hint),
                    onClick = onInstallVector,
                )
            }
        }
    }
}

@Composable
private fun ManagerInstallRow(name: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = detail,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = stringResource(R.string.manager_guide_install),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SetupStepButton(
    index: Int,
    labelRes: Int,
    subtitleRes: Int,
    done: Boolean,
    enabled: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onBlockedClick: () -> Unit,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
) {
    val bgColor = if (optional) {
        if (enabled) MiuixTheme.colorScheme.surfaceVariant else MiuixTheme.colorScheme.disabledPrimaryButton
    } else if (enabled) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.disabledPrimaryButton
    val fgColor = if (optional && enabled) MiuixTheme.colorScheme.onBackgroundVariant
    else if (enabled) MiuixTheme.colorScheme.onPrimary
    else MiuixTheme.colorScheme.disabledOnPrimaryButton
    val label = "$index. " + stringResource(labelRes) + if (done) "  ✓" else ""
    val borderColor = if (current) MiuixTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp, horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = fgColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (current && !done) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.step_tap_here),
                    color = fgColor.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            text = stringResource(subtitleRes),
            color = fgColor.copy(alpha = 0.72f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Shows whether each moving part of the root stack is already in place.
 *
 * Nothing here runs the exploit: staged zips are verified on disk, the manager
 * is looked up through the package manager, and module activity comes from
 * `/proc/modules` or a `su` probe. A row that was never checked says so rather
 * than claiming "missing".
 */
@Composable
private fun ComponentDetectCard(
    statuses: List<ComponentDetector.Status>,
    detecting: Boolean,
    onDetect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.padding(top = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.component_detect_label),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.component_detect_summary),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
            if (statuses.isEmpty()) {
                Text(
                    text = stringResource(R.string.component_status_none),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                )
            } else {
                statuses.forEach { status ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = if (status.ok) {
                                Icons.Rounded.CheckCircleOutline
                            } else {
                                Icons.Rounded.ErrorOutline
                            },
                            contentDescription = null,
                            tint = if (status.ok) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.error
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = status.label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = statusDetail(status),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                            )
                        }
                    }
                }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(
                    if (detecting) R.string.component_detect_running else R.string.component_detect_action,
                ),
                onClick = onDetect,
                enabled = !detecting,
            )
        }
    }
}

@Composable
private fun statusDetail(status: ComponentDetector.Status): String {
    val detail = status.detail
    return when {
        detail == null -> stringResource(R.string.component_status_missing)
        status.key == "module_active" -> stringResource(
            if (status.ok) R.string.component_status_active else R.string.component_status_inactive,
        )
        else -> stringResource(
            if (status.ok) R.string.component_status_ready else R.string.component_status_missing,
        ) + if (detail.isNotBlank() && status.ok) {
            " · " + stringResource(R.string.component_status_version, detail)
        } else {
            ""
        }
    }
}

@Composable
private fun ActivationStatusCard(
    supported: Boolean,
    profileAvailable: Boolean,
    profileValid: Boolean,
    onParametersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = supported && profileAvailable && profileValid
    val missing = !supported || !profileAvailable
    val (backgroundColor, title, icon) = when {
        ready -> Triple(
            when {
                MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
                isSystemInDarkTheme() -> Color(0xFF1A3825)
                else -> Color(0xFFDFFAE4)
            },
            R.string.kernel_supported,
            Icons.Rounded.CheckCircleOutline,
        )

        missing -> Triple(
            MiuixTheme.colorScheme.errorContainer,
            if (!supported) R.string.kernel_unsupported else R.string.kernel_profile_required,
            Icons.Rounded.RemoveCircleOutline,
        )

        else -> Triple(
            when {
                MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.tertiaryContainer
                isSystemInDarkTheme() -> Color(0xFF3E2F1B)
                else -> Color(0xFFFFF0DB)
            },
            R.string.kernel_profile_invalid,
            Icons.Rounded.ErrorOutline,
        )
    }
    val summary = when {
        ready -> R.string.kernel_profile_ready
        !supported -> R.string.kernel_unsupported_summary
        !profileAvailable -> R.string.kernel_profile_required_summary
        else -> R.string.kernel_profile_invalid_summary
    }
    val action = onParametersClick
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = backgroundColor),
        onClick = action,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 110.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (MiuixTheme.isDynamicColor) {
                    when (icon) {
                        Icons.Rounded.CheckCircleOutline -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                        Icons.Rounded.RemoveCircleOutline ->
                            MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)

                        else -> MiuixTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    }
                } else {
                    when (icon) {
                        Icons.Rounded.CheckCircleOutline -> Color(0xFF36D167)
                        Icons.Rounded.RemoveCircleOutline -> Color(0xFFF5A623)
                        else -> Color(0xFFF72727)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 120.dp, bottom = 14.dp),
            ) {
                Text(
                    text = stringResource(title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = stringResource(summary),
                    fontSize = 15.sp,
                )
            }
        }
    }
}

/**
 * Pre-flight checklist for the one-tap run: every dependency and system
 * condition the run needs, with the blocking ones gating the button.
 */
@Composable
private fun RunCheckCard(
    checks: List<RunCheck>,
    modifier: Modifier = Modifier,
) {
    if (checks.isEmpty()) return
    val blockingFailed = checks.count { !it.ok && it.blocking }
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val allOk = blockingFailed == 0 && checks.all { it.ok }
            if (allOk) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircleOutline,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.run_checks_all_ok, checks.size),
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.run_checks_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.run_checks_summary),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                )
            }
            if (!allOk) checks.forEach { check ->
                val labelRes = when (check.id) {
                    RunCheckIds.KERNEL -> R.string.check_kernel
                    RunCheckIds.PROFILE -> R.string.check_profile
                    RunCheckIds.KERNELSU_MANAGER -> R.string.check_kernelsu_manager
                    RunCheckIds.SU_GRANTED -> R.string.check_su_granted
                    RunCheckIds.ALREADY_ROOTED -> R.string.check_already_rooted
                    else -> R.string.check_kernel
                }
                val stateRes = if (check.ok) {
                    R.string.check_state_ok
                } else if (check.blocking) {
                    R.string.check_state_blocked
                } else {
                    R.string.check_state_optional
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = if (check.ok) {
                            Icons.Rounded.CheckCircleOutline
                        } else {
                            Icons.Rounded.ErrorOutline
                        },
                        contentDescription = null,
                        tint = if (check.ok) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.error
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(labelRes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(stateRes),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                        )
                    }
                }
            }
            if (blockingFailed > 0) {
                Text(
                    text = stringResource(R.string.run_checks_blocked, blockingFailed),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(
    deviceName: String,
    socName: String,
    kernelRelease: String,
    modifier: Modifier = Modifier,
) {    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DeviceInfoItem(
                    title = stringResource(R.string.device_label),
                    value = deviceName,
                )
                DeviceInfoItem(
                    title = stringResource(R.string.soc_label),
                    value = socName,
                )
                DeviceInfoItem(
                    title = stringResource(R.string.kernel_label),
                    value = kernelRelease,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoItem(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
    }
}

/* profile-ui: resolved execution view with auto-saved sparse overrides. */
@Composable
internal fun ExecutionEditor(
    state: GhostlockUiState,
    actions: GhostlockActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (field in state.executionFields) {
            val text = state.executionEditing[field.path] ?: field.value.toString()
            val invalid = isFieldInputInvalid(text) || field.path in state.profileInvalidPaths
            TextField(
                value = text,
                onValueChange = { value -> actions.onExecutionFieldChanged(field.path, value) },
                label = fieldLabel(field.path, field.path.substringAfterLast('.')),
                colors = when {
                    invalid ->
                        TextFieldDefaults.textFieldColors(labelColor = FieldErrorHighlight)

                    field.overridden ->
                        TextFieldDefaults.textFieldColors(labelColor = OverrideHighlight)

                    else -> TextFieldDefaults.textFieldColors()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

internal val OverrideHighlight = Color(0xFFF5A623)

/** Red marks an unfilled or non-numeric field in the parameter editors. */
internal val FieldErrorHighlight = Color(0xFFE53935)

internal fun isFieldInputInvalid(text: String): Boolean = text.trim().toLongOrNull() == null

@Composable
private fun RunButton(
    running: Boolean,
    blocked: Boolean,
    labelRes: Int,
    onClick: () -> Unit,
    onBlockedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        TextButton(
            text = stringResource(if (running) R.string.action_running else labelRes),
            enabled = !blocked && !running,
            colors = ButtonDefaults.textButtonColorsPrimary(
                disabledColor = MiuixTheme.colorScheme.disabledPrimaryButton,
                disabledTextColor = MiuixTheme.colorScheme.disabledOnPrimaryButton,
            ),
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
        /* A disabled TextButton consumes no pointer input, so this overlay
         * explains why the run is blocked. */
        if (!running && blocked) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { onBlockedClick() },
            )
        }
    }
}

@Composable
private fun LogPanel(
    lines: List<GhostlockLogLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.text.trimEnd('\r', '\n'),
                        color = lineColor(line.color),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private fun lineColor(color: Int): Color = when (color) {
    0xFFFF6B6B.toInt() -> Color(0xFFFF6B6B)
    0xFF5FD68A.toInt() -> Color(0xFF5FD68A)
    0xFFFFC94D.toInt() -> Color(0xFFFFC94D)
    0xFF60A5FA.toInt() -> Color(0xFF60A5FA)
    else -> Color(0xFFD1D5DB)
}
