package io.zest.fgopatcher

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkMerger
import app.morphe.patcher.apk.ApkSigner
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.apk.ApkUtils.applyTo
import io.zest.fgopatcher.ui.theme.ZestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private const val VANILLA_APP_URL = "https://fgo.bigcereal.com/"
private const val PREFS = "fgo_patcher"
private const val REPO_URL = "https://github.com/Zenzenzest/zest-fgo-patcher"
private const val CONTACT_EMAIL = "zenzenzest@gmail.com"
private const val X_URL = "https://x.com/realzenzenzest"
private const val DISCORD_HANDLE = "zenzenzest"

/** Installed donor apps we can pull libavalon.so from. */
private data class DonorApp(val pkg: String, val label: String, val variantKey: String)

private val DONOR_APPS = listOf(
    DonorApp("io.rayshift.betterfgo.en", "Rayshift FGO (EN)", "en"),
    DonorApp("io.zest.betterfgo.en", "Zest FGO (EN)", "en"),
    DonorApp("io.rayshift.betterfgo", "Rayshift FGO JP", "jp"),
    DonorApp("io.zest.betterfgo", "Zest FGO JP", "jp"),
)

private fun donorRoot(ctx: Context) = File(ctx.filesDir, "donor")

private fun variantReady(ctx: Context, variant: FgoVariant): Boolean =
    FgoPatch.SUPPORTED_ABIS.any { FgoPatch.libAvalonFile(donorRoot(ctx), variant, it).exists() }

data class InstalledGame(val pkg: String, val label: String, val versionName: String)

/** Vanilla games installed on this device (base + split APKs can be read for patching). */
private fun findInstalledGames(ctx: Context): List<InstalledGame> {
    val specs = listOf(
        "com.aniplex.fategrandorder.en" to "Fate/GO EN",
        "com.aniplex.fategrandorder" to "Fate/GO JP",
    )
    return specs.mapNotNull { (pkg, label) ->
        try {
            val info = ctx.packageManager.getPackageInfo(pkg, 0)
            InstalledGame(pkg, label, info.versionName ?: "?")
        } catch (t: Throwable) {
            null
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FgoPatch.assets = assets
        FgoPatch.donorRoot = donorRoot(this)
        setContent {
            FgoPatcherApp()
        }
    }
}

@Composable
fun FgoPatcherApp() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var progress by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var donorStatus by remember { mutableStateOf(donorStatusText(ctx)) }
    var mergeDonor by remember { mutableStateOf(prefs.getBoolean("merge_abis", false)) }
    var lastOutcome by remember { mutableStateOf<PatchOutcome?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val version = rememberVersion(ctx)
    val view = LocalView.current

    fun copyDiscord() {
        clipboard.setText(AnnotatedString(DISCORD_HANDLE))
        scope.launch { snackbar.showSnackbar("Discord handle copied") }
    }

    SideEffect {
        val activity = ctx as? Activity ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }

    LaunchedEffect(Unit) {
        val status = withContext(Dispatchers.IO) {
            refreshDonors(ctx, mergeDonor) { message -> main.post { logs.add(message) } }
        }
        donorStatus = status
    }

    val donorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    importDonor(ctx, uri, mergeDonor) { message -> main.post { logs.add(message) } }
                }
                donorStatus = status
                snackbar.showSnackbar("Donor updated")
            } catch (t: Throwable) {
                main.post { logs.add("donor import failed: ${t.message}") }
                snackbar.showSnackbar(t.message ?: "Donor import failed")
            } finally {
                busy = false
            }
        }
    }

    val installedGames = remember { findInstalledGames(ctx) }
    val logSink: (String) -> Unit = { message -> main.post { logs.add(message) } }
    val progressSink: (Int) -> Unit = { value -> main.post { progress = value } }

    fun launchPatch(block: suspend () -> PatchOutcome) {
        busy = true
        progress = 0
        logs.clear()
        lastOutcome = null
        scope.launch {
            try {
                FgoPatch.donorRoot = donorRoot(ctx)
                FgoPatch.mergeDonorAbis = mergeDonor
                val outcome = block()
                lastOutcome = outcome
                val downloadsUri = outcome.downloadsUri
                if (downloadsUri != null) {
                    logs.add("saved to Downloads/${outcome.file.name}")
                    installUri(ctx, downloadsUri)
                } else {
                    installFile(ctx, outcome.file)
                }
                snackbar.showSnackbar("Patched APK ready")
            } catch (t: Throwable) {
                logs.add("ERROR: ${t.message}")
                snackbar.showSnackbar(t.message ?: "Patch failed")
            } finally {
                busy = false
            }
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        launchPatch {
            withContext(Dispatchers.IO) {
                runPatchFromUri(ctx, uri, logSink, progressSink)
            }
        }
    }

    ZestTheme(darkTheme = darkTheme) {
        FgoPatcherScreen(
            logs = logs,
            progress = progress,
            busy = busy,
            donorStatus = donorStatus,
            mergeDonor = mergeDonor,
            onMergeChange = { enabled ->
                mergeDonor = enabled
                prefs.edit().putBoolean("merge_abis", enabled).apply()
                scope.launch {
                    busy = true
                    try {
                        donorStatus = withContext(Dispatchers.IO) {
                            refreshDonors(ctx, enabled) { message -> main.post { logs.add(message) } }
                        }
                    } finally {
                        busy = false
                    }
                }
            },
            installReady = lastOutcome != null,
            onInstall = {
                lastOutcome?.let { outcome ->
                    val uri = outcome.downloadsUri
                    if (uri != null) installUri(ctx, uri) else installFile(ctx, outcome.file)
                }
            },
            installedGames = installedGames,
            onPatchInstalled = { game ->
                launchPatch {
                    withContext(Dispatchers.IO) {
                        runPatchFromInstalled(ctx, game.pkg, logSink, progressSink)
                    }
                }
            },
            onImportDonor = {
                donorPicker.launch(
                    arrayOf(
                        "application/vnd.android.package-archive",
                        "application/zip",
                        "application/octet-stream",
                    ),
                )
            },
            onPick = {
                apkPicker.launch(
                    arrayOf(
                        "application/zip",
                        "application/octet-stream",
                        "application/vnd.android.package-archive",
                    ),
                )
            },
            darkTheme = darkTheme,
            onToggleTheme = {
                val next = !darkTheme
                darkTheme = next
                prefs.edit().putBoolean("dark_theme", next).apply()
            },
            onAbout = { showAbout = true },
            onOpenRepo = { uriHandler.openUri(REPO_URL) },
            onEmail = { uriHandler.openUri("mailto:$CONTACT_EMAIL") },
            onX = { uriHandler.openUri(X_URL) },
            onDiscord = { copyDiscord() },
            snackbar = snackbar,
            version = version,
        )

        if (showAbout) {
            AboutDialog(
                onDismiss = { showAbout = false },
                version = version,
                onOpenRepo = { uriHandler.openUri(REPO_URL) },
                onEmail = { uriHandler.openUri("mailto:$CONTACT_EMAIL") },
                onX = { uriHandler.openUri(X_URL) },
                onDiscord = { copyDiscord() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FgoPatcherScreen(
    logs: List<String>,
    progress: Int,
    busy: Boolean,
    donorStatus: String,
    mergeDonor: Boolean,
    onMergeChange: (Boolean) -> Unit,
    installReady: Boolean,
    onInstall: () -> Unit,
    installedGames: List<InstalledGame>,
    onPatchInstalled: (InstalledGame) -> Unit,
    onImportDonor: () -> Unit,
    onPick: () -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onAbout: () -> Unit,
    onOpenRepo: () -> Unit,
    onEmail: () -> Unit,
    onX: () -> Unit,
    onDiscord: () -> Unit,
    snackbar: SnackbarHostState,
    version: String,
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val patchRequester = remember { BringIntoViewRequester() }
    val donorRequester = remember { BringIntoViewRequester() }
    val donorReady = !donorStatus.startsWith("No ")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            val wide = maxWidth >= 900.dp
            val horizontal = if (wide) 32.dp else 20.dp

            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(460.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    Color.Transparent,
                                ),
                                radius = 1100f,
                            ),
                        ),
                )
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1120.dp)
                        .fillMaxWidth()
                        .padding(horizontal = horizontal, vertical = 20.dp),
                ) {
                    ZestHeader(
                        wide = wide,
                        darkTheme = darkTheme,
                        onToggleTheme = onToggleTheme,
                        onAbout = onAbout,
                        onOpenRepo = onOpenRepo,
                        onJumpPatch = { scope.launch { patchRequester.bringIntoView() } },
                        onJumpDonor = { scope.launch { donorRequester.bringIntoView() } },
                    )

                    Spacer(Modifier.height(if (wide) 36.dp else 26.dp))
                    Hero(wide = wide, donorReady = donorReady, busy = busy)
                    Spacer(Modifier.height(if (wide) 32.dp else 24.dp))

                    if (wide) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1.05f),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                ChooseGameCard(
                                    games = installedGames,
                                    busy = busy,
                                    onPatchInstalled = onPatchInstalled,
                                    onPick = onPick,
                                    modifier = Modifier.bringIntoViewRequester(patchRequester),
                                )
                                DonorCard(
                                    donorStatus = donorStatus,
                                    donorReady = donorReady,
                                    mergeDonor = mergeDonor,
                                    onMergeChange = onMergeChange,
                                    onImportDonor = onImportDonor,
                                    busy = busy,
                                    modifier = Modifier.bringIntoViewRequester(donorRequester),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(0.95f),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                BuildCard(
                                    logs = logs,
                                    progress = progress,
                                    busy = busy,
                                    installReady = installReady,
                                    onInstall = onInstall,
                                    logHeight = 330.dp,
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ChooseGameCard(
                                games = installedGames,
                                busy = busy,
                                onPatchInstalled = onPatchInstalled,
                                onPick = onPick,
                                modifier = Modifier.bringIntoViewRequester(patchRequester),
                            )
                            DonorCard(
                                donorStatus = donorStatus,
                                donorReady = donorReady,
                                mergeDonor = mergeDonor,
                                onMergeChange = onMergeChange,
                                onImportDonor = onImportDonor,
                                busy = busy,
                                modifier = Modifier.bringIntoViewRequester(donorRequester),
                            )
                            BuildCard(
                                logs = logs,
                                progress = progress,
                                busy = busy,
                                installReady = installReady,
                                onInstall = onInstall,
                                logHeight = 220.dp,
                            )
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    ZestFooter(
                        version = version,
                        onOpenRepo = onOpenRepo,
                        onEmail = onEmail,
                        onX = onX,
                        onDiscord = onDiscord,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZestHeader(
    wide: Boolean,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onAbout: () -> Unit,
    onOpenRepo: () -> Unit,
    onJumpPatch: () -> Unit,
    onJumpDonor: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "Zest",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "FGO PATCHER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        if (wide) {
            TextButton(onClick = onJumpPatch) { Text("Patch") }
            TextButton(onClick = onJumpDonor) { Text("Donor") }
            TextButton(onClick = onAbout) { Text("About") }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onOpenRepo) {
                Icon(Icons.Outlined.Code, "Open GitHub repository")
            }
        } else {
            IconButton(onClick = onAbout) {
                Icon(Icons.Outlined.Info, "About")
            }
        }

        IconButton(onClick = onToggleTheme) {
            Icon(
                imageVector = if (darkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                contentDescription = if (darkTheme) "Switch to light theme" else "Switch to dark theme",
            )
        }
    }
}

@Composable
private fun BrandMark(size: Int = 42) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.3f).dp))
            .background(Color(0xFFC1A38E)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = "Zest FGO Patcher icon",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(wide: Boolean, donorReady: Boolean, busy: Boolean) {
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                text = if (FgoPatch.TEST_BUILD) "Test build" else "Production",
                color = MaterialTheme.colorScheme.primary,
            )
            StatusChip(
                text = if (donorReady) "Donor ready" else "Donor needed",
                color = if (donorReady) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error,
            )
            if (busy) {
                StatusChip(text = "Working", color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Patch Fate/Grand Order",
            style = if (wide) MaterialTheme.typography.displaySmall
            else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "60 FPS, NP Skip, Name Fixes, And Ascension Dialogue Skip.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 760.dp),
        )
        if (FgoPatch.TEST_BUILD) {
            Spacer(Modifier.height(10.dp))
            Text(
                "This build installs as io.zest.test.betterfgo.* and sits next to your production app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SectionHeader(number: String, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ZestCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun ChooseGameCard(
    games: List<InstalledGame>,
    busy: Boolean,
    onPatchInstalled: (InstalledGame) -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    ZestCard(modifier) {
        SectionHeader(
            number = "01",
            title = "Choose your game",
            subtitle = "Patch the vanilla install on this phone, or pick an XAPK.",
        )

        if (games.isEmpty()) {
            Text(
                "No vanilla Fate/Grand Order found on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            games.forEach { game ->
                InstalledGameRow(game = game, busy = busy) { onPatchInstalled(game) }
            }
        }

        Button(
            onClick = onPick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Select XAPK and patch")
        }

        TextButton(
            onClick = { uriHandler.openUri(VANILLA_APP_URL) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                "Need the vanilla app? fgo.bigcereal.com",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InstalledGameRow(
    game: InstalledGame,
    busy: Boolean,
    onPatch: () -> Unit,
) {
    Surface(
        onClick = onPatch,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(game.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "v${game.versionName} • ${game.pkg}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DonorCard(
    donorStatus: String,
    donorReady: Boolean,
    mergeDonor: Boolean,
    onMergeChange: (Boolean) -> Unit,
    onImportDonor: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val statusColor = when {
        donorStatus.startsWith("No ") -> MaterialTheme.colorScheme.error
        donorStatus.startsWith("Test build") -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val statusIcon = when {
        donorStatus.startsWith("No ") -> Icons.Outlined.ErrorOutline
        donorStatus.startsWith("Test build") -> Icons.Outlined.Info
        else -> Icons.Outlined.CheckCircle
    }

    ZestCard(modifier) {
        SectionHeader(
            number = "02",
            title = "Donor library",
            subtitle = "Release builds import libavalon from a BetterFGO APK; test builds skip it.",
        )

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    donorStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        OutlinedButton(
            onClick = onImportDonor,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (donorReady) "Re-import donor APK" else "Import donor APK")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Include donor extra ABIs (armv7)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Needed only when the target XAPK is arm64-only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = mergeDonor, onCheckedChange = onMergeChange, enabled = !busy)
        }
    }
}

@Composable
private fun BuildCard(
    logs: List<String>,
    progress: Int,
    busy: Boolean,
    installReady: Boolean,
    onInstall: () -> Unit,
    logHeight: Dp,
    modifier: Modifier = Modifier,
) {
    ZestCard(modifier) {
        SectionHeader(
            number = "03",
            title = "Build",
            subtitle = "Progress and the live patcher log.",
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = when {
                    busy -> "Working…"
                    installReady -> "Ready to install"
                    else -> "Idle"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (busy) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$progress%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )

        if (installReady) {
            Button(
                onClick = onInstall,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Install patched FGO")
            }
        }

        LogPanel(logs = logs, height = logHeight)
    }
}

@Composable
private fun LogPanel(logs: List<String>, height: Dp) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFF07090C),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                Spacer(Modifier.width(8.dp))
                Text(
                    "PATCHER LOG",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A94A6),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${logs.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5C6678),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (logs.isEmpty()) {
                Text(
                    "Logs will appear here when you start a patch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7688),
                )
            } else {
                val state = rememberLazyListState()
                LaunchedEffect(logs.size) {
                    state.animateScrollToItem(logs.lastIndex)
                }
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxWidth().height(height),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(logs) { line ->
                        val isError = line.startsWith("ERROR") || line.contains("FAILED")
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            color = if (isError) Color(0xFFFF8080) else Color(0xFFC9D1DC),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZestFooter(
    version: String,
    onOpenRepo: () -> Unit,
    onEmail: () -> Unit,
    onX: () -> Unit,
    onDiscord: () -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Built by ZenZenZest",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "v$version • ${if (FgoPatch.TEST_BUILD) "Test build" else "Production"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContactChip("GitHub", Icons.Outlined.Code, onOpenRepo)
            ContactChip("Email", Icons.Outlined.Email, onEmail)
            ContactChip("X / Twitter", Icons.Outlined.AlternateEmail, onX)
            ContactChip("Discord · $DISCORD_HANDLE", Icons.Outlined.Forum, onDiscord)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Not affiliated with Aniplex, Lasengle, Type-Moon, or Rayshift. Game files are not included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContactChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        shape = CircleShape,
    )
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    version: String,
    onOpenRepo: () -> Unit,
    onEmail: () -> Unit,
    onX: () -> Unit,
    onDiscord: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { BrandMark(40) },
        title = { Text("Zest FGO Patcher", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "On-device patching for Fate/Grand Order EN and JP.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Version $version • ${if (FgoPatch.TEST_BUILD) "Test build" else "Production"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Text(
                    "CONTACT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ContactRow(Icons.Outlined.Code, "GitHub repository", onOpenRepo)
                ContactRow(Icons.Outlined.Email, CONTACT_EMAIL, onEmail)
                ContactRow(Icons.Outlined.AlternateEmail, "@realzenzenzest", onX)
                ContactRow(Icons.Outlined.Forum, "Discord: $DISCORD_HANDLE (tap to copy)", onDiscord, Icons.Outlined.ContentCopy)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Text(
                    "Not affiliated with Aniplex, Lasengle, Type-Moon, or Rayshift.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun ContactRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailingIcon: ImageVector = Icons.Outlined.OpenInNew,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            trailingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Suppress("DEPRECATION")
@Composable
private fun rememberVersion(ctx: Context): String = remember {
    runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "1.0"
}

// ---------------------------------------------------------------------------
// Donor preparation
// ---------------------------------------------------------------------------

/**
 * Cached metadata for a variant's donor. Written next to the extracted files so
 * we can skip re-extraction and detect stale/absent donors.
 */
private class DonorMeta {
    var donorPkg: String = ""
    var donorVersionCode: Long = 0
    var donorVersionName: String = ""
    var manual: Boolean = false
    var avalonSha256: String = ""
    var nativePresent: Boolean = false
    var nativeVersionCode: Long = 0
    var nativeVersionName: String = ""
    var source: String = ""
}

private fun metaFile(ctx: Context, variant: FgoVariant) =
    File(FgoPatch.donorDir(donorRoot(ctx), variant), "meta.properties")

private fun readMeta(ctx: Context, variant: FgoVariant): DonorMeta? {
    val file = metaFile(ctx, variant)
    if (!file.exists()) return null
    return try {
        val props = java.util.Properties().apply { file.inputStream().use { load(it) } }
        DonorMeta().apply {
            donorPkg = props.getProperty("donor.pkg", "")
            donorVersionCode = props.getProperty("donor.versionCode", "0").toLongOrNull() ?: 0
            donorVersionName = props.getProperty("donor.versionName", "")
            manual = props.getProperty("donor.manual", "false").toBoolean()
            avalonSha256 = props.getProperty("avalon.sha256", "")
            nativePresent = props.getProperty("native.present", "false").toBoolean()
            nativeVersionCode = props.getProperty("native.versionCode", "0").toLongOrNull() ?: 0
            nativeVersionName = props.getProperty("native.versionName", "")
            source = props.getProperty("source", "")
        }
    } catch (t: Throwable) {
        null
    }
}

private fun writeMeta(ctx: Context, variant: FgoVariant, meta: DonorMeta) {
    try {
        val props = java.util.Properties().apply {
            setProperty("donor.pkg", meta.donorPkg)
            setProperty("donor.versionCode", meta.donorVersionCode.toString())
            setProperty("donor.versionName", meta.donorVersionName)
            setProperty("donor.manual", meta.manual.toString())
            setProperty("avalon.sha256", meta.avalonSha256)
            setProperty("native.present", meta.nativePresent.toString())
            setProperty("native.versionCode", meta.nativeVersionCode.toString())
            setProperty("native.versionName", meta.nativeVersionName)
            setProperty("source", meta.source)
        }
        metaFile(ctx, variant).apply {
            parentFile?.mkdirs()
            outputStream().use { props.store(it, "FGO Patcher donor cache") }
        }
    } catch (t: Throwable) {
        // best effort
    }
}

private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun donorAvalonHash(zip: ZipFile): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    for (abi in FgoPatch.SUPPORTED_ABIS) {
        val entry = zip.getEntry("lib/$abi/libavalon.so") ?: continue
        md.update(abi.toByteArray())
        zip.getInputStream(entry).use { md.update(it.readBytes()) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun clearVariant(ctx: Context, variant: FgoVariant) {
    FgoPatch.donorDir(donorRoot(ctx), variant).deleteRecursively()
}

private fun donorStatusText(ctx: Context): String {
    val labels = FgoPatch.VARIANTS.filter { variantReady(ctx, it) }
        .map { variant -> readMeta(ctx, variant)?.source?.ifEmpty { null } ?: "FGO donor" }
    if (labels.isEmpty()) {
        return if (FgoPatch.avalonRequired) {
            "No compatible FGO detected (required)"
        } else {
            "Test build: no donor needed (avalon omitted)"
        }
    }
    return labels.joinToString(", ")
}

/**
 * Detect installed donors, refresh only what changed, and drop caches whose donor
 * app is gone. Returns the status text.
 */
private fun refreshDonors(ctx: Context, merge: Boolean, log: (String) -> Unit): String {
    for (variant in FgoPatch.VARIANTS) {
        // A manual import is an explicit choice; never overwrite it with a
        // different donor that happens to be installed.
        val cached = readMeta(ctx, variant)
        if (cached?.manual == true && variantReady(ctx, variant)) {
            val version = cached.nativeVersionName.ifBlank { cached.donorVersionName }
            log("${cached.source.ifEmpty { variant.label }} — using imported donor (v$version)")
            continue
        }

        val installed = DONOR_APPS
            .filter { it.variantKey == variant.key }
            .mapNotNull { app ->
                val info = try {
                    ctx.packageManager.getPackageInfo(app.pkg, 0)
                } catch (t: Throwable) {
                    null
                }
                if (info != null) app to info else null
            }
            .maxByOrNull { (app, info) ->
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
                           else info.versionCode.toLong()
                // Newest donor wins; on a tie prefer our own updated app.
                code * 2 + if (app.pkg.startsWith("io.zest.")) 1 else 0
            }

        if (installed == null) {
            val meta = readMeta(ctx, variant)
            if (meta != null && !meta.manual) {
                clearVariant(ctx, variant)
                log("${meta.source.ifEmpty { variant.label }} no longer installed — cleared its cache")
            }
            continue
        }

        val (app, info) = installed
        val apk = File(info.applicationInfo?.sourceDir ?: continue)
        if (!apk.canRead()) {
            log("${app.label} installed but its APK isn't readable — import it manually")
            continue
        }
        prepareFromFile(ctx, apk, variant, app, info, merge, log)
    }
    return donorStatusText(ctx)
}

private fun prepareFromFile(
    ctx: Context,
    apk: File,
    variant: FgoVariant,
    app: DonorApp,
    info: android.content.pm.PackageInfo,
    merge: Boolean,
    log: (String) -> Unit,
) {
    val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    val versionName = info.versionName ?: ""
    val meta = readMeta(ctx, variant) ?: DonorMeta()

    ZipFile(apk).use { zip ->
        val hash = donorAvalonHash(zip)
        val upToDate = !meta.manual &&
            meta.donorPkg == app.pkg &&
            meta.donorVersionCode == versionCode &&
            meta.avalonSha256 == hash &&
            variantReady(ctx, variant)

        if (!upToDate) {
            var extracted = 0
            for (abi in FgoPatch.SUPPORTED_ABIS) {
                val entry = zip.getEntry("lib/$abi/libavalon.so") ?: continue
                val out = FgoPatch.libAvalonFile(donorRoot(ctx), variant, abi)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                extracted++
            }
            if (extracted == 0) return
            meta.avalonSha256 = hash
            log("${app.label} — extracted libavalon (v$versionName)")
        } else {
            log("${app.label} — cache up to date (v$versionName)")
        }

        // Keep the metadata in sync even when the libavalon cache was reused;
        // otherwise a version bump leaves the old donor version recorded.
        meta.donorPkg = app.pkg
        meta.donorVersionCode = versionCode
        meta.donorVersionName = versionName
        meta.manual = false
        meta.source = app.label

        if (merge) {
            val nativeUpToDate = meta.nativePresent &&
                meta.nativeVersionCode == versionCode &&
                meta.nativeVersionName == versionName
            if (!nativeUpToDate) {
                extractNative(zip, ctx, variant)
                meta.nativePresent = true
                meta.nativeVersionCode = versionCode
                meta.nativeVersionName = versionName
                log("${app.label} — extracted extra ABIs (v$versionName)")
            }
        }
    }
    writeMeta(ctx, variant, meta)
}

private fun extractNative(zip: ZipFile, ctx: Context, variant: FgoVariant) {
    val abis = linkedSetOf<String>()
    val all = zip.entries()
    while (all.hasMoreElements()) {
        val name = all.nextElement().name
        if (name.startsWith("lib/")) abis.add(name.removePrefix("lib/").substringBefore('/'))
    }
    for (abi in abis.filter { it != "arm64-v8a" }) {
        FgoPatch.nativeAbiDir(donorRoot(ctx), variant, abi).deleteRecursively()
        val dir = FgoPatch.nativeAbiDir(donorRoot(ctx), variant, abi).apply { mkdirs() }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.startsWith("lib/$abi/") || !entry.name.endsWith(".so")) continue
            if (entry.name.endsWith("libFirebaseCppAppCheck.so")) continue
            zip.getInputStream(entry).use { input ->
                File(dir, entry.name.substringAfterLast('/')).outputStream().use { input.copyTo(it) }
            }
        }
    }
}

private fun importDonor(ctx: Context, uri: Uri, merge: Boolean, log: (String) -> Unit): String {
    log("importing donor BetterFGO APK…")
    val temp = File(ctx.cacheDir, "donor-import.apk")
    try {
        // Materialize the APK so PackageManager can read the real versionName.
        // The binary manifest's string pool is UTF-16, so the old regex found
        // nothing and every manual import was recorded as "unknown".
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }

        val archive = ctx.packageManager.getPackageArchiveInfo(temp.path, 0)
        val pkg = archive?.packageName.orEmpty()
        val versionName = archive?.versionName.orEmpty()
        val versionCode = when {
            archive == null -> 0L
            Build.VERSION.SDK_INT >= 28 -> archive.longVersionCode
            else -> archive.versionCode.toLong()
        }

        val manifest = ZipFile(temp).use { zip ->
            zip.getEntry("AndroidManifest.xml")?.let { zip.getInputStream(it).readBytes() }
        }
        val variant = variantForPackage(pkg) ?: inferVariant(manifest) ?: run {
            log("could not tell if that APK is EN or JP")
            return donorStatusText(ctx)
        }

        // Drop the previous native set so ABIs from the old donor don't linger.
        File(FgoPatch.donorDir(donorRoot(ctx), variant), "native").deleteRecursively()

        var avalonFound = 0
        val avalonParts = StringBuilder()
        ZipFile(temp).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (entry.isDirectory || !name.startsWith("lib/") || !name.endsWith(".so")) continue
                if (name.endsWith("libFirebaseCppAppCheck.so")) continue
                val abi = name.removePrefix("lib/").substringBefore('/')
                val fileName = name.substringAfterLast('/')
                if (fileName != "libavalon.so" && (!merge || abi == "arm64-v8a")) continue
                val bytes = zip.getInputStream(entry).readBytes()
                val dest = if (fileName == "libavalon.so") {
                    FgoPatch.libAvalonFile(donorRoot(ctx), variant, abi)
                } else {
                    File(FgoPatch.nativeAbiDir(donorRoot(ctx), variant, abi), fileName)
                }
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                if (fileName == "libavalon.so") {
                    avalonFound++
                    avalonParts.append(abi).append(sha256(bytes))
                }
            }
        }
        if (avalonFound == 0) {
            log("no libavalon.so found — is that an existing BetterFGO APK?")
            return donorStatusText(ctx)
        }

        val resolvedVersion = versionName.ifBlank { "unknown" }
        writeMeta(
            ctx,
            variant,
            DonorMeta().apply {
                donorPkg = pkg.ifBlank { "manual" }
                donorVersionCode = versionCode
                donorVersionName = resolvedVersion
                manual = true
                avalonSha256 = sha256(avalonParts.toString().toByteArray())
                nativePresent = merge
                nativeVersionCode = versionCode
                nativeVersionName = resolvedVersion
                source = "Donor FGO (${variant.key.uppercase()})"
            },
        )
        log("donor imported for ${variant.label} (v$resolvedVersion)")
        return donorStatusText(ctx)
    } finally {
        temp.delete()
    }
}

private fun variantForPackage(pkg: String): FgoVariant? = when {
    pkg.endsWith(".en") -> FgoPatch.VARIANTS.firstOrNull { it.key == "en" }
    pkg.contains("fategrandorder") || pkg.contains("betterfgo") ->
        FgoPatch.VARIANTS.firstOrNull { it.key == "jp" }
    else -> null
}

private fun inferVariant(manifest: ByteArray?): FgoVariant? {
    val bytes = manifest ?: return null
    val text = String(bytes, Charsets.ISO_8859_1)
    val en = text.contains("betterfgo.en") || text.contains("fategrandorder.en")
    val jp = text.contains("fategrandorder") || text.contains("betterfgo")
    return when {
        en -> FgoPatch.VARIANTS.first { it.key == "en" }
        jp -> FgoPatch.VARIANTS.first { it.key == "jp" }
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Patching pipeline
// ---------------------------------------------------------------------------

private class PatchOutcome(val file: File, val downloadsUri: Uri?)

private fun newWork(ctx: Context): File =
    // Fresh, unique directory instead of clearing a shared one: nothing on the
    // device (including previous work) is ever deleted.
    File(ctx.cacheDir, "work-${System.currentTimeMillis()}").apply { mkdirs() }

private fun runPatchFromUri(
    ctx: Context,
    uri: Uri,
    log: (String) -> Unit,
    setProgress: (Int) -> Unit,
): PatchOutcome {
    val work = newWork(ctx)
    val input = File(work, queryName(ctx, uri) ?: "input.xapk")
    ctx.contentResolver.openInputStream(uri)!!.use { ins ->
        input.outputStream().use { out -> ins.copyTo(out) }
    }
    log("input: ${input.name} (${input.length() / (1024 * 1024)} MiB)")
    return patchInput(ctx, work, input, input.nameWithoutExtension, log, setProgress)
}

/**
 * Patch the game that is already installed on this device, reading its base and
 * split APKs straight from PackageManager (no XAPK needed).
 */
private fun runPatchFromInstalled(
    ctx: Context,
    pkg: String,
    log: (String) -> Unit,
    setProgress: (Int) -> Unit,
): PatchOutcome {
    val work = newWork(ctx)
    val info = ctx.packageManager.getPackageInfo(pkg, 0)
    val appInfo = info.applicationInfo ?: throw IllegalStateException("Package info unavailable")
    val dir = File(work, "installed").apply { mkdirs() }

    File(appInfo.sourceDir).copyTo(File(dir, "base.apk"), overwrite = true)
    appInfo.splitSourceDirs?.forEachIndexed { index, path ->
        File(path).copyTo(File(dir, "split_$index.apk"), overwrite = true)
    }

    val tag = if (pkg.endsWith(".en")) "EN" else "JP"
    val version = info.versionName ?: "unknown"
    log("using installed $pkg (v$version, ${(appInfo.splitSourceDirs?.size ?: 0) + 1} APKs)")
    return patchInput(ctx, work, dir, "FGO_${tag}_$version", log, setProgress)
}

private fun patchInput(
    ctx: Context,
    work: File,
    input: File,
    outputBase: String,
    log: (String) -> Unit,
    setProgress: (Int) -> Unit,
): PatchOutcome {
    setProgress(5)

    val merged = File(work, "merged.apk")
    log("merging split archive (xapk → apk)…")
    ApkMerger().merge(input, merged)
    log("merged: ${merged.length() / (1024 * 1024)} MiB")
    setProgress(25)

    val unsigned = File(work, "unsigned.apk")
    Patcher(
        PatcherConfig(
            apkFile = merged,
            temporaryFilesPath = File(work, "tmp"),
        ),
    ).use { patcher ->
        patcher += setOf(FgoPatch.betterFgoPatch)

        var failed = false
        runBlocking {
            patcher().collect { result ->
                val ex = result.exception
                if (ex == null) {
                    log("applied: ${result.patch.name}")
                } else {
                    failed = true
                    log("FAILED: ${result.patch.name}: $ex")
                }
            }
        }
        if (failed) {
            throw IllegalStateException(
                "Patching failed. Is this the right game (EN/NA or JP) and is its donor available?",
            )
        }
        setProgress(70)

        merged.copyTo(unsigned, overwrite = true)
        patcher.get().applyTo(unsigned)
        log("patched: ${unsigned.length() / (1024 * 1024)} MiB")
    }
    setProgress(85)

    // Test outputs get a unique name so an existing patched APK is never
    // overwritten in Downloads.
    val suffix = if (FgoPatch.TEST_BUILD) "_test_${System.currentTimeMillis()}" else ""
    val signed = sign(ctx, unsigned, work, "Patched_$outputBase$suffix.apk")
    setProgress(100)
    return PatchOutcome(signed, publishToDownloads(ctx, signed))
}

private fun queryName(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

private fun sign(ctx: Context, unsigned: File, work: File, outputName: String): File {
    // Per-user key: generated once and kept in the app's private storage.
    // Test builds use a separate key too, so they are fully independent from
    // the production io.zest.betterfgo.* installs.
    val keystoreName = if (FgoPatch.TEST_BUILD) "signing/patcher-test.keystore"
                       else "signing/patcher.keystore"
    val keystore = File(ctx.filesDir, keystoreName)
    if (!keystore.exists()) {
        keystore.parentFile?.mkdirs()
        val pair = ApkSigner.newPrivateKeyCertificatePair(
            "FGO Patcher",
            Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000),
        )
        val keyStore = ApkSigner.newKeyStore(
            setOf(ApkSigner.KeyStoreEntry("fgo", "fgopatcher", pair)),
        )
        keystore.outputStream().use { keyStore.store(it, "fgopatcher".toCharArray()) }
    }

    val out = File(work, outputName)
    if (out.exists()) out.delete()
    ApkUtils.signApk(
        unsigned,
        out,
        "FGO Patcher",
        ApkUtils.KeyStoreDetails(keystore, "fgopatcher", "fgo", "fgopatcher"),
    )
    return out
}

/** Publishes the patched APK to public Downloads and returns its content URI (API 29+). */
private fun publishToDownloads(ctx: Context, apk: File): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, apk.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            apk.inputStream().use { it.copyTo(out) }
        }
        uri
    } catch (t: Throwable) {
        null
    }
}

private fun installUri(ctx: Context, uri: Uri) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (t: Throwable) {
        Toast.makeText(ctx, "Install prompt failed: ${t.message}", Toast.LENGTH_LONG).show()
    }
}

private fun installFile(ctx: Context, apk: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        installUri(ctx, uri)
    } catch (t: Throwable) {
        Toast.makeText(ctx, "Install prompt failed: ${t.message}", Toast.LENGTH_LONG).show()
    }
}
