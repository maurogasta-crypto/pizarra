package com.maurogasta.pizarra

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Instantaneo.kt — El aviso que despierta la Pizarra en el acto. Sello: pizarra-63
//
// Mauro, 24-oct-2026 (general:C1, fase 2): «una conexión en tiempo real entre
// todos los integrantes, sin un consumo extra que el de la comunicación», y
// «los mensajes en el ecosistema tienen que usar ese canal para notificar
// despertando el dispositivo».
//
// ── CÓMO ANDA ────────────────────────────────────────────────────────────────
// Cuando alguien guarda algo (un mensaje, una tarea, la agenda…), su sitio o
// su Pizarra llama a `despertar` del Netlify de Casa Verde, que verifica la
// sesión y la ficha y manda por Firebase Cloud Messaging un aviso SIN
// contenido al tema de esa base (`pizarra-<base>`). Cada Pizarra suscripta lo
// recibe por la conexión que Google ya tiene abierta en el teléfono —por eso
// no gasta batería de más— y llama a `Despertar.por(que)`, que trae lo que cambió con SU
// sesión. El contenido nunca viaja por el aviso.
//
// ── EL PROYECTO ──────────────────────────────────────────────────────────────
// `avisos-23b6a`, aparte, sin ninguna base de los sitios: su cuenta de
// servicio sólo puede mandar avisos. Estos datos son públicos por diseño (los
// del google-services.json): identifican la app, no dan permisos. Se inicia a
// mano, sin el plugin de google-services, para no sumar nada más al build.
//
// Si algún día un teléfono queda sin Google, se cambia este archivo por ntfy y
// nada más: lo que se hace al despertar vive en Despertar.kt.
// ─────────────────────────────────────────────────────────────────────────────

const val PROYECTO_AVISOS = "avisos-23b6a"
const val URL_DESPERTAR = "https://serene-scone-76bd4e.netlify.app/.netlify/functions/despertar"
val QUE_CAMBIO = setOf("mensajes", "tareas", "agenda", "alertas", "avisos", "reservas", "pedidos", "plata", "llamadas", "todo")

fun temaDe(base: String) = "pizarra-$base"

/** Los temas que corresponden a este teléfono: el de su base, y el de Casa Verde si entró ahí (el 13C). */
fun temasPara(base: String?, conCasaVerde: Boolean): Set<String> =
    listOfNotNull(base?.takeIf { it.isNotBlank() }, if (conCasaVerde) "casaverde" else null).map { temaDe(it) }.toSet()

object Instantaneo {
    private fun prefs(c: Context) = c.getSharedPreferences("instantaneo", Context.MODE_PRIVATE)

    /** Arranca Firebase (una vez) y deja suscripto este teléfono a sus temas, y a ninguno más. Nunca tira. */
    fun iniciar(c: Context) {
        val app = c.applicationContext
        try {
            if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app, FirebaseOptions.Builder()
                .setProjectId(PROYECTO_AVISOS)
                .setApplicationId("1:232285662174:android:47a813703fa60821810b4f")
                .setApiKey("AIzaSyBWHE8pDrINe9pkxEeUvUwFlHPHBscmo3Y")
                .setGcmSenderId("232285662174")
                .build())
            val n = Nube(app)
            val quiero = if (n.conSesion) temasPara(n.sitio.id, CuentaCasaVerde(app).conSesion) else emptySet()
            val tengo = prefs(app).getStringSet("temas", emptySet()) ?: emptySet()
            val fm = FirebaseMessaging.getInstance()
            for (t in tengo - quiero) fm.unsubscribeFromTopic(t)
            for (t in quiero) fm.subscribeToTopic(t)
            prefs(app).edit().putStringSet("temas", quiero).apply()
        } catch (e: Throwable) {
            Bitacora.anotar(app, "aviso instantáneo: no arrancó (${e.javaClass.simpleName})")
        }
    }

    /** Avisar a las otras Pizarras que algo cambió. En su hilo, sin esperar; si falla, lo trae la pasada de 10 minutos. */
    fun avisar(c: Context, base: String, que: String, idToken: () -> String) {
        if (que !in QUE_CAMBIO || Nube.sinRed) return
        val app = c.applicationContext
        thread(name = "despertar") {
            try {
                val con = URL(URL_DESPERTAR).openConnection() as HttpURLConnection
                try {
                    con.requestMethod = "POST"; con.doOutput = true
                    con.connectTimeout = 8_000; con.readTimeout = 10_000
                    con.setRequestProperty("Content-Type", "application/json")
                    con.setRequestProperty("Authorization", "Bearer " + idToken())
                    con.outputStream.use { it.write(cuerpoDespertar(base, que).toByteArray()) }
                    val r = con.responseCode
                    if (r !in 200..299) Bitacora.anotar(app, "despertar: $base/$que contestó $r")
                } finally { con.disconnect() }
            } catch (e: Throwable) { Bitacora.anotar(app, "despertar: ${e.javaClass.simpleName}") }
        }
    }
}

/** El pedido a `despertar`: sólo la base y qué cambió, nunca el contenido. */
fun cuerpoDespertar(base: String, que: String): String =
    org.json.JSONObject().put("base", base).put("que", que).toString()

/** Llega el aviso: la Pizarra se pone al día con su propia sesión. */
class AvisoInstantaneo : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        // pizarra-63: una llamada no espera a que se ponga al día todo lo demás.
        if (m.data["que"] == "llamadas") { Llamadas.revisar(applicationContext); return }
        // pizarra-74: lo demás, sólo la parte que cambió (Despertar.partesDe).
        try { Despertar.por(applicationContext, m.data["que"]) } catch (e: Throwable) { }
    }
    // Con temas no hace falta guardar el token del teléfono en ningún lado.
    override fun onNewToken(token: String) { Instantaneo.iniciar(applicationContext) }
}
