package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

// ─────────────────────────────────────────────────────────────────────────────
// AgendaLogica.kt — La agenda de la semana, para el widget. Sello: pizarra-21
//
// Pedido de Mauro, 12-oct-2026: «quiero dos widgets en la Pizarra: uno para la
// pizarra y otro para la agenda semanal de ese usuario». Lo que muestra es la
// MISMA agenda de Tiempos (`piezasDelDia` de agenda.js), las tres clases:
//   · sus actividades   — `agendas/{uid}.actividades` (título, día, desde, hf)
//   · sus tareas        — `agendas/{uid}.items["f:<tarea>"]` (día, hora…),
//                         con el título de la tarea; una sin día flota en hoy,
//                         y una hecha no va, como allá (`ubicarEnSemana`)
//   · las de los chicos — `eventos/`, de una fecha o de todas las semanas
//                         (`eventosDelDia` de nucleo.js: `semanal`, `hasta`,
//                         `excepto`)
//   · sus alarmas y recordatorios — `alertas/` (pizarra-21, Mauro: «que el
//                         widget de agenda muestre las alarmas»), en su día y a
//                         su hora, como renglón propio con ⏰ o 🔔
// Lo de Casa Verde (`cv:`) vive en la base de Casa Verde, y el widget entra
// sólo a la de Tiempos: no va, y la pantalla lo dice.
//
// No toca red ni Android: lo corre el banco. Si cambia cómo Tiempos guarda una
// actividad, una tarea en la agenda o un evento, cambia acá en la misma tanda.
// ─────────────────────────────────────────────────────────────────────────────

data class EventoChicos(
    val id: String, val titulo: String, val fecha: String, val hora: String, val horaFin: String,
    val semanal: Boolean, val hasta: String, val excepto: List<String>, val lugar: String,
)

/** Un renglón del widget: el encabezado de un día, o una cosa de ese día. */
data class FilaAgenda(
    val dia: String, val hora: String, val titulo: String, val sub: String,
    val clase: String,                     // "dia" | "act" | "tarea" | "chicos" | "alerta"
    val hoy: Boolean = false,
)

private val DIAS = listOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado")
private val ISO = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val NOMBRE_CLASE = mapOf("trabajo" to "Trabajo", "tarea" to "Tarea",
    "personal" to "Personal", "ninos" to "Con los chicos")

fun sumarDiasIso(iso: String, n: Int): String = LocalDate.parse(iso).plusDays(n.toLong()).toString()
/** 0 = domingo, como `getDay()` de JavaScript. */
fun diaSemanaIso(iso: String): Int = LocalDate.parse(iso).dayOfWeek.value % 7
fun nombreDia(iso: String): String = "${DIAS[diaSemanaIso(iso)]} ${iso.substring(8).toInt()}/${iso.substring(5, 7).toInt()}"

private fun txt(f: JSONObject?, c: String): String =
    f?.optJSONObject(c)?.let { if (it.has("stringValue")) it.optString("stringValue") else null }.orEmpty()
private fun bool(f: JSONObject?, c: String): Boolean = f?.optJSONObject(c)?.optBoolean("booleanValue", false) ?: false
private fun mapa(f: JSONObject?, c: String): JSONObject? = f?.optJSONObject(c)?.optJSONObject("mapValue")?.optJSONObject("fields")

fun eventosDeConsulta(r: JSONArray): List<EventoChicos> = (0 until r.length()).mapNotNull { i ->
    val d = r.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
    val f = d.optJSONObject("fields") ?: return@mapNotNull null
    val fecha = txt(f, "fecha")
    if (!ISO.matches(fecha)) return@mapNotNull null
    val exc = f.optJSONObject("excepto")?.optJSONObject("arrayValue")?.optJSONArray("values")
    EventoChicos(
        id = d.optString("name").substringAfterLast('/'), titulo = txt(f, "titulo").ifEmpty { "Actividad" }.take(120),
        fecha = fecha, hora = txt(f, "hora"), horaFin = txt(f, "horaFin"), semanal = bool(f, "semanal"),
        hasta = txt(f, "hasta"), lugar = txt(f, "lugar"),
        excepto = if (exc == null) emptyList() else (0 until exc.length()).map { exc.optJSONObject(it)?.optString("stringValue").orEmpty() },
    )
}

/** Lo mismo que `eventosDelDia` de nucleo.js. */
fun eventoEnDia(e: EventoChicos, iso: String): Boolean {
    if (iso in e.excepto) return false
    if (!e.semanal) return e.fecha == iso
    if (iso < e.fecha || (ISO.matches(e.hasta) && iso > e.hasta)) return false
    return diaSemanaIso(iso) == diaSemanaIso(e.fecha)
}

private fun hhmm(t: String): String = if (t.length >= 16) t.substring(11, 16) else ""
private fun rango(desde: String, hasta: String) = if (desde.isNotEmpty() && hasta.isNotEmpty()) "$desde–$hasta" else desde

/**
 * La semana desde `hoy`, día por día. `agenda` son los `fields` del documento
 * `agendas/{uid}` (o null si todavía no tiene). Cada día lleva su encabezado,
 * aunque esté libre: así se ve la semana entera de un vistazo.
 */
fun agendaDeLaSemana(hoy: String, agenda: JSONObject?, eventos: List<EventoChicos>, tareas: List<Tarea>, dias: Int = 7,
                     alertas: List<Alerta> = emptyList()): List<FilaAgenda> {
    val porId = tareas.associateBy { it.id }
    val porDia = mutableMapOf<String, MutableList<FilaAgenda>>()
    val ultimo = sumarDiasIso(hoy, dias - 1)
    fun meter(d: String, f: FilaAgenda) { if (d in hoy..ultimo) porDia.getOrPut(d) { mutableListOf() }.add(f) }

    mapa(agenda, "actividades")?.let { acts ->
        for (id in acts.keys()) {
            val a = acts.optJSONObject(id)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
            val d = txt(a, "dia")
            if (!ISO.matches(d)) continue
            val hf = txt(a, "hf")
            meter(d, FilaAgenda(d, rango(hhmm(txt(a, "desde")), if (hf.isEmpty()) "" else hhmm(txt(a, "hasta"))),
                txt(a, "titulo").ifEmpty { "Actividad" }.take(120),
                listOf(NOMBRE_CLASE[txt(a, "tipo")].orEmpty(), txt(a, "lugar")).filter { it.isNotEmpty() }.joinToString(" · "), "act"))
        }
    }
    mapa(agenda, "items")?.let { items ->
        for (clave in items.keys()) {
            if (!clave.startsWith("f:")) continue                  // lo de Casa Verde vive en su base
            val m = items.optJSONObject(clave)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
            val t = porId[clave.removePrefix("f:")] ?: continue
            if (t.hecho) continue
            val dia = txt(m, "dia")
            val flota = !ISO.matches(dia)
            val d = if (flota) hoy else dia
            val padre = t.parentId?.let { porId[it]?.titulo }
            meter(d, FilaAgenda(d, rango(txt(m, "hora"), txt(m, "hasta")), if (padre != null) "$padre › ${t.titulo}" else t.titulo,
                listOf("Tarea", txt(m, "lugar"), if (flota) "sin día" else "").filter { it.isNotEmpty() }.joinToString(" · "), "tarea"))
        }
    }
    for (i in 0 until dias) {
        val d = sumarDiasIso(hoy, i)
        for (e in eventos) if (eventoEnDia(e, d))
            meter(d, FilaAgenda(d, rango(e.hora, e.horaFin), e.titulo,
                listOf("Con los chicos", e.lugar).filter { it.isNotEmpty() }.joinToString(" · "), "chicos"))
    }

    for (a in alertas)
        meter(a.dia, FilaAgenda(a.dia, a.hora, (if (a.tipo == "alarma") "⏰ " else "🔔 ") + a.texto,
            if (a.tipo == "alarma") "Alarma" else "Recordatorio", "alerta"))

    val out = mutableListOf<FilaAgenda>()
    for (i in 0 until dias) {
        val d = sumarDiasIso(hoy, i)
        val del = porDia[d].orEmpty().sortedWith(compareBy({ it.hora.ifEmpty { "99" } }, { it.titulo.lowercase() }))
        val nombre = (if (i == 0) "Hoy · " else if (i == 1) "Mañana · " else "") + nombreDia(d)
        out.add(FilaAgenda(d, "", if (del.isEmpty()) "$nombre · libre" else nombre, "", "dia", hoy = i == 0))
        out.addAll(del)
    }
    return out
}

fun filasAgendaAJson(l: List<FilaAgenda>): String = JSONArray().apply {
    l.forEach { put(JSONObject().put("d", it.dia).put("h", it.hora).put("t", it.titulo).put("s", it.sub).put("c", it.clase).put("y", it.hoy)) }
}.toString()

fun filasAgendaDeJson(s: String?): List<FilaAgenda> = try {
    val a = JSONArray(s ?: "[]")
    (0 until a.length()).map { i -> a.getJSONObject(i).let {
        FilaAgenda(it.optString("d"), it.optString("h"), it.optString("t"), it.optString("s"), it.optString("c"), it.optBoolean("y")) } }
} catch (e: Exception) { emptyList() }

/** El cuerpo de un `batchGet` del documento de mi agenda. */
fun pedidoAgenda(uid: String): JSONObject =
    JSONObject().put("documents", JSONArray().put("$BASE_DOCS/agendas/$uid"))

/** Los `fields` de mi agenda, de la respuesta del `batchGet` (null si no existe). */
fun agendaDeRespuesta(r: JSONArray): JSONObject? =
    (0 until r.length()).firstNotNullOfOrNull { r.optJSONObject(it)?.optJSONObject("found")?.optJSONObject("fields") }

/** Todas las actividades de los chicos: las ven los dos. */
fun consultaEventos(): JSONObject =
    JSONObject().put("structuredQuery", JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", "eventos"))))
