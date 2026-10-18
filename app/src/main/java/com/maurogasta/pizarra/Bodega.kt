package com.maurogasta.pizarra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Bodega.kt — La Pizarra lee los mensajes de Airbnb. Sello: pizarra-35
//
// 6-oct-2026, decisión de Mauro: Termux:API no logra leer las notificaciones
// en este Xiaomi (Android 16 / HyperOS 3): el servicio de Termux:API nunca se
// conecta y `termux-notification-list` tira un NullPointerException aunque el
// permiso figure dado. Termux:API está hecha para Android 9 y no pide nada:
// no tiene cómo llevar a la persona al renglón exacto ni decir si Android la
// desconectó. La Pizarra sí.
//
// HACE LO MISMO QUE `telefono-whatsapp.mjs` de `datos`, con el mismo formato,
// para que la ronda, los borradores y las reservas no cambien una línea:
//   · sólo la app de Airbnb, sin los resúmenes del grupo;
//   · cada mensaje con su huella (sha256 de app, chat, texto y líneas, SIN la
//     hora: Airbnb reescribe la misma notificación con otra hora), y lo ya
//     visto no se vuelve a subir;
//   · a `mensajes/<fecha UTC>.json` de la bodega PRIVADA, sumando a lo que hay;
//   · `latido.json` cada 12 horas, con `falla` si no puede leer.
//
// LA CREDENCIAL: el token de GitHub que abre la bodega y ningún otro
// repositorio (el mismo que tenía Termux). Lo pega Mauro una vez; vive en las
// SharedPreferences de la app, fuera de las copias (`sin_copias.xml`), y no
// se muestra nunca. No pasa por un chat.
//
// LO QUE NO SE ANOTA: el texto de un mensaje es de un tercero (un huésped).
// La bitácora dice cuántos y si subió, nunca qué decían.
//
// FACEBOOK (pizarra-34, 18-oct-2026, Mauro: «al detectar información sobre
// pedido de alquiler en Canasvieiras guarde la información del grupo de
// facebook y de ser posible el link al anuncio»). Lo hace el Redmi 13C, el
// teléfono de captura. Se lee SÓLO lo que cumple las dos: habla de alquilar
// (alquiler, alugar, aluguel…) Y nombra Canasvieiras (o «Canas»). Lo demás de
// Facebook no se mira ni se sube. Va a la MISMA `mensajes/<fecha>.json` con
// `app: "facebook"`, así la ronda y las novedades lo traen sin otro camino.
//
// EL ENLACE: Facebook no lo pone en la notificación. Lo que sí trae es la
// acción de abrirla (`contentIntent`), y la Pizarra la reusa en un aviso
// propio, «Pedido de alquiler en Canasvieiras», que al tocarlo abre ESA
// publicación en la app de Facebook aunque la de Facebook ya se haya borrado.
// Dura hasta que se reinicia el teléfono; después queda el grupo y el texto.
// RESPONDER lo hace Mauro, en Facebook: una app que contesta sola es lo que
// hace que Facebook bloquee una cuenta.
//
// Y DESPIERTA AL CHAT (pizarra-35, 18-oct-2026, Mauro: «hacé que el pedido de
// Facebook despierte al chat»). Después de subirlo a la bodega, la Pizarra lo
// deja como un PEDIDO en `reportes/` de Tiempos —con la sesión de Tiempos de
// ese teléfono; la regla deja leerlo sólo a su dueño y al agente, no a
// Florencia— y llama a `avisar-claude` del Netlify de Casa Verde, igual que el
// globo del sitio: la función verifica la sesión y dispara la rutina «Consulta
// en vivo» con la base y el id, NUNCA el texto. La sesión en vivo no tiene la
// bodega (es privada): por eso el texto del pedido va en el reporte.
// ─────────────────────────────────────────────────────────────────────────────

const val PAQUETE_AIRBNB = "com.airbnb.android"
val PAQUETES_FACEBOOK = setOf("com.facebook.katana", "com.facebook.lite", "com.facebook.groups")
const val CANAL_FACEBOOK = "facebook-pedidos"
const val REPO_BODEGA = "maurogasta-crypto/bodega"
private const val CADA_LATIDO = 12 * 3600_000L

/** Un mensaje de Airbnb, con la forma de `mensajesDe` de telefono-whatsapp.mjs. */
data class MensajeAirbnb(val chat: String, val texto: String, val lineas: List<String>, val cuando: String, val app: String = "airbnb") {
    val id: String get() = huellaMensaje(app, chat, texto, lineas)
}

private fun sinAcentos(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

private val ALQUILAR = Regex("\\b(alquil\\w*|alug\\w*)")
private val CANASVIEIRAS = Regex("\\b(canasvieiras|canas)\\b")

/** Un pedido de alquiler en Canasvieiras: las DOS cosas, como pidió Mauro. */
fun esPedidoDeAlquiler(texto: String): Boolean {
    val t = sinAcentos(texto)
    return ALQUILAR.containsMatchIn(t) && CANASVIEIRAS.containsMatchIn(t)
}

/** De una notificación de Facebook, el pedido — o null si no es de Facebook o no es un pedido. */
fun pedidoDeFacebook(paquete: String?, titulo: String?, texto: String?, lineas: List<String>, cuando: Long): MensajeAirbnb? {
    if (paquete !in PAQUETES_FACEBOOK) return null
    val t = titulo.orEmpty(); val x = texto.orEmpty()
    if (x.isBlank() && lineas.isEmpty()) return null
    if (!esPedidoDeAlquiler((listOf(t, x) + lineas).joinToString(" "))) return null
    return MensajeAirbnb(t.take(120), x.take(4000), lineas.map { it.take(1000) }.take(20),
        if (cuando > 0) cuando.toString() else "", app = "facebook")
}

/** La tapa del grupo de notificaciones no es un mensaje (`esResumen`). */
fun esResumen(titulo: String, texto: String): Boolean =
    texto.isBlank() || Regex("^\\d+ (mensajes|messages|mensagens)", RegexOption.IGNORE_CASE).containsMatchIn(texto) ||
        Regex("^(WhatsApp|WhatsApp Business|Airbnb)$", RegexOption.IGNORE_CASE).matches(titulo.trim()) ||
        Regex("(buscando|checking for) (nuevos )?mensajes", RegexOption.IGNORE_CASE).containsMatchIn(texto)

/** De una notificación, el mensaje — o null si no es de Airbnb o es un resumen. */
fun mensajeDe(paquete: String?, titulo: String?, texto: String?, lineas: List<String>, cuando: Long): MensajeAirbnb? {
    if (paquete != PAQUETE_AIRBNB) return null
    val t = titulo.orEmpty(); val x = texto.orEmpty()
    if (esResumen(t, x)) return null
    return MensajeAirbnb(t.take(120), x.take(4000), lineas.map { it.take(1000) }.take(20), if (cuando > 0) cuando.toString() else "")
}

/** La MISMA huella que `huella` de telefono-whatsapp.mjs. */
fun huellaMensaje(app: String, chat: String, texto: String, lineas: List<String>): String {
    val d = MessageDigest.getInstance("SHA-256").digest((listOf(app, chat, texto) + lineas).joinToString("\u0001").toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }.take(20)
}

fun mensajeAJson(m: MensajeAirbnb, captado: String): JSONObject = JSONObject()
    .put("id", m.id).put("chat", m.chat).put("texto", m.texto).put("lineas", JSONArray(m.lineas))
    .put("cuando", m.cuando).put("app", m.app).put("captado", captado)

/** Lo que había en el archivo del día más lo nuevo, sin repetir una huella. */
fun unirMensajes(previos: JSONArray, nuevos: List<MensajeAirbnb>, captado: String): Pair<JSONArray, Int> {
    val ya = HashSet<String>()
    val out = JSONArray()
    for (i in 0 until previos.length()) previos.optJSONObject(i)?.let { out.put(it); ya.add(it.optString("id")) }
    var n = 0
    for (m in nuevos) if (ya.add(m.id)) { out.put(mensajeAJson(m, captado)); n++ }
    return out to n
}

/** El texto del reporte que despierta al chat: el grupo y lo que dice la publicación. */
fun textoPedidoFacebook(m: MensajeAirbnb): String =
    (listOf("Pedido de alquiler en Canasvieiras (Facebook). Grupo: ${m.chat}.", m.texto) + m.lineas)
        .filter { it.isNotBlank() }.joinToString("\n").take(2000)

/** El reporte, con la forma del globo de Tiempos: un pedido, nuevo, de quien escribe. */
fun cuerpoPedidoFacebook(id: String, uid: String, mail: String, m: MensajeAirbnb): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    val campos = JSONObject()
        .put("uid", s(uid)).put("email", s(mail)).put("nombre", s(""))
        .put("tipo", s("pedido")).put("estado", s("nuevo")).put("urgencia", s("alta"))
        .put("pagina", s(PAGINA_FACEBOOK)).put("origenApp", s(SELLO)).put("bodegaId", s(m.id))
        .put("texto", s(textoPedidoFacebook(m)))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$BASE_DOCS/reportes/$id").put("fields", campos))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))
}

const val PAGINA_FACEBOOK = "facebook-pedido"
const val AVISAR_CLAUDE = "https://serene-scone-76bd4e.netlify.app/.netlify/functions/avisar-claude"
/** El origen de Tiempos, que está en la lista blanca de la función (una app no tiene uno). */
const val ORIGEN_TIEMPOS = "https://maurogasta-crypto.github.io"

/** Lo que se le manda a la función: la base y el id, nada más. */
fun cuerpoAvisarClaude(reporteId: String): String = JSONObject().put("base", "tiempos").put("reporteId", reporteId).toString()

/** Un token de GitHub no lleva espacios ni saltos de línea: lo que venga
 *  pegado de la pantalla de Termux, partido en renglones, se junta. */
fun limpiarToken(t: String): String = t.filterNot { it.isWhitespace() || it == '\u200B' }

fun latidoJson(ahoraIso: String, falla: String = ""): String {
    val j = JSONObject().put("ultimo", ahoraIso).put("lee", JSONArray().put("airbnb").put("facebook")).put("desde", SELLO)
    if (falla.isNotBlank()) j.put("falla", falla.take(200))
    return j.toString(1) + "\n"
}

/** Como `toISOString()` de JavaScript: siempre con milésimas y en UTC. */
private val ISO = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
fun isoUtc(ms: Long): String = ISO.format(java.time.Instant.ofEpochMilli(ms))

/** La API de contenidos de GitHub, con el token de la bodega. */
class Bodega(contexto: Context) {
    private val c = contexto.applicationContext
    private val prefs = c.getSharedPreferences("bodega", Context.MODE_PRIVATE)

    val conToken: Boolean get() = !prefs.getString("token", null).isNullOrBlank()
    fun guardarToken(t: String) { prefs.edit().putString("token", limpiarToken(t)).apply() }
    fun olvidarToken() { prefs.edit().remove("token").apply() }

    var ultimoLatido: Long
        get() = prefs.getLong("latido", 0L)
        set(v) { prefs.edit().putLong("latido", v).apply() }
    var ultimaSubida: String
        get() = prefs.getString("subida", "").orEmpty()
        set(v) { prefs.edit().putString("subida", v).apply() }
    /** Lo que la última `subir` subió de verdad (pizarra-35: lo de Facebook despierta al chat). */
    var nuevos: List<MensajeAirbnb> = emptyList()
        private set

    var ultimoError: String
        get() = prefs.getString("error", "").orEmpty()
        set(v) { prefs.edit().putString("error", v).apply() }

    /** Lo visto, para no subir dos veces (tope como VISTOS de Termux). */
    private fun vistos(): MutableList<String> = try {
        val a = JSONArray(prefs.getString("vistos", "[]")); MutableList(a.length()) { a.optString(it) }
    } catch (e: Exception) { mutableListOf() }
    private fun guardarVistos(l: List<String>) { prefs.edit().putString("vistos", JSONArray(l.takeLast(5000)).toString()).apply() }

    /** Pide a GitHub y devuelve (código, cuerpo). */
    private fun github(metodo: String, ruta: String, cuerpo: String? = null): Pair<Int, String> {
        if (Nube.sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val token = prefs.getString("token", null) ?: throw ErrorNube("Falta el token de la bodega.")
        val u = URL("https://api.github.com/repos/$REPO_BODEGA/contents/$ruta")
        val h = u.openConnection() as HttpURLConnection
        try {
            h.requestMethod = metodo
            h.connectTimeout = 10_000; h.readTimeout = 20_000
            h.setRequestProperty("Authorization", "Bearer $token")
            h.setRequestProperty("Accept", "application/vnd.github+json")
            h.setRequestProperty("User-Agent", "pizarra")
            if (cuerpo != null) {
                h.doOutput = true
                h.setRequestProperty("Content-Type", "application/json")
                h.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = h.responseCode
            val texto = (if (codigo in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return codigo to texto
        } catch (e: ErrorNube) { throw e } catch (e: Exception) {
            throw ErrorNube("Sin conexión con GitHub.")
        } finally { h.disconnect() }
    }

    private fun motivoGithub(codigo: Int) = when (codigo) {
        401 -> "GitHub no acepta el token (¿venció?). Pegalo de nuevo."
        403 -> "El token no deja escribir en la bodega."
        404 -> "GitHub no encuentra la bodega con ese token."
        else -> "GitHub contestó $codigo."
    }

    /** Lee un archivo: (contenido, sha) o (null, null) si no existe. */
    private fun leer(ruta: String): Pair<String?, String?> {
        val (codigo, t) = github("GET", ruta)
        if (codigo == 404) return null to null
        if (codigo !in 200..299) throw ErrorNube(motivoGithub(codigo))
        val j = JSONObject(t)
        val txt = String(Base64.decode(j.optString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
        return txt to j.optString("sha")
    }

    /** Escribe un archivo; si otro lo cambió en el medio (409/422), relee y reintenta una vez. */
    private fun escribir(ruta: String, mensaje: String, armar: (String?) -> String?) {
        repeat(2) { intento ->
            val (actual, sha) = leer(ruta)
            val nuevo = armar(actual) ?: return
            val cuerpo = JSONObject().put("message", mensaje)
                .put("content", Base64.encodeToString(nuevo.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (sha != null) cuerpo.put("sha", sha)
            val (codigo, _) = github("PUT", ruta, cuerpo.toString())
            if (codigo in 200..299) return
            if ((codigo == 409 || codigo == 422) && intento == 0) return@repeat
            throw ErrorNube(motivoGithub(codigo))
        }
    }

    /** Comprueba el token leyendo la bodega (no escribe nada). */
    fun probar() {
        val (codigo, _) = github("GET", "latido.json")
        if (codigo !in 200..299 && codigo != 404) throw ErrorNube(motivoGithub(codigo))
    }

    /** Sube lo nuevo. Los vistos se marcan DESPUÉS de subir: si falla, la próxima vez se reintenta. */
    @Synchronized fun subir(mensajes: List<MensajeAirbnb>, ahora: Long = System.currentTimeMillis()): Int {
        val ya = vistos().toHashSet()
        val nv = mensajes.distinctBy { it.id }.filter { it.id !in ya }
        nuevos = emptyList()
        if (nv.isEmpty()) return 0
        val iso = isoUtc(ahora)
        var subidos = 0
        val cuales = nv.map { it.app }.distinct().joinToString(" y ") { if (it == "facebook") "Facebook" else "Airbnb" }
        escribir("mensajes/${iso.take(10)}.json", "$cuales: ${nv.size} mensajes (Pizarra)") { actual ->
            val previos = try { JSONArray(actual ?: "[]") } catch (e: Exception) { JSONArray() }
            val (todo, n) = unirMensajes(previos, nv, iso)
            subidos = n
            if (n == 0) null else todo.toString(1)
        }
        guardarVistos(vistos() + nv.map { it.id })
        nuevos = nv
        ultimaSubida = "$iso · $subidos"
        return subidos
    }

    /** El latido, cada 12 h (o ya, con `forzar`), con la falla si no puede leer. */
    @Synchronized fun latir(falla: String, forzar: Boolean = false, ahora: Long = System.currentTimeMillis()): Boolean {
        if (!forzar && ahora - ultimoLatido < CADA_LATIDO) return false
        escribir("latido.json", if (falla.isBlank()) "Latido del teléfono (Pizarra)" else "Latido del teléfono: NO puede leer (Pizarra)") {
            latidoJson(isoUtc(ahora), falla)
        }
        ultimoLatido = ahora
        return true
    }

    companion object {
        /** ¿Android le dio a la Pizarra el acceso a las notificaciones? */
        fun tieneAcceso(c: Context): Boolean {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return false
            return if (Build.VERSION.SDK_INT >= 27) nm.isNotificationListenerAccessGranted(ComponentName(c, LectorAirbnb::class.java))
            else android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
                .contains(c.packageName)
        }

        fun fallaActual(c: Context): String =
            if (tieneAcceso(c)) "" else "Android no deja a la Pizarra leer las notificaciones: Pizarra → «Dar acceso a notificaciones»."

        /** Desde el trabajo de cada hora: el latido, si toca. Nunca tira. */
        fun alDia(c: Context) {
            val b = Bodega(c)
            if (!b.conToken) return
            try {
                if (b.latir(fallaActual(c))) Bitacora.anotar(c, "bodega: latido")
                b.ultimoError = ""
            } catch (e: Throwable) {
                b.ultimoError = e.message ?: e.toString()
                Bitacora.anotar(c, "bodega: latido falló: ${b.ultimoError}")
            }
        }
    }
}

/** El que lee. Android lo conecta cuando la persona le da el acceso. */
class LectorAirbnb : NotificationListenerService() {
    override fun onListenerConnected() {
        Bitacora.anotar(this, "bodega: lector CONECTADO")
        barrer()
    }

    override fun onListenerDisconnected() {
        Bitacora.anotar(this, "bodega: lector desconectado; pide volver")
        if (Build.VERSION.SDK_INT >= 24) requestRebind(ComponentName(this, LectorAirbnb::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val m = sbn?.let { leer(it) } ?: return
        if (m.app == "facebook") avisarPedido(sbn, m)
        mandar(listOf(m))
    }

    /** El aviso propio del pedido, con la acción de Facebook adentro: tocarlo abre la publicación. */
    private fun avisarPedido(sbn: StatusBarNotification, m: MensajeAirbnb) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CANAL_FACEBOOK, "Pedidos de alquiler (Facebook)",
                NotificationManager.IMPORTANCE_HIGH).apply { description = "Alguien pide alquilar en Canasvieiras en un grupo." })
            val b = Notification.Builder(this, CANAL_FACEBOOK)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("🏠 Pedido de alquiler en Canasvieiras")
                .setContentText(m.chat)
                .setStyle(Notification.BigTextStyle().bigText("${m.chat}\n${m.texto}"))
                .setAutoCancel(true)
            sbn.notification?.contentIntent?.let { b.setContentIntent(it) }
            nm.notify(("fb:" + m.id).hashCode(), b.build())
        } catch (e: Throwable) {
            Bitacora.anotar(this, "bodega: no se pudo avisar el pedido de Facebook: ${e.javaClass.simpleName}")
        }
    }

    /** Lo que ya está en la barra (al conectarse, o con «Subir ahora»). */
    private fun barrer() {
        val todas = try { activeNotifications?.toList().orEmpty() } catch (e: Throwable) { emptyList() }
        mandar(todas.mapNotNull { leer(it) }, latirYa = true)
    }

    private fun leer(sbn: StatusBarNotification): MensajeAirbnb? {
        if (sbn.packageName != PAQUETE_AIRBNB && sbn.packageName !in PAQUETES_FACEBOOK) return null
        val e = sbn.notification?.extras ?: return null
        val titulo = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val texto = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val lineas = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        return mensajeDe(sbn.packageName, titulo, texto, lineas, sbn.notification.`when`)
            ?: pedidoDeFacebook(sbn.packageName, titulo, texto, lineas, sbn.notification.`when`)
    }

    private fun mandar(ms: List<MensajeAirbnb>, latirYa: Boolean = false) {
        val app = applicationContext
        thread(name = "bodega") {
            val b = Bodega(app)
            if (!b.conToken) { Bitacora.anotar(app, "bodega: hay ${ms.size} para subir pero falta el token"); return@thread }
            try {
                val n = b.subir(ms)
                // Sólo de qué apps eran: el texto de un tercero no va a la bitácora.
                val apps = ms.map { it.app }.distinct().joinToString()
                if (n > 0) Bitacora.anotar(app, "bodega: $n mensajes subidos ($apps)")
                b.latir("", forzar = latirYa && b.ultimoLatido == 0L)
                b.ultimoError = ""
                // pizarra-35: cada pedido de Facebook nuevo despierta al chat.
                for (p in b.nuevos.filter { it.app == "facebook" }) {
                    try {
                        Nube(app).avisarPedidoFacebook(p)
                        Bitacora.anotar(app, "bodega: pedido de Facebook enviado a Claude")
                    } catch (e: Throwable) {
                        Bitacora.anotar(app, "bodega: el pedido de Facebook no despertó a Claude: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            } catch (e: Throwable) {
                b.ultimoError = e.message ?: e.toString()
                Bitacora.anotar(app, "bodega: no se pudo subir: ${b.ultimoError}")
            }
        }
    }

    companion object {
        /** «Subir ahora» desde la pantalla: pide a Android que lo reconecte, y él barre al conectarse. */
        fun reconectar(c: Context) {
            if (Build.VERSION.SDK_INT >= 24) requestRebind(ComponentName(c, LectorAirbnb::class.java))
        }
    }
}
