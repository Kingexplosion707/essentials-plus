package com.tx24.spicyplayer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    @Test
    fun `versions compare by number, and a pre-release label comes first`() {
        assertTrue(Releases.compareVersions("0.10.0", "0.9.1") > 0)
        assertTrue(Releases.compareVersions("v0.2.0", "0.1.0-debug") > 0)
        assertEquals(0, Releases.compareVersions("0.1.0", "0.1.0-debug"))
        assertEquals(0, Releases.compareVersions("0.1", "0.1.0"))
        assertTrue(Releases.compareVersions("1.0.0-beta.1", "1.0.0") < 0)
        assertTrue(Releases.compareVersions("1.0.0-beta.10", "1.0.0-beta.2") > 0)
        assertTrue(Releases.compareVersions("1.0.0-rc.1", "1.0.0-beta.3") > 0)
    }

    @Test
    fun `parse keeps releases with an APK and finds its checksum`() {
        val releases = Releases.parse(SAMPLE)
        assertEquals(listOf("v0.3.0", "v0.2.0"), releases.map { it.tag })
        val newest = releases.first()
        assertEquals("0.3.0", newest.version)
        assertEquals("https://example.com/spicy-player-v0.3.0.apk", newest.apkUrl)
        assertEquals("https://example.com/spicy-player-v0.3.0.apk.sha256", newest.sha256Url)
        assertEquals(
            listOf(ReleaseNotes.Entry(null, "Faster lyrics"), ReleaseNotes.Entry("Fixed", "the timeline")),
            newest.notes.sections.single().entries,
        )
    }

    @Test
    fun `a release named after the rename is found, under either name`() {
        val json = """
            [{"tag_name": "v1.0.0", "draft": false, "prerelease": false, "assets": [
              {"name": "spicy-lyrics-mobile-v1.0.0.apk", "browser_download_url": "https://example.com/new.apk", "size": 1},
              {"name": "spicy-lyrics-mobile-v1.0.0.apk.sha256", "browser_download_url": "https://example.com/new.apk.sha256", "size": 1},
              {"name": "spicy-player-v1.0.0.apk", "browser_download_url": "https://example.com/old.apk", "size": 1}]},
             {"tag_name": "v0.9.9", "draft": false, "prerelease": false, "assets": [
              {"name": "spicy-lyrics-mobile-v0.9.9.apk", "browser_download_url": "https://example.com/only-new.apk", "size": 1}]},
             {"tag_name": "v0.9.8", "draft": false, "prerelease": false, "assets": [
              {"name": "some-other-app-v0.9.8.apk", "browser_download_url": "https://example.com/x.apk", "size": 1}]}]
        """.trimIndent()
        val releases = Releases.parse(json)
        assertEquals(listOf("v1.0.0", "v0.9.9"), releases.map { it.tag })
        assertEquals("https://example.com/new.apk", releases[0].apkUrl)
        assertEquals("https://example.com/new.apk.sha256", releases[0].sha256Url)
        assertEquals("https://example.com/only-new.apk", releases[1].apkUrl)
    }

    @Test
    fun `newest respects the pre-release choice`() {
        val releases = Releases.parse(SAMPLE)
        assertEquals("v0.3.0", Releases.newest(releases, "0.1.0", includePrereleases = true)?.tag)
        assertEquals("v0.2.0", Releases.newest(releases, "0.1.0", includePrereleases = false)?.tag)
        assertNull(Releases.newest(releases, "0.3.0", includePrereleases = true))
    }

    private companion object {
        val SAMPLE = """
            [
              {"tag_name": "v0.4.0", "draft": true, "prerelease": false, "assets": [
                {"name": "spicy-player-v0.4.0.apk", "browser_download_url": "https://example.com/d.apk", "size": 1}]},
              {"tag_name": "v0.3.0", "name": "Spicy Player 0.3.0", "draft": false, "prerelease": true,
               "html_url": "https://example.com/v0.3.0",
               "body": "## Changes\n- Faster lyrics\n* **Fixed** the timeline\n\n**Full Changelog**: x...y",
               "assets": [
                {"name": "spicy-player-v0.3.0.apk", "browser_download_url": "https://example.com/spicy-player-v0.3.0.apk", "size": 42},
                {"name": "spicy-player-v0.3.0.apk.sha256", "browser_download_url": "https://example.com/spicy-player-v0.3.0.apk.sha256", "size": 90}]},
              {"tag_name": "v0.2.0", "draft": false, "prerelease": false, "body": "", "assets": [
                {"name": "spicy-player-v0.2.0.apk", "browser_download_url": "https://example.com/b.apk", "size": 1}]},
              {"tag_name": "v0.1.5", "draft": false, "prerelease": false, "assets": []}
            ]
        """.trimIndent()
    }
}
