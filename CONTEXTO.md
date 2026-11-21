# pizarra (Timón en Android) — CONTEXTO para una sesión nueva

> Este archivo es para una sesión de Claude que **no conoce nada del ecosistema**. Explica qué es este repositorio, cómo está armado, cómo funciona,
> qué reglas tiene y por qué, y cómo se trabaja sin romperlo. Leelo entero antes de tocar nada. Es una **foto del 21-nov-2026** (`pizarra-85`):
> si algo de acá contradice el código, **manda el código** y este archivo se corrige en la misma tanda.
> Los demás documentos del repo: `CLAUDE.md` (reglas cortas), `HISTORIA.md` (el porqué de cada regla, con fechas y errores), `README.md` (el detalle de cada función).
> **Hablale a Mauro siempre en castellano rioplatense.**

## 1 · Qué es y para quién

La app de **Android** (Kotlin) del ecosistema de Mauro. Desde el 17-nov-2026 **es Timón en Android**: todo lo *visible* dice Timón. Lo interno **no se renombra**
porque cambiarlo rompe lo instalado: el repo `pizarra`, el paquete `com.maurogasta.pizarra`, el archivo `pizarra.apk`, el sello `pizarra-N` (= `versionName`) y los temas de
aviso `pizarra-<base>`. Un texto nuevo dice «Timón», nunca «Pizarra» ni «Tiempos» como nombre de la app.

Qué hace (de lo central a lo periférico):
1. **Widgets** en la pantalla de inicio: **«Hoy»** (pinta sólo el documento `hoy/{uid}` que arma Timón y tilda con un toque), **«Pizarra (tareas fijadas)»**, **«Mi agenda»** (la semana) y **«Mensajes y novedades»**. Y las **luces de remate**, desde el widget.
2. **Timón adentro** (`TiemposActivity`, un WebView): abre la web de Timón (`maurogasta-crypto.github.io/tiempos/`) como si fuera parte de la app, con los archivos y los widgets entrando por `?solapa=`.
3. **Avisos** de Claude y del ecosistema (un canal de Android por sitio; el aviso *instantáneo* despierta la app por Firebase Cloud Messaging y ésta trae por partes lo que cambió).
4. **Alarmas y recordatorios** de Timón a su hora, con sirena hasta que se la apaga.
5. **Mensajes, handy y llamadas entre teléfonos de la familia/equipo** («de aparato a aparato»): cerca por wi-fi/Bluetooth sin datos, lejos por la base. *Parte de esto está congelado* (ver § 9).
6. **El teléfono de captura (Redmi 13C):** lee los mensajes y notificaciones de Airbnb, las notificaciones de TODAS las apps y los pedidos de alquiler de Facebook (por pantalla, sólo lectura) y los manda a Casa Verde/la bodega para que la **ronda** los clasifique.
7. **Luces de remate** desde un widget, **Compartir → Timón** hacia otro teléfono, **el parte de salud** de las comunicaciones y una **bitácora** que la app escribe sola.

**Rompe la regla «se edita desde el teléfono»** que tienen los demás repos: acá hay **compilación** (Gradle). Cada push a `main` corre el banco, compila y publica `pizarra.apk` en el release `ultimo`
(`.github/workflows/apk.yml`). **No tiene base propia**: usa las bases de los sitios con la cuenta de cada persona.

## 2 · El ecosistema en un párrafo (para no perderte)

Mauro mantiene varios proyectos con ayuda de sesiones de Claude. **`tiempos`** (repo web, «Timón») es la única app de uso diario de Mauro y Florencia; esta app de Android es su cáscara. **`datos`** es el *panel* (tablero de pendientes por proyecto + bóveda de claves que sólo Mauro ve),
con las herramientas y los protocolos; **`bodega`** es un repo privado de copias y registros. Una **ronda** automática (Claude, tres veces por día) lee los pendientes, los avisos y lo que sube el teléfono de captura, prepara borradores y propuestas y respalda las bases;
**no le escribe a nadie de afuera ni escribe plata**. Los **negocios** (Casa Verde, CasaYourte, remate, Paseo, BandoMaps, Hilux, Roli) son apps independientes con su base y su código. El **agente** es un usuario común de Firebase con permisos acotados por las reglas de cada base.
Cada sesión trabaja **un repo a la vez**, lo **reserva** antes de editarlo (`node herramientas/ronda.mjs reservar pizarra` desde `datos`) y empuja a `main` directo después de verificar.

## 3 · Mapa de archivos

Todo el código vive en `app/src/main/java/com/maurogasta/pizarra/` (plano, más de 40 archivos `.kt`); los recursos en `app/src/main/res/`; las pruebas en `app/src/test/java/com/maurogasta/pizarra/`.
La regla de diseño: **la lógica que se puede probar sin teléfono** vive aparte (`Logica.kt`, `AgendaLogica.kt`, `Enlace.kt`, `Hoy` en su parte pura) y los archivos con Android quedan finos.

| Archivo | Qué hace |
|---|---|
| `Hoy.kt` (`pizarra-84/85`) | **El widget «Hoy»**: lee UN documento (`hoy/{uid}` de la base de Timón), pinta los renglones en el orden en que vienen y no calcula nada. El ○ escribe `accion.campos` tal cual y saca el renglón de `hoy/` en la misma escritura; destildar escribe `accion.deshacer`; el texto abre Timón en ese ítem. Desde pizarra-85 pinta también el **proyecto** del renglón («Casa Verde · 17:00») |
| `ToqueActivity.kt` | Pantalla invisible que decide qué hace un toque en un widget de lista (el ☐ tacha, el texto abre la ficha) |
| `PizarraWidget.kt`, `AgendaWidget.kt`, `AgendaLogica.kt`, `Logica.kt` | El widget de tareas fijadas, el de agenda y su cuenta (la semana armada como en Timón); `Logica.kt` traduce Firestore ↔ pizarra y arma lo que se escribe |
| `Nube.kt` (`pizarra-64`) | Hablar con la base de Tiempos por REST: entrar, renovar el token, leer y escribir. Lleva `CLAVE_WEB` (pública por diseño) |
| `Avisos.kt`, `Instantaneo.kt`, `Despertar.kt`, `Abrir.kt` | Los avisos (un canal por sitio, la lista `SITIOS`), el aviso que despierta por FCM, **ponerse al día POR PARTES** (`partesDe(que)`), y adónde lleva tocar un aviso |
| `Alarmas.kt`, `Sirena.kt` | Traer `alertas/`, programarlas (una alarma que ya pasó no se programa), la notificación y la sirena que suena hasta que se apaga |
| `TiemposActivity.kt`, `MainActivity.kt` | Timón adentro (WebView) y los **ajustes** de la app (entrar, la bodega, el diagnóstico, las casillas de lo congelado) y la puerta de Compartir |
| `Mensajes.kt`, `Archivos.kt`, `Compartir.kt`, `Telefonos.kt` | Mensajes entre personas, fotos y documentos por la charla, «Compartir → Timón», y el nombre/color de cada teléfono |
| `Enlace.kt`, `EnlaceServicio.kt`, `EnlaceLejos.kt`, `EnlaceProveedor.kt`, `Radio.kt`, `Handy.kt`, `Llamada.kt` | El **enlace** entre teléfonos: *cerca* (wi-fi NSD/Bluetooth, firmado, sin datos) y *lejos* (por la base), el handy de voz, las llamadas por WebRTC y la **puerta local** que sólo atiende a la app de la Hilux (por paquete y firma). **Congelado por defecto** (§ 9) |
| `Congelado.kt` (`pizarra-83`) | Los dos interruptores de lo congelado: `llamadas()` y `lector()` (default apagados) |
| `Notificaciones.kt`, `Bodega.kt`, `Pantalla.kt`, `CasaVerde.kt`, `Busqueda.kt`, `Disponibilidad.kt`, `Responder.kt` | **El teléfono de captura**: sube TODAS las notificaciones a `notificaciones/` de Casa Verde (con códigos y enlaces **tapados en el teléfono**), los mensajes de Airbnb a la bodega, y lee Facebook por la pantalla (sólo lectura) para armar la respuesta a pedidos de alquiler |
| `Luces.kt`, `Tibio.kt` | Las luces de remate (la sesión de remate, el puente `TUYA_LUCES`, el registro) y mantenerlas «listas» de 8 a 23 |
| `Salud.kt`, `Bitacora.kt` | El parte de salud (cada hora, `salud/<uid>\|<aparato>`) y la bitácora que la app escribe sola (`SELLO` = `versionName`) |
| `Servidor.kt` (`pizarra-82`) | **El servidor del ecosistema escrito UNA vez**: `SERVIDOR = "https://timon-servidor.netlify.app/.netlify/functions"` |
| `Imagenes.kt` | Achicar y subir una captura a Cloudinary (preset sin firma) |
| `AndroidManifest.xml`, `res/xml/*` | Permisos, servicios, widgets (`widget_hoy_info`, `widget_info`, `widget_agenda_info`, `widget_mensajes_info`), el lector de pantalla (`lector_pantalla.xml`) y `sin_copias.xml` (lo que no entra a las copias de Android) |
| `app/build.gradle.kts` | `minSdk 26`, `targetSdk 35`, solo ARM (`arm64-v8a`, `armeabi-v7a`), `versionName = "pizarra-85"`; **dos dependencias y ninguna más**: `firebase-messaging` y `stream-webrtc-android` |

## 4 · Los flujos principales

**«Hoy» (el flujo más importante del plan Timón).** Timón (la web) materializa `hoy/{uid}`: los próximos 7 días, cada renglón con `dia`, `texto`, `espacio`, `proyecto`, `hora`, `clave` y la **acción exacta** que se escribe al tildar y al deshacer. El widget lo lee (una lectura por puesta al día) y sólo elige el día comparando fechas. Al tildar escribe `accion.campos` tal cual sobre su documento (sólo si existe) y **quita el renglón** de `hoy/` en el mismo commit (`removeAllFromArray` con el valor exacto que vino), para que los demás teléfonos de la persona no lo sigan mostrando; el tilde **no espera a la red** y, si la base dice que no, la fila vuelve. Se pone al día con ⟳, al abrir la app, al salir de Timón, cada media hora, cada 10 minutos (es una lectura barata) y con el aviso de «tareas» que manda Timón al escribir `hoy/`. Sin el widget puesto no gasta nada.

**Ponerse al día gastando poco** (`pizarra-74`, `tiempos:A42`). El 27-oct la base de Tiempos se quedó **sin cupo de lecturas** (Spark: 50.000/día) porque cada aviso hacía bajar todo. Desde entonces el aviso instantáneo despierta **sin contenido** y la app trae **sólo la parte que cambió** (`QUE_CAMBIO` ↔ `QUE` de `despertar.mjs` del servidor). Los pedidos de la red se hacen por partes y el banco lo exige.

**Alarmas.** Trae `alertas/` (recordatorios y alarmas de Timón), las programa con `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, muestra la notificación y la pantalla de «Apagar»; `Sirena` suena en un servicio en primer plano hasta que se apaga.

**El teléfono de captura (13C).** Tres entradas: (a) las **notificaciones de todas las apps** van a `notificaciones/` de la base de Casa Verde con los **códigos de verificación y enlaces tapados en el teléfono antes de salir** (`taparCodigos`, `taparEnlaces`: es la regla de oro) y **no** a la bodega (git no olvida); (b) los **mensajes de Airbnb** (solo `com.airbnb.android`) van a la bodega para que la ronda prepare borradores; (c) **Facebook por la pantalla**: lee mientras alguien lo mira, sólo lee (no toca, no desliza, no escribe) y sólo cuenta lo que la persona **elige** (toca «Comentar»); arma la respuesta con la capacidad de cada cabaña y la **disponibilidad** de Casa Verde. **Publicar es siempre de la persona.** La ronda lee todo eso, lo clasifica y borra lo que no sirve.

**Enlace entre teléfonos.** *Cerca* (sin datos): el código y la firma (`Enlace.kt`), un servicio que escucha y manda por wi-fi (NSD) y Bluetooth, el handy en vivo (`Radio.kt`) y «Compartir». *Lejos*: por la base de Tiempos (`EnlaceLejos.kt`), sin coordenadas. Los audios y archivos son **temporales**; **la radio no se graba**. **Claude lee y escucha la charla y la app lo dice a la vista** (`AVISO_CLAUDE_LEE`, no se saca). La **puerta local** sólo atiende a la app de la Hilux (por paquete y firma, `HUELLA_HILUX`) y **nunca lleva una coordenada**.

**Llamadas.** Voz directa por WebRTC (`stream-webrtc-android`); **no se puede probar en el banco**. El cupo de lecturas está en el comentario de `Despertar.kt`.

**Salud y bitácora.** Cada teléfono publica su parte de salud (una vez por hora) con lo que *llegó, sonó o se cortó* (la base sólo sabe lo que se *mandó*); la ronda lo lee. La bitácora (`Bitacora.kt`) **no lleva lo dictado, títulos, textos ni nombres de archivo**; todo `thread {}` ataja `Throwable` y lo anota.

**Luces de remate.** El widget manda un **alias** al puente (el servidor firma contra Tuya); la sección dice su resultado **al lado de su botón** (no con `decir()`); una orden por toque, decidida una sola vez (`Luces.preparar`).

## 5 · Datos y bases (lo que lee y escribe)

La app **no tiene base propia**. Con la cuenta de cada persona habla por REST con: `tiempos-71d42` (tareas, alertas, deseos, `hoy/`, mensajes, charla, `salud/`, enlace), `casaverde-20` (`notificaciones/`, pedidos de alquiler, cabañas y reservas para la disponibilidad), la base de remate (luces) y las de los otros sitios sólo para sus **avisos**. Al servidor del ecosistema (`timon-servidor`) llega por `Servidor.kt`.

| Acción | Qué escribe | Equivale en Timón |
|---|---|---|
| Tachar / destachar | `hecho`, `hechoPor`, `actualizadoEn` en `tareas/{id}` | «✔ Hecha» / «Volver a pendiente» (`cuerpoTachar`) |
| Fijar / sacar de la pizarra | `pizarra.<uid>: true` / se borra | campo por persona (`cuerpoNuevaTarea`/`cuerpoFijar`) |
| Tildar en «Hoy» | lo que diga `accion.campos` del renglón | lo decide Timón; acá no se nombra un campo de un ítem |
| Notificación del 13C | `notificaciones/{huella}` de Casa Verde: `app`, `titulo`, `texto`, `cuando`, `uid`… | — |
| Mensajes, audios, archivos, enlace | colecciones de la charla de Tiempos (reglas v15–v24) | `cuerpoMensaje`, `cuerpoAudio`, `cuerpoLlamada`, `cuerpoParte`, `CAMPOS_ENLACE` |

Todo con `currentDocument.exists` donde corresponde, para no crear lo que alguien borró. **Secretos:** ningún valor real entra al repo, a otro ni a un chat. Las claves web de Firebase de cada sitio (`CLAVE_WEB`, `SITIOS`) y los datos de FCM (`avisos-23b6a`) son **públicos por diseño** (identifican, no dan permisos).
La contraseña de la persona **no se guarda** (se usa una vez); lo que se guarda es el `refreshToken` de cada sesión en `SharedPreferences`, **fuera de las copias** (`sin_copias.xml`). El **token de la bodega** lo pega Mauro una vez en la app y nunca se muestra. La **firma** (`FIRMA_JKS`, `FIRMA_STORE_PASS`, `FIRMA_KEY_PASS`, `FIRMA_ALIAS`) está en GitHub Secrets de este repo, cargada por Mauro; el `.jks` y su clave, en su gestor. **La firma de esta app no es la de la Hilux.**

## 6 · Reglas de trabajo, con su porqué

1. **Dos dependencias y ninguna más** (`firebase-messaging`, `stream-webrtc-android`): el banco falla si aparece una tercera. Todo lo demás es `HttpURLConnection` y `org.json`. Cada dependencia es peso, cupo y superficie de rotura en teléfonos viejos.
2. **El banco ABRE la app** (`AppTest`, Robolectric): una pantalla nueva entra con su caso. **Un hilo nunca tira**: todo `thread {}` ataja `Throwable`.
3. **Pantalla con campo de texto abajo: medir el teclado** (`WindowInsets.Type.ime()`); en la charla se escribe en `EscribirActivity`. **Un widget sólo admite algunas vistas** (un `View` suelto lo rompe).
4. **La regla 3 del plan**: lo que se calcula, se calcula una vez. El widget «Hoy» **no ordena ni calcula**; un banco (`HoyTest`) falla si `Hoy.kt` nombra un campo de un ítem u ordena. Ya hubo ~14 copias «si cambia acá, cambia allá» Kotlin↔JS y se están retirando.
5. **El teléfono de captura sólo LEE.** Facebook: el banco falla si aparece una acción en `Pantalla.kt` o gestos en el XML (automatizar toques dentro de Facebook **bloquea la cuenta**). «Compartir» no abre el link de Instagram y compañía. **Nunca contesta**: publicar es de la persona.
6. **Los códigos y enlaces de verificación se tapan en el teléfono antes de salir.** Es la regla de oro del 13C: un código no puede llegar a ninguna base.
7. **La bitácora no lleva lo dictado, títulos, textos ni nombres de archivo.** El dictado viaja como texto y la app **no tiene claves de IA**.
8. **Una alarma que ya pasó no se programa.**
9. **`SELLO` de `Bitacora.kt` ES el `versionName`** (`SelloTest` los compara): hasta pizarra-82 divergieron y la app instalada decía 80 siendo 82. Sube en **cada tanda**.
10. **Ningún desarrollo pasa de los cupos gratuitos** (`PROTOCOLO-DESARROLLO` § 13): cada tanda que agrega uso dice cuánto para 10 personas.
11. **Archivos completos, nunca diffs**; se empuja a `main` directo **después de verificar**.

### Copias que hay que mantener iguales (si cambia una, cambia la otra en la misma tanda)

`Hoy.kt` ↔ `materializarHoy` de `nucleo.js` de `tiempos` (forma del renglón de `hoy/`) · `cuerpoTachar` ↔ «✔ Hecha» de `app.js` de Timón · `cuerpoNuevaTarea` ↔ cómo el sitio crea una tarea · `posiblesDelDia`/`CATEGORIAS`/`tipoHeredado` ↔ `nucleo.js` · `AgendaLogica.kt` ↔ `agenda.js`/`nucleo.js` (claves de pieza `act:`, `f:`, `cv:`, `ev:<id>|<día>`) · `propuestasDeConsulta`/`CLASES_PROPONE` ↔ `propone.js` · `QUE_CAMBIO`/`partesDe` ↔ `QUE` de `despertar.mjs` (servidor) · `SERVIDOR` ↔ `servidor-1.js` de `datos` · `Bodega.kt` ↔ `telefono-whatsapp.mjs` de `datos` · `CAMPOS_PEDIDO`/`cuerpoPedidoCasaVerde`/`CAMPOS_NOTIFICACION`/`enlaceBusqueda` ↔ reglas y páginas de Casa Verde y `facebook.mjs` · `cuerpoMensaje`… ↔ reglas de Tiempos v15–v24 · `LUCES_WIDGET`/`cuerpoRegistroLuz` ↔ `TUYA_LUCES` y `luces.html` de remate · `HUELLA_HILUX` ↔ la firma de la app de la Hilux · `destinoDeAviso` ↔ temas de `avisos.mjs`.

## 7 · Cómo se verifica

Desde la raíz del repo (necesita JDK 17 y el SDK de Android; en un contenedor nuevo hay que instalarlo con `sdkmanager`, `ANDROID_HOME=/opt/sdk`):
- `./gradlew test assembleRelease lintRelease` **sin errores** (el lint no se apaga). Hoy el banco son **~281 casos en 33 archivos** (Robolectric); incluye `AppTest` (abre la app), `HoyTest`, `CongeladoTest`, `SelloTest`, `ServidorTest` (compara `SERVIDOR` con `servidor-1.js` si `datos` está al lado) y los de cada módulo.
- **El sello sube** en cada tanda (`versionName` y `SELLO`, juntos), y `CLAUDE.md`, `README.md` y este archivo tienen que decir la verdad.
- Si cambia cómo se entra o qué permisos pide, cambia `pizarra.html` de `datos` (la guía de instalación) en la misma tanda.
- Si cambia la forma del renglón de `hoy/`, cambia `materializarHoy` de `tiempos` en la misma tanda y **se publica primero el sitio y después la app**.

## 8 · Cómo se despliega

`apk.yml` corre en cada push a `main`: ejecuta el banco (`./gradlew test`; si falla, **no se construye nada** y deja anotada la prueba que falló), compila y publica `pizarra.apk` en el release **`ultimo`** (un enlace directo que se instala tocándolo desde el teléfono; el título lleva el sello). Cada teléfono **se actualiza instalando encima**: por eso importa la firma propia (sin los cuatro secretos `FIRMA_*`, firmaría con una clave de depuración nueva y habría que desinstalar —se perdería la sesión—). La app **no se auto-actualiza**: Mauro (o Florencia) instala la nueva en cada teléfono. El parte de salud (`salud/`) muestra qué sello tiene cada aparato.

## 9 · Lo que NO se toca y lo que está congelado

- **Congelado por el plan Timón (§ 5 de `ARQUITECTURA.md` de `datos`), APAGADO por defecto desde pizarra-83**: las **llamadas** (no suenan ni arrancan) y el **lector de Facebook** (no lee) hasta prenderlos en la pantalla del enlace; el **enlace cerca** ya nacía apagado. **Nada se borra** y el interruptor de cada teléfono no se toca al actualizar (`CongeladoTest`). No se les suma nada mientras tanto.
- **No renombrar** `pizarra`, `com.maurogasta.pizarra`, `pizarra.apk`, `pizarra-N` ni los temas `pizarra-<base>`: rompe sesiones, el token de la bodega y el enlace con la Hilux (`…pizarra.enlace`).
- **No guardar contraseñas.** Si alguna vez hiciera falta, la respuesta es no.
- **Tiempos va adentro, no se copia** (`esDeTiempos`): lo que hace Timón se cambia en el sitio. «Claude propone» **se muestra, no se acepta acá**.
- **El 13C no manda notificaciones a la bodega** (va a Casa Verde, con vencimiento a 7 días); `mensajes/` de la bodega es sólo Airbnb y pedidos de Facebook.
- **No automatizar toques** dentro de Facebook, WhatsApp ni Airbnb.
- Errores que ya costaron: la base sin cupo el 27-oct; la versión que decía 80 siendo 82 (el sello no se unificaba); pizarra-82 que quedó 5 días perdida en una rama sin mezclar; un widget que se rompió con una vista suelta.

## 10 · Estado actual y qué falta (21-nov-2026)

- **Publicadas:** pizarra-83 (apaga lo congelado, unifica el sello), pizarra-84 (el widget «Hoy», todo dice Timón) y **pizarra-85** (el renglón de «Hoy» trae el proyecto; sale el ⚙ flotante que tapaba la barra de abajo). Falta que Mauro las instale en los teléfonos (el 13C seguía en 83 el 20-nov).
- **En paralelo hasta el 23-nov:** el widget «Pizarra (tareas fijadas)» convive con «Hoy»; se apaga después de una semana de uso de «Hoy» (regla 8 del plan).
- **Pendiente:** vaciar el zip viejo de Casa Verde cuando `salud/` muestre todos los aparatos en ≥ 82 durante una semana; los widgets nuevos que pida la etapa 6 (hilos); mostrar las novedades de «Hoy» sin abrir Timón; y todo lo congelado (enlace cerca, llamadas, lector de Facebook), que se decide después de que lo central ande.
- **Decisiones de Mauro abiertas:** D1 a D6 del mapa `datos/protocolos/INTEGRACION.md` § 9.

## 11 · Glosario

- **Timón:** la app única de uso diario (la web, repo `tiempos`); esta app es su versión Android.
- **Ítem / espacio / `hoy/{uid}`:** la forma única de lo pendiente / dónde vive y quién lo ve / el documento que arma Timón y el widget pinta.
- **Materializar:** dejar armado un documento que otro sólo tiene que pintar.
- **Sello (`pizarra-N`):** el número de versión de la app (`versionName`); sube en cada tanda.
- **13C / Note 9 / A15:** los teléfonos del equipo (el 13C es el de captura; el Note 9 va a bordo de la camioneta; el A15 es el de Florencia).
- **Ronda:** la rutina automática de Claude (tres veces por día); **agente:** el usuario de Firebase que usa; **bodega:** repo privado de copias y registros; **panel:** el repo `datos`.
- **Parte de salud:** lo que cada teléfono dice cada hora sobre lo que llegó, sonó o se cortó.
- **Enlace cerca / lejos:** comunicación entre teléfonos sin datos (wi-fi/Bluetooth) / por la base.
- **Congelado:** apagado por defecto por el plan Timón, sin borrar.
- **Cupo:** el límite gratuito de Firebase Spark (50 K lecturas / 20 K escrituras por día); cada tanda que agrega uso dice cuánto gasta.
