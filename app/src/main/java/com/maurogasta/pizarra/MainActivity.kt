package com.maurogasta.pizarra

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity.kt — Entrar una vez y elegir qué va a la pizarra. Sello: pizarra-1
//
// La pantalla se arma en código y no en XML: son dos estados (sin sesión y con
// sesión) y una lista, y así se lee de arriba abajo en un solo archivo.
//
// Elegir una tarea escribe `pizarra.<mi uid>` en ESA tarea, en la base: la
// selección sobrevive a reinstalar la app y no depende del teléfono.
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : Activity() {
    private lateinit var nube: Nube
    private lateinit var raiz: LinearLayout
    private lateinit var aviso: TextView

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        nube = Nube(this)
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#16191C"))
            addView(raiz)
        })
        pintar()
    }

    private fun pintar() {
        raiz.removeAllViews()
        raiz.addView(titulo("Pizarra"))
        aviso = texto("", 13f, "#9AA0A6")
        if (!nube.conSesion) pintarEntrada() else pintarEleccion()
    }

    private fun pintarEntrada() {
        raiz.addView(texto("Entrá con la misma cuenta que usás en Tiempos. La contraseña se usa una sola vez y no queda guardada en el teléfono.", 14f, "#C8CCD0"))
        val mail = campo("Mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val clave = campo("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        raiz.addView(mail)
        raiz.addView(clave)
        raiz.addView(boton("Entrar") {
            val m = mail.text.toString().trim()
            val c = clave.text.toString()
            if (m.isEmpty() || c.isEmpty()) { decir("Falta el mail o la contraseña."); return@boton }
            decir("Entrando…")
            enSegundo({ nube.entrar(m, c) }) { pintar() }
        })
        raiz.addView(aviso)
    }

    private fun pintarEleccion() {
        raiz.addView(texto("Tildá lo que querés en la pizarra. Desde el widget, tocar una tarea la tacha en Tiempos para todos; tocarla de nuevo la vuelve a pendiente.", 14f, "#C8CCD0"))
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("⟳ Actualizar") { cargar() })
        fila.addView(boton("Poner el widget") { ponerWidget() })
        raiz.addView(fila)
        raiz.addView(aviso)
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id = View.generateViewId() }
        raiz.addView(lista)
        raiz.addView(boton("Sacar las tachadas de la pizarra") { sacarTachadas() })
        raiz.addView(texto("Entraste como ${nube.mail ?: "—"}", 12f, "#6F757B"))
        raiz.addView(boton("Salir") {
            nube.salir()
            Cache(this).filas = emptyList()
            PizarraWidget.dibujar(this)
            pintar()
        })
        listaVista = lista
        cargar()
    }

    private var listaVista: LinearLayout? = null
    private var tareas: List<Tarea> = emptyList()

    private fun cargar() {
        decir("Trayendo las tareas…")
        enSegundo({ nube.tareas() }) { t ->
            tareas = t
            pintarLista()
            decir(if (t.isEmpty()) "No hay tareas en Tiempos." else "")
            PizarraWidget.actualizarDesdeLaRed(this, null)
        }
    }

    private fun pintarLista() {
        val lista = listaVista ?: return
        lista.removeAllViews()
        val porId = tareas.associateBy { it.id }
        for (t in paraElegir(tareas)) {
            val padre = t.parentId?.let { porId[it]?.titulo }
            val nombre = (if (padre != null) "$padre › " else "") + t.titulo +
                (if (t.alcance == "personal") "  · personal" else "") + (if (t.hecho) "  · hecha" else "")
            lista.addView(CheckBox(this).apply {
                text = nombre
                textSize = 15f
                setTextColor(Color.parseColor(if (t.hecho) "#8A8F98" else "#F2F2F2"))
                isChecked = t.enMiPizarra
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { caja, marcada ->
                    caja.isEnabled = false
                    enSegundo({ nube.fijar(t.id, marcada) }, {
                        caja.isEnabled = true
                        caja.isChecked = !marcada
                    }) {
                        caja.isEnabled = true
                        tareas = tareas.map { if (it.id == t.id) it.copy(enMiPizarra = marcada) else it }
                        PizarraWidget.actualizarDesdeLaRed(this@MainActivity, null)
                    }
                }
            })
        }
    }

    private fun sacarTachadas() {
        val hechas = tareas.filter { it.enMiPizarra && it.hecho }
        if (hechas.isEmpty()) { decir("No hay tachadas en la pizarra."); return }
        decir("Sacando ${hechas.size}…")
        enSegundo({ hechas.forEach { nube.fijar(it.id, false) } }) { cargar() }
    }

    private fun ponerWidget() {
        val m = getSystemService(AppWidgetManager::class.java)
        if (m != null && m.isRequestPinAppWidgetSupported) {
            m.requestPinAppWidget(ComponentName(this, PizarraWidget::class.java), null, null)
        } else {
            decir("Mantené apretada la pantalla de inicio → Widgets → Pizarra.")
        }
    }

    /* ── Utilidades ── */

    /** Corre `trabajo` fuera de la pantalla y vuelve con el resultado o el error. */
    private fun <T> enSegundo(trabajo: () -> T, siFalla: (() -> Unit)? = null, listo: (T) -> Unit) {
        thread {
            try {
                val r = trabajo()
                runOnUiThread { listo(r) }
            } catch (e: Exception) {
                runOnUiThread {
                    decir(e.message ?: "Algo salió mal.")
                    siFalla?.invoke()
                    if (e is ErrorNube && e.sinSesion && !nube.conSesion) pintar()
                }
            }
        }
    }

    private fun decir(t: String) {
        aviso.text = t
        aviso.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun titulo(t: String) = texto(t, 26f, "#FFFFFF").apply { setPadding(0, 0, 0, dp(10)) }

    private fun texto(t: String, tam: Float, color: String) = TextView(this).apply {
        text = t
        textSize = tam
        setTextColor(Color.parseColor(color))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun campo(pista: String, tipo: Int) = EditText(this).apply {
        hint = pista
        inputType = tipo
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#6F757B"))
    }

    private fun boton(t: String, alTocar: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { alTocar() }
    }
}
