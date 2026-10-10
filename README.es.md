<p align="center"><img src="docs/icon.png" width="96" alt=""></p>

<h1 align="center">VitaIRC for Android</h1>

<p align="center">
El cliente IRC de <a href="https://github.com/ArchelKrozt/VitaIRC">VitaIRC para PS Vita</a>, rediseñado para pantallas táctiles.<br>
Varios servidores, bouncers con historial, traducción, subida de imágenes, vista previa de enlaces y buscador de imágenes.
</p>

<p align="center">También para <a href="https://github.com/ArchelKrozt/VitaIRC-iOS">iPhone y iPad</a>.</p>

<p align="center"><a href="README.md">English</a> · <b>Español</b></p>

<p align="center">
<img src="screenshots/02_previews.png" width="240" alt="Privado con vista previa de enlaces">
<img src="screenshots/03_translation.png" width="240" alt="Mensajes traducidos bajo el original">
<img src="screenshots/06_channel_search.png" width="240" alt="Buscador de canales">
</p>

## Descarga

Baja **`VitaIRC.apk`** de la [última versión](../../releases/latest), ábrelo en el teléfono y permite
instalar apps desde tu navegador o gestor de archivos cuando Android lo pida. Android 8.0 o superior.

Las actualizaciones se instalan encima y conservan tus servidores, ajustes e historial. La app busca
versiones nuevas una vez al día (Ajustes → *Buscar actualizaciones*).

## Funciones

**IRC**
- Varios servidores a la vez, SSL/TLS, SASL PLAIN, NickServ o contraseña del servidor, canales al
  conectar, recuperación del nick.
- Se reconecta solo (y comprueba la conexión cuando vuelve la red); protección contra flood en privados.
- **Sigue conectado en segundo plano**, con notificaciones de privados y menciones. Al tocar una se
  abre esa conversación.
- Lo que escribes sin conexión se ve atenuado y se envía al reconectar.
- Comandos: `/join /part /msg /query /me /nick /topic /whois /list /notice /away /back /ignore
  /op /deop /voice /devoice /kick /ban /kickban /quote /connect /disconnect /clear /help`.

**Bouncers (soju, ZNC)**
- IRCv3 `server-time`, `batch` y `chathistory`: lo que te perdiste llega con su hora real, también los
  privados; los mensajes anteriores se cargan al subir hasta arriba.
- Reacciones (`+draft/react`) y subidas con `FILEHOST` de soju.

**Traducción**
- Google (gratis, sin cuenta) o ChatGPT con tu API key de OpenAI.
- Mensajes recibidos, los tuyos antes de enviarlos o uno solo; un idioma por canal.

**Imágenes y enlaces**
- Sube desde la galería o la cámara a Litterbox (sin cuenta), ImgBB, Imgur o tu servidor soju; el
  enlace queda en el mensaje. Tus subidas recientes guardan su enlace para borrarlas.
- Vista previa de enlaces: miniaturas, tarjetas de páginas y YouTube, reproductor de video integrado.
- Visor de imágenes con zoom de dos dedos y doble toque.
- Buscador de imágenes en Safebooru, Danbooru y Sankaku, con favoritos, *enviar al chat* y *guardar en
  la galería*. Solo contenido general salvo que actives el contenido adulto en Ajustes (18+).

**Pensado para pantalla táctil**
- Lista de ventanas en un panel lateral (fija a la izquierda en tablets y en horizontal).
- Toca un mensaje para responder, reaccionar, traducirlo, abrir un privado, ignorar o copiar.
- Toca un nick en la lista de usuarios para sus opciones; los operadores tienen op, voz, kick y ban.
- Escribe `@` y el comienzo de un nick para completarlo; respuestas rápidas con nicks y mensajes recientes.
- Explorador de canales con búsqueda en todo el servidor, búsqueda dentro del canal, ir a la última
  mención, silenciar una ventana, palabras que te avisan, colores mIRC, español e inglés.

## Si vienes de la Vita

El archivo de ajustes es el mismo: copia `ux0:data/VitaIRC/config.ini` y funciona aquí también
(servidores, traducción e imágenes). Los historiales usan el mismo formato.

## Compilar

Hace falta JDK 17 y el SDK de Android (plataforma 34).

```sh
./build.sh                       # dist/VitaIRC.apk
./build.sh testReleaseUnitTest   # pruebas del núcleo IRC
python3 tools/gen_strings.py     # tras agregar textos: tabla de inglés -> L.kt
```

La firma de las versiones y la estructura del código están en el [README en inglés](README.md#signing).

## Licencia

[MIT](LICENSE).
