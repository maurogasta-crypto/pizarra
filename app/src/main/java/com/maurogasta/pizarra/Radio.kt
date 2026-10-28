package com.maurogasta.pizarra

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Base64
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Radio.kt — El handy EN VIVO por el enlace cerca, sin datos. Sello: pizarra-75
//
// general:C4, paso 3. Mauro, 26-oct-2026: «se activaría también para la
// comunicación tipo handy», y antes: «que se pueda utilizar sin consumir
// datos… dentro del mismo auto o dentro de la misma casa». El handy de
// pizarra-62 manda MENSAJES de voz por la base; éste es una RADIO: mientras
// el botón está apretado, la voz sale en vivo a la otra Pizarra por el mismo
// enlace cerca (wi-fi de la casa o Bluetooth del auto) y suena allá en el
// acto. Uno habla por vez, como un handy de verdad.
//
// ── CÓMO ────────────────────────────────────────────────────────────────────
// Sonido crudo (PCM) a 8 kHz, en trozos de 100 ms (1.600 bytes). Cada trozo
// es un renglón del enlace, firmado como todo lo demás (tipo «voz»). Son unos
// 23 KB por segundo con la firma: el Bluetooth clásico lleva cinco veces eso.
// Sin códec ni biblioteca: es lo que viene con Android.
//
// ── LO QUE NO HACE ──────────────────────────────────────────────────────────
// · No graba: lo que se dice no queda en ningún lado, ni en la base ni en el
//   teléfono. Por eso tampoco lo escucha Claude (eso es la charla).
// · No va por internet: lejos está la llamada (pizarra-63).
// · No suena si el otro teléfono está en silencio, en una llamada, hablando
//   él mismo, o apagó «Escuchar el handy cerca».
//
// ── pizarra-75: EL HANDY ELIGE SOLO EL CAMINO (`Pulsador`) ──────────────────
// Mauro, 28-oct: «el botón tipo handy tiene que activar directamente el audio
// en el otro dispositivo… si está cerca, en la misma red wi-fi o por
// Bluetooth, directo; cuando no, que lo mande por internet y despierte el
// teléfono a distancia… optando siempre por la conexión directa». Al APRETAR
// se mira si el teléfono de la conversación está conectado cerca
// (`destinoCerca`): si está, la voz sale en vivo por acá, sólo para él (`para`
// en cada trozo, `vozParaMi`); si no, se graba y al soltar va como mensaje de
// voz por Tiempos con `handy: true` (reglas v22), que despierta al otro y suena
// solo al llegar. Lo de internet SÍ queda en la charla (y Claude lo escucha):
// es un mensaje; lo de cerca no queda en ningún lado.
// ─────────────────────────────────────────────────────────────────────────────

const val RADIO_HZ = 8000
const val RADIO_TROZO_MS = 100
const val RADIO_TROZO_BYTES = RADIO_HZ * 2 * RADIO_TROZO_MS / 1000
/** Como mucho esto de una sola vez: un botón trabado no transmite para siempre. */
const val RADIO_MAXIMO_MS = 60_000L

/** Un trozo de voz, listo para el enlace. [para] es la conversación («uid|teléfono», «uid|*» o «» = todos). */
fun mensajeVoz(ronda: String, n: Int, pcm: ByteArray, fin: Boolean, para: String = ""): JSONObject =
    JSONObject().put("v", VERSION_ENLACE).put("app", "pizarra").put("tipo", "voz").put("ronda", ronda.take(20)).put("n", n)
        .put("b64", Base64.getEncoder().encodeToString(pcm)).put("fin", fin).apply { if (para.isNotEmpty()) put("para", para.take(140)) }

/** pizarra-75: ¿la voz que llega es para este teléfono? El mismo criterio que un mensaje de la charla. */
fun vozParaMi(para: String, yo: String?, miTelefono: String): Boolean =
    para.isEmpty() || (yo != null && (para == claveDe(yo, miTelefono) || para == claveDePersona(yo)))

/**
 * pizarra-75: ¿el destino de [clave] está conectado CERCA ahora? «» (todos) alcanza con cualquiera; una
 * persona («uid|*»), con cualquiera de sus teléfonos ([telefonosDe]); un teléfono, con ése.
 */
fun destinoCerca(clave: String, cercanos: Set<String>, telefonosDe: (String) -> Set<String>): Boolean {
    if (cercanos.isEmpty()) return false
    if (clave.isEmpty()) return true
    val uid = clave.substringBefore('|'); val tel = clave.substringAfter('|', "")
    return if (tel == "*") telefonosDe(uid).any { it in cercanos } else tel in cercanos
}

/** Los teléfonos que se le conocen a una persona, por los mensajes que mandó. */
fun telefonosConocidos(ms: List<Mensaje>, uid: String): Set<String> = ms.filter { it.de == uid }.map { it.dispositivo }.filter { it.isNotEmpty() }.toSet()

/** El sonido de un trozo, con desconfianza: si no es voz o es más grande de lo que puede ser, nada. */
fun pcmDeVoz(o: JSONObject): ByteArray? = try {
    if (o.optString("tipo") != "voz") null
    else Base64.getDecoder().decode(o.optString("b64")).takeIf { it.size <= RADIO_TROZO_BYTES * 2 && it.size % 2 == 0 }
} catch (e: Throwable) { null }

/** Si lo que llega puede sonar acá. */
fun radioPuedeSonar(activado: Boolean, hablandoYo: Boolean, enLlamada: Boolean, enSilencio: Boolean): Boolean =
    porQueNoSuena(activado, hablandoYo, enLlamada, enSilencio) == null

/** pizarra-72: por qué NO sonó, en palabras (para el acuse y para el aviso de acá), o null si suena. */
fun porQueNoSuena(activado: Boolean, hablandoYo: Boolean, enLlamada: Boolean, enSilencio: Boolean): String? = when {
    !activado -> "tiene el handy apagado"
    hablandoYo -> "estaba hablando"
    enLlamada -> "estaba en una llamada"
    enSilencio -> "tiene el teléfono en silencio o vibrador"
    else -> null
}

object Radio {
    @Volatile var hablando = false; private set

    fun tienePermiso(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Empieza a transmitir hasta [soltar]. [alTerminar] recibe los segundos. Nunca tira. */
    fun hablar(c: Context, para: String = "", alTerminar: (Int) -> Unit = {}) {
        if (hablando || !tienePermiso(c)) return
        hablando = true
        Altavoz.callar()                       // uno por vez
        val app = c.applicationContext
        thread(name = "radio-habla") {
            var r: AudioRecord? = null
            val empezo = System.currentTimeMillis()
            val ronda = idNuevo().take(10)
            var n = 0
            try {
                val minimo = AudioRecord.getMinBufferSize(RADIO_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")   // tienePermiso() lo comprueba arriba
                val g = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RADIO_HZ, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, RADIO_TROZO_BYTES * 4))
                r = g
                g.startRecording()
                val trozo = ByteArray(RADIO_TROZO_BYTES)
                while (hablando && System.currentTimeMillis() - empezo < RADIO_MAXIMO_MS) {
                    var leido = 0
                    while (leido < trozo.size) { val k = g.read(trozo, leido, trozo.size - leido); if (k <= 0) break; leido += k }
                    if (leido <= 0) break
                    EnlaceServicio.enviarSoloCerca(mensajeVoz(ronda, n++, trozo.copyOf(leido), false, para))
                }
            } catch (e: Throwable) {
                Bitacora.anotar(app, "radio: no se pudo hablar (${e.javaClass.simpleName})")
            } finally {
                try { r?.stop() } catch (e: Throwable) { }
                try { r?.release() } catch (e: Throwable) { }
                try { EnlaceServicio.enviarSoloCerca(mensajeVoz(ronda, n, ByteArray(0), true, para)) } catch (e: Throwable) { }
                hablando = false
                val s = ((System.currentTimeMillis() - empezo) / 1000).toInt()
                Bitacora.anotar(app, "radio: habló $s s")
                try { alTerminar(s) } catch (e: Throwable) { }
            }
        }
    }

    fun soltar() { hablando = false }
}

/**
 * pizarra-75: el botón del handy de cada conversación. Al apretar decide el camino —cerca y en vivo si el
 * otro teléfono está conectado; si no, por internet— y al soltar termina. [dice] recibe lo que pasa, en
 * palabras, para mostrar al lado del botón.
 */
object Pulsador {
    @Volatile var modo = ""; private set          // «cerca», «internet» o «»
    private var grabador: Grabador? = null
    private var para = ""

    /** Devuelve false si falta el permiso del micrófono (lo pide quien llama). */
    fun apretar(c: Context, clave: String, nombre: String, dice: (String) -> Unit): Boolean {
        if (!Radio.tienePermiso(c)) return false
        if (modo.isNotEmpty()) return true
        para = clave
        val ms = with(Charla) { c.mensajesGuardados }
        val cerca = EnlaceAjustes.prendido(c) && destinoCerca(clave, EnlaceServicio.cercanos()) { telefonosConocidos(ms, it) }
        val a = nombre.ifBlank { "todos" }
        if (cerca) {
            modo = "cerca"
            Radio.hablar(c, clave)
            dice("📻 En VIVO, directo con $a (cerca, sin datos)…")
            return true
        }
        val n = Nube(c)
        if (!n.conSesion || !n.esTiempos) { dice("Nadie cerca, y sin cuenta de Tiempos no puede ir por internet."); return true }
        if (clave.isEmpty()) { dice("Nadie cerca. Por internet el handy va a UNA conversación: elegí a quién."); return true }
        try {
            grabador = Grabador(c).also { it.empezar { } }
            modo = "internet"
            dice("🌐 $a no está cerca: grabando, va por internet y le suena al llegar…")
        } catch (e: Throwable) { grabador = null; dice("No se pudo grabar.") }
        if (EnlaceAjustes.prendido(c)) EnlaceServicio.buscarYa(c)   // para la próxima, quizás ya esté cerca
        return true
    }

    fun soltar(c: Context, cancelar: Boolean, dice: (String) -> Unit) {
        when (modo) {
            "cerca" -> { Radio.soltar(); dice(if (cancelar) "Cortado." else "✓ Dicho en vivo. Esperando que conteste…") }
            "internet" -> {
                val g = grabador; grabador = null
                if (g != null) {
                    val r = if (cancelar) { g.cancelar(); null } else g.terminar()
                    if (r == null) dice(if (cancelar) "Cortado: no se mandó." else "Muy corto: no se mandó.")
                    else {
                        dice("Mandando por internet…")
                        Handy.mandar(c, r.first, r.second, g.tipo, para, handy = true) { err -> dice(err ?: "✓ Mandado: le suena al llegar.") }
                    }
                }
            }
        }
        modo = ""
    }
}

/** Lo que llega de la otra Pizarra, al parlante. Lo llama el hilo que lee el enlace. */
object Altavoz {
    private var pista: AudioTrack? = null
    private var ronda = ""
    /** Qué pasó con la ronda que está llegando: «la escuchó» o por qué no. Va en el acuse. */
    @Volatile var comoFue = ""; private set
    private val principal = Handler(Looper.getMainLooper())
    private val apagar = Runnable { callar() }

    @Synchronized fun tocar(c: Context, o: JSONObject) {
        val pcm = pcmDeVoz(o) ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val enLlamada = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
        val enSilencio = am.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val r = o.optString("ronda")
        val nuevo = r != ronda
        if (nuevo) ronda = r
        val porQue = porQueNoSuena(EnlaceAjustes.radio(c), Radio.hablando, enLlamada, enSilencio)
        if (porQue != null) {
            // pizarra-72: antes no sonaba y nadie se enteraba. Ahora se avisa acá y se le contesta al que habló.
            comoFue = "no sonó: $porQue"
            if (nuevo) avisar(c, o.optString("de"), "no sonó: $porQue")
            return
        }
        comoFue = "la escuchó"
        try {
            if (pista == null) {
                pista = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RADIO_HZ).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(RADIO_TROZO_BYTES * 8).setTransferMode(AudioTrack.MODE_STREAM).build()
                    .also { it.play() }
            }
            if (nuevo) avisar(c, o.optString("de"), "Tocá para contestar")
            if (pcm.isNotEmpty()) pista?.write(pcm, 0, pcm.size)
            principal.removeCallbacks(apagar)
            principal.postDelayed(apagar, if (o.optBoolean("fin")) 600L else 2_000L)
        } catch (e: Throwable) { callar() }
    }

    @Synchronized fun callar() {
        try { pista?.stop() } catch (e: Throwable) { }
        try { pista?.release() } catch (e: Throwable) { }
        pista = null
    }

    /** Una notificación callada: quién habla, y con un toque se contesta. */
    private fun avisar(c: Context, de: String, texto: String) {
        try {
            crearCanales(c)
            val abrir = PendingIntent.getActivity(c, 71, Intent(c, RadioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(7072,
                Notification.Builder(c, CANAL_ENLACE).setSmallIcon(android.R.drawable.stat_sys_speakerphone)
                    .setContentTitle("📻 Te habla ${de.ifBlank { "la otra Pizarra" }}").setContentText(texto)
                    .setAutoCancel(true).setTimeoutAfter(60_000).setContentIntent(abrir).build())
        } catch (e: Throwable) { }
    }
}

/** La radio: el botón grande que se mantiene apretado. */
class RadioActivity : Activity() {
    private lateinit var dice: TextView
    private val reloj = Handler(Looper.getMainLooper())

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (12 * d).toInt()) }
        caja.addView(TextView(this).apply { text = "📻 Handy cerca"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        caja.addView(TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#8A8F98"))
            text = "Mantené apretado y hablá: se escucha en vivo en la otra Pizarra, por el wi-fi de la casa o el Bluetooth del auto, sin datos. Uno por vez. No se graba."
        })
        dice = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt()) }
        caja.addView(dice)
        val boton = Button(this).apply {
            text = "🎙 Mantené apretado para hablar"; textSize = 18f
            minHeight = (120 * d).toInt()
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { empezar(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { Radio.soltar(); v.performClick(); true }
                    else -> false
                }
            }
            setOnClickListener { }
        }
        caja.addView(boton)
        caja.addView(CheckBox(this).apply {
            text = "Escuchar el handy de la otra Pizarra"; isChecked = EnlaceAjustes.radio(this@RadioActivity)
            setOnCheckedChangeListener { _, si -> EnlaceAjustes.radioSiNo(this@RadioActivity, si) }
        })
        caja.addView(Button(this).apply { text = "Cerrar"; setOnClickListener { finish() } })
        setContentView(caja)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        if (!EnlaceAjustes.prendido(this)) dice.text = "El enlace cerca está apagado: prendelo en los ajustes de la Pizarra."
        else { EnlaceServicio.buscarYa(this); tic() }
    }

    private fun tic() {
        val acuse = EnlaceServicio.ultimoAcuse.takeIf { System.currentTimeMillis() - EnlaceServicio.ultimoAcuseEn < 20_000L }
        dice.text = when {
            Radio.hablando -> "🔴 Hablando…"
            acuse != null -> "$acuse\nEnlace: ${EnlaceServicio.estado}"
            else -> "Enlace: ${EnlaceServicio.estado}"
        }
        reloj.postDelayed({ tic() }, 500)
    }

    private fun empezar() {
        if (!EnlaceAjustes.prendido(this)) return
        if (!Radio.tienePermiso(this)) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 71); return }
        Radio.hablar(this)
    }

    override fun onPause() { Radio.soltar(); super.onPause() }
    override fun onDestroy() { reloj.removeCallbacksAndMessages(null); Radio.soltar(); super.onDestroy() }
}

/** «🚙 En vivo»: lo que manda el detector, cerca o por internet, mientras esta pantalla está abierta. */
class EnVivoActivity : Activity() {
    private lateinit var dice: TextView
    private lateinit var como: TextView
    private val reloj = Handler(Looper.getMainLooper())
    private var ultimaMira = 0L
    @Volatile private var lejos: JSONObject? = null

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (12 * d).toInt()) }
        caja.addView(TextView(this).apply { text = "🚙 En vivo"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        dice = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER; setPadding(0, (16 * d).toInt(), 0, (8 * d).toInt()) }
        caja.addView(dice)
        como = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#8A8F98")); gravity = Gravity.CENTER }
        caja.addView(como)
        caja.addView(Button(this).apply { text = "Cerrar"; setOnClickListener { finish() } })
        setContentView(caja)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        if (!EnlaceAjustes.prendido(this)) { dice.text = "—"; como.text = "El enlace está apagado: prendelo en los ajustes de la Pizarra."; return }
        EnlaceServicio.buscarYa(this)
        tic()
    }

    private fun tic() {
        val ahora = System.currentTimeMillis()
        // Por internet: mira cada minuto (la primera vez despierta al detector) y trae el vivo cada 10 s.
        if (EnlaceLejos.puede(this) && ahora - (lejos?.optLong("leido") ?: 0L) >= VIVO_LEJOS_CADA_MS) {
            val despertar = ultimaMira == 0L
            val mirar = ahora - ultimaMira >= MIRA_CADA_MS
            if (mirar) ultimaMira = ahora
            lejos = (lejos ?: JSONObject()).put("leido", ahora)
            val app = applicationContext
            thread(name = "en-vivo") {
                try {
                    if (mirar) EnlaceLejos.mirar(app, despertar)
                    EnlaceLejos.vivo(app)?.let { lejos = it.put("leido", System.currentTimeMillis()) }
                } catch (e: Throwable) { }
            }
        }
        val cerca = EnlaceServicio.ultimoVivo
        val o = listOfNotNull(cerca, lejos?.takeIf { it.has("tipo") }).maxByOrNull { it.optLong("t") }
        if (o == null || ahora - o.optLong("t") > 5 * 60_000L) {
            dice.text = "Todavía nada"
            como.text = "Aparece cuando la camioneta está midiendo un viaje y su Pizarra tiene el enlace prendido: cerca por wi-fi o Bluetooth, lejos con internet (cada 10 s)."
        } else {
            dice.text = textoVivo(o)
            val hace = ((ahora - o.optLong("t")) / 1000).coerceAtLeast(0)
            como.text = "desde ${o.optString("de")} · ${if (o === cerca) "cerca, sin datos" else "por internet"} · hace $hace s"
        }
        reloj.postDelayed({ tic() }, 1_000)
    }

    override fun onDestroy() { reloj.removeCallbacksAndMessages(null); super.onDestroy() }
}
