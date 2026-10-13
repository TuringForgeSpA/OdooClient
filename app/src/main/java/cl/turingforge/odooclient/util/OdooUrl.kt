package cl.turingforge.odooclient.util

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object OdooUrl {

    /**
     * Normaliza lo que escribe el usuario:
     *  "miempresa.odoo.com"                → "https://miempresa.odoo.com"
     *  "https://erp.cl/odoo/action-123/"   → "https://erp.cl"   (se quita la ruta del cliente web)
     * Devuelve null si no es una URL http(s) válida.
     */
    fun normalize(raw: String): String? {
        var url = raw.trim().replace(" ", "")
        if (url.isEmpty()) return null
        if (!url.contains("://")) url = "https://$url"

        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host
        if (host.isNullOrBlank() || (!host.contains('.') && host != "localhost")) return null

        // Odoo 18 usa /odoo como ruta del cliente (antes /web). Nos quedamos con la base.
        val path = (uri.encodedPath ?: "").trimEnd('/')
            .replace(Regex("(/odoo|/web)(/.*)?$"), "")

        return "$scheme://${uri.encodedAuthority?.lowercase()}$path"
    }

    /**
     * URL de arranque. No todas las instancias muestran el botón "Iniciar sesión" en su
     * página principal (sitio web público), así que se entra siempre por /web/login.
     * Con `redirect=/odoo`:
     *  - sin sesión → formulario de login y, al entrar, el cliente web de Odoo 18.
     *  - con sesión válida → Odoo redirige directo a /odoo (no se vuelve a pedir login).
     */
    fun loginUrl(baseUrl: String): String = "$baseUrl/web/login?redirect=%2Fodoo"

    /** "https://erp.cl:8443/odoo" → "https://erp.cl:8443" (formato que exige addWebMessageListener). */
    fun origin(url: String): String {
        val uri = Uri.parse(url)
        return "${uri.scheme}://${uri.encodedAuthority}".lowercase()
    }
}

object OdooServerProbe {

    /**
     * Consulta la ruta JSON-RPC pública /web/webclient/version_info para confirmar que
     * la URL es un Odoo y obtener su versión ("18.0", "saas~18.2"...). null = no verificable.
     */
    suspend fun fetchVersion(baseUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$baseUrl/web/webclient/version_info").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use {
                    it.write("""{"jsonrpc":"2.0","method":"call","params":{}}""".toByteArray())
                }
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optJSONObject("result")?.optString("server_version")?.ifBlank { null }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** "saas~18.2" → 18 */
    fun majorVersion(version: String): Int? =
        Regex("""\d+""").find(version)?.value?.toIntOrNull()
}
