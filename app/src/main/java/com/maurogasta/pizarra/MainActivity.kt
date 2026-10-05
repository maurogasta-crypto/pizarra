package com.maurogasta.pizarra

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
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
// MainActivity.kt — Entrar una vez y elegir qué va a la pizarra. Sello: pizarra-4
//
// pizarra-2 (5-oct-2026, «que la Pizarra sea la app de Tiempos»): arriba de
// todo, 🎙 Dictar —con el reconocedor de Android, el mismo del teclado— y el
// texto corregible que se le pasa a Tiempos; después «⭐ Hoy se puede» y las
// próximas alarmas. El widget abre directo en el dictado.
//
// La pantalla se arma en código y no en XML: son dos estados (sin sesión y con
// sesión) y una lista, y así se lee de arriba abajo en un solo archivo.
//
// Elegir una tarea escribe `pizarra.<mi uid>` en ESA tarea, en la base: la
// selección sobrevive a reinstalar la app y no depende del teléfono.
// ─────────────────────────────────────────────────────────────────────────────

const val EXTRA_DICTAR = "dictar"
private const val PEDIDO_VOZ = 41

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
            // Que la caja del dictado no se lleve el foco al abrir (y con él el
            // teclado, tapando los botones): el foco arranca acá.
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val vista = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#16191C"))
            addView(raiz)
        }
        // pizarra-3: desde Android 15 la app se dibuja DEBAJO de las barras del
        // sistema y del teclado, y «adjustResize» ya no achica nada. Sin esto lo
        // de arriba queda bajo la hora y lo de abajo bajo los botones del
        // sistema o el teclado: se ve, y no se puede tocar.
        if (Build.VERSION.SDK_INT >= 30) vista.setOnApplyWindowInsetsListener { v, ins ->
            val m = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime() or
                android.view.WindowInsets.Type.displayCutout())
            v.setPadding(m.left, m.top, m.right, m.bottom)
            ins
        }
        setContentView(vista)
        raiz.requestFocus()
        Bitacora.anotar(this, "pantalla: abre (sesión ${if (nube.conSesion) "sí" else "no"})")
        pintar()
        if (intent?.getBooleanExtra(EXTRA_DICTAR, false) == true && nube.conSesion) dictar()
        mandarPendiente()
    }

    /* pizarra-3: si la vez anterior se cerró o se trabó, se manda a Tiempos. */
    private fun mandarPendiente() {
        val p = Bitacora.pendiente(this) ?: return
        if (!nube.conSesion) return
        thread {
            val ok = try { nube.mandarFalla(p, "no-anda"); true } catch (e: Throwable) {
                Bitacora.anotar(this, "no se pudo mandar la falla: ${e.message}"); false }
            if (ok) Bitacora.olvidarPendiente(this)
            runOnUiThread { decir(if (ok) "La vez anterior la app falló: ya se lo mandé a Claude." else "La vez anterior la app falló; lo mando cuando haya señal.") }
        }
    }

    private fun mandarDiagnostico() {
        val texto = "Diagnóstico pedido a mano desde la app.\n\n" + Bitacora.ultimos(this, 40).joinToString("\n")
        decir("Mandando…")
        enSegundo({ nube.mandarFalla(texto, "traba") }) { decir("Mandado a Claude. Contale en el chat qué estabas tocando.") }
    }

    // El widget con la app ya abierta: singleTop entrega acá.
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (i.getBooleanExtra(EXTRA_DICTAR, false) && nube.conSesion) dictar()
    }

    private fun pintar() {
        raiz.removeAllViews()
        raiz.addView(titulo("Tiempos"))
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

    /* ── pizarra-2: dictar, hoy se puede, alarmas ── */

    private var dictado: EditText? = null
    private var hoyVista: LinearLayout? = null
    private var alertasVista: TextView? = null

    private fun pintarTiempos() {
        raiz.addView(subtitulo("🎙 Para la agenda"))
        raiz.addView(texto("Dictá o escribí, corregí, y Tiempos arma el plan: agenda, pizarra, recordatorio, alarma, deseo o compras.", 13f, "#9AA0A6"))
        val caja = EditText(this).apply {
            hint = "Ej.: mañana ir antes al gimnasio para llevarle los títulos a Pedro"
            minLines = 2
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6F757B"))
        }
        dictado = caja
        raiz.addView(caja)
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("🎙 Dictar") { dictar() })
        fila.addView(boton("✨ Seguir en Tiempos") {
            val t = caja.text.toString().trim()
            if (t.isEmpty()) { decir("Dictá o escribí algo primero."); return@boton }
            abrir(urlDictado(t))
            caja.setText("")
        })
        raiz.addView(fila)
        // Los avisos de la pantalla van acá arriba, al lado del dictado.
        raiz.addView(aviso)

        raiz.addView(subtitulo("⭐ Hoy se puede"))
        hoyVista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(hoyVista)

        raiz.addView(subtitulo("⏰ Alarmas y recordatorios"))
        alertasVista = texto("", 13f, "#C8CCD0")
        raiz.addView(alertasVista)
        permisos()
        cargarTiempos()
    }

    private fun dictar() {
        Bitacora.anotar(this, "dictar: abre el reconocedor")
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-UY")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Decí qué hay que agendar o recordar")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PEDIDO_VOZ)
        } catch (e: ActivityNotFoundException) {
            decir("Este teléfono no tiene reconocedor de voz: escribilo, o usá el micrófono del teclado.")
            dictado?.requestFocus()
        }
    }

    @Deprecated("Activity a secas, sin bibliotecas: es la forma que hay")
    override fun onActivityResult(pedido: Int, resultado: Int, datos: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(pedido, resultado, datos)
        if (pedido != PEDIDO_VOZ || resultado != RESULT_OK) return
        val dicho = datos?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        val caja = dictado ?: return
        if (dicho.isEmpty()) return
        // Se SUMA a lo que ya había: se puede dictar en dos tandas.
        val antes = caja.text.toString().trim()
        caja.setText(if (antes.isEmpty()) dicho else "$antes $dicho")
        caja.setSelection(caja.text.length)
        caja.requestFocus()
        decir("Corregí si hace falta y tocá ✨ Seguir en Tiempos.")
    }

    private fun cargarTiempos() {
        Alarmas.asegurarTrabajo(this)
        Alarmas.sincronizar(this) { m -> Bitacora.anotar(this, "alarmas: $m"); runOnUiThread { pintarAlertas() } }
        thread {
            val hoy = java.time.LocalDate.now().toString()
            val r = try { Result.success(posiblesDelDia(nube.deseos(), hoy)) } catch (e: Throwable) { Result.failure(e) }
            Bitacora.anotar(this, "hoy se puede: " + r.fold({ "${it.size}" }, { "error ${it.message}" }))
            runOnUiThread { pintarHoy(r) }
        }
    }

    private fun pintarHoy(r: Result<List<Deseo>>) {
        val v = hoyVista ?: return
        v.removeAllViews()
        val lista = r.getOrElse { v.addView(texto(it.message ?: "No se pudo traer.", 13f, "#9AA0A6")); return }
        if (lista.isEmpty()) { v.addView(texto("Nada para hoy en los deseos. Se cargan desde Tiempos (un flyer, «me gustaría…»).", 13f, "#6F757B")); return }
        for (d in lista) {
            val hora = if (d.hi.isNotEmpty()) d.hi + (if (d.hf.isNotEmpty()) "–" + d.hf else "") + "  " else ""
            v.addView(texto(hora + d.titulo + (if (d.lugar.isNotEmpty()) " · " + d.lugar else "") +
                (if (d.fecha.isNotEmpty()) "  (sólo hoy)" else ""), 15f, "#F2F2F2").apply {
                setOnClickListener { abrir(TIEMPOS_URL) }
            })
        }
    }

    private fun pintarAlertas() {
        val v = alertasVista ?: return
        val zona = java.time.ZoneId.systemDefault()
        val prox = with(Alarmas) { paraProgramar(alertasGuardadas, System.currentTimeMillis(), zona) }
        val partes = mutableListOf<String>()
        if (prox.isEmpty()) partes.add("No hay ninguna por delante. Se crean dictando («poné una alarma…»).")
        prox.take(6).forEach { (a, _) -> partes.add((if (a.tipo == "alarma") "⏰ " else "🔔 ") + a.dia.substring(8) + "/" + a.dia.substring(5, 7) + " " + a.hora + "  " + a.texto) }
        if (!Alarmas.exactasPermitidas(this)) partes.add("⚠ Android no deja alarmas exactas: pueden sonar unos minutos tarde. Tocá acá para permitirlas.")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            partes.add("⚠ Sin permiso de notificaciones no suena nada. Tocá acá.")
        v.text = partes.joinToString("\n")
        v.setOnClickListener { permisos(insistir = true) }
    }

    /** Notificaciones y alarmas exactas: se piden una vez; si se negaron, se manda a Ajustes. */
    private fun permisos(insistir: Boolean = false) {
        Bitacora.anotar(this, "permisos (insistir=$insistir)")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (insistir && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) && pedidoYa)
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            else { pedidoYa = true; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42) }
            return
        }
        if (insistir && !Alarmas.exactasPermitidas(this) && Build.VERSION.SDK_INT >= 31)
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }
    private var pedidoYa = false

    override fun onRequestPermissionsResult(pedido: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(pedido, permisos, resultados)
        pintarAlertas()
    }

    private fun abrir(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: ActivityNotFoundException) { decir("No hay navegador para abrir Tiempos.") }
    }

    private fun subtitulo(t: String) = texto(t, 18f, "#E8D3A2").apply { setPadding(0, dp(18), 0, dp(4)) }

    private fun pintarEleccion() {
        pintarTiempos()
        raiz.addView(subtitulo("📝 La pizarra"))
        raiz.addView(texto("Tildá lo que querés en la pizarra. Desde el widget, tocar una tarea la tacha en Tiempos para todos; tocarla de nuevo la vuelve a pendiente.", 14f, "#C8CCD0"))
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("⟳ Actualizar") { cargar() })
        fila.addView(boton("Poner el widget") { ponerWidget() })
        raiz.addView(fila)
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id = View.generateViewId() }
        raiz.addView(lista)
        raiz.addView(boton("Sacar las tachadas de la pizarra") { sacarTachadas() })
        raiz.addView(texto("Entraste como ${nube.mail ?: "—"} · $SELLO", 12f, "#6F757B"))
        raiz.addView(boton("🩺 Mandar diagnóstico a Claude") { mandarDiagnostico() })
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
            } catch (e: Throwable) {
                Bitacora.anotar(this, "error: " + (e.message ?: e.toString()))
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
