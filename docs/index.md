# Política de privacidad de TF Odoo

*Última actualización: 16 de octubre de 2026*

**Turing Forge SpA** ("nosotros", "nuestro") ha desarrollado la aplicación móvil **TF Odoo** ("la Aplicación") como un cliente ligero (*WebView wrapper*) para permitir a los usuarios conectarse a sus propias instancias y servidores de Odoo 18.

### 1. Información que recopilamos

**TF Odoo no recopila, almacena, procesa ni comparte ningún tipo de información personal, credenciales de acceso ni datos de navegación en servidores externos de Turing Forge SpA.**

La Aplicación funciona exclusivamente como un contenedor seguro que conecta directamente tu dispositivo móvil con la dirección URL del servidor Odoo especificado por el usuario. Toda la información transmitida (nombres de usuario, contraseñas, registros empresariales y documentos) se gestiona de forma directa y bidireccional entre el dispositivo y el servidor de destino configurado.

### 2. Uso de cookies y almacenamiento local

La Aplicación utiliza el componente nativo `WebView` del sistema operativo Android para renderizar la interfaz web de Odoo. Este componente almacena datos locales en el dispositivo exclusivamente para el funcionamiento de la sesión:

* **Cookies de sesión:** Requeridas para mantener la autenticación del usuario activa dentro del servidor Odoo.
* **Caché y almacenamiento DOM:** Datos temporales utilizados para optimizar la velocidad de carga y la fluidez de la interfaz web.

Estos datos permanecen bajo el control exclusivo del usuario en la memoria local del dispositivo y pueden borrarse en cualquier momento desde los ajustes de aplicaciones de Android.

### 3. Permisos del dispositivo

Para habilitar las funciones operativas de Odoo 18, la Aplicación puede solicitar los siguientes permisos:

* **Acceso a red (`INTERNET` / `ACCESS_NETWORK_STATE`):** Necesario para establecer la comunicación con el servidor Odoo.
* **Almacenamiento y gestión de archivos (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`):** Utilizado únicamente cuando el usuario descarga reportes en formato PDF o adjunta archivos e imágenes a los registros de Odoo.
* **Cámara (`CAMERA`):** Requerido únicamente si el usuario decide capturar fotografías directamente desde la interfaz para adjuntarlas como evidencia en Odoo.

Ninguno de estos permisos se utiliza para rastrear al usuario ni para recopilar información en segundo plano.

### 4. Servicios de terceros y publicidad

TF Odoo **no incluye publicidad de terceros ni integra herramientas de seguimiento, analítica comercial ni bibliotecas de rastreo publicitario**.

### 5. Seguridad

La seguridad de la transmisión de datos depende del servidor Odoo al que se conecte el usuario. Se recomienda configurar y utilizar exclusivamente conexiones cifradas mediante el protocolo HTTPS (`https://`).

### 6. Contacto

Si tienes dudas o consultas sobre esta Política de privacidad, puedes contactarnos a través de:

* **Entidad:** Turing Forge SpA
* **Sitio web:** [https://turingforge.cl](https://turingforge.cl)
* **Correo electrónico:** contacto@turingforge.cl
