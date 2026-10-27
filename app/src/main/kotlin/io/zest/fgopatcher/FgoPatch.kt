package io.zest.fgopatcher

import android.content.res.AssetManager
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.io.File
import java.util.function.Supplier

/** A supported Fate/Grand Order release. */
data class FgoVariant(
    val original: String,
    val new: String,
    val label: String,
    val key: String,
)

/**
 * The BetterFGO patch.
 *
 * `libavalon.so` is NOT shipped; it is imported from a donor BetterFGO APK and
 * read from [donorRoot] at patch time. `loader.mpe` (libLoader + RestClient stub)
 * is our own code and stays bundled.
 */
object FgoPatch {

    /** Set from the Activity before patching (used only for the bundled loader.mpe). */
    lateinit var assets: AssetManager

    /** Directory holding per-variant donor data: `<donorRoot>/<en|jp>/`. */
    lateinit var donorRoot: File

    /** When true, also pull whole native sets for ABIs the donor has but the target lacks. */
    var mergeDonorAbis: Boolean = false

    const val UNITY_ACTIVITY = "Lcom/unity3d/player/UnityPlayerActivity;"
    private const val LOADER_CLASS = "Lcom/loadLib/libLoader;"

    val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")

    val VARIANTS = listOf(
        FgoVariant("com.aniplex.fategrandorder.en", "io.zest.betterfgo.en", "FGO EN", "en"),
        FgoVariant("com.aniplex.fategrandorder", "io.zest.betterfgo", "FGO JP", "jp"),
    )

    fun variantOf(packageName: String): FgoVariant? =
        VARIANTS.firstOrNull { it.original == packageName }

    fun donorDir(root: File, variant: FgoVariant) = File(root, variant.key)
    fun libAvalonFile(root: File, variant: FgoVariant, abi: String) =
        File(donorDir(root, variant), "libavalon-$abi.so")
    fun nativeAbiDir(root: File, variant: FgoVariant, abi: String) =
        File(donorDir(root, variant), "native/$abi")

    private val resourcesPatch = resourcePatch(
        name = "BetterFGO resources",
        description = "Injects libavalon.so, removes AppCheck and rewrites the manifest.",
        default = false,
    ) {
        execute {
            val inputPackage = packageMetadata.packageName
            val variant = variantOf(inputPackage)
                ?: throw PatchException(
                    "Unsupported package \"$inputPackage\". Supported: " +
                        VARIANTS.joinToString { it.original },
                )

            val dir = donorDir(donorRoot, variant)

            // Merging the donor's extra ABIs copies its game libraries, so the donor
            // must be the same game version as this XAPK.
            if (mergeDonorAbis) {
                val props = java.util.Properties().apply {
                    val meta = File(dir, "meta.properties")
                    if (meta.exists()) meta.inputStream().use { load(it) }
                }
                val donorVersion = props.getProperty("native.versionName")
                    ?: props.getProperty("donor.versionName")
                val targetVersion = packageMetadata.versionName
                if (donorVersion.isNullOrBlank() || donorVersion == "unknown" || donorVersion != targetVersion) {
                    throw PatchException(
                        "Cannot add the donor's armv7 libs: donor version " +
                            "(${donorVersion ?: "unknown"}) doesn't match this XAPK ($targetVersion). " +
                            "Turn off the armv7 option or use a matching donor.",
                    )
                }
            }

            val entries = listApkEntries()

            val targetAbis = entries
                .filter { it.startsWith("lib/") }
                .map { it.removePrefix("lib/").substringBefore('/') }
                .toSortedSet()

            // 1. Optionally pull whole native sets for ABIs the target lacks.
            val extraAbis = linkedSetOf<String>()
            if (mergeDonorAbis) {
                File(dir, "native").listFiles().orEmpty().forEach { abiDir ->
                    val abi = abiDir.name
                    if (abi in targetAbis) return@forEach
                    abiDir.listFiles().orEmpty().forEach { lib ->
                        if (lib.name == "libFirebaseCppAppCheck.so") return@forEach
                        val dest = get("lib/$abi/${lib.name}", copy = false)
                        dest.parentFile?.mkdirs()
                        dest.writeBytes(lib.readBytes())
                    }
                    extraAbis.add(abi)
                }
            }

            // 2. Inject libavalon.so for every ABI we can satisfy.
            var injected = 0
            for (abi in targetAbis + extraAbis) {
                val so = libAvalonFile(donorRoot, variant, abi)
                if (!so.exists()) continue
                val dest = get("lib/$abi/libavalon.so", copy = false)
                dest.parentFile?.mkdirs()
                dest.writeBytes(so.readBytes())
                injected++
            }
            if (injected == 0) {
                throw PatchException(
                    "No ${variant.label} donor available. Import a BetterFGO APK for " +
                        "${variant.original}.",
                )
            }

            // 2b. Inject our ascension tap hook + ShadowHook alongside libavalon.
            for (abi in targetAbis + extraAbis) {
                for (name in listOf("libfgohook.so")) {
                    val hook = runCatching {
                        assets.open("fgo/hook/$abi/$name").use { it.readBytes() }
                    }.getOrNull() ?: continue
                    val dest = get("lib/$abi/$name", copy = false)
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(hook)
                }
            }

            // 3. Drop AppCheck for each ABI.
            entries
                .filter { it.startsWith("lib/") && it.endsWith("/libFirebaseCppAppCheck.so") }
                .forEach { delete(it) }

            // 4. Manifest.
            document("AndroidManifest.xml").use { doc ->
                val manifest = doc.getElementsByTagName("manifest").item(0) as? Element
                    ?: throw PatchException("No <manifest> element")

                val originalPackage = manifest.getAttribute("package")

                manifest.setAttribute("package", variant.new)
                manifest.removeAttribute("android:requiredSplitTypes")
                manifest.removeAttribute("android:splitTypes")

                (doc.getElementsByTagName("application").item(0) as? Element)
                    ?.setAttribute("android:label", variant.label)

                val metaData = doc.getElementsByTagName("meta-data")
                for (i in 0 until metaData.length) {
                    val element = metaData.item(i) as? Element ?: continue
                    when (element.getAttribute("android:name")) {
                        "com.android.vending.splits.required" -> {
                            element.setAttribute("android:name", "com.android.dynamic.apk.fused.modules")
                            element.setAttribute("android:value", "base")
                        }
                        "com.android.stamp.type" -> {
                            element.setAttribute("android:value", "STAMP_TYPE_STANDALONE_APK")
                        }
                    }
                }

                replacePackageInAttributes(doc.documentElement, originalPackage, variant.new)
            }
        }
    }

    private fun replacePackageInAttributes(node: org.w3c.dom.Node, from: String, to: String) {
        if (from.isEmpty() || from == to) return
        node.attributes?.let { attributes ->
            for (i in 0 until attributes.length) {
                val attribute = attributes.item(i)
                val value = attribute.nodeValue ?: continue
                if (value.contains(from)) attribute.nodeValue = value.replace(from, to)
            }
        }
        val children = node.childNodes
        for (i in 0 until children.length) replacePackageInAttributes(children.item(i), from, to)
    }

    private val onCreateFingerprint = Fingerprint(
        definingClass = UNITY_ACTIVITY,
        name = "onCreate",
        parameters = listOf("Landroid/os/Bundle;"),
    )

    private val onUnloadedFingerprint = Fingerprint(
        definingClass = UNITY_ACTIVITY,
        name = "onUnityPlayerUnloaded",
    )

    val betterFgoPatch = bytecodePatch(
        name = "BetterFGO (FPS unlock + NP skip)",
        description = "Unlocks 60 FPS and skips NP animations via libavalon.so.",
        default = true,
    ) {
        compatibleWith(
            Compatibility(
                packageName = "com.aniplex.fategrandorder.en",
                name = "Fate/Grand Order (English)",
                apkFileType = ApkFileType.XAPK,
            ),
            Compatibility(
                packageName = "com.aniplex.fategrandorder",
                name = "Fate/Grand Order (Japanese)",
                apkFileType = ApkFileType.XAPK,
            ),
        )

        dependsOn(resourcesPatch)

        extendWith(Supplier { assets.open("fgo/loader.mpe") })

        execute {
            val call = "invoke-static {}, $LOADER_CLASS->loadLib()V"
            onCreateFingerprint.method.addInstructions(0, call)
            onUnloadedFingerprint.method.addInstructions(0, call)
        }
    }
}
