# OdooClient — cliente Android (WebView) para Odoo 18

```
OdooClient/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/cl/turingforge/odooclient/
        │   ├── MainActivity.kt          WebView + cookies + subidas + descargas + Atrás
        │   ├── SetupActivity.kt         Pantalla de URL (valida contra /web/webclient/version_info)
        │   ├── ServerPickerActivity.kt  Selector multi-servidor (conectar / editar / eliminar)
        │   ├── data/ServerRepository.kt Lista de servidores (JSON en SharedPreferences, migra v1)
        │   ├── web/WebProfiles.kt       Perfil de WebView por servidor (sesiones aisladas)
        │   ├── util/ServerShortcuts.kt  Accesos directos dinámicos por empresa
        │   ├── util/OdooUrl.kt          Normalización de URL + probe de versión
        │   ├── util/DownloadSaver.kt    MediaStore / legado + parser de Content-Disposition
        │   └── web/DownloadBridge.kt    JS inyectado para descargas blob: de Odoo
        └── res/
            ├── layout/activity_main.xml, activity_setup.xml
            ├── values/strings.xml, themes.xml
            └── xml/network_security_config.xml, file_paths.xml
```

## Uso
1. Android Studio → New Project → *Empty Views Activity* (genera wrapper e íconos `mipmap/ic_launcher`).
2. Reemplaza los archivos por los de esta carpeta (package `cl.turingforge.odooclient`).
3. Ejecuta. Primera vez → SetupActivity; luego directo a Odoo.

## Notas
- **Reportes PDF**: Odoo 18 descarga vía XHR → `blob:`. Se resuelve con `DownloadBridge` (JS → base64 → MediaStore). Adjuntos GET (`/web/content`) van por `DownloadManager` con la cookie de sesión.
- **HTTP en LAN**: agrega el host en `network_security_config.xml`, o usa HTTPS.
- **Login con Google/Microsoft**: Google bloquea OAuth dentro de WebViews (`disallowed_useragent`). Usa usuario/contraseña de Odoo o abre el SSO en Custom Tabs.
- **Multi-servidor**: con 2+ servidores se muestra el selector al abrir (desactivable). También desde el diálogo de salida, la pantalla de error y los accesos directos del ícono.
- **Aislamiento de sesión**: perfiles de WebView (`MULTI_PROFILE`, WebView reciente). En WebViews antiguos se comparte el perfil por defecto: funciona si cada servidor tiene un host distinto (las cookies no se aíslan por puerto).
