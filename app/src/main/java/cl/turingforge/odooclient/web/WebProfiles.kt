package cl.turingforge.odooclient.web

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cl.turingforge.odooclient.data.OdooServer
import cl.turingforge.odooclient.data.ServerRepository

/**
 * Perfiles de WebView (androidx.webkit MULTI_PROFILE): cada servidor tiene su propio
 * almacén de cookies, localStorage, IndexedDB, Service Workers y caché HTTP.
 *
 * Por qué importa: CookieManager aísla por dominio pero NO por puerto, así que
 * erp.cl:8069 y erp.cl:8070 compartirían la cookie `session_id` y se cerrarían la sesión
 * mutuamente. Con perfiles, cada empresa queda totalmente separada.
 *
 * Si el WebView del dispositivo es antiguo y no soporta perfiles, se usa el perfil por
 * defecto: sigue funcionando bien siempre que los servidores tengan hosts distintos.
 */
object WebProfiles {

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * Debe llamarse justo después de crear/inflar el WebView y ANTES de tocar settings,
     * cargar URLs o registrar scripts. Devuelve el CookieManager del perfil: a partir de aquí
     * hay que usar ese (CookieManager.getInstance() apunta al perfil por defecto).
     */
    fun attach(webView: WebView, server: OdooServer): CookieManager {
        if (!isSupported) return CookieManager.getInstance()
        val profile = ProfileStore.getInstance().getOrCreateProfile(server.profileName)
        WebViewCompat.setProfile(webView, server.profileName)
        return profile.cookieManager
    }

    /**
     * Borra los perfiles de servidores eliminados. deleteProfile() falla si el perfil está en
     * uso por algún WebView vivo, así que los que no se puedan borrar quedan para el próximo intento.
     */
    fun purgePending(repo: ServerRepository) {
        val pending = repo.pendingProfileDeletes
        if (pending.isEmpty()) return
        if (!isSupported) { repo.pendingProfileDeletes = emptySet(); return }
        val store = ProfileStore.getInstance()
        repo.pendingProfileDeletes = pending.filterNot { name ->
            runCatching { store.deleteProfile(name) }.isSuccess
        }.toSet()
    }
}
