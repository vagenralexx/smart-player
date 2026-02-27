package com.exemplo.musicplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews

/**
 * Widget do homescreen do Smart Player.
 * Mostra título, artista, artwork e controles de reprodução.
 */
class PlayerWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE  = "com.exemplo.musicplayer.WIDGET_PLAY_PAUSE"
        const val ACTION_SKIP_NEXT   = "com.exemplo.musicplayer.WIDGET_SKIP_NEXT"
        const val ACTION_SKIP_PREV   = "com.exemplo.musicplayer.WIDGET_SKIP_PREV"

        const val EXTRA_SONG_TITLE   = "song_title"
        const val EXTRA_ARTIST       = "song_artist"
        const val EXTRA_IS_PLAYING   = "is_playing"
        const val EXTRA_ART_URI      = "art_uri"

        /**
         * Actualiza todos os widgets com os dados da música actual.
         * Deve ser chamado pelo ViewModel quando o estado muda.
         */
        fun updateWidgets(
            context:   Context,
            title:     String,
            artist:    String,
            isPlaying: Boolean,
            artUri:    String? = null
        ) {
            val intent = Intent(context, PlayerWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(EXTRA_SONG_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_ART_URI, artUri)

                val mgr  = AppWidgetManager.getInstance(context)
                val ids  = mgr.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id, null, null, false, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                // Envia broadcast para o PlaybackService
                context.sendBroadcast(Intent(ACTION_PLAY_PAUSE).apply {
                    setPackage(context.packageName)
                })
            }
            ACTION_SKIP_NEXT -> {
                context.sendBroadcast(Intent(ACTION_SKIP_NEXT).apply {
                    setPackage(context.packageName)
                })
            }
            ACTION_SKIP_PREV -> {
                context.sendBroadcast(Intent(ACTION_SKIP_PREV).apply {
                    setPackage(context.packageName)
                })
            }
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                val title     = intent.getStringExtra(EXTRA_SONG_TITLE)
                val artist    = intent.getStringExtra(EXTRA_ARTIST)
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                val artUri    = intent.getStringExtra(EXTRA_ART_URI)
                val ids       = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                if (ids != null) {
                    val mgr = AppWidgetManager.getInstance(context)
                    for (id in ids) {
                        updateWidget(context, mgr, id, title, artist, isPlaying, artUri)
                    }
                }
            }
        }
    }

    private fun updateWidget(
        context: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        title: String?,
        artist: String?,
        isPlaying: Boolean,
        artUri: String?
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_player)

        // Textos
        views.setTextViewText(R.id.widget_song_title, title ?: "Smart Player")
        views.setTextViewText(R.id.widget_artist, artist ?: "Toca uma música")

        // Ícone play/pause
        val playIcon = if (isPlaying) android.R.drawable.ic_media_pause
                       else android.R.drawable.ic_media_play
        views.setImageViewResource(R.id.widget_btn_play_pause, playIcon)

        // Artwork do álbum
        if (artUri != null) {
            try {
                val uri = android.net.Uri.parse(artUri)
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                if (bmp != null) views.setImageViewBitmap(R.id.widget_album_art, bmp)
                else views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher)
            } catch (_: Exception) {
                views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher)
            }
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher)
        }

        // Intents para botões
        fun makeBroadcast(action: String) = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(action).apply { setPackage(context.packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        views.setOnClickPendingIntent(R.id.widget_btn_play_pause, makeBroadcast(ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_btn_next,       makeBroadcast(ACTION_SKIP_NEXT))
        views.setOnClickPendingIntent(R.id.widget_btn_prev,       makeBroadcast(ACTION_SKIP_PREV))

        // Abre a app ao clicar no widget
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)

        mgr.updateAppWidget(widgetId, views)
    }
}
