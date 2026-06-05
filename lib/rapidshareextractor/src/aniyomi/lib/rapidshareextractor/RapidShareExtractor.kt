package aniyomi.lib.rapidshareextractor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

class RapidShareExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val context: Application? = null,
) {
    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private const val LOG_TAG = "YFlix"
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun playerHeaders(url: String): Headers {
        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        return Headers.Builder().apply {
            add("User-Agent", DEFAULT_USER_AGENT)
            add("Accept", "*/*")
            add("Accept-Language", "en-US,en;q=0.9")
            add("Referer", url)
            origin?.let { add("Origin", it) }
            add("Sec-Fetch-Dest", "empty")
            add("Sec-Fetch-Mode", "cors")
            add("Sec-Fetch-Site", "cross-site")
            add("Connection", "keep-alive")
        }.build()
    }

    private fun encDecHeaders(url: String): Headers {
        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        return Headers.Builder().apply {
            add("User-Agent", DEFAULT_USER_AGENT)
            add("Accept", "application/json, text/plain, */*")
            add("Referer", url)
            origin?.let { add("Origin", it) }
        }.build()
    }

    private suspend fun unwrapIframeUrl(url: String): String {
        try {
            val parsedUrl = url.toHttpUrl()
            val iframeHeaders = Headers.Builder().apply {
                add("User-Agent", DEFAULT_USER_AGENT)
                add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                add("Referer", url)
            }.build()

            val html = client.newCall(GET(url, iframeHeaders))
                .awaitSuccess()
                .bodyString()

            val iframeRegex = Regex(
                """<iframe[^>]+src=["']([^"']*(?:/e/|rapidshare|rabbitstream|megacloud|dokicloud|cloudemb|vidsrc|2embed|vdrk|primesrc)[^"']*)["']""",
                RegexOption.IGNORE_CASE,
            )
            var realUrl = iframeRegex.find(html)?.groupValues?.getOrNull(1)

            if (realUrl.isNullOrBlank()) {
                val genericIframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                realUrl = genericIframeRegex.findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull {
                        it.contains("/e/") || it.contains("stream") ||
                            it.contains("player") || it.contains("embed") || it.contains("vdrk")
                    }
            }

            if (!realUrl.isNullOrBlank()) {
                val baseUrl = "${parsedUrl.scheme}://${parsedUrl.host}"
                val fixed = UrlUtils.fixUrl(realUrl, baseUrl)
                if (!fixed.isNullOrBlank()) {
                    Log.d(LOG_TAG, "Extractor: Iframe encontrado vía HTML: $fixed")
                    return fixed
                }
            }
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Extractor: Fallo al leer HTML (${e.message})")
        }

        if (context != null) {
            Log.d(LOG_TAG, "Extractor: Intentando con WebView para saltar protección...")
            return try {
                withTimeout(25_000) { unwrapWithWebView(url) }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Extractor: WebView falló o agotó tiempo")
                ""
            }
        }

        return ""
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun unwrapWithWebView(url: String): String {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context!!).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = DEFAULT_USER_AGENT

                    webViewClient = object : WebViewClient() {
                        private var isStarted = false

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val reqUrl = request.url.toString()

                            if (
                                reqUrl.contains("rabbitstream.net/e/") ||
                                reqUrl.contains("rapidshare.com/e/") ||
                                reqUrl.contains("/ajax/v2/embed-code")
                            ) {
                                if (continuation.isActive) {
                                    Log.d(LOG_TAG, "Extractor: ¡URL Capturada!: $reqUrl")
                                    Handler(Looper.getMainLooper()).post { view.destroy() }
                                    continuation.resume(reqUrl)
                                }
                            }

                            if (reqUrl.contains(".m3u8") && !reqUrl.contains("hls/track")) {
                                if (continuation.isActive) {
                                    Log.d(LOG_TAG, "Extractor: ¡HLS Capturado!: $reqUrl")
                                    // Usar el referer real del request para que el stream no dé 403
                                    val reqReferer = request.requestHeaders["Referer"] ?: url
                                    val videoWithHeaders = "$reqUrl#REFERER=$reqReferer"
                                    Handler(Looper.getMainLooper()).post { view.destroy() }
                                    continuation.resume(videoWithHeaders)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            if (loadedUrl.contains("/cdn-cgi/") || isStarted) return
                            isStarted = true

                            Log.d(LOG_TAG, "Extractor: WebView cargó: " + view.title)

                            val handler = Handler(Looper.getMainLooper())
                            val checkIframe = object : Runnable {
                                var attempts = 0
                                override fun run() {
                                    if (!continuation.isActive) return
                                    attempts++

                                    view.evaluateJavascript(
                                        """
                                        (function() {
                                            var results = [];
                                            var iframes = document.getElementsByTagName('iframe');
                                            for (var i = 0; i < iframes.length; i++) {
                                                var src = iframes[i].src || iframes[i].getAttribute('data-src') || iframes[i].getAttribute('data-lazy-src');
                                                if (!src) continue;
                                                results.push(src);
                                                if (src.includes('/e/') || src.includes('stream') || src.includes('rapidshare') ||
                                                    src.includes('rabbitstream') || src.includes('megacloud') || src.includes('vidsrc') ||
                                                    src.includes('embed') || src.includes('vdrk') || src.includes('primesrc')) return src;
                                            }
                                            var html = document.documentElement.innerHTML;
                                            var regex = /https?:\/\/[^\s"'<>]+(?:rabbitstream|rapidshare|megacloud|vidsrc|vdrk|primesrc)[^\s"'<>]+/gi;
                                            var matches = html.match(regex);
                                            if (matches && matches.length > 0) return matches[0];

                                            return 'DEBUG:' + results.join('|');
                                        })();
                                        """.trimIndent(),
                                    ) { result ->
                                        val cleaned = result?.replace("\\\"", "\"")?.trim('"') ?: ""
                                        if (cleaned.startsWith("DEBUG:")) {
                                            if (attempts < 10 && continuation.isActive) {
                                                handler.postDelayed(this, 2000)
                                            } else if (continuation.isActive) {
                                                view.destroy()
                                                continuation.resume("")
                                            }
                                        } else if (cleaned.isNotEmpty() && cleaned != "null") {
                                            Log.d(LOG_TAG, "Extractor: ¡Éxito en Intento $attempts!: $cleaned")
                                            view.destroy()
                                            continuation.resume(cleaned)
                                        } else if (attempts < 10 && continuation.isActive) {
                                            handler.postDelayed(this, 2000)
                                        } else if (continuation.isActive) {
                                            view.destroy()
                                            continuation.resume("")
                                        }
                                    }
                                }
                            }
                            handler.postDelayed(checkIframe, 3000)
                        }
                    }
                    loadUrl(url, mapOf("User-Agent" to DEFAULT_USER_AGENT))
                }

                continuation.invokeOnCancellation {
                    try {
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    suspend fun videosFromUrl(url: String, prefix: String, preferredLang: String): List<Video> {
        val realUrl = url.substringBefore("#REFERER=")
        val savedReferer = url.substringAfter("#REFERER=", "")

        val isDirectRapid = realUrl.contains("/e/") ||
            realUrl.contains("rapidshare") ||
            realUrl.contains("rabbitstream")

        if (!isDirectRapid) {
            if (realUrl.contains(".m3u8")) {
                val refererToUse = savedReferer.ifBlank {
                    realUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: ""
                }
                // Headers completos para evitar 403 en el stream HLS
                val videoHeaders = Headers.Builder().apply {
                    add("User-Agent", DEFAULT_USER_AGENT)
                    add("Referer", refererToUse)
                    add("Origin", refererToUse.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: refererToUse)
                    add("Accept", "*/*")
                    add("Accept-Language", "en-US,en;q=0.9")
                    add("Sec-Fetch-Dest", "empty")
                    add("Sec-Fetch-Mode", "cors")
                    add("Sec-Fetch-Site", "cross-site")
                    add("Connection", "keep-alive")
                }.build()
                return try {
                    playlistUtils.extractFromHls(
                        playlistUrl = realUrl,
                        referer = refererToUse,
                        videoNameGen = { quality -> "$prefix - $quality" },
                    ).map { video -> Video(video.url, video.quality, video.videoUrl, videoHeaders) }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Extractor: Fallo HLS: ${e.message}")
                    emptyList()
                }
            }

            // Para reproductores SPA (videasy, vidsync, etc.) usar WebView directamente
            if (context != null) {
                Log.d(LOG_TAG, "Extractor: Usando WebView para $prefix...")
                return try {
                    val captured = withTimeout(30_000) { unwrapWithWebView(realUrl) }
                    if (captured.isNotBlank() && captured != realUrl) {
                        videosFromUrl(captured, prefix, preferredLang)
                    } else {
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Extractor: WebView falló para $prefix: ${e.message}")
                    emptyList()
                }
            }

            // Sin WebView: intentar extraer iframe via HTTP
            val unwrappedUrl = unwrapIframeUrl(realUrl)
            if (unwrappedUrl.isNotBlank() && unwrappedUrl != realUrl) {
                return videosFromUrl(unwrappedUrl, prefix, preferredLang)
            }
            return emptyList()
        }

        return processDirectRapid(realUrl, prefix)
    }

    private suspend fun processDirectRapid(url: String, prefix: String): List<Video> {
        val rapidUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val userAgent = DEFAULT_USER_AGENT
        val token = rapidUrl.pathSegments.lastOrNull() ?: return emptyList()
        val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
        val mediaUrl = "$baseUrl/media/$token"

        val mediaHeaders = Headers.Builder().apply {
            add("User-Agent", userAgent)
            add("Accept", "application/json, text/plain, */*")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", url)
        }.build()

        Log.d(LOG_TAG, "Extractor: Solicitando medios de $prefix")
        val encryptedResult = try {
            client.newCall(GET(mediaUrl, mediaHeaders)).awaitSuccess().parseAs<EncryptedRapidResponse>().result
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Extractor: Error en $prefix: ${e.message}")
            return emptyList()
        }

        val decryptionBody = buildJsonObject {
            put("text", encryptedResult)
            put("agent", userAgent)
        }.toJsonRequestBody()

        val rapidResult = try {
            client.newCall(
                POST("https://enc-dec.app/api/dec-rapid", body = decryptionBody, headers = encDecHeaders(url)),
            ).awaitSuccess().parseAs<RapidDecryptResponse>().result
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Extractor: No se pudo desencriptar $prefix")
            return emptyList()
        }

        val videoSources = rapidResult.sources
        val playerHeaders = playerHeaders(url)

        return videoSources.flatMap { source ->
            val videoUrl = source.file
            if (videoUrl.contains(".m3u8")) {
                playlistUtils.extractFromHls(
                    playlistUrl = videoUrl,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "$prefix - $quality" },
                ).map { video ->
                    Video(video.url, video.quality, video.videoUrl, playerHeaders)
                }
            } else {
                emptyList()
            }
        }
    }
}

@Serializable
data class EncryptedRapidResponse(val result: String)

@Serializable
data class RapidDecryptResponse(val status: Int, val result: RapidShareResult)

@Serializable
data class RapidShareResult(
    val sources: List<RapidShareSource> = emptyList(),
    val tracks: List<RapidShareTrack> = emptyList(),
)

@Serializable
data class RapidShareSource(val file: String)

@Serializable
data class RapidShareTrack(val file: String, val label: String? = null, val kind: String)
