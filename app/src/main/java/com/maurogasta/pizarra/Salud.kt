package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// Salud.kt — El PARTE DE SALUD de la comunicación de este teléfono. Sello: pizarra-78
//
// Mauro, 28-oct-2026: «que esa misma herramienta pueda encontrar errores que
// estén sucediendo dentro del programa al hacer las comunicaciones entre los
// teléfonos, para confirmar que no se estén perdiendo datos, mensajes o
// transmisiones de handy entre uno y otro, como mantenimiento».
//
// La base sabe lo que se MANDÓ; lo que LLEGÓ, sonó o se cortó lo sabe sólo cada
// teléfono. Este parte lo cuenta, una vez por hora, en `salud/<uid>|<aparato>`
// de Tiempos (reglas v24), y `charla.mjs salud` de `datos` lo cruza con lo
// mandado. Lleva:
//   · CONTADORES: rondas de handy cerca mandadas y oídas, sus huecos (trozos
//     que no llegaron), los acuses («la escuchó» / «no sonó: …»), cortes de la
//     conexión cerca, otro código, audios mandados y fallidos, handy por
//     internet que sonó o no (y por qué);
//   · RECIBIDOS: los ids de los mensajes para este aparato de los últimos 7
//     días que están en su lista (así se ve si alguno no llegó);
//   · EVENTOS: los renglones de la bitácora que son de comunicación (enlace,
//     radio, handy, mensajes, llamada, archivo, despertar), los últimos 40.
// NUNCA lleva texto de un mensaje ni sonido: sólo ids, números y lo que la
// bitácora ya anota (que tampoco lleva contenido). Cupo: una escritura por hora
// por teléfono (240 por día con 10), y lo lee el agente al auditar.
// ─────────────────────────────────────────────────────────────────────────────

const val SALUD_CADA_MS = 3600_000L
const val SALUD_EVENTOS = 40
const val SALUD_RECIBIDOS = 80
const val COLECCION_SALUD = "salud"
/** Lo que de la bitácora es de comunicación. */
private val PREFIJOS_COMUNICACION = listOf("enlace", "radio", "handy", "mensajes", "llamada", "archivo", "despertar", "compartir")

fun esDeComunicacion(renglon: String): Boolean = PREFIJOS_COMUNICACION.any { renglon.startsWith(it) }

/** Los mensajes para este aparato (de otro, de los últimos 7 días) que esta Pizarra tiene: sus ids. */
fun recibidosPara(ms: List<Mensaje>, yo: String, miTelefono: String, ahora: Long): List<String> = ms.filter { m ->
    !(m.de == yo && m.dispositivo == miTelefono) && esParaMi(m, yo, miTelefono) &&
        (instante(m.creado)?.let { ahora - it <= 7 * 24 * 3600_000L } ?: false)
}.sortedByDescending { it.creado }.take(SALUD_RECIBIDOS).map { it.id }

/** El documento del parte, con la forma EXACTA que deja la regla v24 de Tiempos. */
fun cuerpoSalud(docs: String, uid: String, dispositivo: String, sello: String, contadores: Map<String, Int>,
                recibidos: List<String>, eventos: List<String>, charlaHasta: String, enlace: String): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    fun lista(l: List<String>, tope: Int, largo: Int) = JSONObject().put("arrayValue", JSONObject().put("values",
        JSONArray().apply { l.take(tope).forEach { put(s(it.take(largo))) } }))
    val c = JSONObject().apply { contadores.toSortedMap().entries.take(60).forEach { (k, v) -> put(k.take(40), JSONObject().put("integerValue", v.toString())) } }
    val f = JSONObject().put("de", s(uid)).put("dispositivo", s(dispositivo.take(60))).put("sello", s(sello.take(20)))
        .put("contadores", JSONObject().put("mapValue", JSONObject().put("fields", c)))
        .put("recibidos", lista(recibidos, SALUD_RECIBIDOS, 40))
        .put("eventos", lista(eventos, SALUD_EVENTOS, 160))
        .put("charlaHasta", s(charlaHasta.take(40))).put("enlace", s(enlace.take(120)))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_SALUD/${idSalud(uid, dispositivo)}").put("fields", f))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "actualizado").put("setToServerValue", "REQUEST_TIME")))))
}

/** El id del parte: el aparato de esa cuenta. Sin «/», que partiría la ruta. */
fun idSalud(uid: String, dispositivo: String) = (uid + "|" + dispositivo).replace("/", "-").take(120)

/**
 * pizarra-78: la voz que llega, ronda por ronda: devuelve si empezó una ronda nueva y cuántos trozos se
 * perdieron antes de éste. [visto] guarda, por quien habla, su ronda y el trozo que se esperaba.
 */
fun huecosDeVoz(visto: MutableMap<String, Pair<String, Int>>, de: String, ronda: String, n: Int): Pair<Boolean, Int> {
    val (r, esperado) = visto[de] ?: ("" to 0)
    val nueva = r != ronda
    val desde = if (nueva) 0 else esperado
    visto[de] = ronda to (maxOf(desde, n) + 1)
    return nueva to maxOf(0, n - desde)
}

/** pizarra-78: qué contador suma un acuse que llega («la escuchó», «no sonó: …», «llegó»). */
fun contadorDeAcuse(o: JSONObject): String {
    val como = o.optString("como")
    return when (o.optString("que")) {
        "voz" -> if (como.startsWith("no sonó")) "voz.acuse.noSono" else "voz.acuse.escucho"
        "prueba" -> "prueba.acuse"
        "archivo" -> "archivo.acuse"
        else -> "acuse.otro"
    }
}

object Salud {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("salud", Context.MODE_PRIVATE)

    /** Suma [n] a un contador. Nunca tira. */
    fun contar(c: Context, clave: String, n: Int = 1) {
        try {
            synchronized(this) {
                val p = prefs(c)
                val m = JSONObject(p.getString("contadores", "{}") ?: "{}")
                m.put(clave, m.optInt(clave) + n)
                p.edit().putString("contadores", m.toString()).apply()
            }
        } catch (e: Throwable) { }
    }

    fun contadores(c: Context): Map<String, Int> = try {
        val m = JSONObject(prefs(c).getString("contadores", "{}") ?: "{}")
        m.keys().asSequence().associateWith { m.optInt(it) }
    } catch (e: Throwable) { emptyMap() }

    /** Lo de comunicación de la bitácora, los últimos SALUD_EVENTOS (lo llama Bitacora.anotar). */
    fun evento(c: Context, renglon: String) {
        if (!esDeComunicacion(renglon)) return
        try {
            synchronized(this) {
                val p = prefs(c)
                val a = JSONArray(p.getString("eventos", "[]") ?: "[]")
                val l = (0 until a.length()).map { a.optString(it) } + renglon.take(160)
                p.edit().putString("eventos", JSONArray(l.takeLast(SALUD_EVENTOS)).toString()).apply()
            }
        } catch (e: Throwable) { }
    }

    fun eventos(c: Context): List<String> = try {
        val a = JSONArray(prefs(c).getString("eventos", "[]") ?: "[]"); (0 until a.length()).map { a.optString(it) }
    } catch (e: Throwable) { emptyList() }

    /** Sube el parte si pasó SALUD_CADA_MS (o si [ya]). En el hilo de quien llama; nunca tira. */
    fun subir(c: Context, ya: Boolean = false) {
        try {
            val p = prefs(c)
            val ahora = System.currentTimeMillis()
            if (!ya && ahora - p.getLong("subido", 0L) < SALUD_CADA_MS) return
            val n = Nube(c)
            if (!n.conSesion || !n.esTiempos) return
            val yo = n.uid ?: return
            val tel = nombreDelTelefono(c)
            val ms = with(Charla) { c.mensajesGuardados }
            val enlace = if (!EnlaceAjustes.prendido(c)) "apagado"
                else "${EnlaceServicio.estado} · llave ${if (EnlaceAjustes.porCuenta(c) != null) "de la cuenta" else "a mano"}"
            n.subirSalud(cuerpoSalud(BASE_DOCS, yo, tel, SELLO, contadores(c), recibidosPara(ms, yo, tel, ahora), eventos(c),
                ms.maxOfOrNull { it.creado }.orEmpty(), enlace))
            p.edit().putLong("subido", ahora).apply()
        } catch (e: Throwable) { }
    }
}
