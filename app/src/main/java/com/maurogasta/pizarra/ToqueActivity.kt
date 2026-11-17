package com.maurogasta.pizarra

import android.app.Activity
import android.content.Intent
import android.os.Bundle

// ─────────────────────────────────────────────────────────────────────────────
// ToqueActivity.kt — Qué hace un toque en un widget. Sello: pizarra-84 (y el de «Hoy»)
//
// Mauro, 12-oct-2026: «que el widget de pizarra también abra la tarea tocada».
// Un widget de lista tiene UNA sola plantilla para todos sus renglones, así que
// el ☐ y el texto no pueden ir a dos lugares distintos por sí solos. La
// plantilla trae acá, a una pantalla invisible que decide y se cierra:
//   · el ☐  → tacha o destacha (lo de siempre: la difusión ACCION_TACHAR);
//   · el texto → abre Tiempos en Tareas con la ficha de esa tarea (app-34).
// Es una Activity y no una difusión porque Android no deja abrir una pantalla
// desde una difusión en segundo plano.
// ─────────────────────────────────────────────────────────────────────────────

class ToqueActivity : Activity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        try {
            val id = intent.getStringExtra("id")
            val clave = intent.getStringExtra("clave")
            // pizarra-84: el widget «Hoy» (Hoy.kt): el ○ tilda, el texto abre Timón en ese ítem.
            if (clave != null && intent.getStringExtra("que") == "hoy") {
                sendBroadcast(Intent(this, HoyWidget::class.java).setAction(ACCION_HOY_TILDAR)
                    .putExtra("clave", clave).putExtra("tildar", intent.getBooleanExtra("tildar", true)))
            } else if (clave != null && intent.getStringExtra("que") == "abrir_item") {
                startActivity(HoyWidget.intentAbrir(this, Nube(this).conSesion, urlItem(clave)))
            } else if (id != null && intent.getStringExtra("que") == "abrir") {
                startActivity(
                    (if (Nube(this).conSesion) Intent(this, TiemposActivity::class.java).putExtra(EXTRA_URL, urlTarea(id))
                     else Intent(this, MainActivity::class.java))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } else if (id != null) {
                sendBroadcast(Intent(this, PizarraWidget::class.java).setAction(ACCION_TACHAR)
                    .putExtra("id", id).putExtra("hecho", intent.getBooleanExtra("hecho", true)))
            }
        } catch (e: Throwable) {
            Bitacora.anotar(this, "toque: " + (e.message ?: e.javaClass.simpleName))
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
