package check

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/** An image as the check needs it: its layers, in order, and its config. */
class Image(val layers: List<ByteArray>, val config: JsonObject?, val description: String)

/**
 * A base pulled from its registry BY DIGEST, with no daemon: the distribution API over HTTPS,
 * anonymous bearer tokens, blobs verified against their digests. A tag is refused — a verdict about
 * a tag is about whatever the tag pointed at that minute.
 */
object Registry {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }
    private const val ACCEPT =
        "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, " +
            "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"

    fun pull(reference: String): Image {
        require('@' in reference) { "base must be pinned by digest (name@sha256:…), got $reference" }
        val (name, digest) = reference.split('@', limit = 2)
        val first = name.substringBefore('/')
        val (host, repo) =
            if ('.' in first || ':' in first || first == "localhost") {
                first to name.substringAfter('/')
            } else {
                "registry-1.docker.io" to (if ('/' in name) name else "library/$name")
            }
        val session = Session(host, repo)

        var manifest = json.parseToJsonElement(String(session.get("manifests/$digest", ACCEPT))).jsonObject
        val media = manifest["mediaType"]?.jsonPrimitive?.content ?: ""
        if ("index" in media || "manifest.list" in media) {
            val amd64 = manifest["manifests"]!!.jsonArray.map { it.jsonObject }.firstOrNull {
                val p = it["platform"]?.jsonObject
                p?.get("os")?.jsonPrimitive?.content == "linux" && p["architecture"]?.jsonPrimitive?.content == "amd64"
            } ?: error("$reference has no linux/amd64 manifest")
            manifest = json.parseToJsonElement(
                String(session.get("manifests/${amd64["digest"]!!.jsonPrimitive.content}", ACCEPT)),
            ).jsonObject
        }
        val config = session.blob(manifest["config"]!!.jsonObject["digest"]!!.jsonPrimitive.content)
        val layers = manifest["layers"]!!.jsonArray.map { session.blob(it.jsonObject["digest"]!!.jsonPrimitive.content) }
        return Image(layers, json.parseToJsonElement(String(config)).jsonObject, reference)
    }

    private class Session(val host: String, val repo: String) {
        private var token: String? = null

        fun get(path: String, accept: String? = null): ByteArray {
            val uri = URI("https://$host/v2/$repo/$path")
            var response = send(uri, accept, token)
            if (response.statusCode() == 401) {
                token = authenticate(response.headers().firstValue("www-authenticate").orElse(""))
                response = send(uri, accept, token)
            }
            // A blob usually lives in object storage behind a redirect, and that host must not be
            // handed the registry's token.
            if (response.statusCode() in 300..399) {
                val location = response.headers().firstValue("location").orElseThrow()
                response = send(uri.resolve(location), null, null)
            }
            check(response.statusCode() == 200) { "GET $uri: HTTP ${response.statusCode()}" }
            return response.body()
        }

        fun blob(digest: String): ByteArray {
            val bytes = get("blobs/$digest")
            val actual = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(actual == digest) { "blob $digest arrived as $actual" }
            return bytes
        }

        private fun send(uri: URI, accept: String?, bearer: String?): HttpResponse<ByteArray> {
            val b = HttpRequest.newBuilder(uri).GET()
            accept?.let { b.header("Accept", it) }
            bearer?.let { b.header("Authorization", "Bearer $it") }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
        }

        private fun authenticate(challenge: String): String {
            val params = Regex("""(\w+)="([^"]*)"""").findAll(challenge).associate { it.groupValues[1] to it.groupValues[2] }
            val realm = params["realm"] ?: error("no bearer realm in: $challenge")
            val query = listOfNotNull(
                params["service"]?.let { "service=" + URLEncoder.encode(it, Charsets.UTF_8) },
                "scope=" + URLEncoder.encode(params["scope"] ?: "repository:$repo:pull", Charsets.UTF_8),
            ).joinToString("&")
            val body = send(URI("$realm?$query"), null, null).body()
            val o = Json.parseToJsonElement(String(body)).jsonObject
            return (o["token"] ?: o["access_token"])!!.jsonPrimitive.content
        }
    }
}

/**
 * A `docker save` tarball — the whole image as built, base and added layers together. Used to score
 * corpus rows whose images were made by a Dockerfile rather than by adding one binary to a base.
 */
object SavedImage {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(file: File): Image {
        val entries = HashMap<String, ByteArray>()
        TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isFile) entries[e.name.removePrefix("./")] = tar.readAllBytes()
            }
        }
        val manifest = json.parseToJsonElement(String(entries["manifest.json"] ?: error("no manifest.json in $file")))
            .jsonArray.first().jsonObject
        val config = entries[manifest["Config"]!!.jsonPrimitive.content]?.let { json.parseToJsonElement(String(it)).jsonObject }
        val layers = manifest["Layers"]!!.jsonArray.map { entries[it.jsonPrimitive.content] ?: error("layer ${it.jsonPrimitive.content} missing") }
        return Image(layers, config, file.name)
    }
}
