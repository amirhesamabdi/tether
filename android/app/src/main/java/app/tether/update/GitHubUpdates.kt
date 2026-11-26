package app.tether.update

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object GitHubUpdates {
    fun check(installedCode: Long): GatewayRelease? = read(GatewayRelease.API_URL, setOf("api.github.com"), "application/vnd.github+json") { input ->
        val json = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (json.size() + count > 1024 * 1024) throw IOException("release metadata too large")
            json.write(buffer, 0, count)
        }
        GatewayRelease.fromLatest(JSONObject(json.toString("UTF-8")), installedCode)
    }

    fun download(release: GatewayRelease, file: File) {
        read(release.downloadUrl, setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            "application/octet-stream") { input -> file.outputStream().use { release.writeVerified(input, it) } }
    }

    private fun <T> read(address: String, hosts: Set<String>, accept: String, block: (InputStream) -> T): T {
        var url = URL(address)
        repeat(5) {
            if (url.protocol != "https" || url.host !in hosts || url.port !in listOf(-1, 443) || url.userInfo != null) {
                throw IOException("unexpected release host")
            }
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Tether-Android")
                connection.setRequestProperty("Accept", accept)
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use(block)
                    301, 302, 303, 307, 308 -> url = URL(url, connection.getHeaderField("Location") ?: throw IOException("missing redirect"))
                    else -> throw IOException("release request failed ($status)")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }
}
