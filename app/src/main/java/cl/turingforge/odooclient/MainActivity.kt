package cl.turingforge.odooclient

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cl.turingforge.odooclient.data.OdooServer
import cl.turingforge.odooclient.data.ServerRepository
import cl.turingforge.odooclient.databinding.ActivityMainBinding
import cl.turingforge.odooclient.util.DownloadSaver
import cl.turingforge.odooclient.util.FileNames
import cl.turingforge.odooclient.util.OdooUrl
import cl.turingforge.odooclient.web.DownloadBridge
import cl.turingforge.odooclient.web.WebProfiles
import cl.turingforge.odooclient.util.ServerShortcuts
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var server: OdooServer
    private lateinit var cookieManager: CookieManager // del perfil del servidor activo
    private lateinit var serverUrl: String
    private lateinit var serverOrigin: String

    private var webViewAlive = false
    private var mainFrameError = false
    private var documentStartScriptInstalled = false

    // Subida de archivos
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserParams: WebChromeClient.FileChooserParams? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null

    // Permisos pendientes
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingStorageAction: (() -> Unit)? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            deliverFileChooserResult(result.resultCode, result.data)
        }

    private val cameraPermissionForChooser =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            launchFileChooser(includeCamera = granted) // si lo niega, igual puede elegir de galería/archivos
        }

    private val cameraPermissionForWeb =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingWebPermission?.let {
                if (granted) it.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else it.deny()
            }
            pendingWebPermission = null
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) action?.invoke() else toast(R.string.permission_storage_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = ServerRepository(this)
        WebProfiles.purgePending(repo)
        server = resolveServer(repo, savedInstanceState) ?: return // ya se redirigió a Setup o al selector
        serverUrl = server.url
        serverOrigin = OdooUrl.origin(server.url)
        repo.markUsed(server.id)
        ServerShortcuts.publish(this, repo.servers())

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webViewAlive = true

        // PRIMERO el perfil: debe asignarse antes de cualquier otro uso del WebView.
        cookieManager = WebProfiles.attach(binding.webView, server)

        applyWindowInsets()
        configureCookies()
        configureWebView()
        installDownloadBridge()
        configureDownloads()
        configureBackNavigation()

        binding.btnRetry.setOnClickListener { binding.webView.reload() }
        binding.btnChangeServer.setOnClickListener { openServerPicker() }

        // Restaura historial/estado del WebView si Android mató la Activity; si no, carga Odoo.
        if (savedInstanceState == null || binding.webView.restoreState(savedInstanceState) == null) {
            binding.webView.loadUrl(OdooUrl.loginUrl(serverUrl))
        }
    }

    /**
     * Elige el servidor a abrir:
     *  1. El indicado en el Intent (selector, acceso directo).
     *  2. Restauración tras muerte del proceso → el último usado (no interrumpir con el selector).
     *  3. 0 servidores → Setup · 1 → ese · 2+ → selector (o el último si "Preguntar" está apagado).
     */
    private fun resolveServer(repo: ServerRepository, savedInstanceState: Bundle?): OdooServer? {
        intent.getStringExtra(EXTRA_SERVER_ID)?.let { id -> repo.find(id)?.let { return it } }
        if (savedInstanceState != null) repo.lastUsed()?.let { return it }
        val servers = repo.servers()
        when {
            servers.isEmpty() -> startActivity(Intent(this, SetupActivity::class.java))
            servers.size == 1 -> return servers.first()
            !repo.askOnLaunch -> return servers.first()
            else -> startActivity(Intent(this, ServerPickerActivity::class.java))
        }
        finish()
        return null
    }

    /** singleTask: un acceso directo a OTRA empresa llega aquí; se reinicia la tarea con ese servidor. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getStringExtra(EXTRA_SERVER_ID) ?: return
        if (::server.isInitialized && id != server.id) {
            startActivity(intentFor(this, id))
            finish()
        }
    }

    private fun openServerPicker() {
        startActivity(Intent(this, ServerPickerActivity::class.java))
    }

    // ---------------------------------------------------------------------------------------
    // Insets
    // ---------------------------------------------------------------------------------------

    /**
     * Con targetSdk 35 la app es edge-to-edge obligatoriamente y `adjustResize` deja de funcionar.
     * Aplicamos padding por barras del sistema + teclado (IME) para que el navbar de Odoo no quede
     * bajo la status bar y los campos de formularios no queden tapados por el teclado.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cookies (sesión)
    // ---------------------------------------------------------------------------------------

    /**
     * Odoo mantiene la sesión en la cookie `session_id`. CookieManager la persiste en disco,
     * pero escribe de forma diferida: llamamos a flush() en onPageFinished y onPause para que,
     * si el sistema mata el proceso, el usuario no tenga que volver a iniciar sesión.
     * Las cookies de terceros se aceptan para flujos de pago/SSO embebidos (iframes).
     */
    private fun configureCookies() {
        cookieManager.apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }
    }

    // ---------------------------------------------------------------------------------------
    // WebView
    // ---------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() = with(binding.webView) {
        // Inspección remota desde chrome://inspect solo en debug.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        settings.apply {
            // OWL (el framework de Odoo) es 100 % JavaScript: sin esto no hay cliente web.
            javaScriptEnabled = true

            // localStorage/sessionStorage: Odoo guarda ahí preferencias de UI, estado del menú,
            // borradores y caché del bus/POS. Desactivado por defecto en WebView.
            domStorageEnabled = true

            // "Base de datos local": IndexedDB (que usan Odoo 18 y su Service Worker/POS offline)
            // está siempre activo junto con JS. databaseEnabled controla solo WebSQL (obsoleto);
            // lo dejamos activo por compatibilidad con módulos de terceros antiguos.
            @Suppress("DEPRECATION")
            databaseEnabled = true

            // Caché: LOAD_DEFAULT respeta las cabeceras HTTP. Los bundles de Odoo
            // (/web/assets/<hash>/...) llevan hash y Cache-Control de larga duración, así que tras
            // la primera carga JS/CSS salen del disco y el arranque es mucho más rápido.
            // Sin red, LOAD_CACHE_ELSE_NETWORK permite al menos mostrar lo cacheado.
            cacheMode = if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK

            // Odoo es responsivo y define su <meta viewport>: respetarlo y sin zoom de página.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // Evita que el "tamaño de fuente" del sistema escale el texto y rompa el layout de Odoo.
            textZoom = 100

            // window.open() de Odoo (enlaces target=_blank, algunos reportes) → onCreateWindow.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            // Permite reproducir audio/video (notificaciones de Discuss, llamadas) sin gesto previo.
            mediaPlaybackRequiresUserGesture = false

            // Seguridad: el contenido remoto no debe leer file:// del dispositivo.
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Mantenemos el UA móvil original (Odoo lo usa para su layout móvil) y añadimos un
            // sufijo para identificar la app en logs/nginx o en lógica propia de Odoo.
            userAgentString = "$userAgentString OdooAndroidClient/${BuildConfig.VERSION_NAME}"
        }

        // Odoo 18 trae su propio modo oscuro; evitamos que Chromium "oscurezca" la página encima.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }

        // Renderizado por GPU: scroll fluido en kanban/listas largas.
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        overScrollMode = View.OVER_SCROLL_NEVER

        webViewClient = OdooWebViewClient()
        webChromeClient = OdooChromeClient()
    }

    private inner class OdooWebViewClient : WebViewClient() {

        /**
         * - Mismo origen que el servidor → se queda en el WebView.
         * - mailto:, tel:, whatsapp:, geo:, intent:// → app externa.
         * - Otros http(s) de navegación top-level (redirecciones de SSO, pasarelas de pago)
         *   → se quedan, para que el flujo vuelva a Odoo. Los enlaces externos con
         *   target=_blank llegan por onCreateWindow y se abren en el navegador.
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> false
                else -> { openExternal(uri); true }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameError = false
            binding.errorView.isVisible = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            cookieManager.flush()
            // Fallback si el WebView no soporta DOCUMENT_START_SCRIPT (el script se protege de doble ejecución).
            if (!documentStartScriptInstalled && url != null && OdooUrl.origin(url) == serverOrigin) {
                view.evaluateJavascript(DownloadBridge.SCRIPT, null)
            }
            binding.errorView.isVisible = mainFrameError
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Solo el documento principal: un asset o una imagen caída no debe tapar Odoo.
            if (request.isForMainFrame) {
                mainFrameError = true
                binding.errorMessage.text = error.description
                binding.errorView.isVisible = true
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Nunca handler.proceed(): expondría la sesión a un MITM y Google Play rechaza la app.
            // Para certificados propios, agrégalos en network_security_config.xml.
            handler.cancel()
        }

        /**
         * Odoo es pesado; en equipos con poca RAM el sistema puede matar el proceso renderer.
         * Sin este override la app entera crashea. Destruimos el WebView y reiniciamos la Activity.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            webViewAlive = false
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            startActivity(Intent(this@MainActivity, MainActivity::class.java))
            finish()
            return true
        }
    }

    private inner class OdooChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.setProgressCompat(newProgress, true)
            binding.progress.isVisible = newProgress < 100
        }

        /** <input type="file"> de Odoo (adjuntos del chatter, imagen de producto/contacto, etc.). */
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            // Si quedó un callback anterior sin responder, el WebView no vuelve a abrir el selector.
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            fileChooserParams = params

            val accept = params.acceptTypes.filter { it.isNotBlank() }
            val acceptsImages = accept.isEmpty() || accept.any { it.startsWith("image") || it == "*/*" }
            val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

            when {
                !acceptsImages || !hasCamera -> launchFileChooser(includeCamera = false)
                hasPermission(Manifest.permission.CAMERA) -> launchFileChooser(includeCamera = true)
                else -> cameraPermissionForChooser.launch(Manifest.permission.CAMERA)
            }
            return true
        }

        /** Escáner de códigos de barras de Odoo (inventario, POS) usa getUserMedia(). */
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val fromOdoo = OdooUrl.origin(request.origin.toString()) == serverOrigin
                val wantsCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                when {
                    !fromOdoo || !wantsCamera -> request.deny()
                    hasPermission(Manifest.permission.CAMERA) ->
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    else -> {
                        pendingWebPermission = request
                        cameraPermissionForWeb.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }

        /**
         * window.open(): usamos un WebView temporal solo para capturar la URL destino.
         * Mismo origen → se carga en el WebView principal (si es una descarga, el
         * DownloadListener la toma y la página actual no cambia). Externo → navegador.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val temp = WebView(this@MainActivity)
            temp.webViewClient = object : WebViewClient() {
                private var handled = false
                private fun route(v: WebView, uri: Uri) {
                    if (handled) return
                    handled = true
                    v.stopLoading()
                    if (OdooUrl.origin(uri.toString()) == serverOrigin) binding.webView.loadUrl(uri.toString())
                    else openExternal(uri)
                    v.post { v.destroy() }
                }
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                    route(v, req.url); return true
                }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank") route(v, Uri.parse(url))
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }

    // ---------------------------------------------------------------------------------------
    // Subida de archivos
    // ---------------------------------------------------------------------------------------

    private fun launchFileChooser(includeCamera: Boolean) {
        val params = fileChooserParams ?: return

        // accept="image/*,.pdf" → MIME types (las extensiones se traducen con MimeTypeMap).
        val mimeTypes = params.acceptTypes
            .flatMap { it.split(",") }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .mapNotNull {
                if (it.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) else it
            }
            .distinct()

        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }

        val cameraIntent = if (includeCamera) createCameraIntent() else null

        // <input capture> → directo a la cámara.
        val intent = if (params.isCaptureEnabled && cameraIntent != null) {
            cameraIntent
        } else {
            Intent.createChooser(contentIntent, getString(R.string.choose_file)).apply {
                cameraIntent?.let { putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
            }
        }

        try {
            fileChooserLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            deliverFileChooserResult(RESULT_CANCELED, null)
        }
    }

    /** La foto se escribe en cache/camera/ vía FileProvider (no requiere permisos de almacenamiento). */
    private fun createCameraIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("odoo_", ".jpg", dir)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraFile = file
        cameraUri = uri
        return intent
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun deliverFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = filePathCallback ?: return

        val uris: Array<Uri>? = if (resultCode != RESULT_OK) null else {
            val picked = buildList {
                data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
                if (isEmpty()) data?.data?.let(::add)
            }
            // Si no vino nada en el Intent, el resultado es la foto de la cámara (si se escribió).
            val result = picked.ifEmpty {
                listOfNotNull(cameraUri.takeIf { (cameraFile?.length() ?: 0L) > 0L })
            }
            result.toTypedArray().takeIf { it.isNotEmpty() }
        }

        // Siempre responder (aunque sea null); si no, el <input> queda bloqueado.
        callback.onReceiveValue(uris)
        filePathCallback = null
        fileChooserParams = null
        if (uris == null) cameraFile?.delete()
        cameraFile = null
        cameraUri = null
    }

    // ---------------------------------------------------------------------------------------
    // Descargas
    // ---------------------------------------------------------------------------------------

    /**
     * Registra el puente JS ↔ Kotlin para descargas blob: (ver DownloadBridge).
     * addWebMessageListener es la opción segura: solo el origen del servidor Odoo puede
     * llamar al puente (addJavascriptInterface lo expone a cualquier iframe).
     */
    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    private fun installDownloadBridge() {
        val webView = binding.webView
        val allowedOrigins = setOf(serverOrigin)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, DownloadBridge.JS_OBJECT_NAME, allowedOrigins) { _, message, _, _, _ ->
                message.data?.let(::onBridgeMessage)
            }
        } else {
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun postMessage(json: String) = runOnUiThread { onBridgeMessage(json) }
            }, DownloadBridge.JS_OBJECT_NAME)
        }

        // Inyectar antes que cualquier script de Odoo, en cada documento del servidor.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, DownloadBridge.SCRIPT, allowedOrigins)
            documentStartScriptInstalled = true
        }
    }

    /** Descargas GET (ej. /web/content/... de adjuntos) → DownloadManager con la cookie de sesión. */
    private fun configureDownloads() {
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = FileNames.fromDownload(url, contentDisposition, mimeType)
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                // Respaldo por si algún blob escapó al interceptor del script.
                binding.webView.evaluateJavascript(
                    "window.__odooSaveBlob && window.__odooSaveBlob(${JSONObject.quote(url)}, ${JSONObject.quote(fileName)})",
                    null
                )
            } else {
                withStoragePermission { enqueueDownload(url, userAgent, mimeType, fileName) }
            }
        }
    }

    private fun enqueueDownload(url: String, userAgent: String, mimeType: String?, fileName: String) {
        // DownloadManager hace una petición nueva fuera del WebView: sin la cookie session_id
        // Odoo respondería con la página de login en vez del archivo.
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            cookieManager.getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("User-Agent", userAgent)
            setTitle(fileName)
            setDescription(getString(R.string.downloading))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        runCatching { getSystemService<DownloadManager>()?.enqueue(request) }
            .onSuccess { toast(R.string.download_started) }
            .onFailure { toast(R.string.download_failed) }
    }

    private fun onBridgeMessage(json: String) {
        val msg = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "download" -> {
                val dataUrl = msg.optString("data")
                val mime = msg.optString("mime").ifBlank { "application/octet-stream" }
                val name = FileNames.withExtension(FileNames.sanitize(msg.optString("name")), mime)
                withStoragePermission { saveBase64(dataUrl, name, mime) }
            }
            "error" -> toast(R.string.download_failed)
        }
    }

    private fun saveBase64(dataUrl: String, name: String, mime: String) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
                    DownloadSaver.save(this@MainActivity, bytes, name, mime)
                }.getOrNull()
            }
            if (uri == null) {
                toast(R.string.download_failed)
            } else {
                Snackbar.make(binding.root, getString(R.string.download_saved, name), Snackbar.LENGTH_LONG)
                    .setAction(R.string.open) { openFile(uri, mime) }
                    .show()
            }
        }
    }

    private fun openFile(uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) } catch (e: ActivityNotFoundException) { toast(R.string.no_app_for_file) }
    }

    /** Android 9 o inferior necesita WRITE_EXTERNAL_STORAGE para escribir en /Download. */
    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            action()
        } else {
            pendingStorageAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Botón Atrás
    // ---------------------------------------------------------------------------------------

    /**
     * Odoo 18 es una SPA que usa history.pushState en cada vista/registro, por lo que
     * canGoBack()/goBack() recorren la navegación interna de Odoo (lista → formulario → atrás).
     * Sin historial, se confirma la salida.
     */
    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = binding.webView
                if (webViewAlive && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Además de salir, ofrece cambiar de servidor (punto de acceso visible a los ajustes).
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.exit_title)
                        .setMessage(getString(R.string.exit_message, server.name))
                        .setNeutralButton(R.string.change_server) { _, _ -> openServerPicker() }
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.exit_confirm) { _, _ -> finish() }
                        .show()
                }
            }
        })
    }

    // ---------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && webViewAlive) binding.webView.onResume()
    }

    override fun onPause() {
        if (::binding.isInitialized && webViewAlive) {
            binding.webView.onPause()
            cookieManager.flush() // persistir session_id antes de ir a segundo plano
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized && webViewAlive) binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        if (::binding.isInitialized && webViewAlive) {
            filePathCallback?.onReceiveValue(null)
            binding.webView.apply {
                stopLoading()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webViewAlive = false
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------------

    private fun openExternal(uri: Uri) {
        val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
            runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                ?.apply { component = null; selector = null } // evita lanzar componentes internos
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        } ?: return

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null) binding.webView.loadUrl(fallback) else toast(R.string.no_app_for_link)
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService<ConnectivityManager>() ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SERVER_ID = "server_id"

        /** Abre un servidor concreto en una tarea limpia (sin historial del servidor anterior). */
        fun intentFor(context: Context, serverId: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW) // los accesos directos exigen una acción
                .putExtra(EXTRA_SERVER_ID, serverId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
