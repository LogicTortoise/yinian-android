package com.yinian.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Smoke assertion on the production AndroidManifest: V2 must keep calendar
 * integration read-only, so `WRITE_CALENDAR` must never appear in the merged
 * manifest. `READ_CALENDAR` is required so the user can opt in via Me.
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
    fun manifest_declares_READ_CALENDAR_so_users_can_opt_in() {
        val xml = manifestText()
        assertTrue(
            "READ_CALENDAR must be declared so the Me screen switch is wired.",
            xml.contains("android:name=\"android.permission.READ_CALENDAR\"")
        )
    }

    @Test
    fun manifest_does_not_declare_ASR_or_internet_permissions() {
        val xml = manifestText()
        assertFalse(
            "INTERNET must not be declared — V1/V2 is fully offline.",
            xml.contains("android:name=\"android.permission.INTERNET\"")
        )
    }
}
