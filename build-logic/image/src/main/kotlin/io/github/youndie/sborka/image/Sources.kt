package io.github.youndie.sborka.image

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/** An image as the check needs it: its layers in order, the environment and entrypoint of its config. */
public class Image(
    public val layers: List<ByteArray>,
    public val environment: Map<String, String>,
    public val entrypoint: String?,
    public val description: String,
) {
    public companion object {
        /** No layers at all: the check's answer for any dynamic binary is then "no interpreter". */
        public fun scratch(): Image = Image(emptyList(), emptyMap(), null, "scratch")

        internal fun fromConfig(
            layers: List<ByteArray>,
            config: JsonNode?,
            description: String,
        ): Image {
            val c = config?.get("config")
            val env =
                c?.get("Env")?.associate {
                    val s = it.asText()
                    s.substringBefore('=') to s.substringAfter('=', "")
                } ?: emptyMap()
            return Image(layers, env, c?.get("Entrypoint")?.firstOrNull()?.asText(), description)
        }
    }
}

/**
 * A base pulled from its registry BY DIGEST, with no daemon: the distribution API over HTTPS,
 * anonymous bearer tokens, blobs verified against their digests. A tag is refused — a verdict about a
 * tag is about whatever the tag pointed at that minute.
 */
public object Registry {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = ObjectMapper()
    private const val ACCEPT =
        "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, " +
            "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"

    public fun pull(reference: String): Image {
        require('@' in reference) { "the base must be pinned by digest (name@sha256:…), got $reference" }
        val (name, digest) = reference.split('@', limit = 2)
        val first = name.substringBefore('/')
        val (host, repo) =
            if ('.' in first || ':' in first || first == "localhost") {
                first to name.substringAfter('/')
            } else {
                "registry-1.docker.io" to (if ('/' in name) name else "library/$name")
            }
        val session = Session(host, repo)

        var manifest = json.readTree(session.get("manifests/$digest", ACCEPT))
        val media = manifest["mediaType"]?.asText() ?: ""
        if ("index" in media || "manifest.list" in media) {
            val amd64 =
                manifest["manifests"].firstOrNull {
                    val p = it["platform"]
                    p?.get("os")?.asText() == "linux" && p["architecture"]?.asText() == "amd64"
                } ?: error("$reference has no linux/amd64 manifest")
            manifest = json.readTree(session.get("manifests/${amd64["digest"].asText()}", ACCEPT))
        }
        val config = json.readTree(session.blob(manifest["config"]["digest"].asText()))
        val layers = manifest["layers"].map { session.blob(it["digest"].asText()) }
        return Image.fromConfig(layers, config, reference)
    }

    private class Session(
        val host: String,
        val repo: String,
    ) {
        private var token: String? = null

        fun get(
            path: String,
            accept: String? = null,
        ): ByteArray {
            val uri = URI("https://$host/v2/$repo/$path")
            var response = send(uri, accept, token)
            if (response.statusCode() == 401) {
                token = authenticate(response.headers().firstValue("www-authenticate").orElse(""))
                response = send(uri, accept, token)
            }
            // A blob usually lives in object storage behind a redirect, and that host must not be
            // handed the registry's token.
            if (response.statusCode() in 300..399) {
                response = send(uri.resolve(response.headers().firstValue("location").orElseThrow()), null, null)
            }
            check(response.statusCode() == 200) { "GET $uri: HTTP ${response.statusCode()}" }
            return response.body()
        }

        fun blob(digest: String): ByteArray {
            val bytes = get("blobs/$digest")
            val actual =
                "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(actual == digest) { "blob $digest arrived as $actual" }
            return bytes
        }

        private fun send(
            uri: URI,
            accept: String?,
            bearer: String?,
        ): HttpResponse<ByteArray> {
            val b = HttpRequest.newBuilder(uri).GET()
            accept?.let { b.header("Accept", it) }
            bearer?.let { b.header("Authorization", "Bearer $it") }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
        }

        private fun authenticate(challenge: String): String {
            val params =
                Regex("""(\w+)="([^"]*)"""").findAll(challenge).associate {
                    it.groupValues[1] to
                        it.groupValues[2]
                }
            val realm = params["realm"] ?: error("no bearer realm in: $challenge")
            val query =
                listOfNotNull(
                    params["service"]?.let { "service=" + URLEncoder.encode(it, Charsets.UTF_8) },
                    "scope=" + URLEncoder.encode(params["scope"] ?: "repository:$repo:pull", Charsets.UTF_8),
                ).joinToString("&")
            val o = json.readTree(send(URI("$realm?$query"), null, null).body())
            return (o["token"] ?: o["access_token"]).asText()
        }
    }
}

/**
 * A `docker save` tarball — the whole image as built, base and added layers together. How the corpus
 * rows made by a Dockerfile, rather than by one binary on a base, are checked.
 */
public object SavedImage {
    private val json = ObjectMapper()

    public fun read(file: File): Image {
        val entries = HashMap<String, ByteArray>()
        TarArchiveInputStream(file.inputStream().buffered()).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isFile) entries[e.name.removePrefix("./")] = tar.readAllBytes()
            }
        }
        val manifest = json.readTree(entries["manifest.json"] ?: error("no manifest.json in $file")).first()
        val config = entries[manifest["Config"].asText()]?.let(json::readTree)
        val layers = manifest["Layers"].map { entries[it.asText()] ?: error("layer ${it.asText()} missing") }
        return Image.fromConfig(layers, config, file.name)
    }
}
