package com.yinian.android

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Smoke assertion on the production AndroidManifest.
 *
 * V2-ME removed the system-calendar integration. The merged manifest must
 * declare NEITHER `READ_CALENDAR` NOR `WRITE_CALENDAR`. The schedule and
 * draft APIs read exclusively from the local `time_blocks` table. `INTERNET`
 * is still disallowed because V1/V2 is fully offline.
 *
 * The manifest is read from the assembled debug APK first, falling back to the
 * source manifest if no APK has been built yet (CI/build agents running this
 * before a build).
 */
class ManifestPermissionsTest {

    private fun manifestText(): String {
        val candidates = listOf(
            // Built APK's merged manifest (preferred — it carries the real final tree).
            "build/intermediates/merged_manifests/debug/AndroidManifest.xml",
            "build/intermediates/merged_manifest/debug/AndroidManifest.xml",
            // Source manifest as a fallback. Values are not merged; permissions
            // appear here so a stale source still surfaces the assertion.
            "src/main/AndroidManifest.xml"
        )
        for (rel in candidates) {
            val f = File(rel)
            if (f.exists()) return f.readText()
        }
        error("AndroidManifest.xml not found in any candidate path.")
    }

    @Test
    fun merged_manifest_does_not_declare_WRITE_CALENDAR() {
        val xml = manifestText()
        assertFalse(
            "WRITE_CALENDAR must not appear in the merged manifest.",
            xml.contains("android:name=\"android.permission.WRITE_CALENDAR\"")
        )
    }

    @Test
    fun merged_manifest_does_not_declare_READ_CALENDAR() {
        // V2-ME: removed entirely. The app is strictly local — no system
        // calendar reader dependency at all.
        val xml = manifestText()
        assertFalse(
            "READ_CALENDAR must not appear in the merged manifest (removed in V2-ME).",
            xml.contains("android:name=\"android.permission.READ_CALENDAR\"")
        )
    }

    @Test
    fun manifest_does_not_declare_INTERNET_or_STORAGE_or_LOCATION_permissions() {
        // V1/V2 is fully offline. We do not request INTERNET, EXTERNAL_STORAGE,
        // ACCESS_FINE_LOCATION, or ACCESS_COARSE_LOCATION under any flow.
        val xml = manifestText()
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        )
        for (perm in forbidden) {
            assertFalse(
                "Manifest must not declare forbidden permission $perm",
                xml.contains("android:name=\"$perm\"")
            )
        }
    }
}
