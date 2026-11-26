package app.tether.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class GatewayReleaseTest {
    private val url = "https://github.com/amirwmr/tether/releases/download/v1.10.2/Tether-A54-1.10.2.apk"
    private val digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun asset(name: String = "Tether-A54-1.10.2.apk", address: String = url, bytes: Long = 3, hash: String = digest) =
        JSONObject().put("name", name).put("browser_download_url", address).put("size", bytes).put("digest", hash)

    private fun latest(vararg assets: JSONObject) = JSONObject().put("tag_name", "v1.10.2")
        .put("draft", false).put("prerelease", false).put("assets", JSONArray(assets.toList()))

    @Test fun selectsOnlyTheOfficialGatewayApk() {
        val release = GatewayRelease.fromLatest(latest(asset("Tether-Client-1.10.2.apk"), asset()), 1_090_999)!!
        assertEquals("1.10.2", release.version)
        assertEquals("Tether-A54-1.10.2.apk", release.fileName)
        assertEquals(url, release.downloadUrl)
    }

    @Test fun stableReleaseUpdatesItsRcButNeverAnEqualOrNewerBuild() {
        val response = latest(asset())
        assertNotNull(GatewayRelease.fromLatest(response, 1_100_201))
        assertNull(GatewayRelease.fromLatest(response, 1_100_299))
        assertNull(GatewayRelease.fromLatest(response, 1_100_399))
    }

    @Test fun ignoresDraftsPrereleasesAndNonVersionTags() {
        for (response in listOf(latest(asset()).put("draft", true), latest(asset()).put("prerelease", true),
            latest(asset()).put("tag_name", "v1.10.2-rc.2"), latest(asset()).put("tag_name", "nightly"))) {
            assertThrows(MissingUpdate::class.java) { GatewayRelease.fromLatest(response, 0) }
        }
    }

    @Test fun debugSignedAndClientBuildsCannotBeOfferedAsUpdates() {
        val response = latest(asset("Tether-A54-1.10.2-debug-signed.apk"), asset("Tether-Client-1.10.2.apk"))
        assertThrows(MissingUpdate::class.java) { GatewayRelease.fromLatest(response, 0) }
    }

    @Test fun rejectsAlternateDownloadLocationsAndUnverifiableFileMetadata() {
        for (candidate in listOf(asset(address = "$url?other"), asset(address = url.replace("https:", "http:")),
            asset(address = url.replace("/amirwmr/", "/another/")), asset(hash = ""), asset(bytes = 0), asset(bytes = 129L * 1024 * 1024))) {
            assertThrows(MissingUpdate::class.java) { GatewayRelease.fromLatest(latest(candidate), 0) }
        }
    }

    @Test fun permitsOnlyTheExactBytesPublishedForTheRelease() {
        val release = GatewayRelease.fromLatest(latest(asset()), 0)!!
        val output = ByteArrayOutputStream()
        release.writeVerified("abc".byteInputStream(), output)
        assertArrayEquals("abc".toByteArray(), output.toByteArray())

        for (contents in listOf("ab", "abd", "abcd")) {
            assertThrows(InvalidUpdate::class.java) {
                release.writeVerified(contents.byteInputStream(), ByteArrayOutputStream())
            }
        }
    }
}
