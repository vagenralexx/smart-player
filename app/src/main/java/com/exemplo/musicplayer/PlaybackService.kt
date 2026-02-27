package com.exemplo.musicplayer

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Serviço de reprodução em segundo plano.
 *
 * Por que um Service?
 * - A Activity pode ser destruída (usuário muda de app, tela apaga, etc.)
 * - O Service continua rodando em segundo plano.
 * - O sistema Android exibe uma notificação de mídia automaticamente
 *   (com controles de play/pause/skip) quando usamos MediaSessionService.
 *
 * Ciclo de vida:
 *  onCreate()   → cria ExoPlayer + MediaSession
 *  onDestroy()  → libera recursos ao encerrar o app completamente
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Configura os atributos de áudio para indicar ao sistema que é música
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Cria o motor de reprodução (ExoPlayer)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            // Pausa automaticamente ao desconectar fones de ouvido (comportamento padrão esperado)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Cria a sessão de mídia — ela expõe o player para:
        //  - Notificação do sistema (controles na aba de som)
        //  - Android Auto
        //  - Assistente de voz
        //  - MediaController na Activity
        mediaSession = MediaSession.Builder(this, player).build()
    }

    /**
     * Chamado pelo sistema (e pelo MediaController da Activity) para obter a sessão ativa.
     * Retornamos nossa única sessão.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // Libera recursos nesta ordem: player → session → referência
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
