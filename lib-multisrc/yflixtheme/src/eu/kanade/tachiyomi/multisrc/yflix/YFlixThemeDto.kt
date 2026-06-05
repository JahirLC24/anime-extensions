package eu.kanade.tachiyomi.multisrc.yflix

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// ── Respuesta AJAX genérica (usada antes para encrypt/decrypt) ──────────────
@Serializable
data class ResultResponse(
    val result: String = "",
) {
    fun toDocument(): Document = Jsoup.parse(result)
}

// ── Respuesta decrypt de iframe (legado, se mantiene por compatibilidad) ─────
@Serializable
data class DecryptedIframeResponse(
    val result: IframeResult = IframeResult(),
)

@Serializable
data class IframeResult(
    val url: String = "",
)

// ── Sources de episodio de serie (nuevo AJAX /ajax/episode/sources) ──────────
@Serializable
data class EpisodeSourcesResponse(
    val sources: List<EpisodeSource> = emptyList(),
)

@Serializable
data class EpisodeSource(
    val name: String = "",
    val url: String = "",
)

// ── Sources de película embebidos en data-sources del player-wrap ────────────
@Serializable
data class MovieSource(
    val name: String = "",
    val url: String = "",
)
