package app.tether.update

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal class GatewayRelease private constructor(val version: String, val bytes: Long, val sha256: String) {
    val fileName get() = "Tether-A54-$version.apk"
    val downloadUrl get() = "$RELEASES/download/v$version/$fileName"

    /** Verify the byte count and digest before the APK becomes available to the installer. */
    fun writeVerified(input: InputStream, output: OutputStream) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            written += count
            if (written > bytes) throw InvalidUpdate()
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        if (written != bytes || checksum != sha256) throw InvalidUpdate()
    }

    companion object {
        const val API_URL = "https://api.github.com/repos/amirwmr/tether/releases/latest"
        private const val RELEASES = "https://github.com/amirwmr/tether/releases"
        private const val MAX_APK_BYTES = 128L * 1024 * 1024
        private val STABLE_TAG = Regex("v(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)")
        private val SHA256 = Regex("sha256:[0-9a-f]{64}")
        val APK_NAME = Regex("Tether-A54-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk")

        /** A null result means the installed build is already at or above the latest stable release. */
        fun fromLatest(response: JSONObject, installedCode: Long): GatewayRelease? {
            val tag = response.getString("tag_name")
            val match = STABLE_TAG.matchEntire(tag) ?: throw MissingUpdate()
            if (response.getBoolean("draft") || response.getBoolean("prerelease")) throw MissingUpdate()
            val (major, minor, patch) = match.destructured
            // Same stable versionCode as android/app/build.gradle.kts. A stable release also sorts above its RCs.
            val code = major.toLong() * 1_000_000 + minor.toLong() * 10_000 + patch.toLong() * 100 + 99
            if (code <= installedCode) return null

            val version = tag.removePrefix("v")
            val expected = "Tether-A54-$version.apk"
            val assets = response.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") != expected) continue
                val size = asset.optLong("size", -1)
                val digest = asset.optString("digest")
                val release = GatewayRelease(version, size, digest.removePrefix("sha256:"))
                if (size !in 1..MAX_APK_BYTES || !SHA256.matches(digest) ||
                    asset.optString("browser_download_url") != release.downloadUrl) throw MissingUpdate()
                return release
            }
            throw MissingUpdate()
        }
    }
}

internal class MissingUpdate : IOException()
internal class InvalidUpdate : IOException()
