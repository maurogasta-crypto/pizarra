# Pizarra — la app de Tiempos en el teléfono

**Desde `pizarra-2` (5-oct-2026) es la app de Tiempos en Android** —se llama
«Tiempos» en el teléfono; el widget sigue siendo «Pizarra»—. Lo decidió
Mauro: «que la Pizarra sea la app de Tiempos». Hace lo que el sitio no puede
con el teléfono bloqueado (**sonar a una hora**) y lo que el teléfono hace
mejor (**dictar**); lo demás sigue en el sitio.

Widget de Android con las tareas de **Tiempos** que elegiste, para verlas en la
pantalla de inicio y tacharlas con un toque. Tachar acá es lo mismo que «✔
Hecha» en Tiempos: lo ve todo el que comparte esa tarea.

| | |
|---|---|
| APK | release `ultimo` de este repositorio → `pizarra.apk` |
| Base | Firebase `tiempos-71d42`, la de Tiempos, por REST |
| Sello | `pizarra-7` (en cada archivo de `app/src/main/java/…`) |
| Pruebas | `./gradlew test` — 29 casos (11 de la pizarra, 11 de Tiempos, 7 que ABREN la app con Robolectric y tocan todos sus botones), sin red; + uno opcional contra una respuesta real |

## Cómo se usa

1. Instalar `pizarra.apk` y abrir **Pizarra**.
2. Entrar con la misma cuenta de Tiempos. La contraseña se usa una vez y no se
   guarda: queda el `refreshToken` de esa entrada.
3. Tildar las tareas que van a la pizarra (comunes y tus personales).
4. **Poner el widget** (o mantener apretado el inicio → Widgets → Pizarra).
5. En el widget: tocar una tarea la tacha; tocarla tachada la vuelve a
   pendiente. ⟳ actualiza, ✎ abre la app.

## Lo de Tiempos (pizarra-2)

- **🎙 Dictar.** Arriba de todo en la app, y como 🎙 en el widget. Usa el
  reconocedor de voz de Android —el mismo del micrófono del teclado; el del
  navegador no le anduvo a Mauro—. El texto queda en una caja para corregir,
  se puede dictar en dos tandas, y **✨ Seguir en Tiempos** abre el sitio con
  `?dictar=<texto>`: Tiempos lo precarga con su IA y muestra el plan para
  marcar (app-14). La app no llama a la IA: no tiene claves ni las necesita.
- **⏰ Alarmas y 🔔 recordatorios.** Lee MIS `alertas/` (reglas v9), las
  guarda y las programa con el AlarmManager. Un recordatorio es una
  notificación; una alarma es la sirena: suena con el sonido de alarma y se
  repite hasta que se la toca, y con el teléfono bloqueado abre una pantalla
  con «Apagar». Se pone al día al abrir la app, con el widget (media hora), con
  un trabajo de Android cada hora y al prender el teléfono. Lo que ya pasó no
  se programa; lo que se borró en Tiempos se desprograma. Necesita los
  permisos de **notificaciones** y de **alarmas exactas**; si faltan, la app
  lo dice y un toque lleva a Ajustes.
- **⭐ Hoy se puede.** Los deseos de los dos que caen hoy —lo semanal de este
  día y lo de esta fecha—, la misma cuenta que `posiblesDelDia` del sitio
  (deseos-1). Se editan en Tiempos.

No escribe nada nuevo en la base: alertas y deseos sólo se leen.

## Compartir desde otra app (pizarra-7)

«Compartir → Tiempos» aparece en Instagram, Facebook, TikTok, WhatsApp, el
navegador o la galería. Un link llega a la caja del dictado limpio de la
basura de seguimiento (`?igsh=`, `?utm_…`) y con su red; un texto, tal cual;
una imagen, como captura. Como Instagram no deja leer sus publicaciones desde
afuera, la app pide una **📷 Captura** del reel o del flyer. Al tocar **✨
Seguir en Tiempos** la captura se sube (achicada a 1600 px) a la cuenta de
Cloudinary del ecosistema, carpeta `tiempos`, y el sitio la recibe como
`&imagen=` —sólo si es de esa cuenta—. La app nunca abre el link.

## La firma: dejar de desinstalar (desde el teléfono, en Termux)

Hasta que estén los cuatro secretos, cada APK sale firmada con una clave de
depuración nueva y hay que desinstalar para instalar la siguiente (se pierde
sólo la sesión). La pizarra lleva **su propia clave**: la de la Hilux vive en
los secretos de `sitd-hilux`, que GitHub no deja leer de vuelta a nadie.

1. En Termux (`pkg install openjdk-17` si no está):

   ```bash
   keytool -genkeypair -v -keystore pizarra.jks -storetype JKS \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -alias pizarra -dname "CN=Tiempos, O=Tiempos, C=UY"
   ```

   Pide una contraseña dos veces: **la misma las dos**. Guardala ya (gestor de
   contraseñas, o «Guardar el valor» en `claves/` del panel).
2. Pasarla a texto, por archivo y nunca con `cat`:

   ```bash
   base64 -w0 pizarra.jks > pizarra.jks.b64
   wc -c < pizarra.jks.b64
   cp pizarra.jks.b64 /sdcard/Download/firma-pizarra.txt
   ```

   Anotá el número de `wc -c`. Abrí el archivo con el navegador o un editor,
   seleccionar todo, copiar.
3. GitHub → `maurogasta-crypto/pizarra` → Settings → Secrets and variables →
   Actions → New repository secret, cuatro veces: `FIRMA_JKS` (el chorro),
   `FIRMA_STORE_PASS` y `FIRMA_KEY_PASS` (la contraseña), `FIRMA_ALIAS`
   (`pizarra`). Después `rm /sdcard/Download/firma-pizarra.txt`.
4. **Guardá `pizarra.jks` fuera del teléfono** (Drive, el gestor): sin él no se
   puede firmar otra actualización. No va a un repositorio ni a un chat.
5. La próxima corrida sale firmada con la clave propia: una última
   desinstalación, y de ahí en adelante se instala encima.

## Si falla en el teléfono (pizarra-3)

pizarra-2 pasó el banco y compiló, y en el teléfono de Mauro se trababa. El
informe de fallos de HyperOS se va a Xiaomi, no a nosotros. Desde pizarra-3:

- **La app lleva una bitácora** (80 renglones: qué abrió, qué pidió, qué
  error). Si se **cierra** o se **traba** (la pantalla 5 s sin contestar),
  guarda qué estaba haciendo y la pila, y **la próxima vez que se abre lo
  manda a `reportes/` de Tiempos** como una falla, con la sesión de la
  persona —lo mismo que «Algo anda mal» del sitio—. No lleva lo dictado ni
  títulos: el paso, el error y el modelo de teléfono.
- **🩺 Mandar diagnóstico a Claude**, abajo en la app, manda la bitácora a mano.
- **El banco abre la app** (Robolectric, sólo en las pruebas) con y sin
  sesión, y toca cada botón. Robolectric baja su Android con Gradle y corre
  sin red (`copiarAndroidRobolectric`).
- **pizarra-3 se cerraba al abrir** en el teléfono: el trabajo de cada hora
  «con red» exige `ACCESS_NETWORK_STATE`, que faltaba. Robolectric no lo
  exige, así que el banco ahora LEE el manifiesto y falla si el código usa
  algo cuyo permiso no está (pizarra-4).
- **Al volver a la app se pone al día** (pizarra-5). Dictar abre el navegador
  y la alarma la guarda el sitio: una «para dentro de 2 minutos» no sonaba
  porque la app recién se enteraba en la próxima media hora.
- **La alarma suena hasta que se la apaga** (pizarra-6). La primera de verdad
  (5-oct, 00:56) «sonó y se cortó»: sonaba la notificación, y HyperOS corta
  ese sonido aunque lleve FLAG_INSISTENT. Ahora suena un servicio en primer
  plano (`Sirena.kt`) con el volumen de ALARMA, en bucle y vibrando, hasta
  «Apagar» o 3 minutos. Si el reproductor falla, el tono del sistema.
- **Las barras del sistema y el teclado**: desde Android 15 la app se dibuja
  debajo de ellas; ahora el contenido se corre para que nada quede tapado.

## Qué escribe en la base

| Acción | Campos de `tareas/{id}` | Igual que en Tiempos |
|---|---|---|
| Tachar | `hecho: true`, `hechoPor: <uid>`, `actualizadoEn` | «✔ Hecha» |
| Destachar | `hecho: false`, `actualizadoEn` | «Volver a pendiente» |
| Fijar / sacar | `pizarra.<uid>: true` / se borra | campo nuevo, por persona |

Ninguna regla nueva: la de `tareas` ya deja tocar una común a cualquiera de
los dos y una personal a su dueño. Todo con `currentDocument.exists`, para no
crear una tarea que alguien borró.

## Lo que no hace (todavía)

- **No se actualiza en vivo** lo que tacha el otro: llega al tocar ⟳, al tachar
  algo, al abrir la app o cada media hora.
- **No muestra las actividades de Casa Verde** ni la lista de compras: sólo las
  tareas de la familia (`tareas/`).

## Mapa

| Archivo | Qué es |
|---|---|
| `Logica.kt` | traducir Firestore ↔ pizarra; lo que se escribe. Sin Android ni red |
| `Nube.kt` | entrar, renovar el token, leer y escribir por REST |
| `PizarraWidget.kt` | el widget, la caché y las filas |
| `MainActivity.kt` | entrar, dictar, «hoy se puede», las próximas alarmas y elegir qué va a la pizarra |
| `Bitacora.kt` | la bitácora, el cierre y la trabada guardados, y la falla que se manda a Tiempos |
| `Imagenes.kt` | achicar y subir la captura a Cloudinary (preset sin firma, como el sitio) |
| `Sirena.kt` | la alarma sonando: servicio en primer plano, sonido de alarma en bucle, Apagar |
| `Alarmas.kt` | traer `alertas/`, programarlas, la notificación, la pantalla de «Apagar», el trabajo de cada hora |
| `.github/workflows/apk.yml` | banco + APK + release `ultimo` |
