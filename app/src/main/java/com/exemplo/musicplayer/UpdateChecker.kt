package com.exemplo.musicplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Verifica se existe uma versão mais recente do Smart Player no GitHub.
 *
 * Como funciona:
 * 1. Consulta a API pública do GitHub: GET /repos/OWNER/REPO/releases/latest
 * 2. Compara a tag_name da release (ex: "v1.2.0") com a versão do app atual
 * 3. Se houver nova versão, retorna os detalhes para mostrar um diálogo ao utilizador
 *
 * Custo: zero — a API pública do GitHub permite 60 pedidos/hora sem autenticação.
 *
 * CONFIGURAÇÃO NECESSÁRIA:
 * Substituir GITHUB_OWNER e GITHUB_REPO com os teus dados reais após criar o repositório.
 */
object UpdateChecker {

    // ---- MODIFICA ESTES DOIS VALORES após criar o repositório ----
    private const val GITHUB_OWNER = "vagenralexx"   // ex: "joaosilva"
    private const val GITHUB_REPO  = "smart-player"   // nome do repositório

    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,      // ex: "v1.2.0"
        val downloadUrl: String,        // URL direto do APK
        val releaseNotes: String        // Notas da release
    )

    /**
     * Verifica se há nova versão disponível.
     * @return [UpdateInfo] se houver update, null se já estiver na versão mais recente ou se falhar
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersion = "v${context.packageManager
                .getPackageInfo(context.packageName, 0).versionName}"

            val json = URL(API_URL).readText(Charsets.UTF_8)
            val obj  = JSONObject(json)

            val latestVersion = obj.getString("tag_name").trim()   // ex: "v1.2.0"

            // Comparação semântica: ignora espaços e diferenças de maiúsculas
            if (latestVersion.equals(currentVersion.trim(), ignoreCase = true)) return@withContext null

            // Garante que a versão do GitHub é de facto mais recente (evita "downgrade" falso)
            val latest = latestVersion.trimStart('v', 'V')
            val current = currentVersion.trimStart('v', 'V')
            val latestParts  = latest.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
            var isNewer = false
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) { isNewer = true; break }
                if (l < c) { isNewer = false; break }
            }
            if (!isNewer) return@withContext null

            // Encontrar o asset .apk na release
            val assets = obj.getJSONArray("assets")
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (downloadUrl.isEmpty()) return@withContext null

            val notes = obj.optString("body", "").take(300)

            UpdateInfo(latestVersion, downloadUrl, notes)

        } catch (_: Exception) {
            null // Sem internet ou repositório ainda não existe — falha silenciosa
        }
    }

    /**
     * Abre o browser para descarregar o APK da nova versão.
     */
    fun openDownloadPage(context: Context, downloadUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
        context.startActivity(intent)
    }
}
