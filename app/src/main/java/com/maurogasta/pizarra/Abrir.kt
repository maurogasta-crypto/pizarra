package com.maurogasta.pizarra

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Abrir.kt — Adónde lleva tocar un aviso o una novedad. Sello: pizarra-61
//
// Mauro, 21-oct-2026: «revisá los avisos de la pizarra y la forma en la que
// direccionan al hacer clic, algunas no hacen nada claro». Hasta pizarra-57
// tocar un aviso de Claude —en la barra o en el widget— abría la pantalla de la
// app, arriba de todo, y nada más: el aviso decía «llega un huésped» y el toque
// no llevaba a la llegada. Ahora un toque lleva a LO QUE DICE el aviso
// (`destinoDeAviso`), lo marca leído, y sólo si no hay adónde ir abre la lista.
//
// Una pantalla invisible porque Android no deja abrir otra desde una difusión,
// y porque el widget tiene UNA plantilla para todos sus renglones: ésta decide.
// ─────────────────────────────────────────────────────────────────────────────

const val URL_PANEL = "https://maurogasta-crypto.github.io/datos/"

/** Adónde lleva un aviso: el enlace que trae el texto manda; si no, la página del sitio que habla de eso. */
fun destinoDeAviso(a: Aviso): String {
    Regex("https://[^\\s«»\"<>]+").find(a.texto)?.let { return it.value.trimEnd('.', ',', ')', ';', ':') }
    return when {
        a.sitio == "casaverde" && a.tema == "llegada" -> URL_RESERVAS_CV
        a.sitio == "tiempos" && a.tema == "recordatorio" -> TIEMPOS_URL + "?solapa=agenda"
        a.sitio == "panel" -> URL_PANEL
        else -> SITIOS.firstOrNull { it.id == a.sitio }?.panel.orEmpty()
    }
}

/** El Intent que abre una dirección: Tiempos adentro (pizarra-12), lo demás en el navegador. */
fun intentDe(c: Context, url: String): Intent =
    (if (esDeTiempos(url)) Intent(c, TiemposActivity::class.java).putExtra(EXTRA_URL, url)
     else Intent(Intent.ACTION_VIEW, Uri.parse(url))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Toques de los avisos (barra y widget) y de las novedades del widget de mensajes. */
class AbrirActivity : Activity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        try {
            val url = intent.getStringExtra("url").orEmpty()
            val aviso = intent.getStringExtra("aviso").orEmpty()
            if (aviso.isNotEmpty()) {
                // Leído en Casa Verde, Tiempos… (en fondo) y en la lista guardada del teléfono.
                val app = applicationContext
                with(Buzon) { app.avisosGuardados = app.avisosGuardados.map { if (it.id == aviso) it.copy(leido = true) else it } }
                thread(name = "leido") { try { Nube(app).marcarLeido(aviso) } catch (e: Throwable) { Bitacora.anotar(app, "avisos: no se pudo marcar leído: ${e.javaClass.simpleName}") } }
            }
            when {
                intent.getStringExtra("que") == "charla" -> startActivity(Intent(this, CharlaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("clave", intent.getStringExtra("clave").orEmpty()))
                url.isNotEmpty() -> try { startActivity(intentDe(this, url)) } catch (e: Throwable) {
                    Toast.makeText(this, "No hay con qué abrir $url", Toast.LENGTH_LONG).show() }
                else -> startActivity(Intent(this, MainActivity::class.java).putExtra("avisos", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        } catch (e: Throwable) {
            Bitacora.anotar(this, "abrir: " + (e.message ?: e.javaClass.simpleName))
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
