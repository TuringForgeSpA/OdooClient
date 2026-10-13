# Puente JS legado (dispositivos sin WEB_MESSAGE_LISTENER): R8 no debe renombrar los métodos expuestos.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
