package com.exemplo.musicplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repositório responsável por varrer o dispositivo em busca de músicas
 * usando o MediaStore — a API oficial do Android para acessar arquivos de mídia.
 *
 * Como funciona o MediaStore:
 * - É um banco de dados mantido pelo próprio sistema Android.
 * - Sempre que você adiciona/remove um arquivo de áudio, o sistema o indexa automaticamente.
 * - Acessamos ele como um ContentProvider, usando queries com URI + projeção + filtro.
 */
class MusicRepository(private val context: Context) {

    /**
     * Retorna todas as músicas encontradas no armazenamento externo do dispositivo,
     * ordenadas por título.
     *
     * A query ao MediaStore funciona de forma semelhante a um SELECT em SQL:
     *  - URI      → qual "tabela" consultar  (MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
     *  - projection → quais "colunas" trazer  (título, artista, duração, etc.)
     *  - selection  → cláusula WHERE          (apenas arquivos marcados como IS_MUSIC)
     *  - sortOrder  → ORDER BY               (por título, ascendente)
     */
    suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {

        val songs = mutableListOf<Song>()

        // Tabela a ser consultada: arquivos de áudio no armazenamento externo
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: usa a URI paginada (melhor performance)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Colunas que queremos buscar (quanto menos colunas, mais rápido)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,           // ID único da faixa no MediaStore
            MediaStore.Audio.Media.TITLE,          // Título da música
            MediaStore.Audio.Media.ARTIST,         // Nome do artista
            MediaStore.Audio.Media.ALBUM,          // Nome do álbum
            MediaStore.Audio.Media.ALBUM_ID,       // ID do álbum (para buscar a capa)
            MediaStore.Audio.Media.DURATION,       // Duração em milissegundos
            MediaStore.Audio.Media.TRACK,          // Número da faixa no álbum
            MediaStore.Audio.Media.YEAR,           // Ano de lançamento
            MediaStore.Audio.Media.DATA            // Caminho físico do arquivo (legado)
        )

        // Filtro: só queremos arquivos marcados como música (exclui ringtones, notificações, etc.)
        // IS_MUSIC != 0  →  equivale ao WHERE IS_MUSIC = 1
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                "${MediaStore.Audio.Media.DURATION} >= 30000" // ignora clipes < 30 segundos

        // Ordenação: alfabética por título
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        // Executa a query — retorna um Cursor (como um ResultSet em JDBC)
        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,           // selectionArgs: null pois não usamos "?" na selection
            sortOrder
        )?.use { cursor ->
            // Obtém o índice de cada coluna UMA VEZ antes do loop (evita busca repetitiva)
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)

            // Itera linha por linha no resultado
            while (cursor.moveToNext()) {
                val id       = cursor.getLong(idCol)
                val title    = cursor.getString(titleCol) ?: "Sem título"
                val artist   = cursor.getString(artistCol) ?: "<unknown>"
                val album    = cursor.getString(albumCol) ?: "<unknown>"
                val albumId  = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durationCol)
                val track    = cursor.getInt(trackCol)
                val year     = cursor.getInt(yearCol)

                // Monta a URI de conteúdo para o arquivo de áudio:
                // content://media/external/audio/media/<id>
                val songUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                // Monta a URI da capa do álbum:
                // content://media/external/audio/albumart/<albumId>
                // Atenção: pode não existir — o Coil trata isso graciosamente
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )

                songs.add(
                    Song(
                        id          = id,
                        title       = title,
                        artist      = artist,
                        album       = album,
                        duration    = duration,
                        uri         = songUri,
                        albumArtUri = albumArtUri,
                        track       = track,
                        year        = year
                    )
                )
            }
        }

        songs
    }

    /**
     * Busca músicas que contenham o [query] no título, artista ou álbum.
     * Usa seleção parametrizada com "?" para evitar SQL Injection.
     */
    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {

        val songs = mutableListOf<Song>()
        val likeQuery = "%$query%"

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR
        )

        // Busca em título, artista e álbum com termos parametrizados (previne SQL Injection)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND (" +
                "${MediaStore.Audio.Media.TITLE} LIKE ? OR " +
                "${MediaStore.Audio.Media.ARTIST} LIKE ? OR " +
                "${MediaStore.Audio.Media.ALBUM} LIKE ?)"

        val selectionArgs = arrayOf(likeQuery, likeQuery, likeQuery)
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)

            while (cursor.moveToNext()) {
                val id      = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)

                songs.add(
                    Song(
                        id          = id,
                        title       = cursor.getString(titleCol) ?: "Sem título",
                        artist      = cursor.getString(artistCol) ?: "<unknown>",
                        album       = cursor.getString(albumCol) ?: "<unknown>",
                        duration    = cursor.getLong(durationCol),
                        uri         = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId),
                        track       = cursor.getInt(trackCol),
                        year        = cursor.getInt(yearCol)
                    )
                )
            }
        }

        songs
    }
}
