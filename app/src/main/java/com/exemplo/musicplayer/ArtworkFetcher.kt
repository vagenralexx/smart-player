package com.exemplo.musicplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetcher de capas de álbuns via iTunes Search API.
 * Gratuita, sem chave de API necessária.
 */
object ArtworkFetcher {

    private const val MAX_CACHE_SIZE = 300
    private val cache = mutableMapOf<String, String?>()  // max MAX_CACHE_SIZE entries

    /**
     * Devolve URL da capa do álbum em alta resolução (600x600) ou null.
     * Resultado é cacheado para evitar chamadas repetidas.
     */
    suspend fun fetchArtworkUrl(artist: String, album: String): String? {
        val key = "$artist|$album"
        if (cache.containsKey(key)) return cache[key]

        // Evita crescimento ilimitado do cache
        if (cache.size >= MAX_CACHE_SIZE) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }

        return withContext(Dispatchers.IO) {
            try {
                val query  = URLEncoder.encode("$artist $album", "UTF-8")
                val url    = "https://itunes.apple.com/search?term=$query&entity=album&limit=3"
                val conn   = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout    = 5000
                conn.setRequestProperty("User-Agent", "SmartPlayer/1.1.0")

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json     = JSONObject(response)
                val results  = json.getJSONArray("results")
                if (results.length() == 0) { cache[key] = null; return@withContext null }

                // Prefere resultado com artista mais próximo
                val bestResult = (0 until results.length())
                    .map { results.getJSONObject(it) }
                    .firstOrNull { it.optString("artistName").contains(artist, ignoreCase = true) }
                    ?: results.getJSONObject(0)

                // artworkUrl100 → substitui por 600x600
                val artUrl = bestResult.optString("artworkUrl100", null)
                    ?.replace("100x100bb", "600x600bb")

                cache[key] = artUrl
                artUrl
            } catch (e: Exception) {
                cache[key] = null
                null
            }
        }
    }

    /** Limpa o cache (ex: em baixa memória) */
    fun clearCache() = cache.clear()
}
