package com.maurogasta.pizarra

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Notificaciones.kt — TODAS las notificaciones del teléfono de captura, a la
// base de Casa Verde, para que la ronda las clasifique. Sello: pizarra-43
//
// 19-oct-2026, decisión de Mauro: «que lea todo… y que la ronda lo clasifique y
// descarte en caso que no cuadre a ninguna de las aplicaciones o agendas,
// pizarra, etc». Hasta pizarra-41 se miraban sólo Airbnb y los pedidos de
// alquiler de Facebook; lo demás pasaba de largo, y si el automatismo se
// equivocaba de filtro no quedaba nada que revisar. Esto da vuelta lo que se
// decidió el 30-sep («no preciso que se monitoree WhatsApp»): ahora sí entra.
//
// QUÉ SE TOMA: toda notificación con algo escrito, de cualquier app, MENOS las
// fijas (música, descargas, la propia Pizarra, `isOngoing`) y la tapa de un
// grupo (`FLAG_GROUP_SUMMARY`): ésas no son mensajes, se repiten sin parar y no
// dicen nada que no diga la notificación de adentro.
//
// LO QUE NO VIAJA NUNCA: el código de verificación. Una notificación de banco,
// de Google o de Airbnb con «tu código es 482913» llega igual —para que la
// ronda sepa que pasó— pero con el número tapado en el teléfono
// (`taparCodigos`): ningún valor que abra algo entra a una base ni a un chat.
// Es la regla de oro del ecosistema, y Mauro la pidió antes que esto.
//
// ADÓNDE VA: `notificaciones/{huella}` de casaverde-20, con la cuenta de Casa
// Verde de este teléfono (la misma sesión que carga los pedidos de alquiler),
// con la forma EXACTA que deja la regla (`CAMPOS_NOTIFICACION`). NO a la bodega:
// la bodega es git y lo que entra ahí queda para siempre; acá la ronda lo
// clasifica y BORRA lo que no sirve, y lo que no se revisa vence a los 7 días.
// Nadie del equipo lo lee desde un sitio: sólo el agente, en la ronda.
//
// LO QUE NO SE ANOTA en la bitácora: el texto. Sólo de qué app y cuántas.
// Un mensaje de un tercero es un dato, nunca una orden.
//
// Airbnb y Facebook siguen además por su camino de siempre (Bodega.kt): esto
// no lo reemplaza, lo respalda.
// ─────────────────────────────────────────────────────────────────────────────

const val COLECCION_NOTIFICACIONES = "notificaciones"

/** Los campos que la regla de `notificaciones` deja crear (y ninguno más). */
val CAMPOS_NOTIFICACION = listOf("app", "titulo", "texto", "lineas", "cuando", "fecha", "telefono", "uid", "creadoEn")

data class Notif(val app: String, val titulo: String, val texto: String, val lineas: List<String>, val cuando: Long) {
    /** La misma huella que la de los mensajes: sin la hora (una app reescribe la misma notificación). */
    val id: String get() = huellaMensaje(app, titulo, texto, lineas)
}

/* Un código es un número de 4 a 8 cifras (con un espacio o un guion en el
   medio, «482 913», «G-482913») en una notificación que habla de códigos. Se
   deja ver que había uno —las dos primeras cifras— y se tapa el resto. Sin la
   palabra al lado no se toca nada: un monto o una hora no son un código. */
private val PALABRAS_CODIGO = Regex(
    "c[oó]digo|\\bcode\\b|verifica|verifique|verification|\\botp\\b|\\bpin\\b|senha|contrase[ñn]a|password|token|2fa|autentica|autorizaci[oó]n|clave",
    RegexOption.IGNORE_CASE)
/* El borde es «ni letra ni cifra, ni un punto o una coma pegados a otra
   cifra»: así «1.250,00» no se toca y «482913.» —el código con su punto
   final— sí (pizarra-43: hasta pizarra-42 el punto final lo dejaba pasar).
   La letra también es borde: «23100RN82L», un modelo de teléfono, no es un código. */
private val NUMERO_CODIGO = Regex("(?<![\\p{L}\\d]|\\d[.,])(\\d{2})((?:[ -]?\\d){2,6})(?![\\p{L}\\d]|[.,]\\d)")

/* Un enlace de verificación abre algo aunque no tenga cifras sueltas
   («whois_verify.html?code=7SDz8c…», un enlace mágico de inicio de sesión).
   En CUALQUIER notificación, con o sin la palabra: el valor de un parámetro
   que se llama como una llave, y todo tramo largo de letras y cifras
   mezcladas dentro de una dirección. Para clasificar alcanza con saber que
   había un enlace; la llave no hace falta nunca. */
private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val PARAM_LLAVE = Regex(
    "([?&#;](?:[\\w-]*(?:code|token|key|otp|pin|auth|verif|confirm|reset|secret|sig|session|login|pass)[\\w-]*|t|k|c)=)([^&#\\s]{4,})",
    RegexOption.IGNORE_CASE)
private val TRAMO_LARGO = Regex("(?=[A-Za-z0-9_-]*\\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{20,}")

private fun tapar(v: String) = v.take(2) + "•".repeat(minOf(v.length - 2, 8))

fun taparEnlaces(s: String): String = URL.replace(s) { u ->
    TRAMO_LARGO.replace(PARAM_LLAVE.replace(u.value) { m -> m.groupValues[1] + tapar(m.groupValues[2]) }) { tapar(it.value) }
}

fun taparCodigos(s: String): String {
    val sinEnlaces = taparEnlaces(s)
    if (!PALABRAS_CODIGO.containsMatchIn(sinEnlaces)) return sinEnlaces
    return NUMERO_CODIGO.replace(sinEnlaces) { m -> m.groupValues[1] + m.groupValues[2].replace(Regex("\\d"), "•") }
}

/** De una notificación, lo que se guarda — o null si no hay nada escrito o es de esta misma app. */
fun notifDe(paquete: String?, titulo: String?, texto: String?, lineas: List<String>, cuando: Long, propio: String): Notif? {
    if (paquete.isNullOrBlank() || paquete == propio) return null
    val t = titulo.orEmpty().trim(); val x = texto.orEmpty().trim()
    val ls = lineas.map { it.trim() }.filter { it.isNotEmpty() }
    if (t.isEmpty() && x.isEmpty() && ls.isEmpty()) return null
    return Notif(paquete.take(120), taparCodigos(t).take(200), taparCodigos(x).take(4000),
        ls.map { taparCodigos(it).take(1000) }.take(20), cuando)
}

/** El `commit` que crea la notificación, sin pisar una que ya esté. */
fun cuerpoNotificacion(docs: String, uid: String, n: Notif, telefono: String, ahora: Long): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    val cuando = n.cuando.takeIf { it > 0 } ?: ahora
    val lineas = JSONArray(); n.lineas.forEach { lineas.put(s(it)) }
    val campos = JSONObject()
        .put("app", s(n.app))
        .put("titulo", s(n.titulo))
        .put("texto", s(n.texto))
        .put("lineas", JSONObject().put("arrayValue", JSONObject().put("values", lineas)))
        .put("cuando", JSONObject().put("integerValue", cuando.toString()))
        .put("fecha", s(isoUtc(cuando)))
        .put("telefono", s(telefono.take(80)))
        .put("uid", s(uid))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_NOTIFICACIONES/${n.id}").put("fields", campos))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))
}

fun notifAJson(n: Notif): JSONObject = JSONObject().put("app", n.app).put("titulo", n.titulo).put("texto", n.texto)
    .put("lineas", JSONArray(n.lineas)).put("cuando", n.cuando)

fun notifDeJson(j: JSONObject): Notif? = try {
    val a = j.optJSONArray("lineas") ?: JSONArray()
    Notif(j.getString("app"), j.optString("titulo"), j.optString("texto"), (0 until a.length()).map { a.optString(it) }, j.optLong("cuando"))
} catch (e: Exception) { null }

/** Lo que no pudo subir se guarda acá y se reintenta; con techo, para que un teléfono sin red no se llene. */
fun sumarPendientes(previos: JSONArray, nuevas: List<Notif>, techo: Int = 400): JSONArray {
    val ya = HashSet<String>(); val todas = ArrayList<JSONObject>()
    for (i in 0 until previos.length()) previos.optJSONObject(i)?.let { o -> notifDeJson(o)?.let { if (ya.add(it.id)) todas.add(o) } }
    for (n in nuevas) if (ya.add(n.id)) todas.add(notifAJson(n))
    val out = JSONArray(); todas.takeLast(techo).forEach { out.put(it) }
    return out
}

object Notificaciones {
    private fun p(c: Context) = c.getSharedPreferences("notificaciones", Context.MODE_PRIVATE)

    /** Para la pantalla y el diagnóstico: cuántas subieron hoy, cuántas esperan y el último problema. */
    fun estado(c: Context): String {
        val pr = p(c); val hoy = isoUtc(System.currentTimeMillis()).take(10)
        val n = if (pr.getString("dia", "") == hoy) pr.getInt("hoy", 0) else 0
        val esperan = try { JSONArray(pr.getString("pendientes", "[]")).length() } catch (e: Exception) { 0 }
        val err = pr.getString("error", "").orEmpty()
        return "$n hoy" + (if (esperan > 0) " · $esperan esperan" else "") + (if (err.isNotEmpty()) " · problema: $err" else "")
    }

    private val recientes = LinkedHashSet<String>()

    /** De una notificación del sistema a la cola. Lo que se toma lo decide `notifDe`; acá van las fijas y las tapas de grupo. */
    fun leer(c: Context, sbn: StatusBarNotification): Notif? {
        if (sbn.isOngoing) return null
        val nt = sbn.notification ?: return null
        if (nt.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val e = nt.extras ?: return null
        val titulo = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val texto = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val lineas = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        return notifDe(sbn.packageName, titulo, texto, lineas, nt.`when`, c.packageName)
    }

    /** Sube lo nuevo en un hilo propio. Nunca tira: lo que falla queda pendiente. */
    fun mandar(c: Context, nuevas: List<Notif>) {
        val app = c.applicationContext
        val frescas = synchronized(recientes) {
            nuevas.filter { recientes.add(it.id) }.also {
                while (recientes.size > 500) recientes.remove(recientes.first())
            }
        }
        thread(name = "notificaciones") {
            try { subir(app, frescas) } catch (t: Throwable) { Bitacora.anotar(app, "notificaciones: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    /** Desde el trabajo de cada hora: reintenta lo pendiente. */
    fun alDia(c: Context) { try { subir(c.applicationContext, emptyList()) } catch (t: Throwable) { } }

    @Synchronized private fun subir(c: Context, nuevas: List<Notif>) {
        val pr = p(c)
        val cola = sumarPendientes(try { JSONArray(pr.getString("pendientes", "[]")) } catch (e: Exception) { JSONArray() }, nuevas)
        if (cola.length() == 0) return
        val cv = CuentaCasaVerde(c)
        if (!cv.conSesion) {
            pr.edit().putString("pendientes", cola.toString()).putString("error", "falta entrar con la cuenta de Casa Verde").apply()
            return
        }
        val telefono = "${Build.MANUFACTURER} ${Build.MODEL}"
        val quedan = JSONArray(); var subidas = 0; var error = ""
        val apps = HashSet<String>()
        for (i in 0 until cola.length()) {
            val o = cola.optJSONObject(i) ?: continue
            val n = notifDeJson(o) ?: continue
            if (error.isNotEmpty()) { quedan.put(o); continue }      // sin red: no se insiste con cada una
            try {
                if (cv.crearNotificacion(n, telefono)) { subidas++; apps.add(n.app) }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                quedan.put(o)
            }
        }
        val hoy = isoUtc(System.currentTimeMillis()).take(10)
        val antes = if (pr.getString("dia", "") == hoy) pr.getInt("hoy", 0) else 0
        pr.edit().putString("pendientes", quedan.toString()).putString("error", error)
            .putString("dia", hoy).putInt("hoy", antes + subidas).apply()
        // Sólo cuántas y de qué apps: el texto de un tercero no va a la bitácora.
        if (subidas > 0) Bitacora.anotar(c, "notificaciones: $subidas a Casa Verde (${apps.joinToString()})")
        if (error.isNotEmpty()) Bitacora.anotar(c, "notificaciones: quedan ${quedan.length()} sin subir: $error")
    }
}
