package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
// Nube.kt — Hablar con la base de Tiempos. Sello: pizarra-4
//
// Por REST y sin el SDK de Firebase, igual que la app de la Hilux: la app no
// baja nada que no venga con Android.
//
// ── LA CONTRASEÑA NO SE GUARDA ───────────────────────────────────────────────
// Se usa UNA vez para entrar, y lo que queda en el teléfono es el
// `refreshToken` que devuelve ese login. Un token se revoca desde la consola
// sin tocar la contraseña, y quien saque el archivo del teléfono no se lleva
// la cuenta. Es la lección de `sitd-19` en la Hilux.
//
// ── LO QUE PUEDE HACER, LO DICEN LAS REGLAS ─────────────────────────────────
// Entra como la persona misma (Mauro o Florencia), con su usuario común. Lo
// que lee y escribe es lo que las reglas de `tareas` le dejan a esa persona en
// el sitio: las comunes y sus personales. Nada más, ni una cuenta de servicio.
// ─────────────────────────────────────────────────────────────────────────────

// La clave web de Firebase de `tiempos-71d42`: identifica el proyecto, no da
// permisos —eso lo hacen las reglas—. Es la misma de `firebase-init.js`.
const val CLAVE_WEB = "AIzaSyAjf8dxZMljfPDRcbyrUIkP0p_y0lNoCyI"
// Si la clave está restringida a la web de Tiempos, el pedido tiene que decir
// que viene de ahí. No abre nada: es la misma dirección pública del sitio.
private const val REFERER = "https://maurogasta-crypto.github.io/tiempos/"

class ErrorNube(mensaje: String, val sinSesion: Boolean = false) : Exception(mensaje)

class Nube(contexto: Context) {
    companion object {
        /** El banco abre la app sin red: con una sesión inventada, la red de
         *  verdad la rechaza y la app vuelve a «Entrar» (pasó en GitHub). */
        @Volatile var sinRed = false
    }

    private val prefs = contexto.getSharedPreferences("sesion", Context.MODE_PRIVATE)

    val uid: String? get() = prefs.getString("uid", null)
    val mail: String? get() = prefs.getString("mail", null)
    val conSesion: Boolean get() = prefs.getString("refresh", null) != null && uid != null

    private var idToken: String? = null
    private var vence = 0L

    fun entrar(mail: String, clave: String) {
        val r = pedir(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$CLAVE_WEB",
            JSONObject().put("email", mail).put("password", clave).put("returnSecureToken", true).toString(),
            "application/json", null,
        )
        val j = JSONObject(r)
        prefs.edit()
            .putString("refresh", j.getString("refreshToken"))
            .putString("uid", j.getString("localId"))
            .putString("mail", mail)
            .apply()
        idToken = j.getString("idToken")
        vence = System.currentTimeMillis() + 50 * 60 * 1000
    }

    fun salir() {
        prefs.edit().clear().apply()
        idToken = null
    }

    /** El token de una hora, renovado con el refreshToken cuando hace falta. */
    private fun token(): String {
        idToken?.let { if (System.currentTimeMillis() < vence) return it }
        val refresh = prefs.getString("refresh", null) ?: throw ErrorNube("Entrá con tu cuenta de Tiempos.", true)
        val r = try {
            pedir(
                "https://securetoken.googleapis.com/v1/token?key=$CLAVE_WEB",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"),
                "application/x-www-form-urlencoded", null,
            )
        } catch (e: ErrorNube) {
            // Un token revocado o vencido no se arregla reintentando.
            if (e.message?.contains("TOKEN") == true || e.message?.contains("USER") == true) {
                prefs.edit().remove("refresh").apply()
                throw ErrorNube("La sesión venció. Entrá de nuevo con tu cuenta de Tiempos.", true)
            }
            throw e
        }
        val j = JSONObject(r)
        prefs.edit().putString("refresh", j.getString("refresh_token")).apply()
        idToken = j.getString("id_token")
        vence = System.currentTimeMillis() + (j.optString("expires_in", "3600").toLong() - 300) * 1000
        return idToken!!
    }

    /** Las comunes y las personales mías, como las trae el sitio. */
    fun tareas(): List<Tarea> {
        val yo = uid ?: throw ErrorNube("Entrá con tu cuenta de Tiempos.", true)
        val url = "https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery"
        val comunes = JSONArray(pedir(url, consultaComunes().toString(), "application/json", token()))
        val mias = JSONArray(pedir(url, consultaMias(yo).toString(), "application/json", token()))
        return tareasDeConsulta(comunes, yo) + tareasDeConsulta(mias, yo)
    }

    /** Mis recordatorios y alarmas (pizarra-2). */
    fun alertas(): List<Alerta> {
        val yo = uid ?: throw ErrorNube("Entrá con tu cuenta de Tiempos.", true)
        return alertasDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery",
            consultaAlertas(yo).toString(), "application/json", token())))
    }

    /** Los deseos de los dos, para «Hoy se puede» (pizarra-2). */
    fun deseos(): List<Deseo> = deseosDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery",
        consultaDeseos().toString(), "application/json", token())))

    /** Una falla de la app a `reportes/` de Tiempos, con mi sesión (pizarra-3). */
    fun mandarFalla(texto: String, gravedad: String) {
        val yo = uid ?: throw ErrorNube("Entrá con tu cuenta de Tiempos.", true)
        commit(cuerpoFalla(idNuevo(), yo, mail.orEmpty(), texto, gravedad))
    }

    fun tachar(id: String, hecho: Boolean) = commit(cuerpoTachar(id, hecho, uid ?: throw ErrorNube("Sin sesión", true)))

    fun fijar(id: String, fijar: Boolean) = commit(cuerpoFijar(id, fijar, uid ?: throw ErrorNube("Sin sesión", true)))

    private fun commit(cuerpo: JSONObject) {
        pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:commit", cuerpo.toString(), "application/json", token())
    }

    private fun pedir(url: String, cuerpo: String, tipo: String, bearer: String?): String {
        if (sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", tipo)
            c.setRequestProperty("Referer", REFERER)
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299) throw ErrorNube(motivo(codigo, texto), codigo == 401)
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

/** Un error de Firebase dicho en castellano, con el código al lado. */
fun motivo(codigo: Int, texto: String): String {
    val m = try {
        val j = JSONObject(texto)
        val e = j.optJSONObject("error")
        e?.optString("message") ?: j.optString("error_description", "")
    } catch (x: Exception) {
        ""
    }
    return when {
        m.contains("INVALID_LOGIN_CREDENTIALS") || m.contains("INVALID_PASSWORD") || m.contains("EMAIL_NOT_FOUND") ->
            "Mail o contraseña equivocados."
        m.contains("TOO_MANY_ATTEMPTS") -> "Demasiados intentos. Esperá unos minutos."
        codigo == 403 -> "La base no deja: ¿tu cuenta está aprobada en Tiempos?"
        codigo == 404 || m.contains("NOT_FOUND") -> "Esa tarea ya no existe."
        m.isNotEmpty() -> "$m ($codigo)"
        else -> "La base contestó $codigo."
    }
}
