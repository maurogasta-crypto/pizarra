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
| Base | Firebase `tiempos-71d42`, la de Tiempos, por REST; desde pizarra-14 también la de Casa Verde, CasaYourte o remate, para los avisos de quien no es de Tiempos |
| Sello | `pizarra-71` (en cada archivo de `app/src/main/java/…`) |
| Pruebas | `./gradlew test` — 56 casos (11 de la pizarra, 13 de Tiempos, 9 de la bodega, 10 que ABREN la app con Robolectric y tocan todos sus botones), sin red; + uno opcional contra una respuesta real |

## Cómo se usa

1. Instalar `pizarra.apk` y abrir **Pizarra**.
2. Entrar con la misma cuenta de Tiempos. La contraseña se usa una vez y no se
   guarda: queda el `refreshToken` de esa entrada. Si no te acordás de la
   contraseña: escribí el mail y tocá **«Olvidé la contraseña»** (pizarra-17):
   Firebase manda el mail para elegir una nueva, que vale también en la web.
   Y el ojo 👁 al lado de la contraseña la muestra mientras la escribís
   (pizarra-18).
3. Abrir **📝 La pizarra** (viene plegada desde pizarra-15: un toque la abre) y
   tildar las tareas que van a la pizarra (comunes y tus personales). Están
   agrupadas por categoría —Producción, Mantenimiento, Chicos, Casa y comida,
   Personal—, la misma de Tiempos: la de la tarea de más arriba.
4. **Poner el widget** (o mantener apretado el inicio → Widgets → Pizarra).
5. En el widget: tocar el **☐** tacha la tarea; tocarlo tachada la vuelve a
   pendiente. Tocar el **texto** abre la tarea en Tiempos, con su ficha
   (pizarra-23; `?solapa=tareas&abrir=`, app-34). ⟳ actualiza, ✎ abre la app.
5 bis. **Las luces de remate** (pizarra-24): arriba del widget de la pizarra hay
   dos botoncitos, **Entrada** y **Depósito**. Cada toque la prende si está
   apagada y la apaga si está prendida, y se pinta 💡 amarillo cuando está
   prendida. **Desde pizarra-25 el botón cambia en el acto** y sale una sola
   orden (no se pregunta antes cómo estaba: se usa lo último que se supo); a
   los segundos se verifica con el aparato y, si alguien la movió por otro
   lado, el botón se corrige solo. **Desde pizarra-26, de 8 a 23 queda tibia**:
   cada 10 minutos la app renueva la sesión de remate si está por vencer y le
   hace al puente un pedido sin sesión para que no se duerma (no toca Tuya ni
   mueve luces); fuera de ese horario no hace nada. Hace falta entrar una vez con tu
   cuenta de remate en los ajustes de la app (⚙ → «💡 Luces de remate»); tu
   cuenta tiene que tener el permiso de luces. **La contraseña de remate puede
   no ser la de Tiempos**: si no la recordás, «Olvidé la contraseña de remate»
   manda el mail (pizarra-28). Lo que pasa al entrar se lee ahí mismo, debajo
   de los botones. Cada toque queda en el registro
   de luces de remate, a tu nombre.
5 quinquies. **El ✎ y el título del widget abren la solapa Pizarra de Tiempos**
   (pizarra-33). Antes abrían Tiempos sin decir dónde y aparecía la última solapa
   que había quedado, casi siempre la Agenda.
5 quater. **El $ del widget y la foto de factura** (pizarra-32): **$** abre el
   gasto de Tiempos con la foto primero (la IA lee la factura y llena todo). En
   la app, «📷 Foto de factura» —o una imagen que llega por Compartir— se sube y
   abre ese mismo gasto. Lo que se escribe en la caja es una **pregunta**: la IA
   de Tiempos ya no agrega ni cambia nada, sólo contesta.
5 ter. **El widget de la pizarra, más alto** (pizarra-31): la lista ocupa todo el
   alto (entran ocho renglones o más) y los botones van en una columna a la
   derecha: **E** y **D** para las luces de la entrada y el depósito
   (ámbar = prendida), ＋, ✎ y ⟳. Si el widget ya estaba puesto, agrandalo
   un renglón manteniéndolo apretado.
5 bis. **El ＋ del widget de la pizarra** (pizarra-30): agrega una tarea sin abrir
   la app. Se escribe o se dicta con el micrófono del teclado, «Agregar», y
   queda en Tiempos —personal, tuya— y en tu pizarra; la ventanita queda
   abierta para agregar otra, y «Listo» la cierra.
6. **El segundo widget, «Mi agenda»** (pizarra-20, 12-oct-2026): Widgets →
   Tiempos → «Mi agenda». Los siete días que vienen, cada uno con su
   encabezado («Hoy · lunes 12/10», «… · libre» si no hay nada), y en cada día
   lo de la agenda de Tiempos por hora: tus actividades, tus tareas agendadas
   (una sin día flota en hoy), las actividades de los chicos y, desde
   pizarra-21, tus **alarmas ⏰ y recordatorios 🔔** a su hora, con la barrita
   del color de su clase. Es de **lectura**: tocar un renglón abre Tiempos en
   la Agenda con **esa pieza abierta** para editarla (`?abrir=`, agenda-16 /
   pizarra-22), y tocar una alarma abre la pieza a la que pertenece; una
   alarma suelta o el encabezado de un día llevan a ese día.
   **Desde pizarra-29** la alarma de una pieza va DENTRO de su renglón
   («⏰ 15:30 · Salir para…», con el día si suena otro), no aparte; la pieza
   muestra su nota (📝); y el horario va en dos líneas para que entre.
   Lo de Casa Verde no aparece: vive en la base de Casa Verde y el widget entra
   sólo a la de Tiempos. Se pone al día con ⟳, al salir de Tiempos, cada media
   hora y con el trabajo de cada hora.

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

**Desde pizarra-32 (15-oct-2026) es «❓ Preguntale a la IA»: sólo pregunta**
(sugerir-22 de Tiempos); lo de abajo es historia.

**Desde pizarra-15 (10-oct-2026) el cuadro de arriba es «✨ Pedile a la IA»**
y no «Para la agenda»: lo que se escribe ahí puede ser cualquier cosa que
Tiempos registra —un gasto, algo de los chicos, mover o sacar algo de la
agenda, una alarma, un deseo, una compra—. Lo arma Tiempos (sugerir-16); la
app sigue sin llamar a la IA.

## Tiempos ADENTRO de la app (pizarra-12, `tiempos:V7`)

Mauro, 7-oct-2026: «no es práctico dos aplicaciones para hacer lo mismo». El
ícono abre **Tiempos dentro de la app** (`TiemposActivity`, una WebView): la
misma pantalla del sitio —Pizarra, Tareas, Hoy, Agenda con Mes, Chicos,
Plata, Balance—, sin navegador y sin copiar su lógica. Lo nativo sigue: el
widget, las alarmas, la lectura de Airbnb y Compartir.

- Adentro se abre **sólo** el sitio de Tiempos (`esDeTiempos`); cualquier
  otro enlace va al navegador. Los archivos (flyer, boleta) se eligen con el
  selector del sistema.
- **⚙** abajo a la izquierda: los ajustes de la app (`MainActivity`): la
  cuenta del widget, la bodega, el diagnóstico. Compartir sigue entrando ahí.
- **Sin botón de dictado**: se dicta con el 🎤 del teclado. El ✎ del widget
  abre Tiempos en Pizarra: ahí se anota y se elige qué va (pizarra-19: había
  un ✏️ al lado que hacía lo mismo, y se fue).
- La sesión del sitio adentro es aparte de la del widget: la primera vez se
  entra en las dos.
- **Ubicación aproximada** (pizarra-13): el sitio la pide al armar un plan
  para saber si estás en Uruguay o en Brasil (de qué casa sale la alarma de
  salir). La app la concede sólo al sitio de Tiempos, después de preguntarte;
  no pide la precisa ni la guarda.

## 🤖 Claude propone (pizarra-11, `tiempos:V3`)

Lo que Claude te propone para tu agenda o te pregunta, pendiente, con el mismo
filtro que `mias()` de `propone.js` de Tiempos. La app lo MUESTRA; «Ver y
aceptar en Tiempos» abre el sitio, donde están las tarjetas con Aceptar y
Corregir. Aceptar escribe la agenda con la forma de `actividadDePropuesta`, y
copiar esa lógica acá sería un dato en dos lugares.

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

## Los mensajes de Airbnb, a Claude (pizarra-9)

Lo que hacía Termux (`telefono.mjs airbnb --vigilar` de `datos`) y en el
Xiaomi de Mauro dejó de poder: Termux:API está hecha para Android 9, no pide
nada, y en Android 16 / HyperOS 3 su lector nunca se conectó
(`termux-notification-list` → NullPointerException, 6-oct-2026).

- **`LectorAirbnb`** es un lector de notificaciones de Android. Toma SÓLO las
  de `com.airbnb.android`, descarta los resúmenes, y sube cada mensaje nuevo a
  `mensajes/<fecha UTC>.json` de la bodega privada con **el mismo formato y la
  misma huella** que Termux: la ronda, los borradores y las reservas no
  cambiaron una línea. El banco compara la huella contra la de Node.
- **El latido** (`latido.json`) va cada 6 horas (12 hasta pizarra-38) desde el
  trabajo de cada hora, con `falla` si Android no le dio el acceso. La ronda lo
  controla con `datos/herramientas/latido.mjs`.
- **En la app, «📬 Mensajes de Airbnb → Claude»**: si puede leer, si está el
  token, el último envío y el último problema; «Dar acceso a notificaciones»
  lleva al renglón exacto de la Pizarra; «Subir ahora» la reconecta y late.
- **Una vez, en el teléfono:** Ajustes → Aplicaciones → Pizarra → ⋮ →
  «Permitir ajustes restringidos» (Android 13+ lo exige a toda app que no
  viene de Play Store), y después el botón «Dar acceso a notificaciones».
- **El token** abre la bodega y ningún otro repositorio (el mismo de Termux:
  `cat ~/.config/bodega/token`). Se pega una vez, no se muestra, y queda fuera
  de las copias de seguridad.
- **El texto de un mensaje es de un huésped**: la bitácora dice cuántos y si
  subió, nunca qué decía, y el banco lo comprueba leyendo el código.
- **En Termux, la lectura de Airbnb se apaga** para que no escriban dos el
  mismo latido: `rm ~/.termux/boot/bodega-vigilar.sh; pkill -f vigilar`.

## Los pedidos de alquiler de Facebook, y el Redmi 13C (pizarra-34)

18-oct-2026, Mauro: el **Redmi 13C** pasa a ser el teléfono de captura del
ecosistema. La Pizarra de ese teléfono lee Airbnb (como antes el Redmi 15) y
además los **pedidos de alquiler en Canasvieiras** de los grupos de Facebook.

- **Qué entra:** una notificación de Facebook (`com.facebook.katana`, `lite`
  o `groups`) cuyo título, texto o líneas hablen de alquilar —alquiler,
  alugar, aluguel, aluga…— **y** nombren Canasvieiras o «Canas». Las dos
  cosas: lo demás de Facebook no se mira ni se sube (`esPedidoDeAlquiler`,
  con su banco).
- **Adónde va:** a la misma `mensajes/<fecha>.json` de la bodega, con
  `app: "facebook"` y el grupo en `chat`. La ronda y las novedades lo traen
  por el camino de siempre.
- **El enlace:** Facebook no lo pone en la notificación. La Pizarra reusa la
  acción de abrirla en un aviso propio («🏠 Pedido de alquiler en
  Canasvieiras»): tocarlo abre ESA publicación en Facebook, aunque el aviso de
  Facebook ya se haya borrado. Hasta que se reinicie el teléfono.
- **Responder lo hace Mauro**, en Facebook. Una app que contesta sola es lo
  que hace que Facebook bloquee una cuenta.
- **Y va directo a la lista «Pedidos de alquiler» de Casa Verde** (pizarra-36,
  `CasaVerde.kt`), con la cuenta de Casa Verde de ese teléfono (se entra una
  vez, en «🏠 Pedidos de alquiler → Casa Verde»). Ya no despierta al chat con
  cada pedido: la ronda prepara las respuestas, y corre más veces por día en
  temporada alta. Lo de abajo (pizarra-35) quedó como historia.
- **El lector se destraba solo** (pizarra-41). En el Redmi 13C Android dio el
  permiso de leer notificaciones y no conectó nunca el lector, ni
  reiniciando. «Subir ahora» —y, solo, el trabajo de cada hora si pasaron 6 h
  sin escuchar nada— apaga y prende el componente del lector, que obliga al
  sistema a volver a atarlo. La pantalla dice si escucha y cuándo vio la última.
- **Mensajes y novedades** (pizarra-40, `Mensajes.kt`): el tercer widget. Junta
  por fecha los MENSAJES entre Mauro y Florencia (`mensajes/` de Tiempos,
  reglas v15; cada uno dice desde qué teléfono salió, con el nombre que el
  teléfono tiene en Ajustes), los avisos de Claude y, si este teléfono entró a
  Casa Verde, cuántos pedidos de alquiler y cambios de reservas esperan. ✎ abre
  la charla para leer y escribir. Se pone al día cada 10 minutos de 8 a 23,
  con el trabajo de cada hora y con ⟳; un mensaje del otro, o tuyo desde otro
  teléfono, avisa con una notificación.
- **El latido prueba que el lector ESCUCHA** (pizarra-39): cada 6 horas, y
  al instante con «Subir ahora». Lleva `escucha`: cuándo Android conectó el
  lector, la hora de la última notificación de cualquier app (sólo la hora) y
  cuántas vio hoy. Tener el permiso no alcanza: un lector desconectado se ve
  igual que un día sin mensajes. La ronda lo controla con
  `datos/herramientas/latido.mjs`.
- **El latido dice de qué teléfono viene** (pizarra-38): marca y modelo, para
  saber cuál falla si hay dos. Y «🩺 Mandar diagnóstico» pone arriba el estado
  (si lee, el token, el último envío, Casa Verde) y abajo lo MÁS NUEVO de la
  bitácora: hasta pizarra-37 el tope de 2000 cortaba justo eso.
- **Responder con un toque** (pizarra-37, `Responder.kt`). Cada hora la
  Pizarra trae de Casa Verde los pedidos sin atender. Cuando uno ya tiene la
  respuesta de la ronda, su aviso pasa a «✍️ Respuesta lista», con la
  respuesta a la vista. **«Copiar y abrir»** copia la respuesta, abre ESA
  publicación en Facebook y la marca respondida en la lista (si después no la
  mandaste, en la lista se vuelve a «sin atender»). Queda tocar «Comentar»,
  mantener apretado, Pegar y Publicar. «Descartar» la saca de la lista. Lo
  que alguien atendió desde la lista se va solo de la barra. **Hasta
  pizarra-57**: desde pizarra-58 ya no hay aviso «Respuesta lista» (gastaba
  una notificación por pedido); la respuesta la copia la Pizarra al tocar
  «Comentar», y copiar ya no marca respondido.
- **Cada 10 minutos, todo al día** (pizarra-59, `Despertar.kt`): de 8 a 23 la
  Pizarra trae mensajes, tareas, la agenda, los avisos y las alarmas (antes,
  sólo los mensajes; lo demás, una vez por hora). Las luces no, por el cupo de
  Tuya. Es el mismo lugar que usa el aviso instantáneo.
- **Mensajes como chat, por teléfono** (pizarra-61, `Mensajes.kt`): el widget
  tiene pestañas, «Todo» y las conversaciones más recientes, y cada
  conversación se ve en burbujas; la charla (✎) tiene una pestaña por
  teléfono y «Todos». Escribir en una pestaña manda el mensaje a ESE teléfono
  (`para`, reglas v17 de Tiempos: hay que publicarlas).
- **Fotos y documentos** (pizarra-64, `Archivos.kt`): 📎 en la charla. La
  foto se achica antes de salir; un documento va en partes, hasta unos 6 MB.
  Se guardan 7 días en `archivos/` de Tiempos (reglas v20) y se abren al
  tocarlos con la app que tenga el teléfono.
- **Llamadas** (pizarra-63, `Llamada.kt`): 📞 en la charla o en el widget,
  con una conversación elegida. Suena en el otro teléfono aunque esté
  bloqueado; se atiende, se corta, altavoz y silenciar. La voz va directo
  (WebRTC) y nada se graba. Reglas v19 de Tiempos y la función `hielo` del
  Netlify (nueve funciones en el zip). **Desde pizarra-68 suena como el
  teléfono**: tono de llamada y vibración, repitiendo hasta atender, rechazar
  o que el otro corte (máximo 45 s), con «📞 Atender» y «Rechazar» en el
  aviso. En Android 14 o más, para que aparezca en pantalla completa hay que
  permitir «Pantalla completa»: la charla lo ofrece arriba si falta. En
  modo vibrador sólo vibra; en silencio, no suena.
- **El handy** (pizarra-62, `Handy.kt`): 🎙 en el widget abre grabando;
  en la charla se mantiene apretado. El audio llega al otro teléfono en
  segundos y se escucha solo si así lo eligió y no está en silencio. Los
  audios viven en `audios/` de Tiempos (reglas v18) y cada Pizarra borra los
  suyos a los 30 días. La otra persona es UNA pestaña, con todos sus
  teléfonos.
- **Y en el acto, cuando alguien guarda algo** (pizarra-60, `Instantaneo.kt`,
  `general:C1` fase 2). Mandar un mensaje, tachar, agregar o fijar una tarea
  llama a `despertar` del Netlify de Casa Verde, que verifica la sesión y la
  ficha y manda por Firebase Cloud Messaging (proyecto `avisos-23b6a`, aparte)
  un aviso SIN contenido al tema `pizarra-<base>`. Cada Pizarra suscripta lo
  recibe por la conexión que Google ya tiene abierta (no gasta batería de más)
  y llama a `Despertar.todo` con su propia sesión. Si el aviso no llega, la
  pasada de 10 minutos lo trae igual. Necesita `FCM_CUENTA` en Netlify.
- **Cada toque lleva a algún lado** (pizarra-58, `Abrir.kt`, Mauro: «la forma
  en la que direccionan al hacer clic, algunas no hacen nada claro»). Un aviso
  de Claude —en la barra, en la lista de la app o en el widget— abre lo que
  dice (`destinoDeAviso`: el enlace del texto; si no, la llegada en Reservas,
  la agenda de Tiempos o el panel del sitio) y queda leído. El renglón de
  Casa Verde del widget abre Reservas si hay cambios para confirmar y si no
  Pedidos de alquiler; un mensaje, la charla. Antes todo abría la pantalla de
  ajustes, y un aviso nuevo pisaba lo que abría el anterior.
- **Despertaba al chat en el acto** (pizarra-35, hasta pizarra-36). Después de subirlo, la
  Pizarra lo deja como un pedido en `reportes/` de Tiempos (`pagina:
  "facebook-pedido"`, urgencia alta, con el texto: la sesión que despierta no
  tiene la bodega) y llama a `avisar-claude`, como el globo del sitio, con la
  base y el id y el origen de Tiempos. La consulta en vivo arma la respuesta y
  la deja en el **buzón de Mauro**: llega a la Pizarra como aviso de Claude.
  Hace falta que la Pizarra del 13C esté con la cuenta de **Tiempos** de Mauro;
  con otra cuenta, el pedido sube igual pero no despierta a nadie, y la
  bitácora lo dice. La regla de `reportes/` deja leerlo sólo a su dueño y al
  agente: Florencia no lo ve.
- **Para que llegue algo**, en Facebook, en cada grupo: ⋯ → Notificaciones →
  **Todas las publicaciones**. Si no, Facebook no avisa y no hay qué leer.
- **Un solo teléfono lee.** En el Redmi 15 se le saca a la Pizarra el acceso a
  las notificaciones; si leen los dos, escriben dos veces el mismo latido.
- El nombre del lector sigue siendo `LectorAirbnb`: el permiso que dio Android
  va atado a ese nombre, y cambiarlo obligaría a darlo de nuevo.

## TODAS las notificaciones del 13C, para que Claude las clasifique (pizarra-42)

19-oct-2026, Mauro: «que lea todo… y que la ronda lo clasifique y descarte en
caso que no cuadre a ninguna de las aplicaciones o agendas, pizarra, etc».

- **Qué entra:** cada notificación con algo escrito, de cualquier app —WhatsApp,
  bancos, Gmail, lo que sea—, menos las fijas (música, descargas), la tapa de
  un grupo y las de la propia Pizarra (`Notificaciones.leer`, `notifDe`).
- **Lo que no sale nunca: un código de verificación.** En una notificación que
  habla de códigos («código», «code», «senha», «PIN»…), un número de 4 a 8
  cifras se tapa ACÁ, antes de subir: «Tu código es 48••••»
  (`taparCodigos`, con su banco, que también prueba que un monto, una hora o
  un modelo de teléfono no se tocan). **Y un enlace de verificación tampoco**
  (pizarra-43, `taparEnlaces`): en cualquier notificación, el valor de un
  parámetro con nombre de llave (`code`, `token`, `oobCode`…) y todo tramo
  largo de letras y cifras dentro de una dirección se tapan. Lo encontró la
  primera ronda que clasificó: un enlace `whois_verify.html?code=…` llegó
  entero, y un «482913.» con su punto final también habría pasado.
- **Adónde va:** a `notificaciones/` de la base de **Casa Verde**, con la misma
  cuenta de Casa Verde de los pedidos de alquiler. NO a la bodega: es git y lo
  que entra ahí no se olvida. Nadie del equipo la lee desde un sitio; la lee
  Claude en la ronda, usa lo que sirve y borra lo demás. Vence a los 7 días.
- **Si no hay señal:** queda en una cola (hasta 400) y se reintenta cada hora.
- **La pantalla** dice cuántas subieron hoy y si hay alguna esperando, en
  «🏠 Pedidos de alquiler → Casa Verde» y en el diagnóstico.
- **Hace falta publicar `interno/firestore.rules` de Casa Verde** (entra el
  bloque de `notificaciones`): hasta entonces la base dice que no y quedan en
  la cola.

## Facebook por la pantalla, mientras alguien lo mira (pizarra-44)

20-oct-2026, Mauro: «no funcionan las notificaciones push de facebook… buscá
el máximo de información con el mínimo de intervención». Facebook no avisa
cada publicación de un grupo, y la API de grupos la cerró Meta en abril de
2024: lo que queda es leer lo que la app de Facebook ya armó para quien la
mira.

- **Qué hace:** `LectorPantalla` (`Pantalla.kt`) es un servicio de
  accesibilidad. Cada vez que Facebook se desliza o cambia, recorre el árbol
  entero de la ventana —también lo que quedó armado fuera de la vista— y saca
  las publicaciones (`publicacionesDe`: los textos largos, sin repetir la
  tarjeta y su párrafo).
- **Adónde va:** lo que habla de alquileres, temporada o la zona (o se publicó
  en un grupo con eso en el nombre) va a `notificaciones/` de Casa Verde como
  `<paquete>·pantalla`, para que la ronda lo clasifique; un pedido de
  alquiler en Canasvieiras va además a «Pedidos de alquiler». Con los códigos
  y los enlaces tapados. El resto del inicio de Facebook no sale del teléfono.
- **Lo que no hace nunca:** tocar, deslizar, abrir «Ver más» ni escribir. Es
  lo que bloquea una cuenta, y el banco falla si aparece una acción en el
  archivo. Hay que recorrer el grupo a mano: un minuto por grupo alcanza,
  rápido, porque lee lo que quedó armado arriba y abajo.
- **Sólo dentro de Facebook:** `res/xml/lector_pantalla.xml` lo ata a las
  tres apps de Facebook; Android no le pasa nada de ninguna otra.
- **Cómo se enciende:** Pizarra → Casa Verde → «Encender el lector de
  Facebook» → Accesibilidad → Apps descargadas → «Pizarra · lector de
  Facebook». Si Android dice «Ajuste restringido» (pasa con las apps que no
  vienen de Play Store): Ajustes → Apps → Pizarra → ⋮ → «Permitir ajustes
  restringidos», y de nuevo.
- **Cómo se sabe que anda:** la misma sección dice cuántas publicaciones vio
  hoy, cuántas mandó y cuántos pedidos. La bitácora, sólo los números.

### Qué busca, y la respuesta en el acto (pizarra-46)

- **Qué busca lo elige Mauro** en Casa Verde → Pedidos de alquiler → «🔎 Qué
  busco ahora»: por días, por mes o anual; hasta cuántas personas (1 cabaña ≈
  7, 2 ≈ 12, las 3 ≈ 20); familias, parejas, jóvenes o grupos; Canasvieiras,
  el norte de la isla o toda Floripa. La Pizarra lo trae al encender el
  lector y cada hora (`Busqueda.kt`).
- **Sólo se guarda lo que elegís responder** (pizarra-48): recorrer no guarda
  nada. Al tocar «Comentar» (o el cuadro de texto) de una publicación, ésa va a
  «Pedidos de alquiler» con una respuesta rápida que siempre sugiere
  casaverdecanas.com.br, y la respuesta queda copiada: el teclado la ofrece
  arriba y se pega con un toque. Un aviso silencioso dice de quién es. Sin
  avisos flotantes.
- **La respuesta coincide con la ocupación de Casa Verde** (pizarra-49): la
  Pizarra trae la ocupación una vez por sesión de Facebook. Si el pedido dice
  fechas y hay lugar, la respuesta las nombra («Tenemos lugar del 16 de marzo
  al 1 de abril…», o «3 cabañas juntas para los 18»). Si están ocupadas
  (pizarra-51), la respuesta dice lo que SÍ hay: las mismas noches unos días
  antes o después, la parte libre de lo pedido, o para cuántas personas hay
  lugar.
- **Al abrir el comentario sale un aviso** —el único: mientras se recorre no
  hay ninguno— que dice «✅ Hay lugar…» o «⛔ Sin lugar… · copié lo que sí
  hay», con la respuesta copiada a la vista.
- **Lo que ya se comentó se advierte** (pizarra-55): si abajo ya hay una
  respuesta nuestra (con casaverdecanas.com.br), el aviso dice «⚠️ YA
  COMENTADA», no copia nada y la marca respondida en Casa Verde; si sólo la
  elegiste antes en este teléfono, dice el día.

## 📡 El enlace entre Pizarras: cerca sin datos, y lejos (pizarra-70 y 71, `general:C4`)

Dos Pizarras cercanas se avisan **sin internet**: en la casa por el mismo
wi-fi, en el auto por Bluetooth. Mauro, 26-oct-2026: «que se pueda utilizar
sin consumir datos… dentro del mismo auto o dentro de la misma casa, y que
pueda despertar al teléfono».

- **Cómo se prende**: en los ajustes, «📡 Enlace cerca», en las DOS. Una
  muestra su código (diez letras, «ABCDE-FGHJK»); en la otra se escribe y se
  toca «Usar ese código». «Probar» manda un aviso a la otra. Para el auto, los
  dos teléfonos se emparejan UNA vez en los ajustes de Bluetooth de Android.
  Conviene dejarle a Pizarra la batería sin restricciones.
- **Escucha siempre** (`EnlaceServicio`, servicio en primer plano de tipo
  dispositivo conectado, con su notificación fija): por eso lo que llega
  despierta al teléfono. Al prender el teléfono vuelve solo.
- **En la casa** cada Pizarra se anuncia en la red local (NSD, `_pizarra._tcp`)
  y se conectan directo, sin salir a internet. **En el auto**, Bluetooth
  clásico, SÓLO con teléfonos emparejados: no busca aparatos desconocidos.
- **Todo va firmado** con una llave que sale del código (HMAC-SHA256): lo que
  no trae esa firma, lo viejo (más de 10 minutos) y lo repetido no se lee. El
  código no viaja nunca por la red.
- **La puerta local** (`EnlaceProveedor`): la app de la Hilux, en el mismo
  teléfono, le pasa a la Pizarra el viaje en vivo cada 2 s («🚙 82 km/h · 2.100
  RPM · 5.ª · 34,2 km», una notificación quieta) y lo que pasa (empezó, terminó,
  una línea fuera de lo normal: una notificación que suena). Sólo la atiende
  esa app, por su nombre Y por la huella de su firma, y **nunca deja pasar una
  coordenada**: la posición de la camioneta no viaja por acá.
- **Cupo**: cero cerca. No toca la base ni internet.
- **📻 Handy cerca** (pizarra-71, `Radio.kt`): con el enlace conectado, se
  mantiene apretado el botón y la voz sale en vivo en la otra Pizarra, por el
  wi-fi de la casa o el Bluetooth del auto, sin datos. Uno por vez, como una
  radio; no se graba. Se abre desde Ajustes, desde la notificación fija del
  enlace («📻 Hablar») o tocando el aviso «Te habla…». En la otra no suena si
  está en silencio, en una llamada o si apagó «Escuchar el handy».
- **Lejos, por internet** (pizarra-71, `EnlaceLejos.kt`): si no hay nadie
  cerca y la Pizarra entró con la cuenta de Tiempos, los avisos de la Hilux
  van por la base de Tiempos (`enlace/`, reglas v21) y suenan igual en la
  otra. **«🚙 En vivo»** muestra el viaje mientras está abierta: cerca, cada
  2 s; lejos, cada 10 s (sólo mientras alguien la mira se sube algo). Hay que
  publicar las reglas v21 de Tiempos.

## Si falla en el teléfono (pizarra-3)

pizarra-2 pasó el banco y compiló, y en el teléfono de Mauro se trababa. El
informe de fallos de HyperOS se va a Xiaomi, no a nosotros. Desde pizarra-3:

- **Sin notificaciones y con registro de todo** (pizarra-56): al tocar «Comentar»
  la propuesta sale en un cartel que se va solo (no gasta una notificación), y al
  recorrer se registran en silencio en Casa Verde todos los pedidos de alojamiento
  que pasan, con sus fechas y cuántos son, para que el sitio diga cuáles caen en
  fechas libres.
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
- **Una alarma que ya pasó de hora no se cancela** (pizarra-8, `tiempos:A13`).
  La de las 7:26 del 5-oct no sonó: según la bitácora, a las 07:25 estaba
  programada y a las 07:26:14 —al volver a la app— ya no. La puesta al día
  desprogramaba lo que ya había pasado de hora, y si Android la demoraba unos
  segundos, nunca sonaba. Ahora se desprograma sólo lo que se borró en
  Tiempos (`aCancelar`), y cada alarma que llega queda anotada en el disco
  («alerta RECIBIDA»), con si Android deja alarmas exactas.
- **Las barras del sistema y el teclado**: desde Android 15 la app se dibuja
  debajo de ellas; ahora el contenido se corre para que nada quede tapado.

## Qué escribe en la base

| Acción | Campos de `tareas/{id}` | Igual que en Tiempos |
|---|---|---|
| Tachar | `hecho: true`, `hechoPor: <uid>`, `actualizadoEn` | «✔ Hecha» |
| Destachar | `hecho: false`, `actualizadoEn` | «Volver a pendiente» |
| Fijar / sacar | `pizarra.<uid>: true` / se borra | campo nuevo, por persona |
| Una notificación del 13C (pizarra-42) | `notificaciones/{huella}` de Casa Verde: `app`, `titulo`, `texto`, `lineas`, `cuando`, `fecha`, `telefono`, `uid`, `creadoEn` | — (la crea sólo la Pizarra; regla nueva en Casa Verde) |

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
| `PizarraWidget.kt` | el widget de la pizarra, la caché y las filas |
| `AgendaLogica.kt` | la agenda de la semana armada como en Tiempos (actividades, tareas agendadas, chicos). Sin Android ni red |
| `AgendaWidget.kt` | el widget «Mi agenda», su caché y sus renglones |
| `Luces.kt` | las luces de remate: la sesión de remate, el puente y el registro |
| `Tibio.kt` | de 8 a 23, cada 10 min: la sesión de remate al día y el puente despierto |
| `ToqueActivity.kt` | el toque en la pizarra, invisible: el ☐ tacha, el texto abre la tarea |
| `TiemposActivity.kt` | Tiempos adentro (WebView): sólo el sitio, el ⚙ a los ajustes, los archivos |
| `MainActivity.kt` | los ajustes: entrar para el widget, la bodega, el diagnóstico; y la puerta de Compartir |
| `Bitacora.kt` | la bitácora, el cierre y la trabada guardados, y la falla que se manda a Tiempos |
| `Imagenes.kt` | achicar y subir la captura a Cloudinary (preset sin firma, como el sitio) |
| `Sirena.kt` | la alarma sonando: servicio en primer plano, sonido de alarma en bucle, Apagar |
| `Bodega.kt` | leer las notificaciones de Airbnb y subirlas a la bodega, y el latido |
| `Enlace.kt` | el enlace cerca: el código, la firma y leer con desconfianza. Sin Android ni red |
| `EnlaceServicio.kt` | el enlace escuchando y mandando por wi-fi (NSD) y Bluetooth, y las notificaciones de lo que llega |
| `EnlaceProveedor.kt` | la puerta local: sólo la app de la Hilux, por paquete y firma |
| `EnlaceLejos.kt` | el enlace por internet, por la base de Tiempos: los eventos, y el vivo mientras alguien mira |
| `Radio.kt` | el handy cerca en vivo, y las pantallas «📻 Handy cerca» y «🚙 En vivo» |
| `Alarmas.kt` | traer `alertas/`, programarlas, la notificación, la pantalla de «Apagar», el trabajo de cada hora |
| `.github/workflows/apk.yml` | banco + APK + release `ultimo` |
