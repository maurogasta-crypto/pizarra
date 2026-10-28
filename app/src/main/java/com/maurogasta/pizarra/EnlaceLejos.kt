package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// EnlaceLejos.kt — El enlace cuando la otra Pizarra NO está cerca: por
// internet, por la base de Tiempos. Sello: pizarra-71
//
// general:C4, paso 4 (Mauro, 26-oct-2026: «que pase reportes al teléfono…
// reconociendo el teléfono que está funcionando como detector para la
// camioneta, que pueda estar en cualquier lado»). Si la Pizarra del detector
// no tiene a nadie cerca (ni por el wi-fi ni por Bluetooth), lo que tenía para
// mandar va por acá:
//   · un EVENTO (empezó o terminó un viaje, una línea fuera de lo normal, una
//     prueba) se guarda en `enlace/<par>/cosas/e-<t>` y despierta a las otras
//     Pizarras con el aviso instantáneo de siempre («todo»): la otra lo trae
//     con su sesión y suena, igual que si hubiera llegado cerca;
//   · el VIVO (velocidad, km, RPM) sube SÓLO si alguien lo está mirando: la
//     pantalla «🚙 En vivo» deja `mira` cada minuto mientras está abierta, y el
//     detector escribe `vivo` cada 10 s mientras vea una `mira` de menos de
//     90 s. Nadie mira, nada se escribe.
//
// ── EL PAR ───────────────────────────────────────────────────────────────────
// Las dos Pizarras se reconocen por el MISMO código del enlace cerca: `par`
// es un resumen de ese código (no el código: el código no sale nunca del
// teléfono). Un código = un par, cerca y lejos.
//
// ── POR QUÉ NO EL CANAL DIRECTO (WebRTC) ──────────────────────────────────────
// El estudio proponía primero un canal directo y la base como plan B. Se hizo
// sólo la base, a propósito: anda SIEMPRE que haya internet (el directo, con
// los dos en 4G y sin TURN, conecta la mitad de las veces), cabe holgado en el
// cupo gratis, y lo que se pierde es medio segundo de demora en un dato que se
// muestra cada 10 s. El directo queda para si algún día 10 s no alcanza.
//
// ── CUPO (Firebase Spark: 50.000 lecturas y 20.000 escrituras por día) ───────
// Un evento: 1 escritura y 1 lectura por cada otra Pizarra. Mirar en vivo:
// 6 lecturas y 7 escrituras por minuto mientras la pantalla está abierta, y el
// detector lee la `mira` una vez por minuto mientras mide un viaje (60 por
// hora). Ponerse al día cada 10 minutos: 1 lectura. Para 10 personas con un
// detector cada una y media hora de mirar por día: menos de 10.000 de cada
// una. Lo de cada uno se borra a los 7 días.
//
// **Ninguna coordenada**: lo que sube sale de `mensajeDeApp`, que las saca, y
// la regla (v21 de Tiempos) sólo deja los campos de abajo.
// ─────────────────────────────────────────────────────────────────────────────

const val COLECCION_ENLACE = "enlace"
const val SUBCOLECCION_ENLACE = "cosas"
/** Cada cuánto sube el vivo mientras alguien mira. */
const val VIVO_LEJOS_CADA_MS = 10_000L
/** Una `mira` vale esto: la pantalla la renueva cada minuto. */
const val MIRA_VALE_MS = 90_000L
const val MIRA_CADA_MS = 60_000L
const val VIDA_ENLACE_MS = 7 * 24 * 3600_000L
/** Los tipos que viajan por la base: la voz no (es en vivo y cerca). */
val TIPOS_LEJOS = setOf("evento", "prueba", "vivo", "mira")
/** Los campos de un documento de `enlace/`: los mismos que deja la regla v21 de Tiempos. */
val CAMPOS_ENLACE = listOf("de", "dispositivo", "tipo", "app", "titulo", "texto", "kmh", "km", "rpm", "marcha", "t", "creadoEn")

/** El par: un resumen del código, el mismo en las dos Pizarras. Nunca el código. */
fun parDe(codigo: String): String =
    MessageDigest.getInstance("SHA-256").digest("pizarra-enlace-par:$codigo".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

/** El documento de un mensaje: `e-<t>` para un evento, `vivo` y `mira` fijos (se pisan). */
fun idEnlace(o: JSONObject): String? = when (o.optString("tipo")) {
    "evento", "prueba" -> "e-" + o.optLong("t")
    "vivo" -> "vivo"
    "mira" -> "mira"
    else -> null
}

/** Escribir un mensaje en la base, con la forma exacta de la regla. `de` es la cuenta; el teléfono va en `dispositivo`. */
fun cuerpoEnlace(docs: String, par: String, uid: String, o: JSONObject): JSONObject? {
    val id = idEnlace(o) ?: return null
    fun s(v: String) = JSONObject().put("stringValue", v)
    fun n(v: Long) = JSONObject().put("integerValue", v.toString())
    val f = JSONObject().put("de", s(uid)).put("dispositivo", s(o.optString("de").take(60)))
        .put("tipo", s(o.optString("tipo"))).put("t", n(o.optLong("t")))
    if (o.has("app")) f.put("app", s(o.optString("app").take(20)))
    if (o.has("titulo")) f.put("titulo", s(o.optString("titulo").take(80)))
    if (o.has("texto")) f.put("texto", s(o.optString("texto").take(300)))
    if (o.has("kmh")) f.put("kmh", JSONObject().put("doubleValue", o.optDouble("kmh")))
    if (o.has("km")) f.put("km", JSONObject().put("doubleValue", o.optDouble("km")))
    if (o.has("rpm")) f.put("rpm", n(o.optDouble("rpm").toLong()))
    if (o.has("marcha")) f.put("marcha", n(o.optLong("marcha")))
    val w = JSONObject().put("update", JSONObject().put("name", "$docs/$COLECCION_ENLACE/$par/$SUBCOLECCION_ENLACE/$id").put("fields", f))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
    // Un evento nunca pisa a otro; el vivo y la mira se pisan a propósito.
    if (id.startsWith("e-")) w.put("currentDocument", JSONObject().put("exists", false))
    return JSONObject().put("writes", JSONArray().put(w))
}

/**
 * pizarra-75 (reglas v22 de Tiempos): la llave del enlace sale de la CUENTA, no de un código a mano. Es un
 * código como los de siempre, guardado una vez en `enlace_llave/familia`; lo crea la primera Pizarra que lo
 * pide, sin pisar uno que ya esté, y nadie lo cambia. Lo leen sólo los miembros: el agente no.
 */
const val COLECCION_LLAVE = "enlace_llave"
fun cuerpoLlaveFamilia(docs: String, uid: String, codigo: String): JSONObject =
    JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_LLAVE/familia").put("fields", JSONObject()
            .put("codigo", JSONObject().put("stringValue", codigo)).put("creadoPor", JSONObject().put("stringValue", uid))))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))

/** El código guardado en la respuesta de un batchGet de `enlace_llave/familia`, si está y tiene forma. */
fun codigoDeLlave(r: JSONArray): String? =
    r.optJSONObject(0)?.optJSONObject("found")?.optJSONObject("fields")?.optJSONObject("codigo")?.optString("stringValue")
        ?.let { normalizarCodigo(it) }

/** Lo que llegó después de [desde] (por `t`, sin índice compuesto). */
fun consultaEnlace(desde: Long, antes: Long? = null): JSONObject {
    fun filtro(op: String, v: Long) = JSONObject().put("fieldFilter", JSONObject()
        .put("field", JSONObject().put("fieldPath", "t")).put("op", op).put("value", JSONObject().put("integerValue", v.toString())))
    val donde = if (antes == null) filtro("GREATER_THAN", desde) else filtro("LESS_THAN", antes)
    return JSONObject().put("structuredQuery", JSONObject()
        .put("from", JSONArray().put(JSONObject().put("collectionId", SUBCOLECCION_ENLACE)))
        .put("where", donde)
        .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "t")).put("direction", "ASCENDING")))
        .put("limit", 40))
}

/** Un documento de la base, de vuelta como mensaje del enlace (`de` = el teléfono, `uid` = la cuenta). */
fun enlaceDeDoc(d: JSONObject): JSONObject? {
    val f = d.optJSONObject("fields") ?: return null
    fun s(k: String) = f.optJSONObject(k)?.optString("stringValue")
    fun n(k: String) = f.optJSONObject(k)?.let { it.optString("integerValue").toLongOrNull() ?: it.optDouble("doubleValue", Double.NaN).takeIf { x -> !x.isNaN() }?.toLong() }
    fun dbl(k: String) = f.optJSONObject(k)?.let { if (it.has("doubleValue")) it.optDouble("doubleValue") else it.optString("integerValue").toDoubleOrNull() }
    val tipo = s("tipo") ?: return null
    if (tipo !in TIPOS_LEJOS) return null
    val o = JSONObject().put("v", VERSION_ENLACE).put("tipo", tipo).put("t", n("t") ?: return null)
        .put("de", s("dispositivo").orEmpty()).put("uid", s("de").orEmpty()).put("por", "internet")
        .put("id", d.optString("name").substringAfterLast('/'))
    s("app")?.let { o.put("app", it) }; s("titulo")?.let { o.put("titulo", it) }; s("texto")?.let { o.put("texto", it) }
    dbl("kmh")?.let { o.put("kmh", it) }; dbl("km")?.let { o.put("km", it) }
    n("rpm")?.let { o.put("rpm", it) }; n("marcha")?.let { o.put("marcha", it) }
    return o
}

fun enlaceDeConsulta(r: JSONArray): List<JSONObject> =
    (0 until r.length()).mapNotNull { r.optJSONObject(it)?.optJSONObject("document")?.let { d -> enlaceDeDoc(d) } }

/** Un evento de OTRO teléfono que todavía no se mostró. */
fun esParaMostrar(o: JSONObject, miTelefono: String, ultimoVisto: Long): Boolean =
    o.optString("tipo") in setOf("evento", "prueba") && o.optString("de") != miTelefono && o.optLong("t") > ultimoVisto

/** Alguien (otro teléfono) está mirando en vivo, según su última `mira`. */
fun alguienMira(mira: JSONObject?, miTelefono: String, ahora: Long): Boolean =
    mira != null && mira.optString("de") != miTelefono && ahora - mira.optLong("t") in -MIRA_VALE_MS..MIRA_VALE_MS

object EnlaceLejos {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("enlace_lejos", Context.MODE_PRIVATE)
    @Volatile private var mirandoHasta = 0L
    @Volatile private var ultimoVivo = 0L
    @Volatile private var ultimaMirada = 0L

    /** Hace falta una cuenta de Tiempos (la base del par) y el enlace prendido. */
    fun puede(c: Context): Boolean = try {
        EnlaceAjustes.prendido(c) && Nube(c).let { it.conSesion && it.esTiempos } && !Nube.sinRed
    } catch (e: Throwable) { false }

    private fun par(c: Context) = parDe(EnlaceAjustes.codigo(c))

    /** Lo que no se pudo mandar cerca. Devuelve si va a intentarlo (un evento) o si no corresponde. En su hilo, nunca tira. */
    fun mandar(c: Context, o: JSONObject): Boolean {
        if (!puede(c)) return false
        val app = c.applicationContext
        val tipo = o.optString("tipo")
        if (tipo == "vivo") {
            val ahora = System.currentTimeMillis()
            // ¿Alguien mira? Se pregunta una vez por minuto mientras hay vivo.
            if (ahora - ultimaMirada >= MIRA_CADA_MS) {
                ultimaMirada = ahora
                thread(name = "enlace-mira") {
                    try {
                        val m = Nube(app).enlaceLeer(par(app), "mira")
                        if (alguienMira(m, nombreDelTelefono(app), System.currentTimeMillis())) mirandoHasta = m!!.optLong("t") + MIRA_VALE_MS
                    } catch (e: Throwable) { }
                }
            }
            if (ahora > mirandoHasta || ahora - ultimoVivo < VIVO_LEJOS_CADA_MS) return true
            ultimoVivo = ahora
        } else if (tipo !in setOf("evento", "prueba")) return false
        thread(name = "enlace-lejos") {
            try {
                val n = Nube(app)
                n.enlaceEscribir(par(app), o)
                if (tipo != "vivo") {
                    n.avisarCambio("todo")
                    Bitacora.anotar(app, "enlace: un $tipo por internet")
                }
            } catch (e: Throwable) { Bitacora.anotar(app, "enlace lejos: no subió (${e.message?.take(80) ?: e.javaClass.simpleName})") }
        }
        return true
    }

    /** Lo que dejó la otra Pizarra en la base: los eventos suenan, una `mira` despierta el vivo. Lo llama Despertar (la parte «enlace»). */
    fun ponerAlDia(c: Context) {
        if (!puede(c)) return
        val app = c.applicationContext
        val p = prefs(app)
        val yo = nombreDelTelefono(app)
        val n = Nube(app)
        val par = par(app)
        // La primera vez, sólo lo de la última hora: no suena una semana entera de golpe.
        val visto = p.getLong("visto", System.currentTimeMillis() - 3600_000L)
        var max = visto
        for (o in n.enlaceNuevos(par, visto)) {
            max = maxOf(max, o.optLong("t"))
            when {
                o.optString("tipo") == "mira" && alguienMira(o, yo, System.currentTimeMillis()) -> mirandoHasta = o.optLong("t") + MIRA_VALE_MS
                esParaMostrar(o, yo, visto) -> AvisosEnlace.mostrar(app, o)
            }
        }
        p.edit().putLong("visto", max).apply()
        // Una vez por día, lo mío de más de 7 días se borra.
        val ahora = System.currentTimeMillis()
        if (ahora - p.getLong("limpio", 0L) > 24 * 3600_000L) {
            p.edit().putLong("limpio", ahora).apply()
            try { n.enlaceBorrarViejos(par, ahora - VIDA_ENLACE_MS) } catch (e: Throwable) { }
        }
    }

    /** La pantalla «En vivo» avisa que mira; la primera vez, además despierta al detector. */
    fun mirar(c: Context, despertar: Boolean) {
        val app = c.applicationContext
        val n = Nube(app)
        n.enlaceEscribir(par(app), JSONObject().put("tipo", "mira").put("t", System.currentTimeMillis()).put("de", nombreDelTelefono(app)))
        if (despertar) n.avisarCambio("todo")
    }

    /** El último vivo del detector, por la base. */
    fun vivo(c: Context): JSONObject? = Nube(c.applicationContext).enlaceLeer(par(c), "vivo")
}
