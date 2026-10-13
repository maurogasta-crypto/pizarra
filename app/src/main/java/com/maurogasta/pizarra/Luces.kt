package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
// Luces.kt — Las luces de remate desde el widget. Sello: pizarra-26
//
// Mauro, 13-oct-2026: «arriba del todo un botón para encender las luces de la
// entrada y del depósito, dos botoncitos, en el widget de la pizarra».
//
// Son las MISMAS luces que `luces.html` de remate, por el MISMO puente
// (`luces-remate` del Netlify de Casa Verde, `api/tuya.mjs` de remate), y con
// la MISMA puerta: el puente verifica un token de Firebase de `remate-acbc9` y
// lee `usuarios/{uid}` con ese token (activo, y admin o `permisos.luces`). Por
// eso la app guarda una SEGUNDA sesión, sólo para esto: la cuenta de remate de
// la persona. Como la de Tiempos, la contraseña se usa una vez y queda el
// `refreshToken` (fuera de las copias, `sin_copias.xml`).
//
// ── EL ORIGEN ────────────────────────────────────────────────────────────────
// El puente sólo contesta a los orígenes de `ORIGENES_PERMITIDOS` (CORS). Eso
// protege la sesión de quien navega: que una página ajena no use la cuenta
// abierta en su navegador. Una app no tiene origen, así que manda el del panel
// de remate, de donde viene el pedido que reemplaza. Lo que abre la luz sigue
// siendo el token de la persona y su permiso, y nada más.
//
// ── LO MÁS RÁPIDO POSIBLE (pizarra-25, 13-oct, Mauro: «que haya menos
//    latencia en la reacción del botón») ────────────────────────────────────
// Lo lento no era remate —el puente es el único camino a Tuya, porque el
// secreto que firma cada orden no puede estar en el teléfono—: era el toque en
// cuatro idas y vueltas (renovar la sesión, preguntar cómo estaban las dos
// luces, mandar, anotar) antes de redibujar. Ahora:
//   · el botón cambia EN EL ACTO, según lo último que se supo (`cambiarRapido`);
//   · se manda UNA orden, sin preguntar antes (si nunca se supo, sí se pregunta);
//   · la sesión de una hora se guarda, y la conexión se reusa (keep-alive: no se
//     corta con `disconnect`), así no se paga otra vez el saludo seguro;
//   · el registro y la verificación van DESPUÉS de redibujar: a los segundos se
//     le pregunta al aparato y, si alguien la había movido por otro lado, el
//     botón se corrige solo.
// Una conexión abierta para siempre no se puede: un widget no corre todo el
// tiempo (Android lo duerme), y el puente es una función que nace y muere con
// cada pedido.
//
// ── CÓMO SE PRENDE ──────────────────────────────────────────────────────────
// Un botón por luz, que CAMBIA su estado: primero se le pregunta al puente
// cómo está (lo que contesta el aparato, no lo que se recuerda), y se manda lo
// contrario. Cada movimiento queda en `lucesRegistro` de remate, firmado por
// la persona, como desde el panel. Si anotar falla, la luz igual se movió.
// ─────────────────────────────────────────────────────────────────────────────

const val PUENTE_LUCES = "https://serene-scone-76bd4e.netlify.app/.netlify/functions/luces-remate"
const val ORIGEN_REMATE = "https://rematetaller.github.io"
/** Las dos del widget, en su orden. Los alias son los de `TUYA_LUCES`. */
val LUCES_WIDGET = listOf("entrada" to "Entrada", "deposito" to "Depósito")

data class Luz(val alias: String, val label: String, val encendida: Boolean?, val enLinea: Boolean?)

/** La respuesta del GET del puente: `{ok, luces:[{alias,label,encendida,enLinea}]}`. */
fun lucesDeRespuesta(texto: String): List<Luz> {
    val j = JSONObject(texto)
    val a = j.optJSONArray("luces") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val alias = o.optString("alias").ifEmpty { return@mapNotNull null }
        Luz(alias, o.optString("label", alias),
            if (o.isNull("encendida") || !o.has("encendida")) null else o.optBoolean("encendida"),
            if (o.isNull("enLinea") || !o.has("enLinea")) null else o.optBoolean("enLinea"))
    }
}

/** Lo que se le manda al puente para mover una luz. */
fun cuerpoLuz(alias: String, encender: Boolean): JSONObject = JSONObject().put("luz", alias).put("encender", encender)

/** El renglón de `lucesRegistro`, como el que escribe `luces.html` (`cuando` lo pone el servidor). */
fun cuerpoRegistroLuz(docs: String, id: String, uid: String, nombre: String, luz: Luz, encendida: Boolean): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    val campos = JSONObject().put("uid", s(uid)).put("nombre", s(nombre)).put("luz", s(luz.alias))
        .put("label", s(luz.label)).put("encendida", JSONObject().put("booleanValue", encendida))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/lucesRegistro/$id").put("fields", campos))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "cuando").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))
}

class Luces(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("luces", Context.MODE_PRIVATE)
    private val remate: Sitio = SITIOS.first { it.id == "remate" }

    val conSesion: Boolean get() = prefs.getString("refresh", null) != null
    val mail: String get() = prefs.getString("mail", "").orEmpty()
    /** Lo último que dijo el puente de cada luz: para pintar los botones sin esperar. */
    /** Lo que se cree ahora (antes de que conteste el puente), para pintar el botón. */
    fun marcar(alias: String, prendida: Boolean) { prefs.edit().putBoolean("luz.$alias", prendida).apply() }
    fun ultimo(alias: String): Boolean? = if (!prefs.contains("luz.$alias")) null else prefs.getBoolean("luz.$alias", false)
    var estado: String
        get() = prefs.getString("estado", "").orEmpty()
        set(v) { prefs.edit().putString("estado", v).apply() }

    // pizarra-25: la sesión de una hora queda guardada (no sólo en memoria), así
    // un toque después de que Android durmió la app no paga otra renovación.
    private var idToken: String?
        get() = prefs.getString("id", null)
        set(v) { prefs.edit().putString("id", v).apply() }
    private var vence: Long
        get() = prefs.getLong("vence", 0L)
        set(v) { prefs.edit().putLong("vence", v).apply() }

    fun entrar(mail: String, clave: String) {
        val j = JSONObject(pedir("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${remate.clave}", "POST",
            JSONObject().put("email", mail).put("password", clave).put("returnSecureToken", true).toString(), "application/json", null, remate.referer))
        prefs.edit().putString("refresh", j.getString("refreshToken")).putString("uid", j.getString("localId")).putString("mail", mail).apply()
        idToken = j.getString("idToken"); vence = System.currentTimeMillis() + 50 * 60 * 1000
    }

    fun salir() { prefs.edit().clear().apply(); idToken = null }

    private fun token(): String {
        idToken?.let { if (System.currentTimeMillis() < vence) return it }
        val refresh = prefs.getString("refresh", null) ?: throw ErrorNube("Para las luces, entrá con tu cuenta de remate en los ajustes.", true)
        val r = try {
            pedir("https://securetoken.googleapis.com/v1/token?key=${remate.clave}", "POST",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"), "application/x-www-form-urlencoded", null, remate.referer)
        } catch (e: ErrorNube) {
            if (e.message?.contains("TOKEN") == true || e.message?.contains("USER") == true) {
                prefs.edit().remove("refresh").apply()
                throw ErrorNube("La sesión de remate venció: entrá de nuevo en los ajustes.", true)
            }
            throw e
        }
        val j = JSONObject(r)
        prefs.edit().putString("refresh", j.getString("refresh_token")).apply()
        idToken = j.getString("id_token")
        vence = System.currentTimeMillis() + (j.optString("expires_in", "3600").toLong() - 300) * 1000
        return idToken!!
    }

    /** pizarra-26 (Tibio.kt): la sesión renovada si le quedan menos de 15 min, y
     *  un pedido SIN sesión al puente para que la función no se duerma. */
    fun mantenerTibia() {
        if (!conSesion) return
        if (vence - System.currentTimeMillis() < 15 * 60_000L) { idToken = null; token() }
        try { pedir(PUENTE_LUCES, "GET", null, null, null, ORIGEN_REMATE + "/") }
        catch (e: ErrorNube) { /* «falta la sesión» es justo lo que se espera */ }
    }

    fun luces(): List<Luz> {
        val l = lucesDeRespuesta(pedir(PUENTE_LUCES, "GET", null, null, token(), ORIGEN_REMATE + "/"))
        prefs.edit().apply { l.forEach { if (it.encendida != null) putBoolean("luz.${it.alias}", it.encendida) } }.apply()
        return l
    }

    /** Lo que el botón va a hacer, sin red: lo contrario de lo último que se supo
     *  (null si nunca se supo: entonces hay que preguntar). */
    fun proximo(alias: String): Boolean? = ultimo(alias)?.let { !it }

    /** Manda UNA orden, sin preguntar antes. Devuelve lo que contestó el puente. */
    fun mandar(alias: String, encender: Boolean): Luz {
        val r = JSONObject(pedir(PUENTE_LUCES, "POST", cuerpoLuz(alias, encender).toString(), "application/json", token(), ORIGEN_REMATE + "/"))
        prefs.edit().putBoolean("luz.$alias", encender).apply()
        return Luz(alias, r.optString("label", alias), encender, true)
    }

    /** Si nunca se supo cómo estaba, se pregunta una vez (el primer toque). */
    fun aEncender(alias: String): Boolean {
        proximo(alias)?.let { return it }
        val luz = luces().firstOrNull { it.alias == alias } ?: throw ErrorNube("El puente no conoce la luz «$alias».")
        if (luz.enLinea == false) throw ErrorNube("${luz.label}: el aparato no contesta.")
        return luz.encendida != true
    }

    /** El renglón del registro, después de que la luz ya se movió. Nunca tira. */
    fun anotar(luz: Luz, encendida: Boolean) {
        try {
            val docs = "projects/${remate.proyecto}/databases/(default)/documents"
            pedir("https://firestore.googleapis.com/v1/$docs:commit", "POST",
                cuerpoRegistroLuz(docs, idNuevo(), prefs.getString("uid", "").orEmpty(), mail, luz, encendida).toString(),
                "application/json", token(), remate.referer)
        } catch (e: Exception) { /* la luz es el trabajo; el registro es la memoria */ }
    }

    private fun pedir(url: String, metodo: String, cuerpo: String?, tipo: String?, bearer: String?, referer: String): String {
        if (Nube.sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Referer", referer)
            if (url == PUENTE_LUCES) c.setRequestProperty("Origin", ORIGEN_REMATE)
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", tipo ?: "application/json")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299) {
                val m = try { JSONObject(texto).optString("motivo") } catch (e: Exception) { "" }
                throw ErrorNube(if (m.isNotEmpty()) "Luces: $m" else motivo(codigo, texto), codigo == 401)
            }
            return texto
        } catch (e: ErrorNube) {
            throw e
        } catch (e: Exception) {
            throw ErrorNube("Sin conexión. Probá de nuevo cuando haya señal.")
        }
        // Sin `disconnect()`, a propósito (pizarra-25): leer la respuesta entera
        // y cerrar el stream devuelve la conexión al pozo, y el próximo pedido al
        // mismo lugar se ahorra el saludo seguro.
    }
}
