package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

// ─────────────────────────────────────────────────────────────────────────────
// Disponibilidad.kt — Que la respuesta a un pedido de Facebook diga la verdad
// sobre la ocupación de Casa Verde. Sello: pizarra-51
//
// 20-oct-2026, Mauro: «tiene que mirar disponibilidad al inicio de las
// búsquedas, en esa sesión, una sola vez, y buscar que coincida con la
// disponibilidad en Casa Verde la respuesta al pedido de Facebook». Pasó con
// un pedido del 13 al 21 de enero: la respuesta rápida ofrecía lugar y las
// tres cabañas estaban ocupadas.
//
// UNA VEZ POR SESIÓN: al abrir Facebook después de media hora sin abrirlo, la
// Pizarra trae `disponibilidad/` y `cabanas/` de Casa Verde (las dos son de
// lectura pública: son el espejo del sitio) y las guarda. Con eso decide, al
// tocar «Comentar»:
//   · fechas que se entienden y hay lugar → la respuesta dice que hay lugar
//     para esas fechas (y en cuántas cabañas, si hace falta más de una);
//   · fechas que se entienden y NO hay lugar → no se copia ni se guarda nada,
//     y el aviso silencioso dice que esas fechas están ocupadas;
//   · un mes suelto → si hay noches libres ese mes, lo dice;
//   · sin fechas → la respuesta de siempre, que invita a mirar la página.
// Lo que no se puede leer del texto no se inventa.
// ─────────────────────────────────────────────────────────────────────────────

data class Ocupacion(val cabana: String, val desde: LocalDate, val hasta: LocalDate)   // hasta = día de salida
data class CabanaCV(val id: String, val max: Int)

/** Lo que pide el texto: un rango de noches, o un mes entero si sólo dice el mes. */
sealed class Pedido {
    data class Rango(val desde: LocalDate, val hasta: LocalDate) : Pedido()
    data class Mes(val mes: YearMonth) : Pedido()
}

/** pizarra-51: lo que SÍ se puede ofrecer cuando lo pedido está ocupado. `parcial`: una parte
 *  de las mismas fechas; si no, otras fechas con las mismas noches. `personas`: para cuántos hay
 *  lugar en esas fechas, cuando lo que no alcanza es el cupo. */
data class Alternativa(val desde: LocalDate, val hasta: LocalDate, val cabanas: List<CabanaCV>, val parcial: Boolean, val personas: Int = 0)

/** Lo que se puede ofrecer. */
sealed class Lugar {
    data class Hay(val cabanas: List<CabanaCV>, val desde: LocalDate, val hasta: LocalDate) : Lugar()
    data class Lleno(val desde: LocalDate, val hasta: LocalDate, val porCupo: Boolean, val alt: Alternativa? = null) : Lugar()
    data class MesConLugar(val mes: YearMonth) : Lugar()
    data class MesLleno(val mes: YearMonth, val alt: Alternativa? = null) : Lugar()
    object SinFechas : Lugar()
}

/* La cabaña 1 tiene `capacidad: 1` en la base (las otras, {base, max}). Hasta que
   se corrija, vale lo que completa las 20 personas que dijo Mauro. */
private val CAPACIDAD_SI_FALTA = mapOf("c1" to 8, "c2" to 7, "c3" to 5)

private fun plano(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

private val MESES = linkedMapOf(
    "enero" to 1, "janeiro" to 1, "febrero" to 2, "fevereiro" to 2, "marzo" to 3, "marco" to 3, "abril" to 4,
    "mayo" to 5, "maio" to 5, "junio" to 6, "junho" to 6, "julio" to 7, "julho" to 7, "agosto" to 8,
    "septiembre" to 9, "setiembre" to 9, "setembro" to 9, "octubre" to 10, "outubro" to 10,
    "noviembre" to 11, "novembro" to 11, "diciembre" to 12, "dezembro" to 12)
private val MES = MESES.keys.joinToString("|")
private const val HASTA = "(?:al|a|hasta|ate|y el|-|–)"

/** El año de un mes sin año: el próximo que todavía no pasó. */
private fun anio(mes: Int, dia: Int, hoy: LocalDate, explicito: String?): Int {
    // Un año escrito que ya pasó es un error de tipeo («24/01/24» en octubre de 2026): manda el próximo.
    explicito?.toIntOrNull()?.let { val a = if (it < 100) 2000 + it else it; if (a >= hoy.year) return a }
    val esteAnio = runCatching { LocalDate.of(hoy.year, mes, dia.coerceAtMost(YearMonth.of(hoy.year, mes).lengthOfMonth())) }.getOrNull()
    return if (esteAnio != null && esteAnio.isBefore(hoy)) hoy.year + 1 else hoy.year
}

private fun fecha(d: Int, m: Int, hoy: LocalDate, a: String?): LocalDate? =
    runCatching { LocalDate.of(anio(m, d, hoy, a), m, d) }.getOrNull()

private fun rango(desde: LocalDate?, hasta: LocalDate?): Pedido? {
    if (desde == null || hasta == null) return null
    val h = if (!hasta.isAfter(desde)) hasta.plusYears(1) else hasta     // «28/12 al 03/01»
    return if (h.isAfter(desde) && desde.until(h).toTotalMonths() < 3) Pedido.Rango(desde, h) else null
}

/** Pascua (algoritmo de Meeus/Butcher), para Semana Santa y Carnaval. */
fun pascua(anio: Int): LocalDate {
    val a = anio % 19; val b = anio / 100; val c = anio % 100; val d = b / 4; val e = b % 4
    val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
    val mes = (h + l - 7 * m + 114) / 31; val dia = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(anio, mes, dia)
}

/** Qué fechas pide el texto, o null si no se entiende. */
fun fechasPedidas(texto: String, hoy: LocalDate): Pedido? {
    val t = plano(texto)
    // «13/01 al 21/01», «04.01 al 12.01», «28-12-2026 al 3-1»
    Regex("(\\d{1,2})[./-](\\d{1,2})(?:[./-](\\d{2,4}))?\\s*$HASTA\\s*(?:el\\s*)?(\\d{1,2})[./-](\\d{1,2})(?:[./-](\\d{2,4}))?").find(t)?.let { m ->
        val g = m.groupValues
        rango(fecha(g[1].toInt(), g[2].toInt(), hoy, g[3].ifEmpty { null }), fecha(g[4].toInt(), g[5].toInt(), hoy, g[6].ifEmpty { g[3].ifEmpty { null } }))?.let { return it }
    }
    // «del 28 de diciembre al 3 de enero», «del 10 al 23 de enero», «de 10 a 15 de janeiro»
    Regex("(\\d{1,2})(?:\\s*de\\s*($MES))?\\s*$HASTA\\s*(?:el\\s*)?(\\d{1,2})\\s*(?:de\\s*)?($MES)(?:\\s*(?:de\\s*)?(\\d{4}))?").find(t)?.let { m ->
        val g = m.groupValues
        val mh = MESES.getValue(g[4]); val md = if (g[2].isNotEmpty()) MESES.getValue(g[2]) else mh
        rango(fecha(g[1].toInt(), md, hoy, g[5].ifEmpty { null }), fecha(g[3].toInt(), mh, hoy, g[5].ifEmpty { null }))?.let { return it }
    }
    // «enero 10 al 23», «janeiro de 5 a 12»
    Regex("($MES)\\s*(?:de\\s*|del\\s*)?(\\d{1,2})\\s*$HASTA\\s*(?:el\\s*)?(\\d{1,2})\\b").find(t)?.let { m ->
        val g = m.groupValues; val mes = MESES.getValue(g[1])
        rango(fecha(g[2].toInt(), mes, hoy, null), fecha(g[3].toInt(), mes, hoy, null))?.let { return it }
    }
    // «primera quincena de marzo», «segunda quinzena de março»
    Regex("(primera|primer|primeira|1ra|1a|1era|segunda|2da|2a)\\s*quin(?:cena|zena)\\s*(?:de\\s*)?($MES)(?:\\s*(?:de\\s*)?(\\d{4}))?").find(t)?.let { m ->
        val g = m.groupValues; val mes = MESES.getValue(g[2]); val primera = g[1].startsWith("p") || g[1].startsWith("1")
        val a = anio(mes, if (primera) 1 else 16, hoy, g[3].ifEmpty { null })
        val ym = YearMonth.of(a, mes)
        return if (primera) Pedido.Rango(ym.atDay(1), ym.atDay(16)) else Pedido.Rango(ym.atDay(16), ym.plusMonths(1).atDay(1))
    }
    // Las fechas que se dicen por su nombre.
    if (Regex("\\bsemana santa\\b").containsMatchIn(t)) {
        val p = pascua(hoy.year).let { if (it.isBefore(hoy)) pascua(hoy.year + 1) else it }
        return Pedido.Rango(p.minusDays(3), p.plusDays(1))
    }
    if (Regex("\\bcarnaval\\b").containsMatchIn(t)) {
        val p = pascua(hoy.year).let { if (it.minusDays(46).isBefore(hoy)) pascua(hoy.year + 1) else it }
        return Pedido.Rango(p.minusDays(51), p.minusDays(46))
    }
    if (Regex("\\b(ano nuevo|reveillon|fin de ano|virada)\\b").containsMatchIn(t)) {
        val a = if (hoy.monthValue == 1 && hoy.dayOfMonth <= 2) hoy.year - 1 else hoy.year
        return Pedido.Rango(LocalDate.of(a, 12, 29), LocalDate.of(a + 1, 1, 2))
    }
    // Un mes suelto: «para enero», «em fevereiro».
    Regex("\\b(?:en|em|para|de|todo|mes de)\\s+($MES)(?:\\s*(?:de\\s*)?(\\d{4}))?\\b").find(t)?.let { m ->
        val mes = MESES.getValue(m.groupValues[1])
        return Pedido.Mes(YearMonth.of(anio(mes, 28, hoy, m.groupValues[2].ifEmpty { null }), mes))
    }
    return null
}

private fun choca(o: Ocupacion, desde: LocalDate, hasta: LocalDate) = o.desde.isBefore(hasta) && o.hasta.isAfter(desde)

/** Las cabañas libres TODAS las noches del rango. */
fun libres(ocup: List<Ocupacion>, cabanas: List<CabanaCV>, desde: LocalDate, hasta: LocalDate): List<CabanaCV> =
    cabanas.filter { c -> ocup.none { it.cabana == c.id && choca(it, desde, hasta) } }

/** Las que alcanzan para `personas` (0 = no se sabe): una sola si entra, si no, las más grandes primero. */
fun alcanzan(libres: List<CabanaCV>, personas: Int): List<CabanaCV>? {
    if (libres.isEmpty()) return null
    if (personas <= 0) return listOf(libres.maxByOrNull { it.max }!!)
    libres.filter { it.max >= personas }.minByOrNull { it.max }?.let { return listOf(it) }
    val juntas = ArrayList<CabanaCV>()
    for (c in libres.sortedByDescending { it.max }) { juntas.add(c); if (juntas.sumOf { it.max } >= personas) return juntas }
    return null
}

private fun noches(d: LocalDate, h: LocalDate) = java.time.temporal.ChronoUnit.DAYS.between(d, h).toInt()

/** Lo que sí hay cuando el rango pedido está ocupado (pizarra-51, Mauro: «prepara una respuesta
 *  acorde diciendo lo que sí podemos ofrecer»). Primero las mismas noches corridas hasta una
 *  semana; si no, la parte libre más larga de lo pedido (la mitad o más); si no, las mismas
 *  noches dentro de los 45 días. Nunca antes de mañana. */
fun alternativa(desde: LocalDate, hasta: LocalDate, personas: Int, ocup: List<Ocupacion>, cabanas: List<CabanaCV>, hoy: LocalDate): Alternativa? {
    val n = noches(desde, hasta)
    fun prueba(d: LocalDate): Alternativa? {
        if (!d.isAfter(hoy)) return null
        val h = d.plusDays(n.toLong())
        return alcanzan(libres(ocup, cabanas, d, h), personas)?.let { Alternativa(d, h, it, false) }
    }
    fun corrida(max: Int): Alternativa? {
        for (k in 1..max) { prueba(desde.plusDays(k.toLong()))?.let { return it }; prueba(desde.minusDays(k.toLong()))?.let { return it } }
        return null
    }
    corrida(7)?.let { return it }
    // La parte libre más larga adentro de lo pedido.
    var mejor: Alternativa? = null
    for (i in 0 until n) for (j in n downTo i + 1) {
        val d = desde.plusDays(i.toLong()); val h = desde.plusDays(j.toLong())
        if (!d.isAfter(hoy)) continue
        val m = mejor
        if (m != null && noches(m.desde, m.hasta) >= j - i) break
        val a = alcanzan(libres(ocup, cabanas, d, h), personas)
        if (a != null) mejor = Alternativa(d, h, a, true)
    }
    mejor?.takeIf { noches(it.desde, it.hasta) * 2 >= n && noches(it.desde, it.hasta) >= 2 }?.let { return it }
    return corrida(45)
}

/** Lo que se puede ofrecer para este pedido. */
fun lugarPara(texto: String, personas: Int, ocup: List<Ocupacion>, cabanas: List<CabanaCV>, hoy: LocalDate): Lugar =
    when (val p = fechasPedidas(texto, hoy)) {
        null -> Lugar.SinFechas
        is Pedido.Rango -> {
            val l = libres(ocup, cabanas, p.desde, p.hasta)
            alcanzan(l, personas)?.let { Lugar.Hay(it, p.desde, p.hasta) } ?: run {
                // Lo que no alcanza es el cupo: se dice para cuántos SÍ hay lugar en esas fechas.
                val cupo = l.sumOf { it.max }
                val alt = if (l.isNotEmpty() && personas > cupo) Alternativa(p.desde, p.hasta, l, false, cupo)
                    else alternativa(p.desde, p.hasta, personas, ocup, cabanas, hoy)
                Lugar.Lleno(p.desde, p.hasta, l.isNotEmpty(), alt)
            }
        }
        is Pedido.Mes -> {
            // Hay lugar ese mes si alguna cabaña que alcanza tiene cinco noches seguidas libres.
            val dias = (1..p.mes.lengthOfMonth()).map { p.mes.atDay(it) }
            val sirven = cabanas.filter { personas <= 0 || it.max >= personas }
            val hay = sirven.any { c -> dias.windowed(5).any { w -> libres(ocup, listOf(c), w.first(), w.last().plusDays(1)).isNotEmpty() } }
            if (hay) Lugar.MesConLugar(p.mes)
            else Lugar.MesLleno(p.mes, (0..60).asSequence().map { p.mes.plusMonths(1).atDay(1).plusDays(it.toLong()) }
                .mapNotNull { d -> alcanzan(libres(ocup, sirven, d, d.plusDays(5)), personas)?.let { Alternativa(d, d.plusDays(5), it, false) } }.firstOrNull())
        }
    }

private val MESES_ES = listOf("", "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
private val MESES_PT = listOf("", "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

/** «del 13 al 21 de enero», «del 28 de diciembre al 3 de enero». */
fun rangoEnPalabras(desde: LocalDate, hasta: LocalDate, pt: Boolean): String {
    val m = if (pt) MESES_PT else MESES_ES
    val d = if (pt) "de" else "del"; val a = if (pt) "a" else "al"
    return if (desde.month == hasta.month) "$d ${desde.dayOfMonth} $a ${hasta.dayOfMonth} de ${m[hasta.monthValue]}"
    else "$d ${desde.dayOfMonth} de ${m[desde.monthValue]} $a ${hasta.dayOfMonth} de ${m[hasta.monthValue]}"
}

fun mesEnPalabras(mes: YearMonth, pt: Boolean) = (if (pt) MESES_PT else MESES_ES)[mes.monthValue]

/** De la respuesta de `runQuery`: los `fields` de cada documento, con su id. */
fun documentosDeConsulta(texto: String): List<Pair<String, JSONObject>> = try {
    val a = JSONArray(texto)
    (0 until a.length()).mapNotNull { i ->
        val d = a.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
        d.optString("name").substringAfterLast('/') to (d.optJSONObject("fields") ?: JSONObject())
    }
} catch (e: Exception) { emptyList() }

private fun cadena(f: JSONObject, k: String) = f.optJSONObject(k)?.optString("stringValue").orEmpty()

fun ocupacionesDe(docs: List<Pair<String, JSONObject>>): List<Ocupacion> = docs.mapNotNull { (_, f) ->
    val c = cadena(f, "cabanaId"); val d = runCatching { LocalDate.parse(cadena(f, "desde")) }.getOrNull()
    val h = runCatching { LocalDate.parse(cadena(f, "hasta")) }.getOrNull()
    if (c.isEmpty() || d == null || h == null || !h.isAfter(d)) null else Ocupacion(c, d, h)
}

fun cabanasDe(docs: List<Pair<String, JSONObject>>): List<CabanaCV> = docs.mapNotNull { (id, f) ->
    if (f.optJSONObject("activa")?.optBoolean("booleanValue", true) == false) return@mapNotNull null
    val cap = f.optJSONObject("capacidad")
    val max = cap?.optJSONObject("mapValue")?.optJSONObject("fields")?.optJSONObject("max")?.optString("integerValue")?.toIntOrNull()
        ?: cap?.optString("integerValue")?.toIntOrNull()?.takeIf { it > 1 }
        ?: CAPACIDAD_SI_FALTA[id] ?: return@mapNotNull null
    CabanaCV(id, max)
}

/** La ocupación guardada en el teléfono: se trae UNA vez por sesión de Facebook. */
object Ocupado {
    private const val SESION = 30 * 60_000L
    private fun p(c: Context) = c.getSharedPreferences("disponibilidad", Context.MODE_PRIVATE)

    fun ocupaciones(c: Context): List<Ocupacion> = try {
        val a = JSONArray(p(c).getString("ocup", "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
            runCatching { Ocupacion(it.getString("c"), LocalDate.parse(it.getString("d")), LocalDate.parse(it.getString("h"))) }.getOrNull() } }
    } catch (e: Exception) { emptyList() }

    fun cabanas(c: Context): List<CabanaCV> = try {
        val a = JSONArray(p(c).getString("cabanas", "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { CabanaCV(it.getString("id"), it.getInt("max")) } }
    } catch (e: Exception) { emptyList() }

    fun traida(c: Context): Long = p(c).getLong("en", 0)

    /** ¿Empieza una sesión? (Facebook abierto después de media hora sin abrirlo.) Anota la hora. */
    @Synchronized fun empiezaSesion(c: Context, ahora: Long = System.currentTimeMillis()): Boolean {
        val antes = p(c).getLong("visto", 0)
        p(c).edit().putLong("visto", ahora).apply()
        return ahora - antes > SESION
    }

    /** Trae la ocupación y las cabañas. Nunca tira: si falla, queda lo de la vez anterior. */
    fun traer(c: Context) {
        try {
            val cv = CuentaCasaVerde(c)
            if (!cv.conSesion) return
            val oc = ocupacionesDe(cv.coleccion("disponibilidad"))
            val cb = cabanasDe(cv.coleccion("cabanas"))
            if (cb.isEmpty()) return
            p(c).edit()
                .putString("ocup", JSONArray(oc.map { JSONObject().put("c", it.cabana).put("d", it.desde.toString()).put("h", it.hasta.toString()) }).toString())
                .putString("cabanas", JSONArray(cb.map { JSONObject().put("id", it.id).put("max", it.max) }).toString())
                .putLong("en", System.currentTimeMillis()).apply()
            Bitacora.anotar(c, "disponibilidad: ${oc.size} ocupaciones y ${cb.size} cabañas, al empezar la sesión")
        } catch (t: Throwable) {
            Bitacora.anotar(c, "disponibilidad: no se pudo traer: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}
