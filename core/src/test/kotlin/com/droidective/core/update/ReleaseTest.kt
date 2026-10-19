package com.droidective.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {
    @Test
    fun readsGitHubsReply() {
        val release = Release.parse(reply())!!
        assertEquals(Version(0, 2, 0), release.version)
        assertEquals("v0.2.0", release.tag)
        assertEquals("${Release.REPO}/releases/tag/v0.2.0", release.page)
        assertEquals("droidective-android-0.2.0.apk", release.apk?.name)
        assertEquals(6_512_384, release.apk?.size)
        assertEquals("Widgets and developer toggles.", release.notes)
    }

    @Test
    fun draftsPreReleasesAndOddTagsAreNotOffered() {
        assertNull(Release.parse(reply(extra = """"draft": true,""")))
        assertNull(Release.parse(reply(extra = """"prerelease": true,""")))
        assertNull(Release.parse(reply(tag = "nightly")))
    }

    @Test
    fun aLinkThatLeavesThisRepoIsRefused() {
        assertNull(Release.parse(reply(page = "https://example.com/releases/tag/v0.2.0")))
        assertNull(
            Release.parse(reply(page = "https://github.com/Droidective/droidective-android-fork/r"))
        )
        val offRepoApk =
            reply(apkUrl = "https://example.com/droidective-android-0.2.0.apk").let(Release::parse)
        assertNull(offRepoApk?.apk)
    }

    @Test
    fun aReplyItCannotReadIsNull() {
        assertNull(Release.parse(""))
        assertNull(Release.parse("<html>rate limited</html>"))
        assertNull(Release.parse("""{"message": "Not Found"}"""))
        assertNull(Release.parse("[]"))
    }

    @Test
    fun aReleaseWithNoApkIsStillARelease() {
        val release = Release.parse(reply(assets = "[]"))!!
        assertNull(release.apk)
    }

    @Test
    fun onlyANewerReleaseIsOfferedAndOnlyOnce() {
        val release = Release.parse(reply())!!
        assertTrue(UpdatePolicy.isNewer("0.1.0", release))
        assertTrue(UpdatePolicy.isNewer("0.2.0-beta.3", release))
        assertFalse(UpdatePolicy.isNewer("0.2.0", release))
        assertFalse(UpdatePolicy.isNewer("0.3.0", release))
        assertFalse(UpdatePolicy.isNewer("not a version", release))

        assertTrue(UpdatePolicy.shouldNotify("0.1.0", release, notifiedTag = null))
        assertTrue(UpdatePolicy.shouldNotify("0.1.0", release, notifiedTag = "v0.1.5"))
        assertFalse(UpdatePolicy.shouldNotify("0.1.0", release, notifiedTag = "v0.2.0"))
        assertFalse(UpdatePolicy.shouldNotify("0.2.0", release, notifiedTag = null))
    }

    companion object {
        /** GitHub's `releases/latest` reply, trimmed of the fields nobody reads. */
        fun reply(
            tag: String = "v0.2.0",
            page: String = "${Release.REPO}/releases/tag/$tag",
            apkUrl: String = "${Release.REPO}/releases/download/$tag/droidective-android-0.2.0.apk",
            assets: String =
                """[
                  {"name": "droidective-android-0.2.0.apk.sha256",
                   "browser_download_url": "$apkUrl.sha256", "size": 98},
                  {"name": "droidective-android-0.2.0.apk",
                   "browser_download_url": "$apkUrl", "size": 6512384,
                   "content_type": "application/vnd.android.package-archive"}
                ]""",
            extra: String = "",
        ) =
            """{
              "url": "https://api.github.com/repos/Droidective/droidective-android/releases/1",
              "html_url": "$page",
              "id": 1,
              "tag_name": "$tag",
              "name": "Droidective for Android 0.2.0",
              $extra
              "published_at": "2026-11-02T10:00:00Z",
              "author": {"login": "github-actions[bot]"},
              "assets": $assets,
              "body": "Widgets and developer toggles."
            }"""
    }
}
