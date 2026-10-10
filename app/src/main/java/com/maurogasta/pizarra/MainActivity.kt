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
// MainActivity.kt — Los ajustes de la app, y Compartir. Sello: pizarra-15
//
// pizarra-15 (10-oct-2026, Mauro): «📝 La pizarra» está PLEGADA y se abre con
// un toque; adentro, las tareas por categoría (`paraElegirPorCategoria`). Y el
// cuadro de arriba ya no es «Para la agenda» sino «✨ Pedile a la IA»: lo que
// se escribe ahí puede ser cualquier cosa que Tiempos registra —un gasto, los
// chicos, la agenda, una alarma, un deseo, compras—, y el plan lo arma Tiempos.
//
// pizarra-14 (tiempos:V10, 9-oct-2026): la Pizarra es de todo el equipo. Al
// entrar se elige el sitio; arriba de todo van los AVISOS de Claude (Avisos.kt)
// con su configuración —un canal de Android por sitio—, y lo de Tiempos sólo
// aparece con una cuenta de Tiempos.
//
// pizarra-12 (7-oct-2026, tiempos:V7): Tiempos se abre ADENTRO de la app
// (TiemposActivity), y ésta pasa a ser la pantalla de ajustes —la cuenta del
// widget, la bodega, el diagnóstico— y la que recibe «Compartir». Sin botón de
// dictado: se dicta con el micrófono del teclado (Mauro: «sacar el dictado»).
// Lo que sigue cuenta cómo era.
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

private const val PEDIDO_CAPTURA = 43

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
        recibirCompartido(intent)
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
    /* pizarra-5: al VOLVER a la app (del navegador, después de dictar) se pone
       al día. Una alarma «para dentro de 2 minutos» la guarda el sitio, y la
       app no se enteraba hasta la próxima media hora: no sonó (5-oct, 00:45). */
    private var yaAbrio = false
    override fun onResume() {
        super.onResume()
        if (!yaAbrio) { yaAbrio = true; return }      // la primera vez ya cargó onCreate
        refrescarBodega()
        if (!nube.conSesion || alertasVista == null) return
        Bitacora.anotar(this, "vuelve a la app: se pone al día")
        cargarTiempos()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        recibirCompartido(i)
    }

    private fun pintar() {
        raiz.removeAllViews()
        raiz.addView(titulo(if (!nube.conSesion) "Pizarra" else if (nube.esTiempos) "Tiempos" else "Pizarra · ${nube.sitio.nombre}"))
        aviso = texto("", 13f, "#9AA0A6")
        when {
            !nube.conSesion -> pintarEntrada()
            nube.esTiempos -> { pintarAvisos(); pintarEleccion() }
            else -> { pintarAvisos(); pintarCuenta() }
        }
    }

    private var sitioElegido = "tiempos"

    private fun pintarEntrada() {
        raiz.addView(texto("¿De qué sitio sos? Entrá con la misma cuenta que usás ahí. La contraseña se usa una sola vez y no queda guardada en el teléfono.", 14f, "#C8CCD0"))
        val grupo = android.widget.RadioGroup(this)
        for (s in SITIOS.filter { it.cuenta }) grupo.addView(android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = s.nombre
            textSize = 15f
            setTextColor(Color.parseColor("#F2F2F2"))
            isChecked = s.id == sitioElegido
            setOnCheckedChangeListener { _, si -> if (si) sitioElegido = s.id }
        })
        raiz.addView(grupo)
        val mail = campo("Mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val clave = campo("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        raiz.addView(mail)
        raiz.addView(clave)
        raiz.addView(boton("Entrar") {
            val m = mail.text.toString().trim()
            val c = clave.text.toString()
            if (m.isEmpty() || c.isEmpty()) { decir("Falta el mail o la contraseña."); return@boton }
            decir("Entrando…")
            enSegundo({ nube.entrar(m, c, sitioElegido) }) {
                Bitacora.anotar(this, "entró con ${nube.sitio.id}")
                // Con Tiempos, el ícono abre Tiempos adentro; con otro sitio, esta pantalla.
                if (nube.esTiempos) startActivity(Intent(this, TiemposActivity::class.java))
                pintar()
            }
        })
        raiz.addView(aviso)
    }

    /* ── pizarra-14: los avisos de Claude, de todo el ecosistema ── */

    private var avisosVista: LinearLayout? = null

    private fun pintarAvisos() {
        raiz.addView(subtitulo("🔔 Avisos de Claude"))
        raiz.addView(texto("Lo que Claude te avisa de ${if (nube.esTiempos) "todos los sitios" else nube.sitio.nombre}. Llegan solos como notificación (puede tardar hasta una hora). Cada sitio tiene su canal: lo silenciás o lo dejás sonar en «Configurar».", 13f, "#9AA0A6"))
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        avisosVista = lista
        raiz.addView(lista)
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("⟳ Traer") { traerAvisos() })
        fila.addView(boton("⚙ Configurar") { configurarAvisos() })
        raiz.addView(fila)
        raiz.addView(texto("WhatsApp se prende o se apaga en «Mis avisos por WhatsApp» de tu sitio.", 12f, "#6F757B").apply {
            setOnClickListener { abrir(nube.sitio.panel) }
        })
        Buzon.crearCanales(this)
        permisos()
        Alarmas.asegurarTrabajo(this)
        pintarListaAvisos(with(Buzon) { avisosGuardados })
        traerAvisos()
    }

    private fun traerAvisos() {
        enSegundo({ Buzon.ponerAlDia(this, nube) }) { pintarListaAvisos(it) }
    }

    private fun pintarListaAvisos(avisos: List<Aviso>) {
        val v = avisosVista ?: return
        v.removeAllViews()
        if (avisos.isEmpty()) { v.addView(texto("Todavía no hay avisos.", 13f, "#6F757B")); return }
        for (a in avisos.take(15)) {
            val cuando = instante(a.creado)?.let {
                java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(it))
            }.orEmpty()
            v.addView(texto("${if (a.leido) "" else "● "}${iconoTema(a.tema)} ${nombreSitio(a.sitio)} · $cuando\n${a.texto}", 14f,
                if (a.leido) "#8A8F98" else "#F2F2F2").apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    if (!a.leido) enSegundo({ nube.marcarLeido(a.id) }) {
                        pintarListaAvisos(with(Buzon) { avisosGuardados }.map { x -> if (x.id == a.id) x.copy(leido = true) else x })
                    }
                    // Un enlace del ecosistema en el texto se abre (Tiempos, adentro).
                    Regex("https://\\S+").find(a.texto)?.let { m -> abrir(m.value.trimEnd('.', ',', ')')) }
                }
            })
        }
    }

    /** La configuración de los avisos: la pantalla de notificaciones de la app,
     *  con un canal por sitio. Es de Android: no hay que inventar otra. */
    private fun configurarAvisos() {
        Buzon.crearCanales(this)
        try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        catch (e: ActivityNotFoundException) { decir("Buscá en Ajustes → Aplicaciones → Pizarra → Notificaciones.") }
    }

    /** Lo que queda abajo con una cuenta que no es de Tiempos. */
    private fun pintarCuenta() {
        raiz.addView(aviso)
        raiz.addView(texto("Entraste como ${nube.mail ?: "—"} (${nube.sitio.nombre}) · $SELLO", 12f, "#6F757B"))
        raiz.addView(boton("Abrir ${nube.sitio.nombre}") { abrir(nube.sitio.panel) })
        raiz.addView(boton("Salir") { nube.salir(); pintar() })
    }

    /* ── pizarra-2: dictar, hoy se puede, alarmas ── */

    private var dictado: EditText? = null
    private var hoyVista: LinearLayout? = null
    private var proponeVista: LinearLayout? = null
    private var alertasVista: TextView? = null

    private fun pintarTiempos() {
        raiz.addView(subtitulo("✨ Pedile a la IA"))
        raiz.addView(texto("Lo que sea, dictado o escrito: un gasto, algo de los chicos, mover o sacar algo de tu agenda, una alarma, un deseo, una compra. Tiempos arma el plan y vos marcás qué va.", 13f, "#9AA0A6"))
        val caja = EditText(this).apply {
            hint = "Ej.: gasté 850 en la farmacia · el básquet pasa a las 18 · mañana alarma 7:30"
            minLines = 2
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6F757B"))
        }
        dictado = caja
        raiz.addView(caja)
        compartidoPendiente?.let { caja.setText(it); compartidoPendiente = null }
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("📷 Captura") { elegirCaptura() })
        raiz.addView(fila)
        capturaVista = texto("", 12f, "#9AA0A6")
        raiz.addView(capturaVista)
        pintarCaptura()
        raiz.addView(boton("✨ Seguir en Tiempos") { seguir() })
        // Los avisos de la pantalla van acá arriba, al lado del dictado.
        raiz.addView(aviso)

        // pizarra-11 (tiempos:V3): lo que Claude te propone. Aceptar es en Tiempos.
        raiz.addView(subtitulo("🤖 Claude propone"))
        proponeVista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(proponeVista)

        raiz.addView(subtitulo("⭐ Hoy se puede"))
        hoyVista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(hoyVista)

        raiz.addView(subtitulo("⏰ Alarmas y recordatorios"))
        alertasVista = texto("", 13f, "#C8CCD0")
        raiz.addView(alertasVista)
        permisos()
        cargarTiempos()
    }

    /* ── pizarra-7: Compartir desde otra app (Instagram, Facebook, TikTok…) ── */

    private var captura: Uri? = null
    private var capturaVista: TextView? = null
    private var compartidoPendiente: String? = null

    /** Lo que llegó por «Compartir»: el texto a la caja; una imagen, como captura. */
    private fun recibirCompartido(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        i.action = null                                   // que girar la pantalla no lo repita
        val img = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        if (img != null && (i.type ?: "").startsWith("image/")) guardarCaptura(img)
        val t = textoCompartido(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
        Bitacora.anotar(this, "compartido: " + (if (img != null) "imagen " else "") + linksDe(t).map { redDe(it) })
        if (t.isNotEmpty()) {
            val caja = dictado
            if (caja == null) compartidoPendiente = t else caja.setText(t)
        }
        if (!nube.conSesion) { decir("Entrá con tu cuenta y lo compartido queda en la caja del dictado."); return }
        val links = linksDe(t)
        decir(when {
            links.isNotEmpty() && captura == null && links.any { redDe(it) != "la web" } ->
                "${redDe(links[0])} no deja leer sus publicaciones desde afuera: agregá una 📷 Captura del reel o del flyer, y si querés dictá qué te interesa."
            else -> "Corregí o agregá lo que quieras y tocá ✨ Seguir en Tiempos."
        })
    }

    private fun elegirCaptura() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { @Suppress("DEPRECATION") startActivityForResult(Intent.createChooser(i, "Captura del reel o del flyer"), PEDIDO_CAPTURA) }
        catch (e: ActivityNotFoundException) { decir("No hay dónde elegir una imagen en este teléfono.") }
    }

    /** Se copia ya: el permiso para leer lo compartido dura lo que dura esta pantalla. */
    private fun guardarCaptura(u: Uri) {
        try {
            val f = java.io.File(cacheDir, "captura.img")
            contentResolver.openInputStream(u)?.use { ent -> f.outputStream().use { ent.copyTo(it) } }
            captura = Uri.fromFile(f)
        } catch (e: Throwable) {
            Bitacora.anotar(this, "captura: no se pudo copiar: ${e.message}")
            decir("No pude leer esa imagen.")
        }
        pintarCaptura()
    }

    private fun pintarCaptura() {
        val v = capturaVista ?: return
        v.text = if (captura != null) "📷 Captura lista · se sube al tocar Seguir  (tocá acá para sacarla)" else ""
        v.visibility = if (captura != null) View.VISIBLE else View.GONE
        v.setOnClickListener { captura = null; pintarCaptura() }
    }

    private fun seguir() {
        val caja = dictado ?: return
        val t = caja.text.toString().trim().ifEmpty { if (captura != null) "Lo de la captura." else "" }
        if (t.isEmpty()) { decir("Escribí o agregá una captura primero."); return }
        val c = captura
        if (c == null) { abrir(urlDictado(t)); caja.setText(""); return }
        decir("Subiendo la captura…")
        enSegundo({ Imagenes.subir(this, c) }) { url ->
            Bitacora.anotar(this, "captura subida")
            abrir(urlDictado(t, url))
            caja.setText(""); captura = null; pintarCaptura(); decir("")
        }
    }

    @Deprecated("Activity a secas, sin bibliotecas: es la forma que hay")
    override fun onActivityResult(pedido: Int, resultado: Int, datos: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(pedido, resultado, datos)
        if (pedido == PEDIDO_CAPTURA) { if (resultado == RESULT_OK) datos?.data?.let { guardarCaptura(it) }; return }
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
        thread {
            val r = try { Result.success(nube.propuestas()) } catch (e: Throwable) { Result.failure(e) }
            Bitacora.anotar(this, "claude propone: " + r.fold({ "${it.size}" }, { "error ${it.message}" }))
            runOnUiThread { pintarPropone(r) }
        }
    }

    private fun pintarPropone(r: Result<List<Propuesta>>) {
        val v = proponeVista ?: return
        v.removeAllViews()
        val ps = r.getOrElse { v.addView(texto("No se pudo traer: ${it.message}", 13f, "#9AA0A6")); return }
        if (ps.isEmpty()) { v.addView(texto("Nada pendiente.", 13f, "#9AA0A6")); return }
        for (p in ps) {
            val cuando = listOf(p.dia, p.hora).filter { it.isNotEmpty() }.joinToString(" ")
            val que = (if (p.clase == "consulta") "❓ " else "📅 ") + p.titulo.ifEmpty { p.resumen }
            v.addView(texto(que + (if (cuando.isNotEmpty()) "  · $cuando" else ""), 14f, "#F2F2F2"))
        }
        v.addView(boton("Ver y aceptar en Tiempos") { abrir(TIEMPOS_URL) })
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

    /** Tiempos se abre ADENTRO (pizarra-12); lo demás, en el navegador. */
    private fun abrir(url: String) {
        if (esDeTiempos(url)) { startActivity(Intent(this, TiemposActivity::class.java).putExtra(EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); return }
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: ActivityNotFoundException) { decir("No hay navegador para abrir Tiempos.") }
    }

    private fun subtitulo(t: String) = texto(t, 18f, "#E8D3A2").apply { setPadding(0, dp(18), 0, dp(4)) }

    private fun pintarEleccion() {
        pintarTiempos()
        // pizarra-15: plegada. Se abre con un toque, y recién ahí la lista.
        val titulo = subtitulo("").apply { isClickable = true }
        val cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cuerpo.addView(texto("Tildá lo que querés en la pizarra. Desde el widget, tocar una tarea la tacha en Tiempos para todos; tocarla de nuevo la vuelve a pendiente.", 14f, "#C8CCD0"))
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("⟳ Actualizar") { cargar() })
        fila.addView(boton("Poner el widget") { ponerWidget() })
        cuerpo.addView(fila)
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id = View.generateViewId() }
        cuerpo.addView(lista)
        cuerpo.addView(boton("Sacar las tachadas de la pizarra") { sacarTachadas() })
        pizarraTitulo = titulo
        pizarraCuerpo = cuerpo
        titulo.setOnClickListener { pizarraAbierta = !pizarraAbierta; pintarPliegue() }
        raiz.addView(titulo)
        raiz.addView(cuerpo)
        pintarPliegue()
        pintarBodega()
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

    /* ── pizarra-9: los mensajes de Airbnb a la bodega (Bodega.kt) ──
       Lo que Termux:API no pudo en este Xiaomi. Tres cosas a la vista: si
       Android le dio el acceso, si está el token, y qué subió por última vez. */
    private var bodegaVista: TextView? = null
    private fun pintarBodega() {
        raiz.addView(subtitulo("📬 Mensajes de Airbnb → Claude"))
        val estado = texto("", 14f, "#C8CCD0"); bodegaVista = estado
        raiz.addView(estado)
        raiz.addView(boton("Dar acceso a notificaciones") { abrirAccesoNotificaciones() })
        val token = campo("Token de la bodega (lo pegás una vez)", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        raiz.addView(token)
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("Guardar token") { guardarToken(token) })
        fila.addView(boton("Subir ahora") { subirAhora() })
        raiz.addView(fila)
        refrescarBodega()
    }

    private fun refrescarBodega() {
        val b = Bodega(this)
        val acceso = Bodega.tieneAcceso(this)
        bodegaVista?.text = listOf(
            if (acceso) "✔ Puede leer las notificaciones." else "✖ Todavía no puede leer las notificaciones. Si el interruptor sale gris: Ajustes → Aplicaciones → Pizarra → ⋮ → «Permitir ajustes restringidos», y volvé a tocar el botón.",
            if (b.conToken) "✔ Token de la bodega guardado." else "✖ Falta el token de la bodega (en Termux: cat ~/.config/bodega/token).",
            "Último envío: " + b.ultimaSubida.ifEmpty { "todavía nada" },
            if (b.ultimoError.isNotEmpty()) "Último problema: ${b.ultimoError}" else "",
        ).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun abrirAccesoNotificaciones() {
        Bitacora.anotar(this, "bodega: abre el acceso a notificaciones")
        val comp = ComponentName(this, LectorAirbnb::class.java)
        val directo = if (Build.VERSION.SDK_INT >= 30) Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, comp.flattenToString()) else null
        for (i in listOfNotNull(directo, Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            try { startActivity(i); return } catch (e: Exception) { }
        }
        decir("Buscá «acceso a notificaciones» en Ajustes y prendé Pizarra.")
    }

    private fun guardarToken(campo: EditText) {
        // pizarra-10: copiado de la pantalla de Termux, un token largo llega
        // partido en renglones (Termux corta la línea al ancho de la pantalla)
        // y GitHub contesta 401. Un token no lleva espacios ni saltos: se sacan.
        val t = limpiarToken(campo.text.toString())
        if (t.length < 20) { decir("Pegá el token entero."); return }
        val b = Bodega(this)
        b.guardarToken(t)
        campo.setText("")
        decir("Probando el token…")
        enSegundo({ conError(b) { b.probar(); b.latir(Bodega.fallaActual(this), forzar = true) } }) {
            Bitacora.anotar(this, "bodega: token guardado y probado (${t.length} caracteres)")
            decir("Listo: la bodega contesta y ya latió.")
            refrescarBodega()
        }
    }

    /** El error queda ESCRITO en la sección, no sólo arriba de la pantalla
     *  (pizarra-10: Mauro tocaba «Subir ahora» y «no hacía nada»). */
    private fun <T> conError(b: Bodega, trabajo: () -> T): T = try {
        trabajo().also { b.ultimoError = "" }
    } catch (e: Throwable) {
        b.ultimoError = e.message ?: e.toString()
        runOnUiThread { refrescarBodega() }
        throw e
    }

    private fun subirAhora() {
        val b = Bodega(this)
        if (!b.conToken) { decir("Primero guardá el token de la bodega."); return }
        decir("Subiendo…")
        LectorAirbnb.reconectar(this)
        enSegundo({ conError(b) { b.latir(Bodega.fallaActual(this), forzar = true) } }) {
            decir(if (Bodega.tieneAcceso(this)) "Latió. Lo que esté en la barra de Airbnb sube enseguida." else "Latió, avisando que no puede leer: falta el acceso.")
            refrescarBodega()
        }
    }

    private var listaVista: LinearLayout? = null
    private var tareas: List<Tarea> = emptyList()
    private var pizarraAbierta = false
    private var pizarraTitulo: TextView? = null
    private var pizarraCuerpo: LinearLayout? = null

    /** El título dice cuántas hay en la pizarra, y una flecha si está abierta o no. */
    private fun pintarPliegue() {
        val fijadas = tareas.count { it.enMiPizarra && !it.hecho }
        pizarraTitulo?.text = (if (pizarraAbierta) "▾ " else "▸ ") + "📝 La pizarra" + (if (fijadas > 0) "  ($fijadas)" else "")
        pizarraCuerpo?.visibility = if (pizarraAbierta) View.VISIBLE else View.GONE
    }

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
        pintarPliegue()
        val porId = tareas.associateBy { it.id }
        for ((cat, grupo) in paraElegirPorCategoria(tareas)) {
          val enEsta = grupo.count { it.enMiPizarra }
          lista.addView(texto((CATEGORIAS[cat] ?: cat) + (if (enEsta > 0) "  · $enEsta en la pizarra" else ""), 15f, "#E8D3A2").apply {
              setPadding(0, dp(12), 0, dp(2)) })
          for (t in grupo) {
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
                        pintarPliegue()
                        PizarraWidget.actualizarDesdeLaRed(this@MainActivity, null)
                    }
                }
            })
          }
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
