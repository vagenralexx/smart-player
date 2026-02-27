package com.exemplo.musicplayer

import android.net.Uri

/**
 * Representa uma música encontrada no dispositivo via MediaStore.
 *
 * @param id        ID único fornecido pelo MediaStore (MediaStore.Audio.Media._ID)
 * @param title     Título da faixa
 * @param artist    Nome do artista
 * @param album     Nome do álbum
 * @param duration  Duração em milissegundos
 * @param uri       URI de conteúdo para reprodução (content://media/external/audio/media/<id>)
 * @param albumArtUri URI da capa do álbum (pode ser nula se não houver arte)
 * @param track     Número da faixa dentro do álbum (usado para ordenar)
 * @param year      Ano de lançamento
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: Uri,
    val albumArtUri: Uri?,
    val track: Int = 0,
    val year: Int = 0
) {
    /**
     * Formata a duração para exibição no formato mm:ss
     */
    val durationFormatted: String
        get() {
            val totalSeconds = duration / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    /**
     * Retorna o artista ou "Artista desconhecido" se vazio
     */
    val artistOrUnknown: String
        get() = if (artist.isBlank() || artist == "<unknown>") "Artista desconhecido" else artist

    /**
     * Retorna o álbum ou "Álbum desconhecido" se vazio
     */
    val albumOrUnknown: String
        get() = if (album.isBlank() || album == "<unknown>") "Álbum desconhecido" else album
}
