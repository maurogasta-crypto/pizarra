package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
// CasaVerde.kt — Los pedidos de alquiler de Facebook, directo a la lista de
// Casa Verde. Sello: pizarra-42
//
// 18-oct-2026, Mauro: que la lista de pedidos se llene sin despertar al chat
// cada vez que aparece una notificación; el chat se despierta unas veces por
// día —más en temporada alta— y ahí prepara las respuestas.
//
// Es una segunda sesión, como la de remate para las luces (Luces.kt): la
// cuenta de CASA VERDE de la persona, guardada aparte («casaverde»), fuera de
// las copias (sin_copias.xml). La contraseña se usa una vez.
//
// Crea UN documento por pedido en `pedidos_alquiler/{huella}` de casaverde-20,
// con la forma exacta que deja crear la regla (`camposPedido`): nunca pisa uno
// que ya está (`exists: false`), así que el mismo pedido visto dos veces es el
// mismo renglón. Lo que escribió un tercero no va a la bitácora.
//
// Y TRAE LAS RESPUESTAS (pizarra-37, 18-oct-2026, Mauro: «llegar lo más
// directamente posible al mensaje en cuestión a responder… la menor
// intervención del humano»). Cada hora lee los pedidos sin atender
// (`nuevos`); los que ya tienen la respuesta de la ronda los avisa
// Responder.kt, y al tocar «Copiar y abrir» se marcan respondidos (`marcar`,
// sólo los campos que la regla deja tocar).
// ─────────────────────────────────────────────────────────────────────────────

const val COLECCION_PEDIDOS = "pedidos_alquiler"

/** Los campos que la regla de `pedidos_alquiler` deja crear a una persona. */
val CAMPOS_PEDIDO = listOf("grupo", "texto", "cuando", "fecha", "buscar", "estado", "fuente", "uid", "creadoEn")

/** La MISMA búsqueda que `enlaceBusqueda` de datos/herramientas/facebook.mjs:
 *  las primeras doce palabras, sin «Fulano publicó:» ni signos. Facebook no da
 *  el enlace de la publicación en la notificación. Si cambia allá, cambia acá. */
fun enlaceBusqueda(texto: String): String {
    val sinAutor = texto.replace(Regex("^[^:\\n]{1,60}\\b(publicó|publicou|posted|compartió|compartilhou|escribió)[^:\\n]*:\\s*", RegexOption.IGNORE_CASE), "")
    val limpio = sinAutor.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(12).joinToString(" ")
    return if (limpio.isEmpty()) "" else "https://www.facebook.com/search/posts/?q=" + URLEncoder.encode(limpio, "UTF-8").replace("+", "%20")
}

/** El `commit` que crea el pedido, sin pisar uno que ya esté. */
fun cuerpoPedidoCasaVerde(docs: String, uid: String, m: MensajeAirbnb, ahora: Long): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    val cuando = m.cuando.toLongOrNull()?.takeIf { it > 0 } ?: ahora
    val texto = (listOf(m.texto) + m.lineas).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(4000)
    val campos = JSONObject()
        .put("grupo", s(m.chat.take(120)))
        .put("texto", s(texto))
        .put("cuando", JSONObject().put("integerValue", cuando.toString()))
        .put("fecha", s(isoUtc(cuando)))
        .put("buscar", s(enlaceBusqueda(m.texto.ifBlank { texto })))
        .put("estado", s("nuevo"))
        .put("fuente", s("pizarra"))
        .put("uid", s(uid))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_PEDIDOS/${m.id}").put("fields", campos))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))
}

/** Un pedido sin atender, como lo necesita el aviso (nunca va a la bitácora). */
data class PedidoCV(val id: String, val grupo: String, val texto: String, val respuesta: String, val buscar: String, val enlace: String)

/** La consulta de los pedidos sin atender. Sin `orderBy`, para no pedir un índice compuesto. */
fun cuerpoConsultaNuevos(): JSONObject = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", COLECCION_PEDIDOS)))
    .put("where", JSONObject().put("fieldFilter", JSONObject()
        .put("field", JSONObject().put("fieldPath", "estado"))
        .put("op", "EQUAL")
        .put("value", JSONObject().put("stringValue", "nuevo"))))
    .put("limit", 200))

/** La respuesta de `runQuery`: un renglón por documento (y uno sin documento si no hay ninguno). */
fun pedidosDeConsulta(texto: String): List<PedidoCV> {
    val a = try { JSONArray(texto) } catch (e: Exception) { return emptyList() }
    return (0 until a.length()).mapNotNull { i ->
        val d = a.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
        val f = d.optJSONObject("fields") ?: JSONObject()
        fun s(k: String) = f.optJSONObject(k)?.optString("stringValue").orEmpty()
        PedidoCV(d.optString("name").substringAfterLast('/'), s("grupo"), s("texto"), s("respuesta").trim(), s("buscar"), s("enlace"))
    }
}

/** Marca un pedido: sólo `estado`, `atendidoPor` y `atendidoEn`, que es lo que la regla deja a una persona. */
fun cuerpoMarcar(docs: String, id: String, estado: String, quien: String): JSONObject =
    JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_PEDIDOS/$id").put("fields", JSONObject()
            .put("estado", JSONObject().put("stringValue", estado))
            .put("atendidoPor", JSONObject().put("stringValue", quien))))
        .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("estado").put("atendidoPor")))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "atendidoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", true))))

class CuentaCasaVerde(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("casaverde", Context.MODE_PRIVATE)
    private val cv: Sitio = SITIOS.first { it.id == "casaverde" }

    val conSesion: Boolean get() = prefs.getString("refresh", null) != null
    val mail: String get() = prefs.getString("mail", "").orEmpty()
    private val uid: String get() = prefs.getString("uid", "").orEmpty()
    private var idToken: String? = null
    private var vence = 0L

    var ultimo: String
        get() = prefs.getString("ultimo", "").orEmpty()
        set(v) { prefs.edit().putString("ultimo", v).apply() }
    /** Cuándo trajo las respuestas por última vez (pizarra-37). */
    var respuestas: String
        get() = prefs.getString("respuestas", "").orEmpty()
        set(v) { prefs.edit().putString("respuestas", v).apply() }

    fun entrar(mail: String, clave: String) {
        val j = JSONObject(try {
            pedir("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${cv.clave}",
                JSONObject().put("email", mail).put("password", clave).put("returnSecureToken", true).toString(), "application/json", null)
        } catch (e: ErrorNube) {
            if (e.message?.startsWith("Mail o contraseña") == true)
                throw ErrorNube("Mail o contraseña de Casa Verde equivocados. Puede no ser la misma que la de Tiempos: probá «Olvidé la contraseña de Casa Verde».")
            throw e
        })
        prefs.edit().putString("refresh", j.getString("refreshToken")).putString("uid", j.getString("localId")).putString("mail", mail).apply()
        idToken = j.getString("idToken"); vence = System.currentTimeMillis() + 50 * 60 * 1000
    }

    fun restablecer(mail: String) {
        try {
            pedir("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=${cv.clave}",
                JSONObject().put("requestType", "PASSWORD_RESET").put("email", mail).toString(), "application/json", null)
        } catch (e: ErrorNube) {
            if (e.message?.contains("EMAIL_NOT_FOUND") == true) throw ErrorNube("Ese mail no tiene cuenta en Casa Verde.")
            throw e
        }
    }

    fun salir() { prefs.edit().clear().apply(); idToken = null }

    private fun token(): String {
        idToken?.let { if (System.currentTimeMillis() < vence) return it }
        val refresh = prefs.getString("refresh", null) ?: throw ErrorNube("Entrá con tu cuenta de Casa Verde en los ajustes de la Pizarra.", true)
        val r = try {
            pedir("https://securetoken.googleapis.com/v1/token?key=${cv.clave}",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"), "application/x-www-form-urlencoded", null)
        } catch (e: ErrorNube) {
            if (e.message?.contains("TOKEN") == true || e.message?.contains("USER") == true) {
                prefs.edit().remove("refresh").apply()
                throw ErrorNube("La sesión de Casa Verde venció: entrá de nuevo en los ajustes.", true)
            }
            throw e
        }
        val j = JSONObject(r)
        prefs.edit().putString("refresh", j.getString("refresh_token")).apply()
        idToken = j.getString("id_token")
        vence = System.currentTimeMillis() + (j.optString("expires_in", "3600").toLong() - 300) * 1000
        return idToken!!
    }

    /** Crea el pedido. Devuelve false si ya estaba (no es un error). */
    fun crearPedido(m: MensajeAirbnb, ahora: Long = System.currentTimeMillis()): Boolean {
        return try {
            pedir("https://firestore.googleapis.com/v1/${cv.docs}:commit",
                cuerpoPedidoCasaVerde(cv.docs, uid, m, ahora).toString(), "application/json", token())
            true
        } catch (e: ErrorNube) {
            if (e.message?.contains("ALREADY_EXISTS") == true || e.message?.contains("FAILED_PRECONDITION") == true ||
                e.message?.contains("already exists", ignoreCase = true) == true) false else throw e
        }
    }

    /** pizarra-42: una notificación del teléfono a `notificaciones/`. False si ya estaba (no es un error). */
    fun crearNotificacion(n: Notif, telefono: String, ahora: Long = System.currentTimeMillis()): Boolean {
        return try {
            pedir("https://firestore.googleapis.com/v1/${cv.docs}:commit",
                cuerpoNotificacion(cv.docs, uid, n, telefono, ahora).toString(), "application/json", token())
            true
        } catch (e: ErrorNube) {
            if (e.message?.contains("ALREADY_EXISTS") == true || e.message?.contains("FAILED_PRECONDITION") == true ||
                e.message?.contains("already exists", ignoreCase = true) == true) false else throw e
        }
    }

    /** Los pedidos sin atender de la lista (todos, no sólo los de este teléfono). */
    fun nuevos(): List<PedidoCV> =
        pedidosDeConsulta(pedir("https://firestore.googleapis.com/v1/${cv.docs}:runQuery",
            cuerpoConsultaNuevos().toString(), "application/json", token()))

    /** pizarra-40: cuántos cambios de reservas esperan que alguien los confirme. */
    fun cambiosPendientes(): Int {
        val q = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "cambios_reserva")))
            .put("where", JSONObject().put("fieldFilter", JSONObject()
                .put("field", JSONObject().put("fieldPath", "estado")).put("op", "EQUAL")
                .put("value", JSONObject().put("stringValue", "pendiente"))))
            .put("limit", 100))
        val a = JSONArray(pedir("https://firestore.googleapis.com/v1/${cv.docs}:runQuery", q.toString(), "application/json", token()))
        return (0 until a.length()).count { a.optJSONObject(it)?.has("document") == true }
    }

    /** «Respondido» o «descartado», a nombre de quien entró en este teléfono. */
    fun marcar(id: String, estado: String) {
        pedir("https://firestore.googleapis.com/v1/${cv.docs}:commit",
            cuerpoMarcar(cv.docs, id, estado, "$mail (Pizarra)").toString(), "application/json", token())
    }

    private fun pedir(url: String, cuerpo: String, tipo: String, bearer: String?): String {
        if (Nube.sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", tipo)
            c.setRequestProperty("Referer", cv.referer)
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299) {
                val crudo = try { JSONObject(texto).optJSONObject("error")?.optString("status").orEmpty() } catch (e: Exception) { "" }
                throw ErrorNube(if (crudo == "ALREADY_EXISTS" || crudo == "FAILED_PRECONDITION") crudo else motivo(codigo, texto), codigo == 401)
            }
            return texto
        } catch (e: ErrorNube) {
            throw e
        } catch (e: Exception) {
            throw ErrorNube("Sin conexión. Probá de nuevo cuando haya señal.")
        } finally {
            c.disconnect()
        }
    }
}
