package com.ghostlock.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** The legal documents bundled under `assets/legal/`. */
enum class LegalDocument(val assetName: String, val titleRes: Int, val englishAssetName: String = assetName) {
    Terms("LEGAL.md", R.string.legal_terms, englishAssetName = "LEGAL.en.md"),
    License("LICENSE.txt", R.string.legal_license),
    Notice("NOTICE.txt", R.string.legal_notice),
    ApacheLicense("Apache-2.0.txt", R.string.legal_apache),
}

/** The asset to open, following the language the UI is currently showing. */
internal fun LegalDocument.assetFor(isEnglish: Boolean): String =
    if (isEnglish) englishAssetName else assetName

/**
 * Renders one bundled legal document as selectable plain text.
 *
 * The text is the repository's own file, copied into assets by
 * `syncLegalAssets`, so what the user reads is exactly what ships in the
 * source tree. No Markdown renderer is pulled in for four static documents:
 * the markup is dropped and the text stays readable.
 */
@Composable
internal fun LegalScreen(document: LegalDocument, onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current
    /* The Chinese text is authoritative, so it is the fallback: only an
     * English UI gets the translated copy. */
    val isEnglish = LocalConfiguration.current.locales[0].language == "en"
    val assetName = document.assetFor(isEnglish)
    val text by produceState(initialValue = "", assetName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("legal/$assetName")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrDefault("")
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(document.titleRes),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
        ) {
            item(key = "body") {
                Card {
                    SelectionContainer {
                        Text(
                            text = stripMarkup(text),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Drops the Markdown decorations the documents use.
 *
 * Headings keep their text and get a blank line around them; list markers stay
 * because they carry meaning. Inline code backticks and emphasis markers go:
 * they would otherwise show up as literal punctuation in the middle of prose.
 */
internal fun stripMarkup(raw: String): String = raw
    .lineSequence()
    .joinToString("\n") { line ->
        when {
            line.startsWith("#") -> "\n" + line.trimStart('#').trim() + "\n"
            else -> line
        }
    }
    .replace("**", "")
    .replace("`", "")
