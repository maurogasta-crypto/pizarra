package com.maurogasta.pizarra

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Llamada.kt — Llamadas de voz entre Pizarras, sin WhatsApp. Sello: pizarra-63
//
// Mauro, 24-oct-2026 (general:C2, Fase B): «llamada directo en tiempo real…
// que conecte teléfonos que tengan la apk de pizarra», con la condición «no
// quiero que ningún desarrollo pase de los cupos gratuitos», y «cualquier cosa
// se ajusta el proyecto para tres teléfonos o cuatro».
//
// ── CÓMO ANDA ────────────────────────────────────────────────────────────────
// La VOZ va directo de teléfono a teléfono (WebRTC, la biblioteca de Stream:
// la segunda y última excepción a «sin dependencias»). Lo único que pasa por
// la base es cómo se encuentran, en `llamadas/` de Tiempos (reglas v19):
//   1. Quien llama arma su «oferta» (con todos sus caminos posibles, sin
//      mandarlos de a uno: menos idas y vueltas) y crea la llamada; el aviso
//      instantáneo despierta a las Pizarras, y la del destinatario suena
//      (`suenaAca`), con pantalla completa aunque esté bloqueada.
//   2. El otro atiende con su «respuesta» o rechaza; si es «a la persona», le
//      suena en todos sus teléfonos y atiende el primero.
//   3. Cualquiera corta. Nada se graba.
// Para encontrarse: STUN gratis; si la red no deja hablar directo (4G), un
// TURN de cupo fijo que entrega la función `hielo` del Netlify sólo a quien
// tiene sesión (las claves no van en la app). Sin TURN, en un 4G cerrado la
// llamada no conecta y la pantalla lo dice.
//
// ── LO QUE GASTA ─────────────────────────────────────────────────────────────
// Unas 60 lecturas de la base por llamada (mientras suena se mira cada 1-2 s;
// en la llamada, cada 3 s, para saber si el otro cortó) y 2 a 4 escrituras.
// Con 4 teléfonos y 20 llamadas por día, unas 2.000 lecturas: nada contra las
// 50.000 del cupo. La voz, unos 40 MB por hora, del plan de datos de cada uno.
// ─────────────────────────────────────────────────────────────────────────────

const val COLECCION_LLAMADAS = "llamadas"
const val URL_HIELO = "https://serene-scone-76bd4e.netlify.app/.netlify/functions/hielo"
const val CANAL_LLAMADAS = "llamadas"
const val SUENA_MS = 45_000L

data class Llamada(val id: String, val de: String, val nombre: String, val dispositivo: String, val para: String,
                   val estado: String, val oferta: String, val respuesta: String, val atendio: String, val creado: String)

/** Una llamada de un documento de Firestore (REST). */
fun llamadaDeDoc(d: JSONObject): Llamada? {
    val f = d.optJSONObject("fields") ?: return null
    fun s(k: String) = f.optJSONObject(k)?.let { it.optString("stringValue", "").ifEmpty { it.optString("timestampValue", "") } }.orEmpty()
    val id = d.optString("name").substringAfterLast('/')
    if (id.isEmpty() || s("de").isEmpty()) return null
    return Llamada(id, s("de"), s("nombre").take(40), s("dispositivo").take(60), s("para"), s("estado"), s("oferta"), s("respuesta"),
        s("atendio"), s("creadoEn"))
}

fun llamadasDeConsulta(r: JSONArray): List<Llamada> = (0 until r.length()).mapNotNull { i -> r.optJSONObject(i)?.optJSONObject("document")?.let { llamadaDeDoc(it) } }

/** Las que están sonando (un filtro de un campo: sin índice compuesto). */
fun consultaSonando(): JSONObject = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", COLECCION_LLAMADAS)))
    .put("where", JSONObject().put("fieldFilter", JSONObject().put("field", JSONObject().put("fieldPath", "estado"))
        .put("op", "EQUAL").put("value", JSONObject().put("stringValue", "llamando"))))
    .put("limit", 20))

/** ¿Tiene que sonar en ESTE teléfono? Es para él (o para mi persona y no la hice yo), y es de los últimos 45 s. */
fun suenaAca(l: Llamada, yo: String, miTelefono: String, ahora: Long): Boolean =
    l.estado == "llamando" && !(l.de == yo && l.dispositivo == miTelefono) &&
        (l.para == claveDe(yo, miTelefono) || (l.para == claveDePersona(yo) && l.de != yo)) &&
        (instante(l.creado)?.let { ahora - it in -30_000L..SUENA_MS } ?: false)

private fun s(v: String) = JSONObject().put("stringValue", v)

/** La llamada nueva, con la forma EXACTA que deja crear la regla v19. */
fun cuerpoLlamada(docs: String, id: String, uid: String, nombre: String, dispositivo: String, para: String, oferta: String): JSONObject =
    JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_LLAMADAS/$id").put("fields", JSONObject()
            .put("de", s(uid)).put("nombre", s(nombre.take(40))).put("dispositivo", s(dispositivo.take(60))).put("para", s(para.take(140)))
            .put("estado", s("llamando")).put("oferta", s(oferta))))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))

/** Atender (con respuesta) o rechazar (sin ella): sólo esos campos, como pide la regla. */
fun cuerpoAtender(docs: String, id: String, atendio: String, respuesta: String?): JSONObject {
    val campos = JSONObject().put("estado", s(if (respuesta == null) "rechazada" else "atendida")).put("atendio", s(atendio.take(60)))
    if (respuesta != null) campos.put("respuesta", s(respuesta))
    return JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_LLAMADAS/$id").put("fields", campos))
        .put("updateMask", JSONObject().put("fieldPaths", JSONArray().apply { campos.keys().forEach { put(it) } }))
        .put("currentDocument", JSONObject().put("exists", true))))
}

/** Cortar o cancelar, con la hora del servidor. */
fun cuerpoCortar(docs: String, id: String, estado: String): JSONObject = JSONObject().put("writes", JSONArray().put(JSONObject()
    .put("update", JSONObject().put("name", "$docs/$COLECCION_LLAMADAS/$id").put("fields", JSONObject().put("estado", s(estado))))
    .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("estado")))
    .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "fin").put("setToServerValue", "REQUEST_TIME")))
    .put("currentDocument", JSONObject().put("exists", true))))

/** Un servidor de conexión, como lo contesta `hielo`. */
data class Hielo(val urls: List<String>, val usuario: String = "", val clave: String = "")

val STUN_SI_FALLA = listOf(Hielo(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")))

fun hieloDeRespuesta(texto: String): List<Hielo> = try {
    val a = JSONObject(texto).getJSONArray("iceServers")
    (0 until a.length()).mapNotNull { i ->
        val o = a.getJSONObject(i)
        val u = o.opt("urls")
        val urls = when (u) { is JSONArray -> (0 until u.length()).map { u.getString(it) }; is String -> listOf(u); else -> emptyList() }
            .filter { it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:") }
        if (urls.isEmpty()) null else Hielo(urls, o.optString("username"), o.optString("credential"))
    }.ifEmpty { STUN_SI_FALLA }
} catch (e: Exception) { STUN_SI_FALLA }

/** Lo que se ve mientras tanto. */
fun textoDeEstado(estado: String, segundos: Int = 0): String = when (estado) {
    "preparando" -> "Preparando…"
    "llamando" -> "Llamando…"
    "sonando" -> "Te está llamando"
    "conectando" -> "Conectando…"
    "en_llamada" -> "En llamada · " + duracionTexto(segundos)
    "rechazada" -> "No atiende"
    "sin_respuesta" -> "No contestó"
    "cortada" -> "Llamada terminada"
    "otro" -> "Atendiste en otro teléfono"
    "fallo" -> "No se pudo conectar. En 4G puede hacer falta el servidor puente (TURN)."
    else -> estado
}

// ─── El motor (WebRTC) ───────────────────────────────────────────────────────

/** Una llamada de voz con un PeerConnection. Todo bloqueante: se usa desde un hilo. */
class Motor(private val c: Context) {
    companion object {
        private var fabrica: PeerConnectionFactory? = null
        @Synchronized fun fabrica(c: Context): PeerConnectionFactory = fabrica ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(c.applicationContext).createInitializationOptions())
            val adm = JavaAudioDeviceModule.builder(c.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()
            PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory().also { fabrica = it }
        }
    }

    private var pc: PeerConnection? = null
    private var pista: AudioTrack? = null
    private val juntados = CountDownLatch(1)

    fun preparar(hielo: List<Hielo>, alCambiar: (PeerConnection.PeerConnectionState) -> Unit) {
        val f = fabrica(c)
        val servidores = hielo.map { h ->
            PeerConnection.IceServer.builder(h.urls).apply { if (h.usuario.isNotEmpty()) { setUsername(h.usuario); setPassword(h.clave) } }.createIceServer()
        }
        val cfg = PeerConnection.RTCConfiguration(servidores).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceGatheringChange(e: PeerConnection.IceGatheringState) { if (e == PeerConnection.IceGatheringState.COMPLETE) juntados.countDown() }
            override fun onConnectionChange(e: PeerConnection.PeerConnectionState) { alCambiar(e) }
            override fun onSignalingChange(e: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(e: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(x: org.webrtc.IceCandidate) {}
            override fun onIceCandidatesRemoved(x: Array<out org.webrtc.IceCandidate>) {}
            override fun onAddStream(x: org.webrtc.MediaStream) {}
            override fun onRemoveStream(x: org.webrtc.MediaStream) {}
            override fun onDataChannel(x: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: throw IllegalStateException("no se pudo armar la conexión")
        pista = f.createAudioTrack("voz", f.createAudioSource(MediaConstraints()))
        pc!!.addTrack(pista, listOf("pizarra"))
    }

    private fun crear(oferta: Boolean): SessionDescription {
        var r: SessionDescription? = null; var error = ""
        val l = CountDownLatch(1)
        val o = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { r = d; l.countDown() }
            override fun onCreateFailure(e: String?) { error = e.orEmpty(); l.countDown() }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }
        if (oferta) pc!!.createOffer(o, MediaConstraints()) else pc!!.createAnswer(o, MediaConstraints())
        l.await(10, TimeUnit.SECONDS)
        return r ?: throw IllegalStateException("no se armó la descripción: $error")
    }

    private fun poner(local: Boolean, d: SessionDescription) {
        var error: String? = "tardó demasiado"
        val l = CountDownLatch(1)
        val o = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { error = null; l.countDown() }
            override fun onSetFailure(e: String?) { error = e.orEmpty(); l.countDown() }
        }
        if (local) pc!!.setLocalDescription(o, d) else pc!!.setRemoteDescription(o, d)
        l.await(10, TimeUnit.SECONDS)
        error?.let { throw IllegalStateException("no se pudo poner la descripción: $it") }
    }

    /** La oferta, con todos los caminos juntados (como mucho 5 s). */
    fun oferta(): String {
        poner(true, crear(true)); juntados.await(5, TimeUnit.SECONDS)
        return pc!!.localDescription.description
    }

    fun responder(oferta: String): String {
        poner(false, SessionDescription(SessionDescription.Type.OFFER, oferta))
        poner(true, crear(false)); juntados.await(5, TimeUnit.SECONDS)
        return pc!!.localDescription.description
    }

    fun recibir(respuesta: String) = poner(false, SessionDescription(SessionDescription.Type.ANSWER, respuesta))

    fun silenciar(v: Boolean) { pista?.setEnabled(!v) }

    fun terminar() {
        try { pc?.close() } catch (e: Throwable) { }
        try { pc?.dispose() } catch (e: Throwable) { }
        pc = null; pista = null
    }
}

// ─── Quién suena ─────────────────────────────────────────────────────────────

object Llamadas {
    private fun prefs(c: Context) = c.getSharedPreferences("llamadas", Context.MODE_PRIVATE)

    /** Mira si alguien me está llamando; si sí, suena (una vez por llamada). Lo llaman el aviso instantáneo y el despertar. Nunca tira. */
    fun revisar(c: Context) {
        try {
            val n = Nube(c)
            if (!n.conSesion || !n.esTiempos) return
            val yo = n.uid.orEmpty(); val tel = nombreDelTelefono(c)
            val p = prefs(c)
            val sonadas = p.getStringSet("sonadas", emptySet()) ?: emptySet()
            val nuevas = n.llamadasSonando().filter { suenaAca(it, yo, tel, System.currentTimeMillis()) && it.id !in sonadas }
            for (l in nuevas) sonar(c, l)
            if (nuevas.isNotEmpty()) p.edit().putStringSet("sonadas", (sonadas + nuevas.map { it.id }).toList().takeLast(50).toSet()).apply()
        } catch (e: Throwable) { Bitacora.anotar(c, "llamadas: ${e.javaClass.simpleName}") }
    }

    private fun canal(c: Context): NotificationManager? {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return null
        nm.createNotificationChannel(NotificationChannel(CANAL_LLAMADAS, "Llamadas", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Cuando te llaman desde otra Pizarra." })
        return nm
    }

    /** Pantalla completa (aunque esté bloqueado) y, si Android no la deja, un aviso arriba con Atender. */
    private fun sonar(c: Context, l: Llamada) {
        val nm = canal(c) ?: return
        val abrir = Intent(c, LlamadaActivity::class.java).putExtra("entrante", l.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(c, l.id.hashCode(), abrir, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val quien = if (l.nombre.isBlank()) l.dispositivo else "${l.nombre} · ${l.dispositivo}"
        val n = Notification.Builder(c, CANAL_LLAMADAS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("📞 Te llama $quien").setContentText("Tocá para atender")
            .setCategory(Notification.CATEGORY_CALL).setFullScreenIntent(pi, true).setContentIntent(pi)
            .setOngoing(true).setAutoCancel(true).setTimeoutAfter(SUENA_MS).build()
        try { nm.notify(("llamada:" + l.id).hashCode(), n) } catch (e: SecurityException) { }
        Bitacora.anotar(c, "llamadas: suena")
    }

    fun perdida(c: Context, quien: String) {
        val nm = canal(c) ?: return
        val pi = PendingIntent.getActivity(c, 63, Intent(c, CharlaActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            nm.notify(("perdida:" + quien).hashCode(), Notification.Builder(c, CANAL_LLAMADAS).setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle("📞 Llamada perdida").setContentText(quien).setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: SecurityException) { }
    }

    fun callarAviso(c: Context, id: String) { c.getSystemService(NotificationManager::class.java)?.cancel(("llamada:$id").hashCode()) }
}

/** Mientras dura la llamada: que Android no corte el micrófono con la pantalla apagada. */
class LlamadaServicio : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("en_llamada", "En llamada", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 64, Intent(this, LlamadaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "en_llamada").setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("📞 En llamada").setContentText(i?.getStringExtra("quien").orEmpty()).setContentIntent(pi).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7063, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(7063, n)
        } catch (e: Throwable) { Bitacora.anotar(this, "llamada: sin servicio (${e.javaClass.simpleName})"); stopSelf() }
        return START_NOT_STICKY
    }
}

// ─── La pantalla ─────────────────────────────────────────────────────────────

/** Llamar (extra «para» = clave de la conversación) o atender (extra «entrante» = id de la llamada). */
class LlamadaActivity : Activity() {
    private lateinit var dice: TextView
    private lateinit var atender: Button
    private lateinit var colgar: Button
    private lateinit var altavoz: Button
    private lateinit var mudo: Button
    private val reloj = Handler(Looper.getMainLooper())
    private var motor: Motor? = null
    private var id = ""
    private var saliente = false
    private var estado = "preparando"
    private var empezo = 0L
    private var terminada = false
    private var tono: android.media.Ringtone? = null
    private var silenciado = false
    private var enAltavoz = false
    private var quien = ""
    private var oferta = ""

    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val n = Nube(this)
        if (!n.conSesion || !n.esTiempos) { finish(); return }
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (40 * d).toInt(), (24 * d).toInt(), (24 * d).toInt()); setBackgroundColor(Color.parseColor("#1B1D21")) }
        val nombre = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        caja.addView(nombre)
        dice = TextView(this).apply { textSize = 16f; setTextColor(Color.parseColor("#AEB3BA")); gravity = Gravity.CENTER; setPadding(0, (12 * d).toInt(), 0, (40 * d).toInt()) }
        caja.addView(dice)
        fun boton(t: String, color: String) = Button(this).apply { text = t; textSize = 16f; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 28 * d; setColor(Color.parseColor(color)) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * d).toInt()).apply { topMargin = (10 * d).toInt() } }
        atender = boton("📞 Atender", "#2E7D32"); colgar = boton("Cortar", "#C62828")
        altavoz = boton("🔈 Altavoz", "#3A3F47"); mudo = boton("🎙 Silenciar", "#3A3F47")
        listOf(atender, altavoz, mudo, colgar).forEach { caja.addView(it) }
        setContentView(caja)

        colgar.setOnClickListener { cortar(if (estado == "sonando") "rechazar" else "cortar") }
        atender.setOnClickListener { atenderla() }
        altavoz.setOnClickListener { enAltavoz = !enAltavoz; ponerAltavoz(enAltavoz); altavoz.text = if (enAltavoz) "🔊 Altavoz (sí)" else "🔈 Altavoz" }
        mudo.setOnClickListener { silenciado = !silenciado; motor?.silenciar(silenciado); mudo.text = if (silenciado) "🎙 Silenciado" else "🎙 Silenciar" }

        val entrante = intent.getStringExtra("entrante").orEmpty()
        saliente = entrante.isEmpty()
        if (saliente) {
            val para = intent.getStringExtra("para").orEmpty()
            if (para.isEmpty()) { finish(); return }
            quien = intent.getStringExtra("nombre").orEmpty().ifBlank { "Pizarra" }
            nombre.text = quien
            if (!Handy.tienePermiso(this)) { pedirMicrofono(); return }
            llamar(para)
        } else {
            id = entrante
            Llamadas.callarAviso(this, id)
            nombre.text = "…"
            thread {
                val l = try { Nube(this).leerLlamada(id) } catch (e: Throwable) { null }
                runOnUiThread {
                    if (l == null || l.estado != "llamando") { poner("cortada"); terminarLuego(); return@runOnUiThread }
                    quien = if (l.nombre.isBlank()) l.dispositivo else "${l.nombre} · ${l.dispositivo}"
                    nombre.text = quien; oferta = l.oferta
                    poner("sonando"); sonarTono(); vigilar()
                }
            }
        }
        pintarBotones()
    }

    private fun pedirMicrofono() { dice.text = "Hace falta el permiso del micrófono."; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 63) }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, permisos, resultados)
        if (codigo != 63) return
        if (!Handy.tienePermiso(this)) { dice.text = "Sin el micrófono no se puede hablar."; terminarLuego(); return }
        if (saliente) llamar(intent.getStringExtra("para").orEmpty()) else atenderla()
    }

    private fun poner(e: String) { estado = e; dice.text = textoDeEstado(e, segundos()); pintarBotones() }
    private fun segundos() = if (empezo == 0L) 0 else ((System.currentTimeMillis() - empezo) / 1000).toInt()

    private fun pintarBotones() {
        atender.visibility = if (estado == "sonando") android.view.View.VISIBLE else android.view.View.GONE
        val enCurso = estado in listOf("conectando", "en_llamada")
        altavoz.visibility = if (enCurso) android.view.View.VISIBLE else android.view.View.GONE
        mudo.visibility = altavoz.visibility
        colgar.text = if (estado == "sonando") "Rechazar" else "Cortar"
        colgar.visibility = if (terminada) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun alCambiar(e: PeerConnection.PeerConnectionState) = runOnUiThread {
        when (e) {
            PeerConnection.PeerConnectionState.CONNECTED -> if (estado != "en_llamada") {
                empezo = System.currentTimeMillis(); poner("en_llamada"); iniciarServicio()
                val tic = object : Runnable { override fun run() { if (estado == "en_llamada") { dice.text = textoDeEstado(estado, segundos()); reloj.postDelayed(this, 1000) } } }
                reloj.post(tic)
            }
            PeerConnection.PeerConnectionState.FAILED -> if (!terminada) { if (estado == "en_llamada") cortar("cortar") else { poner("fallo"); cortar("cortar", mostrar = false) } }
            else -> {}
        }
    }

    private fun hielo(): List<Hielo> = try { hieloDeRespuesta(Nube(this).hielo()) } catch (e: Throwable) { STUN_SI_FALLA }

    private fun llamar(para: String) {
        poner("preparando"); modoLlamada(true)
        thread {
            try {
                val m = Motor(this).also { motor = it }
                m.preparar(hielo()) { alCambiar(it) }
                val o = m.oferta()
                id = Nube(this).crearLlamada(para, o, nombreDelTelefono(this))
                runOnUiThread { poner("llamando"); vigilar() }
            } catch (e: Throwable) {
                Bitacora.anotar(this, "llamada: no salió: " + (e.message ?: e.javaClass.simpleName))
                runOnUiThread { dice.text = "No se pudo llamar: " + (e.message ?: "error"); cortar("cortar", mostrar = false) }
            }
        }
    }

    private fun atenderla() {
        if (!Handy.tienePermiso(this)) { pedirMicrofono(); return }
        callarTono(); poner("conectando"); modoLlamada(true)
        thread {
            try {
                val m = Motor(this).also { motor = it }
                m.preparar(hielo()) { alCambiar(it) }
                val r = m.responder(oferta)
                Nube(this).atenderLlamada(id, nombreDelTelefono(this), r)
            } catch (e: Throwable) {
                // Si otro teléfono atendió primero, la regla dice que no: se avisa y se cierra.
                runOnUiThread { poner("otro"); cortar("nada", mostrar = false) }
            }
        }
    }

    /** Mientras suena se mira cada 1-2 s; en la llamada, cada 3 s: si el otro cortó, se corta. */
    private fun vigilar() {
        val desde = System.currentTimeMillis()
        val tic = object : Runnable {
            override fun run() {
                if (terminada || id.isEmpty()) return
                thread {
                    val l = try { Nube(this@LlamadaActivity).leerLlamada(id) } catch (e: Throwable) { null }
                    runOnUiThread {
                        if (terminada) return@runOnUiThread
                        val pasado = System.currentTimeMillis() - desde
                        when {
                            l == null -> {}
                            l.estado in listOf("cortada", "cancelada") -> {
                                if (!saliente && estado == "sonando") Llamadas.perdida(this@LlamadaActivity, quien)
                                poner("cortada"); cortar("nada", mostrar = false)
                            }
                            l.estado == "rechazada" -> { poner(if (saliente) "rechazada" else "otro"); cortar("nada", mostrar = false) }
                            l.estado == "atendida" && saliente && estado == "llamando" -> {
                                poner("conectando")
                                thread { try { motor?.recibir(l.respuesta) } catch (e: Throwable) { runOnUiThread { poner("fallo"); cortar("cortar", mostrar = false) } } }
                            }
                            l.estado == "atendida" && !saliente && estado == "sonando" -> { callarTono(); poner("otro"); cortar("nada", mostrar = false) }
                            l.estado == "llamando" && pasado > SUENA_MS -> { if (saliente) { poner("sin_respuesta"); cortar("cancelar", mostrar = false) } }
                        }
                        if (!terminada) reloj.postDelayed(this, if (estado == "en_llamada") 3000 else if (saliente) 1000 else 2000)
                    }
                }
            }
        }
        reloj.postDelayed(tic, 1000)
    }

    /** «cortar», «cancelar», «rechazar» (avisan en la base) o «nada» (ya lo dijo la base). */
    private fun cortar(como: String, mostrar: Boolean = true) {
        if (terminada) return
        terminada = true; callarTono(); reloj.removeCallbacksAndMessages(null)
        val llamada = id
        thread {
            try {
                val n = Nube(this)
                when {
                    llamada.isEmpty() -> {}
                    como == "rechazar" -> n.atenderLlamada(llamada, nombreDelTelefono(this), null)
                    como == "cancelar" -> n.cortarLlamada(llamada, "cancelada")
                    como == "cortar" -> n.cortarLlamada(llamada, if (estado == "llamando" || estado == "preparando") "cancelada" else "cortada")
                }
            } catch (e: Throwable) { }
        }
        motor?.terminar(); motor = null
        modoLlamada(false)
        stopService(Intent(this, LlamadaServicio::class.java))
        if (mostrar) poner("cortada") else pintarBotones()
        terminarLuego()
    }

    private fun terminarLuego() { terminada = true; reloj.postDelayed({ finish() }, 1800) }

    private fun sonarTono() {
        try {
            val am = getSystemService(AudioManager::class.java)
            if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            tono = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (e: Throwable) { }
    }
    private fun callarTono() { try { tono?.stop() } catch (e: Throwable) { }; tono = null }

    @Suppress("DEPRECATION")
    private fun modoLlamada(si: Boolean) {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            am.mode = if (si) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            if (!si) am.isSpeakerphoneOn = false
        } catch (e: Throwable) { }
    }

    @Suppress("DEPRECATION")
    private fun ponerAltavoz(si: Boolean) {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (si) am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
                else am.clearCommunicationDevice()
            } else am.isSpeakerphoneOn = si
        } catch (e: Throwable) { }
    }

    private fun iniciarServicio() {
        try { startForegroundService(Intent(this, LlamadaServicio::class.java).putExtra("quien", quien)) } catch (e: Throwable) { }
    }

    override fun onDestroy() {
        if (!terminada) cortar("cortar", mostrar = false)
        reloj.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
