# Manual de desarrollador — OdooClient (Android)

Cliente Android nativo para Odoo 18. Muestra el cliente web responsivo de Odoo dentro de un `WebView` y suma lo que el navegador no resuelve solo: varios servidores con sesiones aisladas, subida de archivos desde cámara o galería, descarga de reportes PDF y navegación con el botón Atrás.

---

## 1. Requisitos

| Elemento | Versión |
| --- | --- |
| Android Studio | Ladybug o superior (AGP 8.9) |
| JDK | 17 |
| Kotlin | 2.1.20 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| Odoo | 18.0 / saas~18.x (otras versiones funcionan con aviso) |

Dependencias (`app/build.gradle.kts`): `core-ktx`, `appcompat`, `activity-ktx`, `material`, `androidx.webkit`, `lifecycle-runtime-ktx`, `kotlinx-coroutines-android`.

## 2. Compilar y ejecutar

1. Android Studio → *New Project → Empty Views Activity*, package `cl.turingforge.odooclient` (genera el wrapper de Gradle y los íconos `mipmap/ic_launcher`).
2. Copia encima el contenido de esta carpeta.
3. *Run*. La primera ejecución abre `SetupActivity`.

Depuración del contenido web: en builds `debug`, `WebView.setWebContentsDebuggingEnabled(true)` permite inspeccionar Odoo desde `chrome://inspect` en el PC.

## 3. Estructura

```
app/src/main/
├── AndroidManifest.xml
├── java/cl/turingforge/odooclient/
│   ├── MainActivity.kt          WebView: configuración, cookies, subidas, descargas, Atrás, enrutado
│   ├── SetupActivity.kt         Agregar / editar servidor (nombre + URL, validación)
│   ├── ServerPickerActivity.kt  Selector de servidores (conectar, editar, eliminar)
│   ├── data/ServerRepository.kt Modelo OdooServer + persistencia (JSON en SharedPreferences)
│   ├── util/OdooUrl.kt          Normalización de URL, URL de login, probe de versión
│   ├── util/DownloadSaver.kt    Guardado en Descargas + parser de Content-Disposition
│   ├── util/ServerShortcuts.kt  Accesos directos dinámicos por servidor
│   ├── web/DownloadBridge.kt    JavaScript inyectado para descargas blob:
│   └── web/WebProfiles.kt       Perfiles de WebView (aislamiento de sesión)
└── res/
    ├── layout/   activity_main, activity_setup, activity_server_picker, item_server
    ├── xml/      network_security_config, file_paths, shortcuts
    ├── drawable/ bg_server_avatar, ic_more_vert
    └── values/   strings, themes
```

## 4. Flujo de arranque

`MainActivity` es la actividad LAUNCHER y decide qué mostrar (`resolveServer`):

```
Intent trae EXTRA_SERVER_ID válido ─────────────► abrir ese servidor
Restauración tras muerte del proceso ───────────► último servidor usado
0 servidores ───────────────────────────────────► SetupActivity
1 servidor ─────────────────────────────────────► ese servidor
2+ servidores y "Preguntar al abrir" = true ────► ServerPickerActivity
2+ servidores y "Preguntar al abrir" = false ───► último servidor usado
```

- `MainActivity.intentFor(context, serverId)` es la única forma de abrir un servidor: añade `ACTION_VIEW` (obligatorio para shortcuts) y `NEW_TASK | CLEAR_TASK`, de modo que el historial del servidor anterior no se mezcla.
- `launchMode="singleTask"`: si llega un `onNewIntent` con otro `serverId` (acceso directo), se reinicia la tarea con ese servidor.
- La URL inicial es siempre `URL_BASE/web/login?redirect=%2Fodoo` (`OdooUrl.loginUrl`). Sin sesión aparece el formulario de login; con sesión válida Odoo redirige directo a `/odoo`. Esto cubre las instancias con sitio web público que no muestran el botón de login.

## 5. Configuración del WebView

Todo está en `MainActivity.configureWebView()`.

| Ajuste | Valor | Motivo |
| --- | --- | --- |
| `javaScriptEnabled` | `true` | El cliente de Odoo (OWL) es 100 % JS. |
| `domStorageEnabled` | `true` | `localStorage`/`sessionStorage`: preferencias de UI, menú, bus. |
| `databaseEnabled` | `true` | WebSQL (obsoleto), por compatibilidad. IndexedDB está siempre activo con JS. |
| `cacheMode` | `LOAD_DEFAULT` / `LOAD_CACHE_ELSE_NETWORK` sin red | Los assets de Odoo llevan hash y caché larga. |
| `useWideViewPort`, `loadWithOverviewMode` | `true` | Respetar el `<meta viewport>` de Odoo. |
| Zoom | desactivado | Odoo es responsivo. |
| `textZoom` | `100` | La escala de fuente del sistema rompe el layout. |
| `setSupportMultipleWindows` + `javaScriptCanOpenWindowsAutomatically` | `true` | `window.open` se procesa en `onCreateWindow`. |
| `mediaPlaybackRequiresUserGesture` | `false` | Sonidos y llamadas de Discuss. |
| `allowFileAccess` | `false` | El contenido remoto no puede leer `file://`. |
| `mixedContentMode` | `NEVER_ALLOW` | Sin HTTP dentro de HTTPS. |
| User-Agent | original + ` OdooAndroidClient/<versión>` | Odoo sigue detectando móvil; el sufijo identifica la app en los logs. |
| Algorithmic darkening | desactivado | Odoo 18 tiene su propio modo oscuro. |
| Safe Browsing | activado | — |
| Capa | `LAYER_TYPE_HARDWARE` | Scroll fluido en kanban y listas. |

Además:

- **`configChanges`** en el manifest evita recrear la Activity al rotar o desplegar un plegable: la SPA no se recarga y no se pierden formularios a medio editar.
- **Insets (edge-to-edge obligatorio con targetSdk 35):** padding por barras del sistema **e IME**. `adjustResize` ya no actúa, así que sin esto el teclado tapa los campos.
- **`onRenderProcessGone`:** si el sistema mata el renderer por memoria, se destruye el WebView y se reinicia la Activity en vez de cerrar la app con un crash.
- **Sin `SwipeRefreshLayout`:** Odoo hace scroll en divs internos, `scrollY` siempre vale 0 y el gesto se dispararía al hacer scroll.

### Navegación de enlaces

| Caso | Comportamiento |
| --- | --- |
| http(s), navegación top-level | Dentro del WebView (incluye redirecciones de SSO o pasarelas de pago). |
| `mailto:`, `tel:`, `whatsapp:`, `geo:`… | App externa. |
| `intent://` | `Intent.parseUri` sin `component`/`selector`; si no hay app, `browser_fallback_url`. |
| `window.open` / `target=_blank` del mismo origen | Se carga en el WebView principal (o dispara una descarga). |
| `window.open` a otro origen | Navegador del sistema. |

### Errores y SSL

- `onReceivedError`: solo los del documento principal muestran la pantalla nativa (Reintentar / Cambiar servidor).
- `onReceivedSslError`: siempre `cancel()`. Nunca `proceed()`, porque Google Play rechaza la app y expone la sesión.

## 6. Sesión, cookies y perfiles

- Odoo guarda la sesión en la cookie `session_id`. Se llama a `CookieManager.flush()` en `onPageFinished` y en `onPause` para que sobreviva si el proceso muere.
- **Perfiles (`web/WebProfiles.kt`):** cada `OdooServer` tiene un `profileName`. `WebProfiles.attach()` llama a `WebViewCompat.setProfile()` **antes de cualquier otro uso del WebView** y devuelve el `CookieManager` de ese perfil. A partir de ahí `MainActivity` usa siempre ese `cookieManager` (no `CookieManager.getInstance()`, que apunta al perfil por defecto), también para las cabeceras de `DownloadManager`.
- Por qué perfiles: las cookies se aíslan por dominio, **no por puerto**. `erp.cl:8069` y `erp.cl:8070` compartirían `session_id` y se cerrarían la sesión mutuamente.
- Fallback: si `WebViewFeature.MULTI_PROFILE` no está soportado (WebView antiguo), todos los servidores usan el perfil por defecto. Funciona siempre que los hosts sean distintos.
- Borrado: `ServerRepository.remove()` deja el perfil en `pendingProfileDeletes`. `WebProfiles.purgePending()` (en `onCreate` de Main y `onResume` del selector) intenta `ProfileStore.deleteProfile()`, que falla si hay un WebView usándolo; en ese caso queda pendiente para el siguiente intento.

## 7. Subida de archivos

`OdooChromeClient.onShowFileChooser`:

1. Guarda el `ValueCallback`. Si había uno pendiente, primero responde `null`; si no, el `<input>` queda bloqueado.
2. Traduce `accept` (`image/*`, `.pdf`…) a MIME types con `MimeTypeMap`.
3. Si se aceptan imágenes y hay cámara: pide `CAMERA` si falta y añade `ACTION_IMAGE_CAPTURE` como intent inicial del chooser. Con `capture`, abre la cámara directamente.
4. La foto se escribe en `cache/camera/` vía `FileProvider` (sin permisos de almacenamiento).
5. Soporta selección múltiple (`clipData`). Siempre llama a `onReceiveValue`, aunque sea con `null`.

El escáner de códigos de barras de Odoo usa `getUserMedia`. `onPermissionRequest` concede `VIDEO_CAPTURE` solo al origen del servidor activo.

## 8. Descargas

Hay dos caminos:

**a) GET con `Content-Disposition`** (adjuntos `/web/content/...`) → `DownloadListener` → `DownloadManager`, con la cookie del perfil y el User-Agent. El nombre se obtiene con `FileNames.fromDownload`, que entiende `filename*=UTF-8''...` (RFC 5987), formato que `URLUtil.guessFileName` no soporta.

**b) Reportes PDF (`blob:`)** → Odoo 18 hace un POST por XHR, recibe un Blob y simula un clic en `<a href="blob:..." download="X.pdf">`. `DownloadManager` no puede leer `blob:` y Odoo revoca la URL a los pocos milisegundos. Solución en `web/DownloadBridge.kt`:

- Se inyecta con `addDocumentStartJavaScript` (solo en el origen del servidor) o, en WebViews sin esa función, con `evaluateJavascript` en `onPageFinished`.
- Intercepta `HTMLAnchorElement.prototype.click` y los clics en enlaces `blob:`/`data:`, lee el Blob con `FileReader` y lo envía como data-URL base64 por el objeto `OdooAndroid`.
- `OdooAndroid` se registra con `addWebMessageListener` restringido al origen del servidor. Si no está soportado, se usa `addJavascriptInterface` (misma firma `postMessage(string)`).
- Kotlin decodifica y guarda en `IO` (`DownloadSaver`): MediaStore Downloads en Android 10+ y archivo + `FileProvider` en Android 8–9 (pide `WRITE_EXTERNAL_STORAGE`). Luego muestra un Snackbar con la acción *Abrir*.

Límite: el contenido viaja en memoria. Es adecuado para reportes (KB a decenas de MB), no para archivos de cientos de MB.

## 9. Varios servidores

**Modelo** (`data/ServerRepository.kt`):

```kotlin
data class OdooServer(id, name, url, profileName, lastUsed)
```

Se persiste como JSON en `odoo_client_prefs` (clave `servers`), junto con `ask_on_launch` y `pending_profile_deletes`.

**Migración v1 → v2:** si existe la clave antigua `server_url`, se crea un servidor con `profileName = Profile.DEFAULT_PROFILE_NAME`, que es donde ya vive su cookie, así que el usuario no vuelve a iniciar sesión.

**Setup** (`SetupActivity`):
- Agregar: guarda y conecta.
- Editar (`EXTRA_SERVER_ID`): guarda y vuelve.
- Rechaza URLs duplicadas. Sin nombre, usa el host.
- Valida con `POST /web/webclient/version_info` (JSON-RPC público): `null` → aviso "no verificado"; versión mayor ≠ 18 → aviso de versión. El usuario puede continuar en ambos casos.

**Normalización** (`OdooUrl.normalize`): añade `https://` si falta y quita la barra final y las rutas del cliente (`/odoo/...`, `/web/...`).

**Selector** (`ServerPickerActivity`): lista ordenada por último uso. Al eliminar un servidor con el selector abierto encima de `MainActivity`, se reinicia la tarea para cerrar ese WebView y liberar su perfil.

**Accesos directos:**
- Estático `Servidores` (`res/xml/shortcuts.xml`) → selector.
- Dinámicos: los 3 servidores más recientes (`ServerShortcuts.publish`, con 2 o más servidores).
- Al eliminar un servidor se desactiva su acceso anclado.

## 10. Permisos y manifest

| Permiso / elemento | Para qué |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Cargar Odoo; elegir el modo de caché. |
| `CAMERA` (+ `uses-feature` no requerido) | Fotos para adjuntar; escáner web. Al declararla, `ACTION_IMAGE_CAPTURE` exige el permiso concedido. |
| `WRITE_EXTERNAL_STORAGE` `maxSdkVersion=28` | Descargas en Android 8–9. |
| `<queries>` | `IMAGE_CAPTURE`, `GET_CONTENT`, `VIEW` (visibilidad de paquetes, Android 11+). |
| `FileProvider` `${applicationId}.fileprovider` | Fotos de la cámara y abrir descargas legacy. |
| `allowBackup="false"` | No restaurar cookies de sesión en otro dispositivo. |
| `networkSecurityConfig` | Solo HTTPS; excepciones HTTP explícitas; CAs de usuario solo en debug. |

## 11. Personalización para un cliente (white-label)

1. **`applicationId` / `namespace`** en `app/build.gradle.kts`.
2. **`res/xml/shortcuts.xml`**: actualiza `targetPackage` y `targetClass`, que no admiten placeholders. Si no lo haces, el acceso "Servidores" falla en silencio.
3. **Nombre y color:** `app_name` en `strings.xml`, `odoo_purple` en `themes.xml`, ícono en `mipmap/`.
4. **Servidor fijo (opcional):** para precargar una URL, crea el servidor en `ServerRepository` en el primer arranque, o lee un `BuildConfig` field.
5. **HTTP en LAN:** añade el host o la IP a `<domain-config cleartextTrafficPermitted="true">`.
6. **Certificado propio o autofirmado en producción:** añade la CA en `res/raw` y referénciala en `network_security_config.xml`.

## 12. Release

- `isMinifyEnabled` + `isShrinkResources` activos. `proguard-rules.pro` conserva los métodos `@JavascriptInterface` del puente legacy.
- Firma: *Build → Generate Signed App Bundle*. Sube un `.aab` a Play.
- Sube `versionCode` y `versionName` en cada versión (el sufijo del User-Agent usa `versionName`).

## 13. Checklist de pruebas

- [ ] Primer arranque → Setup; URL sin esquema; URL con `/odoo/...` pegada.
- [ ] Servidor con sitio web público: entra al login sin buscar el botón.
- [ ] Cerrar y reabrir la app: la sesión se mantiene.
- [ ] Forzar detención (*Ajustes → Forzar detención*) y reabrir: la sesión se mantiene.
- [ ] Rotar en un formulario a medio editar: no se pierde nada.
- [ ] Adjuntar en el chatter: cámara, galería y varios archivos; cancelar y volver a intentarlo.
- [ ] Imprimir factura (PDF): se guarda en Descargas y *Abrir* funciona. Probar en Android 9 y en 14+.
- [ ] Descargar un adjunto existente (GET).
- [ ] Escáner de códigos de barras (Inventario).
- [ ] Atrás: recorre el historial de Odoo; sin historial muestra el diálogo con *Cambiar servidor*.
- [ ] Dos servidores en el mismo host y distinto puerto: sesiones independientes.
- [ ] Accesos directos: estático y dinámicos; eliminar un servidor anclado.
- [ ] Modo avión: pantalla de error → Reintentar.
- [ ] Actualizar desde v1 (una URL): migra sin pedir login.

## 14. Limitaciones conocidas

- **Google/Microsoft OAuth:** Google bloquea el login en WebViews (`disallowed_useragent`). Alternativas: login de Odoo, o abrir `/auth_oauth/signin` en Custom Tabs y capturar el retorno con App Links.
- **Geolocalización** (Asistencias con ubicación): no implementada. Requiere `ACCESS_FINE_LOCATION` y `onGeolocationPermissionsShowPrompt`.
- **Notificaciones push:** Odoo usa Web Push, que el WebView no soporta. Requiere FCM + un módulo en el servidor.
- **Archivos muy grandes por `blob:`:** limitados por memoria (ver §8).
- **Perfiles:** requieren un WebView reciente; ver el fallback en §6.

## 15. Ideas de evolución

- Custom Tabs para SSO.
- Geolocalización para Asistencias.
- Bloqueo biométrico al abrir la app (`androidx.biometric`).
- Puente FCM para notificaciones de Discuss.
- Configuración gestionada (MDM / *managed configurations*) para precargar servidores en flotas corporativas.
