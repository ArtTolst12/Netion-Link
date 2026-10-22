package io.github.arttolst12.netion

import android.content.Context
import io.github.arttolst12.netion.ui.AppLanguage

/**
 * Localized text for foreground-service notifications and service startup errors.
 * It deliberately reads the app preference rather than relying solely on the system
 * locale, so notification language follows the language selected in the UI.
 */
class NotificationText private constructor(private val strings: Text) {

    val vpnChannelName: String get() = strings.vpnChannelName
    val proxyChannelName: String get() = strings.proxyChannelName
    val proxyTitle: String get() = strings.proxyTitle
    val stopAction: String get() = strings.stopAction
    val vpnStopped: String get() = strings.vpnStopped
    val proxyStopped: String get() = strings.proxyStopped

    fun vpnTitle(proxyType: String): String = strings.vpnTitle.format(proxyType)

    fun vpnBody(host: String, port: Int): String = strings.vpnBody.format(host, port)

    fun proxyBody(httpPort: Int, socks5Port: Int): String = strings.proxyBody.format(httpPort, socks5Port)

    fun vpnStartError(detail: String? = null): String = errorText(strings.vpnStartError, detail)

    fun proxyStartError(detail: String? = null): String = errorText(strings.proxyStartError, detail)

    private fun errorText(prefix: String, detail: String?): String =
        if (detail.isNullOrBlank()) prefix else "$prefix: $detail"

    companion object {
        private const val PREFS_NAME = "netion_prefs"
        private const val LANGUAGE_KEY = "app_language"

        fun forContext(context: Context): NotificationText {
            val code = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LANGUAGE_KEY, null)
            val language = AppLanguage.values().firstOrNull { it.code == code }
                ?: AppLanguage.fromSystemLocale()
            return NotificationText(textFor(language))
        }

        private fun textFor(language: AppLanguage): Text = when (language) {
            AppLanguage.RU -> Text(
                vpnChannelName = "VPN-клиент",
                proxyChannelName = "Раздача прокси",
                vpnTitle = "Netion Link — VPN-клиент (%s)",
                proxyTitle = "Netion Link — Раздача (сервер)",
                vpnBody = "Подключено через %s:%d",
                proxyBody = "HTTP: %d · SOCKS5: %d",
                stopAction = "Остановить",
                vpnStopped = "VPN остановлен",
                proxyStopped = "Раздача остановлена",
                vpnStartError = "Не удалось запустить VPN",
                proxyStartError = "Не удалось запустить раздачу",
            )
            AppLanguage.EN -> Text(
                "VPN client", "Proxy sharing", "Netion Link — VPN Client (%s)",
                "Netion Link — Sharing (Server)", "Connected through %s:%d",
                "HTTP: %d · SOCKS5: %d", "Stop", "VPN stopped", "Sharing stopped",
                "Could not start VPN", "Could not start sharing",
            )
            AppLanguage.DE -> Text(
                "VPN-Client", "Proxy-Freigabe", "Netion Link — VPN-Client (%s)",
                "Netion Link — Freigabe (Server)", "Verbunden über %s:%d",
                "HTTP: %d · SOCKS5: %d", "Stoppen", "VPN beendet", "Freigabe beendet",
                "VPN konnte nicht gestartet werden", "Freigabe konnte nicht gestartet werden",
            )
            AppLanguage.FR -> Text(
                "Client VPN", "Partage proxy", "Netion Link — Client VPN (%s)",
                "Netion Link — Partage (serveur)", "Connecté via %s:%d",
                "HTTP : %d · SOCKS5 : %d", "Arrêter", "VPN arrêté", "Partage arrêté",
                "Impossible de démarrer le VPN", "Impossible de démarrer le partage",
            )
            AppLanguage.ES -> Text(
                "Cliente VPN", "Compartir proxy", "Netion Link — Cliente VPN (%s)",
                "Netion Link — Compartir (servidor)", "Conectado mediante %s:%d",
                "HTTP: %d · SOCKS5: %d", "Detener", "VPN detenido", "Uso compartido detenido",
                "No se pudo iniciar la VPN", "No se pudo iniciar el uso compartido",
            )
            AppLanguage.PT -> Text(
                "Cliente VPN", "Compartilhamento de proxy", "Netion Link — Cliente VPN (%s)",
                "Netion Link — Compartilhamento (servidor)", "Conectado por %s:%d",
                "HTTP: %d · SOCKS5: %d", "Parar", "VPN interrompida", "Compartilhamento interrompido",
                "Não foi possível iniciar a VPN", "Não foi possível iniciar o compartilhamento",
            )
            AppLanguage.PL -> Text(
                "Klient VPN", "Udostępnianie proxy", "Netion Link — Klient VPN (%s)",
                "Netion Link — Udostępnianie (serwer)", "Połączono przez %s:%d",
                "HTTP: %d · SOCKS5: %d", "Zatrzymaj", "VPN zatrzymany", "Udostępnianie zatrzymane",
                "Nie można uruchomić VPN", "Nie można uruchomić udostępniania",
            )
            AppLanguage.ZH -> Text(
                "VPN 客户端", "代理共享", "Netion Link — VPN 客户端（%s）",
                "Netion Link — 共享（服务器）", "已通过 %s:%d 连接",
                "HTTP：%d · SOCKS5：%d", "停止", "VPN 已停止", "共享已停止",
                "无法启动 VPN", "无法启动共享",
            )
            AppLanguage.JA -> Text(
                "VPN クライアント", "プロキシ共有", "Netion Link — VPN クライアント（%s）",
                "Netion Link — 共有（サーバー）", "%s:%d 経由で接続中",
                "HTTP: %d · SOCKS5: %d", "停止", "VPN を停止しました", "共有を停止しました",
                "VPN を開始できません", "共有を開始できません",
            )
            AppLanguage.KO -> Text(
                "VPN 클라이언트", "프록시 공유", "Netion Link — VPN 클라이언트 (%s)",
                "Netion Link — 공유 (서버)", "%s:%d을(를) 통해 연결됨",
                "HTTP: %d · SOCKS5: %d", "중지", "VPN이 중지됨", "공유가 중지됨",
                "VPN을 시작할 수 없습니다", "공유를 시작할 수 없습니다",
            )
        }
    }

    private data class Text(
        val vpnChannelName: String,
        val proxyChannelName: String,
        val vpnTitle: String,
        val proxyTitle: String,
        val vpnBody: String,
        val proxyBody: String,
        val stopAction: String,
        val vpnStopped: String,
        val proxyStopped: String,
        val vpnStartError: String,
        val proxyStartError: String,
    )
}
