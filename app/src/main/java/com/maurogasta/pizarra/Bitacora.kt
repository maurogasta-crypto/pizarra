package com.maurogasta.pizarra

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Bitacora.kt — Que la app diga sola qué le pasó. Sello: pizarra-6
//
// 5-oct-2026: pizarra-2 pasó el banco y compiló, y en el teléfono de Mauro
// «se traba, no deja ningún botón». El informe de fallos que ofreció HyperOS
// se fue a Xiaomi, no a nosotros, y no quedó nada para leer.
//
// Desde acá la app anota lo que hace (un anillo de 80 renglones), y si se
// CIERRA (una excepción sin atajar) o se TRABA (la pantalla no contesta en 5
// segundos) guarda qué estaba haciendo —con la pila del hilo de la pantalla—
// y al volver a abrirla lo manda a `reportes/` de Tiempos como una falla, con
// la sesión de la persona: lo mismo que «Algo anda mal» del sitio. La ronda de
// Claude lo trae como cualquier falla.
//
// Lo que NO lleva: el texto dictado, títulos de tareas, la sesión. Sólo qué
// pantalla, qué paso, qué error y el modelo de teléfono.
// ─────────────────────────────────────────────────────────────────────────────

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Bitacora.vigilar(this)
        Bitacora.anotar(this, "abre la app · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · $SELLO")
    }
}

const val SELLO = "pizarra-6"

object Bitacora {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("bitacora", Context.MODE_PRIVATE)
    private val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    fun anotar(c: Context, t: String) = synchronized(this) {
        val p = prefs(c)
        val l = renglones(p.getString("anillo", null)) + (hora.format(Date()) + " " + t.take(300))
        p.edit().putString("anillo", JSONArray(l.takeLast(80)).toString()).apply()
    }

    fun ultimos(c: Context, n: Int = 25): List<String> = renglones(prefs(c).getString("anillo", null)).takeLast(n)

    private fun renglones(s: String?): List<String> = try {
        val a = JSONArray(s ?: "[]"); (0 until a.length()).map { a.optString(it) }
    } catch (e: Exception) { emptyList() }

    /** Lo que quedó para mandar de la vez anterior (un cierre o una trabada). */
    fun pendiente(c: Context): String? = prefs(c).getString("pendiente", null)

    private fun guardarPendiente(c: Context, que: String, detalle: String) {
        val texto = "$que\n\n$detalle\n\nLo último que hizo:\n" + ultimos(c).joinToString("\n")
        // commit y no apply: después de esto el proceso se muere.
        prefs(c).edit().putString("pendiente", texto.take(1900)).commit()
    }

    fun olvidarPendiente(c: Context) { prefs(c).edit().remove("pendiente").apply() }

    /** Excepciones sin atajar y una pantalla que no contesta. */
    fun vigilar(c: Context) {
        val app = c.applicationContext
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, e ->
            try { guardarPendiente(app, "La app se CERRÓ (hilo «${hilo.name}»).", pila(e)) } catch (x: Throwable) {}
            anterior?.uncaughtException(hilo, e)
        }
        // El perro guardián: cada 2 s le pide a la pantalla que conteste; si en
        // 5 s no lo hizo, está trabada, y se anota DÓNDE (la pila del hilo de
        // la pantalla). Una vez por apertura: con una alcanza para saberlo.
        val pantalla = Handler(Looper.getMainLooper())
        Thread({
            var avisado = false
            while (!avisado) {
                val contesto = java.util.concurrent.atomic.AtomicBoolean(false)
                pantalla.post { contesto.set(true) }
                SystemClock.sleep(5000)
                if (!contesto.get()) {
                    val p = Looper.getMainLooper().thread.stackTrace.take(25).joinToString("\n") { "  at $it" }
                    try { guardarPendiente(app, "La pantalla se TRABÓ: 5 s sin contestar.", p) } catch (x: Throwable) {}
                    anotar(app, "TRABADA — guardada para mandar")
                    avisado = true
                }
            }
        }, "guardian").apply { isDaemon = true }.start()
    }

    fun pila(e: Throwable): String = (e.toString() + "\n" + e.stackTrace.take(20).joinToString("\n") { "  at $it" } +
        (e.cause?.let { "\nCausa: $it\n" + it.stackTrace.take(8).joinToString("\n") { s -> "  at $s" } } ?: "")).take(1400)
}

/**
 * El cuerpo de un `commit` que crea UNA falla en `reportes/` de Tiempos, con
 * la forma del sitio (sugerir.js): tipo falla, gravedad, estado nuevo. Lo
 * pide la regla: `uid` el de quien escribe y `texto` de 1 a 2000.
 */
fun cuerpoFalla(id: String, uid: String, mail: String, texto: String, gravedad: String): JSONObject {
    fun s(v: String) = JSONObject().put("stringValue", v)
    val campos = JSONObject()
        .put("uid", s(uid)).put("email", s(mail)).put("nombre", s(""))
        .put("tipo", s("falla")).put("estado", s("nuevo")).put("gravedad", s(gravedad))
        .put("pagina", s("app-telefono")).put("origenApp", s(SELLO))
        .put("texto", s(texto.trim().ifEmpty { "(sin detalle)" }.take(2000)))
        .put("esperaba", s("Que la app del teléfono abriera y respondiera."))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$BASE_DOCS/reportes/$id").put("fields", campos))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))
}

/** Un id como los de Firestore: 20 letras y números. */
fun idNuevo(azar: java.util.Random = java.security.SecureRandom()): String {
    val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..20).map { abc[azar.nextInt(abc.length)] }.joinToString("")
}
