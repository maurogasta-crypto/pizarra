package com.maurogasta.pizarra

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// AgregarActivity.kt — El «＋» del widget de la pizarra. Sello: pizarra-30
//
// Mauro, 14-oct-2026: «en la pizarra del widget tiene que haber un más que
// permita agregar un ítem rápidamente». Una ventanita encima de la pantalla de
// inicio: se escribe (o se dicta con el micrófono del teclado), «Agregar», y la
// tarea queda en Tiempos —personal, mía— y en MI pizarra, con la misma forma
// que la del sitio (`cuerpoNuevaTarea`). Queda abierta para agregar otra; el
// resultado se dice debajo del campo, al lado del botón (como pide CLAUDE.md:
// una pantalla que habla con la red dice lo que pasó ahí mismo).
// Sin sesión de Tiempos no hay dónde guardarla: lleva a entrar.
// ─────────────────────────────────────────────────────────────────────────────

class AgregarActivity : Activity() {
    private lateinit var campo: EditText
    private lateinit var dice: TextView
    private lateinit var boton: Button
    private val agregadas = mutableListOf<Fila>()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (!Nube(this).conSesion) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }
        caja.addView(TextView(this).apply { text = "Agregar a la pizarra"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        campo = EditText(this).apply {
            hint = "Qué hay que hacer"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setOnEditorActionListener { _, accion, _ -> if (accion == EditorInfo.IME_ACTION_DONE) { agregar(); true } else false }
        }
        caja.addView(campo)
        dice = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#8A8F98")); text = "" }
        caja.addView(dice)
        val botones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        botones.addView(Button(this).apply { text = "Listo"; setOnClickListener { finish() } })
        boton = Button(this).apply { text = "Agregar"; setOnClickListener { agregar() } }
        botones.addView(boton)
        caja.addView(botones)
        setContentView(caja)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        campo.requestFocus()
    }

    private fun agregar() {
        val titulo = tituloNuevo(campo.text.toString())
        if (titulo.isEmpty()) { dice.text = "Escribí qué hay que hacer."; return }
        boton.isEnabled = false
        dice.text = "Agregando…"
        thread {
            val error = try { val id = Nube(this).agregar(titulo); agregadas.add(Fila(id, titulo, false)); null }
                        catch (e: Throwable) { Bitacora.anotar(this, "agregar: " + (e.message ?: e.javaClass.simpleName)); e.message ?: "no se pudo" }
            runOnUiThread {
                boton.isEnabled = true
                if (error == null) {
                    dice.text = "✓ «$titulo» está en tu pizarra. Podés agregar otra."
                    campo.setText("")
                    // En el widget en el acto; después, lo que diga la base.
                    val cache = Cache(this)
                    cache.filas = (cache.filas.filter { f -> agregadas.none { it.id == f.id } } + agregadas)
                        .sortedWith(compareBy<Fila>({ it.hecho }, { it.texto.lowercase() }))
                    PizarraWidget.dibujar(this)
                    PizarraWidget.actualizarDesdeLaRed(this, null)
                } else dice.text = "No se agregó: $error. Probá de nuevo."
            }
        }
    }
}
