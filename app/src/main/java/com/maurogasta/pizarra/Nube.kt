package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
// Nube.kt — Hablar con la base de Tiempos. Sello: pizarra-64
//
// pizarra-14 (tiempos:V10, 9-oct-2026): la Pizarra es para todo el equipo. Al
// entrar se elige el SITIO (Avisos.kt, `SITIOS`): Tiempos para Mauro y
// Florencia, Casa Verde, CasaYourte o remate para los demás. Con otro sitio la
// app sólo trae los avisos; lo de Tiempos (tareas, alertas, deseos) pide
// Tiempos, y lo dice.
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

    private val ctx = contexto.applicationContext
    private val prefs = contexto.getSharedPreferences("sesion", Context.MODE_PRIVATE)

    val uid: String? get() = prefs.getString("uid", null)
    /** El sitio con el que se entró. Una sesión de antes de pizarra-14 es de Tiempos. */
    val sitio: Sitio get() = sitio(prefs.getString("sitio", "tiempos"))
    val esTiempos: Boolean get() = sitio.id == "tiempos"
    val mail: String? get() = prefs.getString("mail", null)
    val conSesion: Boolean get() = prefs.getString("refresh", null) != null && uid != null

    private var idToken: String? = null
    private var vence = 0L

    fun entrar(mail: String, clave: String, sitioId: String = "tiempos") {
        val s = sitio(sitioId)
        val r = pedir(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${s.clave}",
            JSONObject().put("email", mail).put("password", clave).put("returnSecureToken", true).toString(),
            "application/json", null,
        )
        val j = JSONObject(r)
        prefs.edit()
            .putString("sitio", s.id)
            .putString("refresh", j.getString("refreshToken"))
            .putString("uid", j.getString("localId"))
            .putString("mail", mail)
            .apply()
        idToken = j.getString("idToken")
        vence = System.currentTimeMillis() + 50 * 60 * 1000
        Instantaneo.iniciar(ctx) // pizarra-60: los temas de ESTA base
    }

    /** pizarra-17: «Olvidé la contraseña». Firebase manda el mail para elegir
     *  una nueva; la app no ve ni guarda nada. Es la misma cuenta del sitio,
     *  así que la contraseña nueva sirve también en la web. */
    fun restablecer(mail: String, sitioId: String = "tiempos") {
        val s = sitio(sitioId)
        try {
            pedir(
                "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=${s.clave}",
                JSONObject().put("requestType", "PASSWORD_RESET").put("email", mail).toString(),
                "application/json", null,
            )
        } catch (e: ErrorNube) {
            if (e.message?.contains("EMAIL_NOT_FOUND") == true)
                throw ErrorNube("Ese mail no tiene cuenta en ${s.nombre}. Pedile el alta a Mauro.")
            throw e
        }
    }

    fun salir() {
        prefs.edit().clear().apply()
        idToken = null
        Instantaneo.iniciar(ctx) // sin sesión, sin temas
    }

    /** El token de una hora, renovado con el refreshToken cuando hace falta. */
    private fun token(): String {
        idToken?.let { if (System.currentTimeMillis() < vence) return it }
        val refresh = prefs.getString("refresh", null) ?: throw ErrorNube("Entrá con tu cuenta.", true)
        val r = try {
            pedir(
                "https://securetoken.googleapis.com/v1/token?key=${sitio.clave}",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"),
                "application/x-www-form-urlencoded", null,
            )
        } catch (e: ErrorNube) {
            // Un token revocado o vencido no se arregla reintentando.
            if (e.message?.contains("TOKEN") == true || e.message?.contains("USER") == true) {
                prefs.edit().remove("refresh").apply()
                throw ErrorNube("La sesión venció. Entrá de nuevo con tu cuenta de ${sitio.nombre}.", true)
            }
            throw e
        }
        val j = JSONObject(r)
        prefs.edit().putString("refresh", j.getString("refresh_token")).apply()
        idToken = j.getString("id_token")
        vence = System.currentTimeMillis() + (j.optString("expires_in", "3600").toLong() - 300) * 1000
        return idToken!!
    }

    /** Lo de Tiempos sólo con una cuenta de Tiempos (pizarra-14). */
    private fun deTiempos(): String {
        val yo = uid ?: throw ErrorNube("Entrá con tu cuenta de Tiempos.", true)
        if (!esTiempos) throw ErrorNube("La pizarra de tareas es de Tiempos: entraste con ${sitio.nombre}.")
        return yo
    }

    /** pizarra-40: los mensajes de la charla de Tiempos, los más nuevos primero. */
    fun mensajes(desde: String = ""): List<Mensaje> {
        deTiempos()
        return mensajesDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery",
            consultaMensajes(desde = desde).toString(), "application/json", token())))
    }

    /**
     * pizarra-77: cuándo limpió la charla el gestor de Claude (`familia/charla.limpiadoEn`, lo escribe
     * `charla.mjs limpiar` de `datos`). Una lectura; «» si nunca o si no se pudo.
     */
    fun limpiezaCharla(): String {
        deTiempos()
        val r = JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:batchGet",
            JSONObject().put("documents", JSONArray().put("$BASE_DOCS/familia/charla")).toString(), "application/json", token()))
        return limpiezaDeRespuesta(r)
    }

    /** pizarra-80: los cambios de nombre de los teléfonos (`familia/telefonos.nombres`). Una lectura. */
    fun renombres(): Map<String, String> {
        deTiempos()
        return renombresDeRespuesta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:batchGet",
            JSONObject().put("documents", JSONArray().put("$BASE_DOCS/familia/telefonos")).toString(), "application/json", token())))
    }

    /** pizarra-80: publica que los nombres [viejos] de este teléfono ahora son [nuevo]. Una escritura. */
    fun publicarRenombre(viejos: Collection<String>, nuevo: String) { val yo = deTiempos(); commit(cuerpoRenombre(BASE_DOCS, yo, viejos, nuevo)) }

    /** pizarra-78: el parte de salud de este aparato (reglas v24 de Tiempos). */
    fun subirSalud(cuerpo: JSONObject) { deTiempos(); commit(cuerpo) }

    /** pizarra-62: un mensaje de VOZ. Primero el audio (`audios/`), después el mensaje que lo nombra. */
    fun mandarAudio(datos: ByteArray, dur: Int, tipo: String, dispositivo: String, para: String = "", handy: Boolean = false): String {
        if (Nube.sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val yo = deTiempos()
        val id = idNuevo()
        commit(cuerpoAudio(BASE_DOCS, id, yo, datos, dur, tipo))
        commit(cuerpoMensaje(BASE_DOCS, idNuevo(), yo, miNombre(), dispositivo, if (handy) textoDeHandy(dur) else textoDeAudio(dur), para, id, dur, handy = handy))
        avisarCambio("mensajes")
        return id
    }

    // ─── pizarra-64: fotos y documentos (Archivos.kt, reglas v20) ───
    /** Primero las partes, después el mensaje que las nombra. Devuelve el id del archivo. */
    fun mandarArchivo(partes: List<ByteArray>, nombre: String, tipo: String, tam: Long, dispositivo: String, para: String = ""): String {
        if (Nube.sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val yo = deTiempos(); val id = idNuevo()
        partes.forEachIndexed { n, b -> commit(cuerpoParte(BASE_DOCS, id, n, partes.size, yo, b, nombre, tipo)) }
        commit(cuerpoMensaje(BASE_DOCS, idNuevo(), yo, miNombre(), dispositivo, textoDeArchivo(nombre, tipo, tam), para,
            archivo = id, nombreArchivo = nombre, partes = partes.size))
        avisarCambio("mensajes")
        return id
    }

    fun bajarArchivo(id: String, partes: Int): ByteArray? {
        deTiempos()
        val docs = JSONArray().apply { (0 until partes).forEach { put("$BASE_DOCS/$COLECCION_ARCHIVOS/${idDeParte(id, it)}") } }
        val r = JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:batchGet", JSONObject().put("documents", docs).toString(), "application/json", token()))
        return partesDeRespuesta(r, partes)?.let { juntar(it) }
    }

    fun borrarArchivos(idsDePartes: List<String>) {
        if (idsDePartes.isEmpty()) return
        deTiempos()
        commit(JSONObject().put("writes", JSONArray().apply { idsDePartes.forEach { put(JSONObject().put("delete", "$BASE_DOCS/$COLECCION_ARCHIVOS/$it")) } }))
    }

    // ─── pizarra-63: las llamadas (Llamada.kt, reglas v19) ───
    fun crearLlamada(para: String, oferta: String, dispositivo: String): String {
        if (Nube.sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val yo = deTiempos(); val id = idNuevo()
        commit(cuerpoLlamada(BASE_DOCS, id, yo, miNombre(), dispositivo, para, oferta))
        avisarCambio("llamadas")
        return id
    }

    fun leerLlamada(id: String): Llamada? {
        deTiempos()
        val r = JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:batchGet",
            JSONObject().put("documents", JSONArray().put("$BASE_DOCS/$COLECCION_LLAMADAS/$id")).toString(), "application/json", token()))
        return r.optJSONObject(0)?.optJSONObject("found")?.let { llamadaDeDoc(it) }
    }

    fun llamadasSonando(): List<Llamada> {
        deTiempos()
        return llamadasDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery",
            consultaSonando().toString(), "application/json", token())))
    }

    /** Atender (con respuesta) o rechazar (null). */
    fun atenderLlamada(id: String, dispositivo: String, respuesta: String?) {
        deTiempos(); commit(cuerpoAtender(BASE_DOCS, id, dispositivo, respuesta)); avisarCambio("llamadas")
    }

    fun cortarLlamada(id: String, estado: String) { deTiempos(); commit(cuerpoCortar(BASE_DOCS, id, estado)); avisarCambio("llamadas") }

    // ─── pizarra-71: el enlace lejos (EnlaceLejos.kt, reglas v21) ───
    fun enlaceEscribir(par: String, o: JSONObject) {
        val yo = deTiempos()
        commit(cuerpoEnlace(BASE_DOCS, par, yo, o) ?: throw ErrorNube("Ese mensaje no va por la base."))
    }

    fun enlaceNuevos(par: String, desde: Long): List<JSONObject> {
        deTiempos()
        return enlaceDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS/$COLECCION_ENLACE/$par:runQuery",
            consultaEnlace(desde).toString(), "application/json", token())))
    }

    /** pizarra-75: el código del enlace de la familia; si no hay, lo crea (y si otro lo creó en el medio, lee ése). */
    fun llaveFamilia(): String {
        val yo = deTiempos()
        val url = "https://firestore.googleapis.com/v1/$BASE_DOCS:batchGet"
        val leer = { codigoDeLlave(JSONArray(pedir(url, JSONObject().put("documents", JSONArray().put("$BASE_DOCS/$COLECCION_LLAVE/familia")).toString(), "application/json", token()))) }
        leer()?.let { return it }
        try { commit(cuerpoLlaveFamilia(BASE_DOCS, yo, codigoNuevo())) } catch (e: ErrorNube) { }
        return leer() ?: throw ErrorNube("No se pudo traer la llave del enlace: ¿están publicadas las reglas v22 de Tiempos?")
    }

    fun enlaceLeer(par: String, id: String): JSONObject? {
        deTiempos()
        val r = JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:batchGet",
            JSONObject().put("documents", JSONArray().put("$BASE_DOCS/$COLECCION_ENLACE/$par/$SUBCOLECCION_ENLACE/$id")).toString(), "application/json", token()))
        return r.optJSONObject(0)?.optJSONObject("found")?.let { enlaceDeDoc(it) }
    }

    /** Lo mío de antes de [antes] (la regla deja borrar sólo lo propio). */
    fun enlaceBorrarViejos(par: String, antes: Long) {
        val yo = deTiempos()
        val viejos = enlaceDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS/$COLECCION_ENLACE/$par:runQuery",
            consultaEnlace(0, antes).toString(), "application/json", token()))).filter { it.optString("uid") == yo }
        if (viejos.isEmpty()) return
        commit(JSONObject().put("writes", JSONArray().apply {
            viejos.forEach { put(JSONObject().put("delete", "$BASE_DOCS/$COLECCION_ENLACE/$par/$SUBCOLECCION_ENLACE/${it.optString("id")}")) }
        }))
    }

    /** Los servidores de conexión, de la función `hielo` (el TURN tiene clave: no va en la app). */
    fun hielo(): String = pedir(URL_HIELO, JSONObject().put("base", sitio.id).toString(), "application/json", token())

    /** Los bytes de un audio, o null si ya no está (se borra a los 30 días). */
    fun bajarAudio(id: String): ByteArray? {
        deTiempos()
        val r = JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:batchGet",
            JSONObject().put("documents", JSONArray().put("$BASE_DOCS/$COLECCION_AUDIOS/$id")).toString(), "application/json", token()))
        return bytesDeAudio(r)
    }

    /** Borra audios míos (la regla deja sólo los propios). */
    fun borrarAudios(ids: List<String>) {
        if (ids.isEmpty()) return
        deTiempos()
        commit(JSONObject().put("writes", JSONArray().apply { ids.forEach { put(JSONObject().put("delete", "$BASE_DOCS/$COLECCION_AUDIOS/$it")) } }))
    }

    /** Mi nombre, de mi ficha de `miembros/` (se guarda: no cambia). */
    fun miNombre(): String {
        prefs.getString("nombre", null)?.let { if (it.isNotBlank()) return it }
        val yo = deTiempos()
        val n = try {
            val c = java.net.URL("https://firestore.googleapis.com/v1/$BASE_DOCS/miembros/$yo").openConnection() as HttpURLConnection
            try {
                c.setRequestProperty("Authorization", "Bearer " + token()); c.setRequestProperty("Referer", REFERER)
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                if (c.responseCode !in 200..299) "" else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    .optJSONObject("fields")?.optJSONObject("nombre")?.optString("stringValue").orEmpty()
            } finally { c.disconnect() }
        } catch (e: Exception) { "" }
        val nombre = n.ifBlank { mail?.substringBefore('@').orEmpty() }.take(40)
        if (n.isNotBlank()) prefs.edit().putString("nombre", nombre).apply()
        return nombre
    }

    fun mandarMensaje(texto: String, dispositivo: String, para: String = "") {
        if (Nube.sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val yo = deTiempos()
        if (texto.isBlank()) throw ErrorNube("El mensaje está vacío.")
        commit(cuerpoMensaje(BASE_DOCS, idNuevo(), yo, miNombre(), dispositivo, texto, para))
        avisarCambio("mensajes")
    }

    /** pizarra-60: después de escribir, las otras Pizarras se enteran en el acto (Instantaneo.kt). */
    fun avisarCambio(que: String) = Instantaneo.avisar(ctx, sitio.id, que) { token() }

    /** Mis avisos del ecosistema, de la base del sitio con el que entré (pizarra-14). */
    fun avisos(): List<Aviso> {
        val yo = uid ?: throw ErrorNube("Entrá con tu cuenta.", true)
        return avisosDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/${sitio.docs}:runQuery",
            consultaAvisos(yo).toString(), "application/json", token())))
    }

    fun marcarLeido(id: String) {
        pedir("https://firestore.googleapis.com/v1/${sitio.docs}:commit", cuerpoLeido(sitio.docs, id).toString(), "application/json", token())
    }

    /** Las comunes y las personales mías, como las trae el sitio. */
    fun tareas(): List<Tarea> {
        val yo = deTiempos()
        val url = "https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery"
        val comunes = JSONArray(pedir(url, consultaComunes().toString(), "application/json", token()))
        val mias = JSONArray(pedir(url, consultaMias(yo).toString(), "application/json", token()))
        return tareasDeConsulta(comunes, yo) + tareasDeConsulta(mias, yo)
    }

    /** pizarra-20: mi agenda de la semana, para su widget (AgendaLogica.kt). */
    fun agendaSemana(hoy: String, tareas: List<Tarea>? = null): List<FilaAgenda> {
        val yo = deTiempos()
        val docs = "https://firestore.googleapis.com/v1/$BASE_DOCS"
        val agenda = agendaDeRespuesta(JSONArray(pedir("$docs:batchGet", pedidoAgenda(yo).toString(), "application/json", token())))
        val eventos = eventosDeConsulta(JSONArray(pedir("$docs:runQuery", consultaEventos().toString(), "application/json", token())))
        // pizarra-21: y mis alarmas y recordatorios de esos días.
        return agendaDeLaSemana(hoy, agenda, eventos, tareas ?: tareas(), alertas = alertas())
    }

    /** Mis recordatorios y alarmas (pizarra-2). */
    fun alertas(): List<Alerta> {
        val yo = deTiempos()
        return alertasDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery",
            consultaAlertas(yo).toString(), "application/json", token())))
    }

    /** Los deseos de los dos, para «Hoy se puede» (pizarra-2). */
    fun deseos(): List<Deseo> = deTiempos().let { _ -> deseosDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery",
        consultaDeseos().toString(), "application/json", token()))) }

    /** Lo que Claude me propone, pendiente (pizarra-11). */
    fun propuestas(): List<Propuesta> {
        val yo = deTiempos()
        return propuestasDeConsulta(JSONArray(pedir("https://firestore.googleapis.com/v1/$BASE_DOCS:runQuery",
            consultaPropuestas().toString(), "application/json", token())), yo)
    }

    /** Una falla de la app a `reportes/` de Tiempos, con mi sesión (pizarra-3). */
    fun mandarFalla(texto: String, gravedad: String) {
        // Los reportes de cada sitio tienen otra forma: por ahora, las fallas
        // de la app llegan a Claude sólo desde una cuenta de Tiempos.
        val yo = deTiempos()
        commit(cuerpoFalla(idNuevo(), yo, mail.orEmpty(), texto, gravedad))
    }

    fun tachar(id: String, hecho: Boolean) { commit(cuerpoTachar(id, hecho, deTiempos())); avisarCambio("tareas") }

    /** pizarra-30: una tarea nueva, mía y en mi pizarra. Devuelve su id. */
    fun agregar(titulo: String): String {
        val id = idNuevo()
        commit(cuerpoNuevaTarea(id, titulo, deTiempos()))
        avisarCambio("tareas")
        return id
    }

    fun fijar(id: String, fijar: Boolean) { commit(cuerpoFijar(id, fijar, deTiempos())); avisarCambio("tareas") }

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
            c.setRequestProperty("Referer", sitio.referer.ifEmpty { REFERER })
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
        codigo == 403 -> "La base no deja: ¿tu cuenta está aprobada en ese sitio (y sus reglas publicadas)?"
        codigo == 404 || m.contains("NOT_FOUND") -> "Esa tarea ya no existe."
        m.isNotEmpty() -> "$m ($codigo)"
        else -> "La base contestó $codigo."
    }
}
