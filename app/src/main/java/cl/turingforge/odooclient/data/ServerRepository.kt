package cl.turingforge.odooclient.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.webkit.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Un servidor Odoo registrado. `profileName` es el perfil de WebView que aísla su sesión
 * (cookies, localStorage, IndexedDB, caché) del resto de servidores.
 */
data class OdooServer(
    val id: String,
    val name: String,
    val url: String,
    val profileName: String,
    val lastUsed: Long = 0L
)

/** Lista de servidores persistida como JSON en SharedPreferences. */
class ServerRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    init { migrateLegacySingleUrl() }

    /** Ordenados por último uso (el más reciente primero). */
    fun servers(): List<OdooServer> = read().sortedByDescending { it.lastUsed }

    fun find(id: String): OdooServer? = read().firstOrNull { it.id == id }

    fun findByUrl(url: String): OdooServer? = read().firstOrNull { it.url.equals(url, ignoreCase = true) }

    fun lastUsed(): OdooServer? = servers().firstOrNull()

    fun add(name: String, url: String): OdooServer {
        val id = UUID.randomUUID().toString()
        val server = OdooServer(id, name, url, profileName = "srv_" + id.replace("-", ""))
        write(read() + server)
        return server
    }

    fun update(id: String, name: String, url: String) =
        write(read().map { if (it.id == id) it.copy(name = name, url = url) else it })

    fun markUsed(id: String) =
        write(read().map { if (it.id == id) it.copy(lastUsed = System.currentTimeMillis()) else it })

    /** Quita el servidor y deja su perfil de WebView en cola para borrarlo cuando no esté en uso. */
    fun remove(id: String): OdooServer? {
        val server = find(id) ?: return null
        write(read().filterNot { it.id == id })
        if (server.profileName != Profile.DEFAULT_PROFILE_NAME) {
            pendingProfileDeletes = pendingProfileDeletes + server.profileName
        }
        return server
    }

    /** true: con 2+ servidores se muestra el selector al abrir. false: abre el último usado. */
    var askOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_ASK_ON_LAUNCH, true)
        set(value) = prefs.edit { putBoolean(KEY_ASK_ON_LAUNCH, value) }

    var pendingProfileDeletes: Set<String>
        get() = prefs.getStringSet(KEY_PENDING_PROFILES, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_PENDING_PROFILES, value) }

    private fun read(): List<OdooServer> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                OdooServer(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    profileName = o.getString("profile"),
                    lastUsed = o.optLong("lastUsed")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(servers: List<OdooServer>) {
        val array = JSONArray()
        servers.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("url", it.url)
                    .put("profile", it.profileName).put("lastUsed", it.lastUsed)
            )
        }
        prefs.edit { putString(KEY_SERVERS, array.toString()) }
    }

    /**
     * v1 guardaba una sola URL. La convertimos en el primer servidor usando el perfil
     * "Default" del WebView, que es donde ya vive su cookie de sesión: el usuario no
     * tiene que volver a iniciar sesión tras actualizar la app.
     */
    private fun migrateLegacySingleUrl() {
        if (prefs.contains(KEY_SERVERS)) return
        val legacy = prefs.getString(KEY_LEGACY_URL, null) ?: return
        val server = OdooServer(
            id = UUID.randomUUID().toString(),
            name = Uri.parse(legacy).host ?: legacy,
            url = legacy,
            profileName = Profile.DEFAULT_PROFILE_NAME,
            lastUsed = System.currentTimeMillis()
        )
        write(listOf(server))
        prefs.edit { remove(KEY_LEGACY_URL) }
    }

    private companion object {
        const val FILE_NAME = "odoo_client_prefs" // mismo archivo que v1, para migrar
        const val KEY_LEGACY_URL = "server_url"
        const val KEY_SERVERS = "servers"
        const val KEY_ASK_ON_LAUNCH = "ask_on_launch"
        const val KEY_PENDING_PROFILES = "pending_profile_deletes"
    }
}
