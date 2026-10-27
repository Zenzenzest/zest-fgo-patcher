package io.zest.fgopatcher

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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkMerger
import app.morphe.patcher.apk.ApkSigner
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.apk.ApkUtils.applyTo
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
        FgoPatch.assets = assets
        FgoPatch.donorRoot = donorRoot(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FgoPatcherApp()
            }
        }
    }
}

@Composable
fun FgoPatcherApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var progress by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var donorStatus by remember { mutableStateOf(donorStatusText(ctx)) }
    var mergeDonor by remember {
        mutableStateOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("merge_abis", false))
    }
    var lastOutcome by remember { mutableStateOf<PatchOutcome?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    // Detect installed donors on launch.
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
            } catch (t: Throwable) {
                main.post { logs.add("donor import failed: ${t.message}") }
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
            } catch (t: Throwable) {
                logs.add("ERROR: ${t.message}")
                Toast.makeText(ctx, "Failed: ${t.message}", Toast.LENGTH_LONG).show()
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

    FgoPatcherScreen(
        logs = logs,
        progress = progress,
        busy = busy,
        donorStatus = donorStatus,
        mergeDonor = mergeDonor,
        onMergeChange = { enabled ->
            mergeDonor = enabled
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("merge_abis", enabled).apply()
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
        onImportDonor = {
            donorPicker.launch(
                arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream"),
            )
        },
        installedGames = installedGames,
        onPatchInstalled = { game ->
            launchPatch {
                withContext(Dispatchers.IO) {
                    runPatchFromInstalled(ctx, game.pkg, logSink, progressSink)
                }
            }
        },
        onPick = {
            apkPicker.launch(
                arrayOf("application/zip", "application/octet-stream", "application/vnd.android.package-archive"),
            )
        },
    )
}

@Composable
fun FgoPatcherScreen(
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
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp),
        ) {
            Text("FGO Patcher", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "Select the vanilla Fate/Grand Order .xapk (English/NA or Japanese). " +
                    "It will be merged, patched (FPS unlock + NP skip) and signed on-device.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            val uriHandler = LocalUriHandler.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Download the Vanilla App Here: ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "fgo.bigcereal.com",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { uriHandler.openUri(VANILLA_APP_URL) },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                donorStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (donorStatus.startsWith("No ")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
            if (donorStatus.startsWith("No ")) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onImportDonor, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Import donor BetterFGO APK")
                }
            } else {
                TextButton(onClick = onImportDonor, enabled = !busy) {
                    Text("Re-import / change donor")
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = mergeDonor, onCheckedChange = onMergeChange, enabled = !busy)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Also include the donor's extra ABIs (armv7)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPick,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Working…" else "Select FGO XAPK and patch")
            }

            if (installedGames.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Or patch the game already installed on this phone:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Your vanilla game is only read — it won't be modified. " +
                        "The patched copy installs as a separate app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                installedGames.forEach { game ->
                    OutlinedButton(
                        onClick = { onPatchInstalled(game) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Patch installed ${game.label} (v${game.versionName})")
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    logs.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }

            if (installReady) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onInstall,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Install patched FGO")
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 820)
@Composable
private fun FgoPatcherScreenPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        FgoPatcherScreen(
            logs = listOf(
                "input: vanilla_2395.xapk (81 MiB)",
                "merging split archive…",
                "merged: 72 MiB",
                "applied: BetterFGO (FPS unlock + NP skip)",
                "patched: 72 MiB",
                "signing…",
                "saved to Downloads/Patched_vanilla_2395.apk",
            ),
            progress = 100,
            busy = false,
            donorStatus = "Rayshift FGO (EN), Rayshift FGO JP",
            mergeDonor = true,
            onMergeChange = {},
            installReady = true,
            onInstall = {},
            installedGames = listOf(
                InstalledGame("com.aniplex.fategrandorder.en", "Fate/GO EN", "2.95.0"),
            ),
            onPatchInstalled = {},
            onImportDonor = {},
            onPick = {},
        )
    }
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
    return if (labels.isEmpty()) "No compatible FGO detected (required)" else labels.joinToString(", ")
}

/**
 * Detect installed donors, refresh only what changed, and drop caches whose donor
 * app is gone. Returns the status text.
 */
private fun refreshDonors(ctx: Context, merge: Boolean, log: (String) -> Unit): String {
    for (variant in FgoPatch.VARIANTS) {
        val installed = DONOR_APPS
            .filter { it.variantKey == variant.key }
            .firstNotNullOfOrNull { app ->
                val info = try {
                    ctx.packageManager.getPackageInfo(app.pkg, 0)
                } catch (t: Throwable) {
                    null
                }
                if (info != null) app to info else null
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
            meta.donorPkg = app.pkg
            meta.donorVersionCode = versionCode
            meta.donorVersionName = versionName
            meta.manual = false
            meta.avalonSha256 = hash
            meta.source = app.label
            log("${app.label} — extracted libavalon (v$versionName)")
        } else {
            log("${app.label} — cache up to date (v$versionName)")
        }

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
    val files = LinkedHashMap<String, ByteArray>()
    var manifest: ByteArray? = null
    ctx.contentResolver.openInputStream(uri)!!.use { input ->
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "AndroidManifest.xml") {
                    manifest = zip.readBytes()
                } else if (name.endsWith("/libavalon.so") && name.startsWith("lib/")) {
                    files[name] = zip.readBytes()
                } else if (merge && name.startsWith("lib/") && name.endsWith(".so") &&
                    !name.endsWith("libFirebaseCppAppCheck.so")
                ) {
                    val abi = name.removePrefix("lib/").substringBefore('/')
                    if (abi != "arm64-v8a") files[name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    val variant = inferVariant(manifest) ?: run {
        log("could not tell if that APK is EN or JP")
        return donorStatusText(ctx)
    }

    var avalonFound = 0
    val avalonParts = StringBuilder()
    for ((name, bytes) in files) {
        val abi = name.removePrefix("lib/").substringBefore('/')
        val fileName = name.substringAfterLast('/')
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
    if (avalonFound == 0) {
        log("no libavalon.so found — is that an existing BetterFGO APK?")
        return donorStatusText(ctx)
    }

    val versionName = inferVersionName(manifest)
    writeMeta(
        ctx,
        variant,
        DonorMeta().apply {
            donorPkg = "manual"
            manual = true
            donorVersionName = versionName ?: "unknown"
            avalonSha256 = sha256(avalonParts.toString().toByteArray())
            nativePresent = merge
            nativeVersionName = versionName ?: "unknown"
            source = "Donor FGO (${variant.key.uppercase()})"
        },
    )
    log("donor imported for ${variant.label}")
    return donorStatusText(ctx)
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

private fun inferVersionName(manifest: ByteArray?): String? {
    val bytes = manifest ?: return null
    val text = String(bytes, Charsets.ISO_8859_1)
    return Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b").find(text)?.value
}

// ---------------------------------------------------------------------------
// Patching pipeline
// ---------------------------------------------------------------------------

private class PatchOutcome(val file: File, val downloadsUri: Uri?)

private fun newWork(ctx: Context): File =
    File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }

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

    val signed = sign(ctx, unsigned, work, "Patched_$outputBase.apk")
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
    val keystore = File(ctx.filesDir, "signing/patcher.keystore")
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
