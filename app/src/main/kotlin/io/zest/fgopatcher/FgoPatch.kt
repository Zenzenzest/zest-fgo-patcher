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
import java.util.function.Supplier

/** A supported Fate/Grand Order release. */
data class FgoVariant(
    val original: String,
    val new: String,
    val label: String,
    val key: String,
)

/**
 * Zest's in-house patch.
 *
 * No donor APK: the bundled libfgohook.so provides 60 FPS,
 * NP skip, ascension dialogue skip and name fixes. `loader.mpe` is our own code.
 */
object FgoPatch {

    /** Set from the Activity before patching (used only for the bundled loader.mpe). */
    lateinit var assets: AssetManager

    const val UNITY_ACTIVITY = "Lcom/unity3d/player/UnityPlayerActivity;"
    private const val LOADER_CLASS = "Lcom/loadLib/libLoader;"

    const val TEST_BUILD = false
    private val testInfix = ""
    private val testLabel = ""

    val VARIANTS = listOf(
        FgoVariant(
            "com.aniplex.fategrandorder.en",
            "io.zest.${testInfix}betterfgo.en",
            "FGO EN$testLabel",
            "en",
        ),
        FgoVariant(
            "com.aniplex.fategrandorder",
            "io.zest.${testInfix}betterfgo",
            "FGO JP$testLabel",
            "jp",
        ),
    )

    fun variantOf(packageName: String): FgoVariant? =
        VARIANTS.firstOrNull { it.original == packageName }

    private val resourcesPatch = resourcePatch(
        name = "BetterFGO resources",
        description = "Injects the in-house hook, removes AppCheck and rewrites the manifest.",
        default = false,
    ) {
        execute {
            val inputPackage = packageMetadata.packageName
            val variant = variantOf(inputPackage)
                ?: throw PatchException(
                    "Unsupported package \"$inputPackage\". Supported: " +
                        VARIANTS.joinToString { it.original },
                )

            val entries = listApkEntries()

            val targetAbis = entries
                .filter { it.startsWith("lib/") }
                .map { it.removePrefix("lib/").substringBefore('/') }
                .toSortedSet()

            // Remove any legacy donor library when re-patching an older APK.
            entries.filter { it.endsWith("/libavalon.so") }.forEach { delete(it) }

            // Inject the in-house hook for every ABI the target ships.
            for (abi in targetAbis) {
                val hook = runCatching {
                    assets.open("fgo/hook/$abi/libfgohook.so").use { it.readBytes() }
                }.getOrNull() ?: continue
                val dest = get("lib/$abi/libfgohook.so", copy = false)
                dest.parentFile?.mkdirs()
                dest.writeBytes(hook)
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
        name = "Zest native hooks (60 FPS, NP skip, ascension, names)",
        description = "Unlocks 60 FPS, skips NP animations, adds ascension dialogue skip and name fixes via the bundled libfgohook.so.",
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
