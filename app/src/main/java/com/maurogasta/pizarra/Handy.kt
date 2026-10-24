package com.maurogasta.pizarra

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Handy.kt — Mensajes de VOZ entre las Pizarras, como un handy. Sello: pizarra-62
//
// Mauro, 24-oct-2026 (general:C2, Fase A): «que las llamadas puedan hacerse
// directamente desde la pizarra sin utilizar WhatsApp… un sistema de
// comunicación tipo handy». Se graba, se suelta, y en el otro teléfono se
// escucha SOLO en segundos (si eligió «escuchar los audios solos»; si no,
// queda la burbuja con ▶).
//
// ── DÓNDE VIVE EL AUDIO ──────────────────────────────────────────────────────
// En la base de Tiempos, `audios/{id}` (reglas v18): los bytes, la duración y
// el tipo; el mensaje de `mensajes/` lo nombra (`audio`, `dur`). NO en
// Cloudinary: es la cuenta de las fotos de Casa Verde, comparte su cupo
// gratis, y borrar ahí pide el secreto, que no puede ir en un teléfono. Acá
// cada Pizarra borra SUS audios a los 30 días (`limpiar`; la regla deja sólo
// los propios). Voz comprimida: unos 2 KB por segundo (Opus a 16 kbps), así
// que un minuto son ~120 KB y el tope de la regla (400 KB) sobra.
//
// ── CÓMO LLEGA ───────────────────────────────────────────────────────────────
// Por el mismo camino que un mensaje: el aviso instantáneo despierta al otro
// teléfono, que trae lo nuevo (Charla.ponerAlDia) y acá decide si lo
// reproduce (`debeSonarSolo`): es para mí, no es mío, tiene menos de 10
// minutos —lo viejo que llega después de un rato sin señal no se larga a
// hablar— y el teléfono no está en silencio ni en una llamada.
// ─────────────────────────────────────────────────────────────────────────────

const val COLECCION_AUDIOS = "audios"
const val DURACION_MAXIMA = 60
private const val VIDA_AUDIO_MS = 30L * 24 * 3600_000L

/** El texto que acompaña a un mensaje de voz: lo que se ve donde no hay ▶ (la web, una notificación). */
fun textoDeAudio(dur: Int) = "🎙 Mensaje de voz (${duracionTexto(dur)})"

fun duracionTexto(seg: Int) = "${seg / 60}:${(seg % 60).toString().padStart(2, '0')}"

/** El audio nuevo, con la forma EXACTA que deja crear la regla v18 de Tiempos. */
fun cuerpoAudio(docs: String, id: String, uid: String, datos: ByteArray, dur: Int, tipo: String): JSONObject =
    JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_AUDIOS/$id").put("fields", JSONObject()
            .put("de", JSONObject().put("stringValue", uid))
            .put("datos", JSONObject().put("bytesValue", java.util.Base64.getEncoder().encodeToString(datos)))
            .put("dur", JSONObject().put("integerValue", dur.coerceIn(1, DURACION_MAXIMA).toString()))
            .put("tipo", JSONObject().put("stringValue", tipo))))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))

/** Los bytes que contesta `batchGet`; null si el audio ya no está. */
fun bytesDeAudio(r: JSONArray): ByteArray? {
    val d = r.optJSONObject(0)?.optJSONObject("found") ?: return null
    val b = d.optJSONObject("fields")?.optJSONObject("datos")?.optString("bytesValue").orEmpty()
    return if (b.isEmpty()) null else try { java.util.Base64.getDecoder().decode(b) } catch (e: Exception) { null }
}

/** ¿Este audio recién llegado se escucha solo? */
fun debeSonarSolo(m: Mensaje, yo: String, miTelefono: String, ahora: Long, activado: Boolean): Boolean =
    activado && m.audio.isNotEmpty() && !(m.de == yo && m.dispositivo == miTelefono) && esParaMi(m, yo, miTelefono) &&
        (instante(m.creado)?.let { ahora - it in 0..10 * 60_000L } ?: false)

/** Los audios míos que ya cumplieron 30 días (id → cuándo los mandé). */
fun audiosParaBorrar(mios: Map<String, Long>, ahora: Long): List<String> = mios.filter { ahora - it.value > VIDA_AUDIO_MS }.keys.toList()

/** Graba voz: Opus en Ogg si el teléfono lo tiene (Android 10+), si no AAC. */
class Grabador(private val c: Context) {
    private var r: MediaRecorder? = null
    private var archivo: File? = null
    private var empezo = 0L
    val tipo: String = if (Build.VERSION.SDK_INT >= 29) "audio/ogg" else "audio/mp4"
    val segundos: Int get() = if (empezo == 0L) 0 else ((System.currentTimeMillis() - empezo) / 1000).toInt()

    fun empezar(alTope: () -> Unit) {
        val f = File(c.cacheDir, "grabando." + (if (tipo == "audio/ogg") "ogg" else "m4a"))
        archivo = f
        @Suppress("DEPRECATION")
        val m = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()
        m.setAudioSource(MediaRecorder.AudioSource.MIC)
        if (Build.VERSION.SDK_INT >= 29) { m.setOutputFormat(MediaRecorder.OutputFormat.OGG); m.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS); m.setAudioEncodingBitRate(16_000) }
        else { m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); m.setAudioEncodingBitRate(24_000) }
        m.setAudioChannels(1); m.setAudioSamplingRate(16_000)
        m.setMaxDuration(DURACION_MAXIMA * 1000)
        m.setOnInfoListener { _, que, _ -> if (que == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) alTope() }
        m.setOutputFile(f.absolutePath)
        m.prepare(); m.start()
        r = m; empezo = System.currentTimeMillis()
    }

    /** Para y devuelve los bytes y los segundos; null si fue demasiado corto o falló. */
    fun terminar(): Pair<ByteArray, Int>? {
        val m = r ?: return null
        val seg = ((System.currentTimeMillis() - empezo + 500) / 1000).toInt().coerceIn(1, DURACION_MAXIMA)
        r = null; empezo = 0L
        val ok = try { m.stop(); true } catch (e: Throwable) { false }
        try { m.release() } catch (e: Throwable) { }
        val f = archivo ?: return null
        if (!ok || !f.exists() || f.length() < 200) { f.delete(); return null }
        return f.readBytes().also { f.delete() } to seg
    }

    fun cancelar() { try { r?.stop() } catch (e: Throwable) { }; try { r?.release() } catch (e: Throwable) { }; r = null; empezo = 0L; archivo?.delete() }
}

object Handy {
    private fun prefs(c: Context) = c.getSharedPreferences("handy", Context.MODE_PRIVATE)

    /** «Escuchar los audios solos»: encendido salvo que se apague. */
    fun soloActivado(c: Context) = prefs(c).getBoolean("solo", true)
    fun elegirSolo(c: Context, v: Boolean) = prefs(c).edit().putBoolean("solo", v).apply()

    fun tienePermiso(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Manda lo grabado. En su hilo; avisa el resultado por `listo` (null = salió). */
    fun mandar(c: Context, datos: ByteArray, dur: Int, tipo: String, para: String, listo: (String?) -> Unit) {
        val app = c.applicationContext
        thread {
            val error = try {
                val id = Nube(app).mandarAudio(datos, dur, tipo, nombreDelTelefono(app), para)
                val p = prefs(app)
                val mios = JSONObject(p.getString("mios", "{}") ?: "{}").put(id, System.currentTimeMillis())
                p.edit().putString("mios", mios.toString()).apply()
                Charla.ponerAlDia(app); null
            } catch (e: Throwable) {
                Bitacora.anotar(app, "handy: no salió: " + (e.message ?: e.javaClass.simpleName))
                val m = e.message ?: "no se pudo"
                if (m.startsWith("La base no deja")) "Para mandar audios hay que publicar las reglas v18 de Tiempos." else m
            }
            listo(error)
        }
    }

    /** Lo recién llegado que se escucha solo, uno detrás del otro, si el teléfono no está en silencio ni en una llamada. */
    fun alLlegar(c: Context, nube: Nube, nuevos: List<Mensaje>) {
        val yo = nube.uid.orEmpty(); val tel = nombreDelTelefono(c)
        val sonar = nuevos.filter { debeSonarSolo(it, yo, tel, System.currentTimeMillis(), soloActivado(c)) }.sortedBy { it.creado }
        if (sonar.isEmpty()) return
        val am = c.getSystemService(AudioManager::class.java)
        if (am != null && (am.ringerMode != AudioManager.RINGER_MODE_NORMAL || am.mode != AudioManager.MODE_NORMAL)) return
        for (m in sonar) nube.bajarAudio(m.audio)?.let { reproducir(c, it, m.dur) }
    }

    /** Trae y reproduce un audio (el ▶ de una burbuja). En su hilo. */
    fun escuchar(c: Context, id: String, dur: Int, dice: (String) -> Unit = {}) {
        val app = c.applicationContext
        thread {
            try {
                val b = Nube(app).bajarAudio(id)
                if (b == null) dice("Ese audio ya no está (se borran a los 30 días).") else reproducir(app, b, dur)
            } catch (e: Throwable) { dice("No se pudo escuchar: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** Reproduce y espera a que termine (se llama desde un hilo). */
    private fun reproducir(c: Context, datos: ByteArray, dur: Int) {
        val f = File(c.cacheDir, "escuchando.audio").apply { writeBytes(datos) }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath); mp.prepare(); mp.start()
            val hasta = System.currentTimeMillis() + (dur.coerceAtLeast(1) + 3) * 1000L
            while (System.currentTimeMillis() < hasta && mp.isPlaying) Thread.sleep(200)
        } finally { try { mp.release() } catch (e: Throwable) { }; f.delete() }
    }

    /** Una vez por día: borra mis audios de más de 30 días (los ajenos los borra su dueño). */
    fun limpiar(c: Context, nube: Nube) {
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("limpio", 0) < 24 * 3600_000L) return
        val mios = JSONObject(p.getString("mios", "{}") ?: "{}")
        val mapa = mios.keys().asSequence().associateWith { mios.getLong(it) }
        val viejos = audiosParaBorrar(mapa, System.currentTimeMillis())
        nube.borrarAudios(viejos)
        viejos.forEach { mios.remove(it) }
        p.edit().putString("mios", mios.toString()).putLong("limpio", System.currentTimeMillis()).apply()
    }
}

/** El 🎙 del widget: abre y ya está grabando; «Mandar» lo manda a la pestaña elegida (o a todos). */
class HandyActivity : Activity() {
    private var grabador: Grabador? = null
    private lateinit var dice: TextView
    private val reloj = Handler(Looper.getMainLooper())
    private var para = ""
    private var mandando = false

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val n = Nube(this)
        if (!n.conSesion || !n.esTiempos) { finish(); return }
        para = intent.getStringExtra("clave").orEmpty()
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (12 * d).toInt()) }
        caja.addView(TextView(this).apply { text = "🎙 Handy"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val nombre = if (para.isEmpty()) "todos" else {
            val yo = n.uid.orEmpty()
            conversaciones(with(Charla) { mensajesGuardados }, yo, nombreDelTelefono(this))
                .firstOrNull { it.clave == para }?.let { c -> etiquetaConversacion(c, emptyList(), yo).removePrefix("📱 ") } ?: "todos"
        }
        caja.addView(TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#8A8F98")); text = "Para $nombre · hasta ${DURACION_MAXIMA} s\n$AVISO_CLAUDE_LEE" })
        dice = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER; setPadding(0, (18 * d).toInt(), 0, (18 * d).toInt()) }
        caja.addView(dice)
        val botones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        botones.addView(Button(this).apply { text = "Cancelar"; setOnClickListener { grabador?.cancelar(); finish() } })
        botones.addView(Button(this).apply { text = "Mandar"; setOnClickListener { mandar() } })
        caja.addView(botones)
        setContentView(caja)
        window.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        if (Handy.tienePermiso(this)) empezar()
        else { dice.text = "Hace falta el permiso del micrófono."; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 62) }
    }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, permisos, resultados)
        if (codigo == 62 && Handy.tienePermiso(this)) empezar() else dice.text = "Sin el micrófono no se puede grabar."
    }

    private fun empezar() {
        try {
            grabador = Grabador(this).also { it.empezar { runOnUiThread { mandar() } } }
            val tic = object : Runnable { override fun run() { val g = grabador ?: return; dice.text = "🔴 " + duracionTexto(g.segundos); reloj.postDelayed(this, 250) } }
            reloj.post(tic)
        } catch (e: Throwable) { dice.text = "No se pudo grabar: " + (e.message ?: e.javaClass.simpleName); grabador = null }
    }

    private fun mandar() {
        if (mandando) return
        val g = grabador ?: return
        mandando = true; reloj.removeCallbacksAndMessages(null)
        val r = g.terminar(); grabador = null
        if (r == null) { dice.text = "Muy corto: no se mandó."; mandando = false; return }
        dice.text = "Mandando…"
        Handy.mandar(this, r.first, r.second, g.tipo, para) { error ->
            runOnUiThread { if (error == null) finish() else { dice.text = error; mandando = false } }
        }
    }

    override fun onDestroy() { reloj.removeCallbacksAndMessages(null); if (!mandando) grabador?.cancelar(); super.onDestroy() }
}
