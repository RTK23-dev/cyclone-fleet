package com.cyclone.mobile.runtime.background

import org.junit.Assert.*
import org.junit.Test

class OfficialHelperInstallPolicyTest {
    @Test fun pinnedUrlIsGithubReleaseApkNotPlay() {
        val url = OfficialHelperInstallPolicy.URL
        assertTrue(url.startsWith("https://github.com/RikkaApps/Shizuku/releases/download/"))
        assertTrue("shizuku-v${OfficialHelperInstallPolicy.VERSION}" in url)
        assertTrue(url.endsWith(".apk"))
        assertFalse("play.google.com" in url.lowercase())
        assertFalse(url.lowercase().startsWith("market:"))
        assertEquals(url, OfficialHelperDownload.URL)
        assertTrue(OfficialHelperInstallPolicy.isPinnedOfficialUrl(url))
        assertFalse(OfficialHelperInstallPolicy.isForbiddenStoreUrl(url))
    }

    @Test fun sha256Is64HexCharsMatchingTheDownloader() {
        val sha = OfficialHelperInstallPolicy.SHA256
        assertEquals(64, sha.length)
        assertTrue(sha.matches(Regex("[0-9a-f]{64}")))
        assertEquals(sha, OfficialHelperDownload.SHA256)
    }

    @Test fun helperPackageMatchesBackgroundSetup() {
        assertEquals("moe.shizuku.privileged.api", OfficialHelperInstallPolicy.PACKAGE)
        assertEquals("13.6.0", OfficialHelperInstallPolicy.VERSION)
        assertEquals(OfficialHelperInstallPolicy.PACKAGE, BackgroundSetup.SHIZUKU_PACKAGE)
    }

    @Test fun storeListingsAreForbiddenAndPinnedGithubIsNot() {
        assertTrue(
            OfficialHelperInstallPolicy.isForbiddenStoreUrl(
                "market://details?id=moe.shizuku.privileged.api",
            ),
        )
        assertTrue(
            OfficialHelperInstallPolicy.isForbiddenStoreUrl(
                "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api",
            ),
        )
        assertTrue(
            OfficialHelperInstallPolicy.isForbiddenStoreUrl(
                "https://PLAY.GOOGLE.COM/store/apps/details?id=moe.shizuku.privileged.api",
            ),
        )
        assertFalse(OfficialHelperInstallPolicy.isForbiddenStoreUrl(OfficialHelperInstallPolicy.URL))
    }

    @Test fun pinnedOfficialUrlAcceptsOnlyTheOfficial1360GithubApk() {
        assertTrue(OfficialHelperInstallPolicy.isPinnedOfficialUrl(OfficialHelperInstallPolicy.URL))
        assertFalse(
            OfficialHelperInstallPolicy.isPinnedOfficialUrl(
                "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api",
            ),
        )
        assertFalse(
            OfficialHelperInstallPolicy.isPinnedOfficialUrl(
                "market://details?id=moe.shizuku.privileged.api",
            ),
        )
        assertFalse(
            OfficialHelperInstallPolicy.isPinnedOfficialUrl(
                "https://github.com/RikkaApps/Shizuku/releases/download/v13.5.4/shizuku-v13.5.4.apk",
            ),
        )
        assertFalse(
            OfficialHelperInstallPolicy.isPinnedOfficialUrl(
                OfficialHelperInstallPolicy.URL.removeSuffix(".apk") + ".asc",
            ),
        )
        assertFalse(
            OfficialHelperInstallPolicy.isPinnedOfficialUrl(
                "http://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.apk",
            ),
        )
    }

    @Test fun backStaysAvailableForEveryPhaseIncludingFailureAndCancel() {
        val phases = listOf("READY", "CONFIRM", "INSTALLING", "INSTALLED", "BLOCKED")
        for (phase in phases) {
            for (failed in listOf(false, true)) {
                for (cancelled in listOf(false, true)) {
                    assertTrue(OfficialHelperInstallPolicy.backAvailable(phase, failed, cancelled))
                }
            }
        }
    }
}
