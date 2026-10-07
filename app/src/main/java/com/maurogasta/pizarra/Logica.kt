package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// Logica.kt — Lo que se puede probar sin teléfono. Sello: pizarra-11
//
// Traducir las tareas de Tiempos (Firestore REST) a la lista de la pizarra, y
// armar lo que se le manda a la base al tachar o al fijar una tarea. No toca
// red ni Android: lo corre el banco (`./gradlew test`).
//
// ── QUÉ ESCRIBE, Y POR QUÉ ESO Y NADA MÁS ────────────────────────────────────
// Tachar es EXACTAMENTE el botón «✔ Hecha» de Tiempos: `hecho: true` y
// `hechoPor: <uid>`. Destachar es «Volver a pendiente»: `hecho: false`. Así lo
// que se marca acá se ve igual en el sitio, para los dos.
//
// Qué tareas van a la pizarra lo dice el campo `pizarra` de la propia tarea:
// un mapa uid → true. Vive en la tarea y no en el teléfono para que reinstalar
// la app no la vacíe, y es por persona para que la de Mauro no sea la de
// Florencia. La regla de `tareas` ya lo permite: no hizo falta tocarla.
// ─────────────────────────────────────────────────────────────────────────────

const val PROYECTO = "tiempos-71d42"
const val BASE_DOCS = "projects/$PROYECTO/databases/(default)/documents"

data class Tarea(
    val id: String,
    val titulo: String,
    val hecho: Boolean,
    val alcance: String,
    val duenio: String,
    val parentId: String?,
    val enMiPizarra: Boolean,
    val color: String?,
)

/** Lee una tarea del formato de Firestore REST. Lo que no entiende, null. */
fun tareaDe(doc: JSONObject, uid: String): Tarea? {
    val nombre = doc.optString("name", "")
    val id = nombre.substringAfterLast('/', "")
    if (id.isEmpty()) return null
    val f = doc.optJSONObject("fields") ?: JSONObject()
    val titulo = texto(f, "titulo")?.trim().orEmpty()
    if (titulo.isEmpty()) return null
    val pizarra = f.optJSONObject("pizarra")?.optJSONObject("mapValue")?.optJSONObject("fields")
    val mia = pizarra?.optJSONObject(uid)?.optBoolean("booleanValue", false) ?: false
    return Tarea(
        id = id,
        titulo = titulo.take(120),
        hecho = f.optJSONObject("hecho")?.optBoolean("booleanValue", false) ?: false,
        alcance = texto(f, "alcance") ?: "comun",
        duenio = texto(f, "duenio").orEmpty(),
        parentId = texto(f, "parentId"),
        enMiPizarra = mia,
        color = texto(f, "color"),
    )
}

private fun texto(f: JSONObject, campo: String): String? =
    f.optJSONObject(campo)?.let { if (it.has("stringValue")) it.optString("stringValue") else null }

/** La respuesta de runQuery: una lista de { document } (o sólo { readTime }). */
fun tareasDeConsulta(respuesta: JSONArray, uid: String): List<Tarea> =
    (0 until respuesta.length()).mapNotNull { i ->
        respuesta.optJSONObject(i)?.optJSONObject("document")?.let { tareaDe(it, uid) }
    }

/**
 * Lo que muestra el widget: las tareas que la persona fijó, pendientes
 * primero y tachadas al final. Una subtarea lleva el nombre de su proyecto
 * adelante, porque «Comprar tornillos» sola no dice de qué.
 */
fun paraLaPizarra(todas: List<Tarea>): List<Fila> {
    val porId = todas.associateBy { it.id }
    return todas.filter { it.enMiPizarra }
        .sortedWith(compareBy<Tarea>({ it.hecho }, { it.titulo.lowercase() }))
        .map { t ->
            val padre = t.parentId?.let { porId[it]?.titulo }
            Fila(t.id, if (padre != null) "$padre › ${t.titulo}" else t.titulo, t.hecho)
        }
}

data class Fila(val id: String, val texto: String, val hecho: Boolean)

/** Las que se pueden ofrecer para fijar: pendientes y las ya fijadas. */
fun paraElegir(todas: List<Tarea>): List<Tarea> =
    todas.filter { !it.hecho || it.enMiPizarra }
        .sortedWith(compareBy<Tarea>({ !it.enMiPizarra }, { it.hecho }, { it.titulo.lowercase() }))

/* ── Lo que se escribe ─────────────────────────────────────────────────────
   Un `commit` con UNA escritura: sólo los campos de la máscara, la tarea tiene
   que existir (no se crea una tarea fantasma si alguien la borró en el medio),
   y `actualizadoEn` con la hora del servidor, como `serverTimestamp()` del
   sitio. */

private fun escritura(id: String, campos: JSONObject, mascara: List<String>): JSONObject =
    JSONObject()
        .put("update", JSONObject().put("name", "$BASE_DOCS/tareas/$id").put("fields", campos))
        .put("updateMask", JSONObject().put("fieldPaths", JSONArray(mascara)))
        .put("updateTransforms", JSONArray().put(
            JSONObject().put("fieldPath", "actualizadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", true))

/** Tachar (hecho) o destachar (pendiente). Igual que los botones del sitio. */
fun cuerpoTachar(id: String, hecho: Boolean, uid: String): JSONObject {
    val campos = JSONObject().put("hecho", JSONObject().put("booleanValue", hecho))
    val mascara = mutableListOf("hecho")
    if (hecho) {
        campos.put("hechoPor", JSONObject().put("stringValue", uid))
        mascara.add("hechoPor")
    }
    return JSONObject().put("writes", JSONArray().put(escritura(id, campos, mascara)))
}

/**
 * Fijar o sacar una tarea de MI pizarra. Toca sólo `pizarra.<mi uid>`: la
 * del otro queda como está. Sacar es dejar el campo en la máscara y afuera de
 * los datos, que en Firestore quiere decir borrarlo.
 */
fun cuerpoFijar(id: String, fijar: Boolean, uid: String): JSONObject {
    val campos = JSONObject()
    if (fijar) campos.put("pizarra", JSONObject().put("mapValue",
        JSONObject().put("fields", JSONObject().put(uid, JSONObject().put("booleanValue", true)))))
    // Entre acentos graves: un uid puede empezar con un número, y entonces no
    // es un nombre de campo válido sin ellos.
    return JSONObject().put("writes", JSONArray().put(escritura(id, campos, listOf("pizarra.`$uid`"))))
}

/** Las dos consultas del sitio: las comunes, y las personales mías. */
fun consultaComunes(): JSONObject = consulta(filtro("alcance", "comun"))

fun consultaMias(uid: String): JSONObject = consulta(JSONObject().put("compositeFilter", JSONObject()
    .put("op", "AND")
    .put("filters", JSONArray().put(filtro("alcance", "personal")).put(filtro("duenio", uid)))))

private fun filtro(campo: String, valor: String) = JSONObject().put("fieldFilter", JSONObject()
    .put("field", JSONObject().put("fieldPath", campo))
    .put("op", "EQUAL")
    .put("value", JSONObject().put("stringValue", valor)))

private fun consulta(where: JSONObject) = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", "tareas")))
    .put("where", where))

/* ── La caché del widget ───────────────────────────────────────────────────
   El widget no puede esperar a la red para dibujarse: dibuja lo último que
   se guardó, y la red lo corrige después. */

fun filasAJson(filas: List<Fila>): String = JSONArray().apply {
    filas.forEach { put(JSONObject().put("id", it.id).put("texto", it.texto).put("hecho", it.hecho)) }
}.toString()

fun filasDeJson(texto: String?): List<Fila> = try {
    val a = JSONArray(texto ?: "[]")
    (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { o ->
            val id = o.optString("id", "")
            if (id.isEmpty()) null else Fila(id, o.optString("texto", ""), o.optBoolean("hecho", false))
        }
    }
} catch (e: Exception) {
    emptyList()
}

/** Tachar sin esperar a la red: la fila cambia ya, y la red lo confirma. */
fun conTachada(filas: List<Fila>, id: String, hecho: Boolean): List<Fila> =
    filas.map { if (it.id == id) it.copy(hecho = hecho) else it }
        .sortedWith(compareBy<Fila>({ it.hecho }, { it.texto.lowercase() }))

/* ── pizarra-2: la app de Tiempos en el teléfono (5-oct-2026) ──────────────
   Mauro: «que la Pizarra sea la app de Tiempos». Lo que el sitio no puede
   hacer con el teléfono bloqueado —sonar a una hora— y lo que el teléfono
   hace mejor que el sitio —dictar— viven acá. Lo demás sigue en el sitio:
   esta app le pasa el dictado y Tiempos arma el plan con su IA.

   ── LAS ALERTAS ──────────────────────────────────────────────────────────
   `alertas/` de Tiempos (reglas v9): cada una es de su dueño, con `tipo`
   recordatorio o alarma, `texto`, `dia` (AAAA-MM-DD) y `hora` (HH:MM), en la
   hora del teléfono. Un recordatorio es una notificación; una alarma suena
   hasta que se la apaga. */

data class Alerta(val id: String, val tipo: String, val texto: String, val dia: String, val hora: String)

private val DIA_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val HORA_RE = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

fun alertaDe(doc: JSONObject): Alerta? {
    val id = doc.optString("name", "").substringAfterLast('/', "")
    val f = doc.optJSONObject("fields") ?: return null
    val tipo = texto(f, "tipo") ?: return null
    if (id.isEmpty() || (tipo != "recordatorio" && tipo != "alarma")) return null
    val dia = texto(f, "dia").orEmpty()
    if (!DIA_RE.matches(dia)) return null
    val hora = texto(f, "hora").orEmpty().let { if (HORA_RE.matches(it)) it else "08:00" }
    val t = texto(f, "texto")?.trim().orEmpty()
    if (t.isEmpty()) return null
    return Alerta(id, tipo, t.take(200), dia, hora)
}

fun alertasDeConsulta(respuesta: JSONArray): List<Alerta> =
    (0 until respuesta.length()).mapNotNull { i ->
        respuesta.optJSONObject(i)?.optJSONObject("document")?.let { alertaDe(it) }
    }

/** El instante de una alerta, en la zona del teléfono. Si no se entiende, null. */
fun instanteDe(a: Alerta, zona: java.time.ZoneId): Long? = try {
    java.time.LocalDateTime.of(java.time.LocalDate.parse(a.dia), java.time.LocalTime.parse(a.hora))
        .atZone(zona).toInstant().toEpochMilli()
} catch (e: Exception) {
    null
}

/**
 * Las que hay que dejar programadas: de ahora en adelante y hasta `dias`
 * días, por hora. Una que ya pasó no se programa —Android la dispararía en
 * el acto, y sonar a destiempo enseña a no hacerle caso—.
 */
fun paraProgramar(alertas: List<Alerta>, ahora: Long, zona: java.time.ZoneId, dias: Int = 14): List<Pair<Alerta, Long>> {
    val hasta = ahora + dias * 24L * 3600 * 1000
    return alertas.mapNotNull { a -> instanteDe(a, zona)?.let { a to it } }
        .filter { (_, t) -> t > ahora && t <= hasta }
        .sortedBy { it.second }
}

/** Mis alertas: la regla deja leer sólo las propias, y la consulta lo dice. */
fun consultaAlertas(uid: String): JSONObject = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", "alertas")))
    .put("where", filtro("uid", uid)))

fun alertasAJson(a: List<Alerta>): String = JSONArray().apply {
    a.forEach { put(JSONObject().put("id", it.id).put("tipo", it.tipo).put("texto", it.texto).put("dia", it.dia).put("hora", it.hora)) }
}.toString()

fun alertasDeJson(texto: String?): List<Alerta> = try {
    val a = JSONArray(texto ?: "[]")
    (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { o ->
            val x = Alerta(o.optString("id"), o.optString("tipo"), o.optString("texto"), o.optString("dia"), o.optString("hora"))
            if (x.id.isEmpty() || !DIA_RE.matches(x.dia) || !HORA_RE.matches(x.hora)) null else x
        }
    }
} catch (e: Exception) {
    emptyList()
}

/* ── LOS DESEOS: «qué se puede hacer hoy» ──────────────────────────────────
   La misma cuenta que `posiblesDelDia` de nucleo.js de Tiempos (deseos-1): lo
   semanal de ese día de la semana (`dias`, 0 = domingo) y lo de esa fecha,
   sin los descartados, por hora. Si cambia allá, cambia acá. */

data class Deseo(val id: String, val titulo: String, val lugar: String, val dias: List<Int>, val fecha: String,
                 val hi: String, val hf: String, val estado: String)

fun deseoDe(doc: JSONObject): Deseo? {
    val id = doc.optString("name", "").substringAfterLast('/', "")
    val f = doc.optJSONObject("fields") ?: return null
    val titulo = texto(f, "titulo")?.trim().orEmpty()
    if (id.isEmpty() || titulo.isEmpty()) return null
    val vals = f.optJSONObject("dias")?.optJSONObject("arrayValue")?.optJSONArray("values") ?: JSONArray()
    val dias = (0 until vals.length()).mapNotNull { i ->
        val v = vals.optJSONObject(i) ?: return@mapNotNull null
        (v.optString("integerValue", "").toIntOrNull() ?: if (v.has("doubleValue")) v.optDouble("doubleValue").toInt() else null)
            ?.takeIf { it in 0..6 }
    }.distinct().sorted()
    return Deseo(id, titulo.take(120), texto(f, "lugar").orEmpty(), dias,
        texto(f, "fecha").orEmpty().takeIf { DIA_RE.matches(it) }.orEmpty(),
        texto(f, "hi").orEmpty().takeIf { HORA_RE.matches(it) }.orEmpty(),
        texto(f, "hf").orEmpty().takeIf { HORA_RE.matches(it) }.orEmpty(),
        texto(f, "estado") ?: "deseo")
}

fun deseosDeConsulta(respuesta: JSONArray): List<Deseo> =
    (0 until respuesta.length()).mapNotNull { i ->
        respuesta.optJSONObject(i)?.optJSONObject("document")?.let { deseoDe(it) }
    }

fun consultaDeseos(): JSONObject = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", "deseos"))))

/** 0 = domingo, como Date.getDay() del sitio. */
fun diaSemana(iso: String): Int = java.time.LocalDate.parse(iso).dayOfWeek.value % 7

fun posiblesDelDia(deseos: List<Deseo>, iso: String): List<Deseo> {
    val w = diaSemana(iso)
    return deseos.filter { d -> d.estado != "descartado" && (if (d.fecha.isNotEmpty()) d.fecha == iso else w in d.dias) }
        .sortedWith(compareBy<Deseo>({ it.hi.ifEmpty { "99" } }, { it.titulo }))
}

/* ── EL DICTADO ────────────────────────────────────────────────────────────
   El reconocedor de Android —el mismo del micrófono del teclado, que a Mauro
   le anda— escribe acá; lo corregido viaja a Tiempos como ?dictar=, y Tiempos
   lo precarga con su IA (app-14). La dirección no lleva nada más. */
const val TIEMPOS_URL = "https://maurogasta-crypto.github.io/tiempos/"

fun urlDictado(texto: String, imagen: String = ""): String =
    TIEMPOS_URL + "?dictar=" + java.net.URLEncoder.encode(texto.trim().take(2000), "UTF-8") +
        (if (esImagenNuestra(imagen)) "&imagen=" + java.net.URLEncoder.encode(imagen, "UTF-8") else "")

/* ── COMPARTIR DESDE OTRA APP (pizarra-7, 5-oct-2026) ──────────────────────
   Mauro: «hacé lo de Compartir desde Instagram, o cualquier otro link de red
   social». Instagram no se deja leer por un programa (pide login), así que la
   app no abre el link: lo guarda con lo que vino escrito y pide una captura
   del reel o del flyer, que la IA sí lee. */

/** Una imagen sólo viaja si es de la cuenta de Cloudinary del ecosistema. */
const val CLOUDINARY_CUENTA = "dnwfu8ffn"
fun esImagenNuestra(u: String): Boolean = u.startsWith("https://res.cloudinary.com/$CLOUDINARY_CUENTA/")

private val URL_RE = Regex("""https?://\S+""")

/** Los links de un texto compartido, sin la basura de seguimiento de Instagram y compañía. */
fun linksDe(texto: String): List<String> = URL_RE.findAll(texto).map { m ->
    val u = m.value.trimEnd('.', ',', ')', ']', '»', '"')
    // ?igsh=, ?utm_…, ?stkn=: identifican a quien compartió, no al evento.
    if (Regex("""(instagram\.com|facebook\.com|fb\.watch|tiktok\.com|youtu)""").containsMatchIn(u)) u.substringBefore('?') else u
}.distinct().toList()

/** De dónde viene un link, para decírselo a la IA y a la persona. */
fun redDe(u: String): String = when {
    "instagram.com" in u -> "Instagram"
    "facebook.com" in u || "fb.watch" in u || "fb.me" in u -> "Facebook"
    "tiktok.com" in u -> "TikTok"
    "youtu" in u -> "YouTube"
    "wa.me" in u || "whatsapp" in u -> "WhatsApp"
    else -> "la web"
}

/**
 * Lo que llega por «Compartir», puesto como texto para el dictado: lo que la
 * otra app mandó escrito (sin el link repetido) y los links aparte, cada uno
 * con su red. Si no vino nada escrito, una línea que lo dice, para que la IA
 * no invente a partir de una dirección.
 */
fun textoCompartido(asunto: String?, texto: String?): String {
    val crudo = listOfNotNull(asunto?.trim(), texto?.trim()).filter { it.isNotEmpty() }.distinct().joinToString("\n")
    val links = linksDe(crudo)
    val resto = URL_RE.replace(crudo, "").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    val partes = mutableListOf<String>()
    if (resto.isNotEmpty()) partes.add(resto.take(1500))
    else if (links.isNotEmpty()) partes.add("Algo que vi en ${redDe(links[0])} y quiero agendar o guardar (sin texto: mirá la captura).")
    links.take(3).forEach { partes.add("Link (${redDe(it)}): $it") }
    return partes.joinToString("\n")
}

/**
 * Qué alarmas se desprograman al ponerse al día: SÓLO las que se borraron de
 * la base (pizarra-8, tiempos:A13). Una cuya hora ya pasó sigue en la base
 * hasta que alguien la saca, y no se toca: si Android la demoró unos segundos,
 * cancelarla es que no suene nunca.
 */
fun aCancelar(programadas: Set<String>, enLaBase: Set<String>): Set<String> = programadas - enLaBase

/* ── CLAUDE PROPONE (pizarra-11, tiempos:V3) ──────────────────────────────
   Lo que Claude propone para MI agenda (clase «agenda») o me pregunta a mí
   (clase «consulta»), pendiente. La app lo MUESTRA y lleva a Tiempos para
   aceptarlo: aceptar escribe la agenda con la forma de `actividadDePropuesta`
   de nucleo.js, y copiar esa lógica acá sería un dato en dos lugares. Es el
   mismo filtro que `mias()` de propone.js: si cambia allá, cambia acá. */
data class Propuesta(val id: String, val clase: String, val resumen: String, val titulo: String, val dia: String, val hora: String)

val CLASES_PROPONE = listOf("agenda", "consulta")

fun consultaPropuestas(): JSONObject = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", "propuestas")))
    .put("where", JSONObject().put("fieldFilter", JSONObject()
        .put("field", JSONObject().put("fieldPath", "estado"))
        .put("op", "EQUAL")
        .put("value", JSONObject().put("stringValue", "pendiente")))))

/** `paraMi` de nucleo.js: datos.para es un uid o una lista de uids. */
fun propuestaParaMi(datos: JSONObject?, uid: String): Boolean {
    val para = datos?.optJSONObject("para") ?: return false
    if (para.has("stringValue")) return para.optString("stringValue") == uid
    val vs = para.optJSONObject("arrayValue")?.optJSONArray("values") ?: return false
    return (0 until vs.length()).any { vs.optJSONObject(it)?.optString("stringValue") == uid }
}

fun propuestasDeConsulta(respuesta: JSONArray, uid: String): List<Propuesta> =
    (0 until respuesta.length()).mapNotNull { i ->
        val d = respuesta.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
        val f = d.optJSONObject("fields") ?: return@mapNotNull null
        val clase = texto(f, "clase").orEmpty()
        if (texto(f, "estado") != "pendiente" || clase !in CLASES_PROPONE) return@mapNotNull null
        val datos = f.optJSONObject("datos")?.optJSONObject("mapValue")?.optJSONObject("fields")
        if (!propuestaParaMi(datos, uid)) return@mapNotNull null
        val dt = datos ?: JSONObject()
        Propuesta(d.optString("name").substringAfterLast('/'), clase, texto(f, "resumen").orEmpty().take(200),
            (texto(dt, "titulo") ?: texto(dt, "texto")).orEmpty().take(120),
            texto(dt, "dia").orEmpty(), texto(dt, "hi").orEmpty())
    }.sortedWith(compareBy<Propuesta>({ it.dia.ifEmpty { "9999" } }, { it.hora }))
