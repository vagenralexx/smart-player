package com.exemplo.musicplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetcher de metadados de artistas via MusicBrainz API.
 * Gratuita e sem autenticação necessária.
 * Rate limit: 1 req/segundo — respeitado pelo cache.
 */
object MusicBrainzFetcher {

    data class ArtistInfo(
        val name:        String,
        val country:     String?,
        val genres:      List<String>,
        val disambiguation: String?   // ex: "American rock band"
    )

    private val cache = mutableMapOf<String, ArtistInfo?>()

    suspend fun fetchArtistInfo(artistName: String): ArtistInfo? {
        if (cache.containsKey(artistName)) return cache[artistName]

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(artistName, "UTF-8")
                val searchUrl = "https://musicbrainz.org/ws/2/artist/?query=artist:$encoded&limit=1&fmt=json"

                val conn = URL(searchUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout    = 6000
                // MusicBrainz exige User-Agent identificado
                conn.setRequestProperty("User-Agent", "SmartPlayer/1.1.0 (https://github.com/vagenralexx/smart-player)")

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json    = JSONObject(response)
                val artists = json.getJSONArray("artists")
                if (artists.length() == 0) { cache[artistName] = null; return@withContext null }

                val artist = artists.getJSONObject(0)
                val name   = artist.optString("name", artistName)
                val country    = artist.optString("country").takeIf { it.isNotBlank() }
                val disambig   = artist.optString("disambiguation").takeIf { it.isNotBlank() }

                // Géneros
                val genreList = mutableListOf<String>()
                val tags = artist.optJSONArray("tags")
                if (tags != null) {
                    for (i in 0 until minOf(tags.length(), 5)) {
                        val tag = tags.getJSONObject(i)
                        if (tag.optInt("count", 0) > 0)
                            genreList.add(tag.optString("name").replaceFirstChar { it.uppercase() })
                    }
                }

                val info = ArtistInfo(name, country, genreList, disambig)
                cache[artistName] = info
                info
            } catch (e: Exception) {
                cache[artistName] = null
                null
            }
        }
    }

    fun clearCache() = cache.clear()
}
