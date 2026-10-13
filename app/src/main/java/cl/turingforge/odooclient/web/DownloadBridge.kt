package cl.turingforge.odooclient.web

/**
 * Por qué existe: Odoo 18 NO descarga los reportes PDF con un GET normal. Su utilidad
 * `download()` hace un POST por XHR a /report/download, recibe un Blob, crea una URL `blob:`
 * y simula un clic en un <a download="Factura.pdf">. El DownloadListener del WebView recibe
 * ese `blob:` pero DownloadManager no puede leerlo (vive solo en memoria del renderer) y
 * Odoo revoca la URL a los pocos ms.
 *
 * Solución: este script se inyecta al inicio de cada documento de Odoo, intercepta los clics
 * en enlaces blob:/data:, lee el Blob con FileReader y lo envía a Kotlin como data-URL base64
 * a través del objeto `OdooAndroid` (addWebMessageListener o, en WebViews antiguos,
 * addJavascriptInterface; ambos exponen `postMessage(string)`).
 *
 * Ojo: el contenido viaja en memoria; adecuado para reportes (KB–decenas de MB).
 * No usar `$` en el JS: este es un raw string de Kotlin.
 */
object DownloadBridge {

    const val JS_OBJECT_NAME = "OdooAndroid"

    val SCRIPT = """
        (function () {
          if (window.__odooAndroidBridge) return;
          window.__odooAndroidBridge = true;

          function post(obj) {
            try { OdooAndroid.postMessage(JSON.stringify(obj)); }
            catch (e) { console.error('OdooAndroid bridge no disponible', e); }
          }

          function save(href, name) {
            fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
              var reader = new FileReader();
              reader.onloadend = function () {
                post({ type: 'download', data: reader.result, name: name || 'descarga',
                       mime: blob.type || 'application/octet-stream' });
              };
              reader.readAsDataURL(blob);
            }).catch(function (e) { post({ type: 'error', message: String(e) }); });
          }
          window.__odooSaveBlob = save;

          function isBlobLink(a) {
            return !!(a && a.href && (a.href.indexOf('blob:') === 0 || a.href.indexOf('data:') === 0));
          }

          // Clic programático: a.click() (lo que hace download.js de Odoo).
          var nativeClick = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function () {
            if (isBlobLink(this)) { save(this.href, this.getAttribute('download')); return; }
            return nativeClick.apply(this, arguments);
          };

          // Clic real del usuario o dispatchEvent sobre un <a href="blob:...">.
          document.addEventListener('click', function (ev) {
            var a = ev.target && ev.target.closest ? ev.target.closest('a') : null;
            if (isBlobLink(a)) {
              ev.preventDefault();
              ev.stopImmediatePropagation();
              save(a.href, a.getAttribute('download'));
            }
          }, true);
        })();
    """.trimIndent()
}
