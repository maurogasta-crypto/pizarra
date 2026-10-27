package com.maurogasta.pizarra

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Base64
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Compartir.kt — «Compartir → Pizarra»: mandar lo que sea a otro teléfono.
// Sello: pizarra-73
//
// Mauro, 27-oct-2026 (general:C5): «si yo estoy ante una imagen o un archivo o
// algo que estoy viendo en otra aplicación y toco compartir, quiero que
// aparezca la pizarra para podérselo enviar a alguno de los otros teléfonos de
// forma directa». Y eligió la opción 1: sin un «drive» propio.
//
// ── CÓMO VIAJA ───────────────────────────────────────────────────────────────
// · Un ARCHIVO (foto, video, documento), si el teléfono de destino está CERCA
//   con el enlace conectado: entero, por el wi-fi de la casa o el Bluetooth,
//   en trozos firmados de 2.400 bytes (`trozosDe`, tipo «archivo»), sin datos
//   y sin pasar por ninguna base. Hasta `CERCA_MAXIMO`. La foto va como está.
// · Si está LEJOS: por la charla, como el 📎 (`Archivos.mandar`): la base de
//   Tiempos, hasta unos 6 MB, la foto achicada, y se borra a los 7 días.
// · Un TEXTO o un link: siempre por la charla (es chico y queda en la
//   conversación).
// Lo que llega cerca se guarda en la caché de la app, avisa con una
// notificación que lo abre, y trae «Guardar en Descargas».
//
// ── CUPO ─────────────────────────────────────────────────────────────────────
// Cerca: cero. Lejos: lo de `Archivos.kt` (1 escritura por parte, 1 lectura
// por parte al abrir; se va a los 7 días). No hay regla nueva.
//
// La bitácora dice cuánto y de qué tipo, nunca el nombre ni el texto.
// ─────────────────────────────────────────────────────────────────────────────

/** Bytes de archivo por renglón del enlace: en Base64 y con la firma entra holgado en LARGO_MAXIMO. */
const val TROZO_ARCHIVO = 2400
/** Lo más grande que se manda cerca de una vez (un video corto). */
const val CERCA_MAXIMO = 200L * 1024 * 1024
/** Lo más grande que se manda lejos: lo que entra en la charla. */
const val LEJOS_MAXIMO = PARTE_MAX.toLong() * PARTES_MAX

/** Cuántos trozos lleva un archivo de [tam] bytes. */
fun trozosDe(tam: Long): Int = if (tam <= 0) 0 else ((tam + TROZO_ARCHIVO - 1) / TROZO_ARCHIVO).toInt()

/** Un nombre de archivo que no sale de su carpeta ni rompe nada. */
fun nombreSeguro(n: String): String =
    n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\w.\\- ()áéíóúñÁÉÍÓÚÑ]"), "_").trim().ifBlank { "archivo" }.take(80)

/** Un trozo del archivo, para el enlace. */
fun mensajeArchivo(id: String, n: Int, total: Int, nombre: String, tipo: String, tam: Long, datos: ByteArray): JSONObject =
    JSONObject().put("v", VERSION_ENLACE).put("app", "pizarra").put("tipo", "archivo").put("id", id.take(24))
        .put("n", n).put("total", total).put("nombre", nombreSeguro(nombre)).put("mime", tipo.take(100)).put("tam", tam)
        .put("b64", Base64.getEncoder().encodeToString(datos))

/** Un trozo que llega, leído con desconfianza: forma, numeración y tamaño. */
data class TrozoArchivo(val id: String, val n: Int, val total: Int, val nombre: String, val tipo: String, val tam: Long, val datos: ByteArray)

fun trozoDe(o: JSONObject): TrozoArchivo? = try {
    val id = o.optString("id")
    val n = o.optInt("n", -1); val total = o.optInt("total", 0); val tam = o.optLong("tam", -1)
    val datos = Base64.getDecoder().decode(o.optString("b64"))
    when {
        o.optString("tipo") != "archivo" || !id.matches(Regex("[A-Za-z0-9]{1,24}")) -> null
        tam <= 0 || tam > CERCA_MAXIMO || total != trozosDe(tam) || n !in 0 until total -> null
        datos.size > TROZO_ARCHIVO || (n < total - 1 && datos.size != TROZO_ARCHIVO) -> null
        else -> TrozoArchivo(id, n, total, nombreSeguro(o.optString("nombre")), o.optString("mime").ifBlank { "application/octet-stream" }, tam, datos)
    }
} catch (e: Throwable) { null }

/** Lo que se va a mandar, dicho en una línea. */
fun resumenCompartido(nombres: List<Pair<String, Long>>, texto: String): String = when {
    nombres.size == 1 -> "📎 ${nombres[0].first} (${tamTexto(nombres[0].second)})"
    nombres.size > 1 -> "📎 ${nombres.size} archivos (${tamTexto(nombres.sumOf { it.second })})"
    texto.isNotBlank() -> "📝 " + texto.take(160) + if (texto.length > 160) "…" else ""
    else -> "Nada para mandar"
}

/** Un destino de la ventanita: cerca (por el enlace) o lejos (por la charla, con su `para`). */
data class Destino(val etiqueta: String, val cerca: Boolean, val para: String)

/**
 * Los destinos, en orden: el teléfono conectado cerca primero (si hay), después cada conversación de la
 * charla y «Todos». Cerca sólo sirve para archivos: un texto va siempre por la charla.
 */
fun destinosDeCompartir(cs: List<Conversacion>, yo: String, cercano: String?, hayArchivos: Boolean, conSesion: Boolean): List<Destino> {
    val l = mutableListOf<Destino>()
    if (cercano != null && hayArchivos) l.add(Destino("📡 $cercano · cerca, sin datos", true, ""))
    if (conSesion) {
        cs.forEach { l.add(Destino(etiquetaConversacion(it, cs, yo) + " · por internet", false, it.clave)) }
        l.add(Destino("👥 Todos · por internet", false, ""))
    }
    return l
}

/** Lo que llega cerca, trozo por trozo, a la caché; completo, a `cache/entrega` y un aviso. */
object RecepcionCerca {
    private val enCurso = mutableMapOf<String, Int>()   // id → próximo trozo esperado

    /** Devuelve el nombre cuando el archivo quedó completo; null mientras tanto o si algo no cierra. */
    @Synchronized fun recibir(c: Context, o: JSONObject): String? {
        val t = trozoDe(o) ?: return null
        val dir = File(c.cacheDir, "recibiendo").apply { mkdirs() }
        val parte = File(dir, t.id)
        if (t.n == 0) { parte.delete(); enCurso[t.id] = 0 }
        if (enCurso[t.id] != t.n) { enCurso.remove(t.id); parte.delete(); return null }   // se perdió uno: se descarta entero
        try { parte.appendBytes(t.datos) } catch (e: Throwable) { enCurso.remove(t.id); return null }
        if (t.n < t.total - 1) { enCurso[t.id] = t.n + 1; return null }
        enCurso.remove(t.id)
        if (parte.length() != t.tam) { parte.delete(); return null }
        val final = File(File(c.cacheDir, "entrega").apply { mkdirs() }, "${t.id}-${t.nombre}")
        if (!parte.renameTo(final)) { parte.copyTo(final, true); parte.delete() }
        Bitacora.anotar(c, "compartir: llegó cerca un archivo (${t.tipo.substringBefore('/')}, ${tamTexto(t.tam)}) desde ${o.optString("de")}")
        avisar(c, final, t, o.optString("de"))
        return t.nombre
    }

    private fun avisar(c: Context, f: File, t: TrozoArchivo, de: String) {
        try {
            crearCanales(c)
            val uri = Uri.parse("content://$AUTORIDAD_ENTREGA/${f.name}")
            val ver = Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, t.tipo)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t.nombre).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val id = f.name.hashCode()
            val abrir = PendingIntent.getActivity(c, id, ver, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = Notification.Builder(c, CANAL_ENLACE_EVENTOS).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("📎 ${t.nombre}").setContentText("${tamTexto(t.tam)} · de ${de.ifBlank { "la otra Pizarra" }} · tocá para abrir")
                .setAutoCancel(true).setContentIntent(abrir)
            if (Build.VERSION.SDK_INT >= 29) b.addAction(Notification.Action.Builder(null, "Guardar en Descargas",
                PendingIntent.getBroadcast(c, id, Intent(c, GuardarReceptor::class.java).putExtra("archivo", f.name).putExtra("tipo", t.tipo).putExtra("aviso", id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
        } catch (e: Throwable) { }
    }
}

/** «Guardar en Descargas» de un archivo que llegó cerca (Android 10+, sin permisos: MediaStore). */
class GuardarReceptor : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pendiente = goAsync()
        thread {
            try {
                val nombre = i.getStringExtra("archivo").orEmpty()
                val f = File(File(c.cacheDir, "entrega"), nombre)
                if (Build.VERSION.SDK_INT >= 29 && f.exists() && f.canonicalFile.parentFile == File(c.cacheDir, "entrega").canonicalFile) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, nombre.substringAfter('-'))
                        put(MediaStore.Downloads.MIME_TYPE, i.getStringExtra("tipo") ?: "application/octet-stream")
                    }
                    val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    if (u != null) c.contentResolver.openOutputStream(u)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(i.getIntExtra("aviso", 0))
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(c, "Guardado en Descargas", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Throwable) { Bitacora.anotar(c, "compartir: no se guardó en Descargas (${e.javaClass.simpleName})") }
            finally { pendiente.finish() }
        }
    }
}

/** La ventanita que aparece en «Compartir»: qué se manda y a qué teléfono. */
class EnviarActivity : Activity() {
    private val uris = mutableListOf<Uri>()
    private val info = mutableListOf<Pair<String, Long>>()
    private var texto = ""
    private lateinit var dice: TextView
    private lateinit var lista: LinearLayout
    @Volatile private var mandando = false

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        leerIntent(intent)
        val d = resources.displayMetrics.density
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (12 * d).toInt()) }
        caja.addView(TextView(this).apply { text = "📤 Mandar a otro teléfono"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        caja.addView(TextView(this).apply { textSize = 14f; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt()); text = resumenCompartido(info, texto) })
        dice = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#E8D3A2")) }
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        caja.addView(ScrollView(this).apply { addView(lista) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        caja.addView(dice)
        caja.addView(Button(this).apply { text = "Cancelar"; setOnClickListener { finish() } })
        setContentView(caja)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), (resources.displayMetrics.heightPixels * 0.7).toInt())
        EnlaceServicio.buscarYa(this)
        pintarDestinos()
    }

    private fun leerIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND) {
            val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            if (u != null) uris.add(u)
        } else if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            val l = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            l?.let { uris.addAll(it.take(20)) }
        }
        texto = textoCompartido(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
        for (u in uris) info.add(datosDe(u))
    }

    private fun datosDe(u: Uri): Pair<String, Long> = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) (it.getString(0) ?: "archivo") to (if (it.isNull(1)) -1L else it.getLong(1)) else null
        } ?: ("archivo" to -1L)
    } catch (e: Throwable) { "archivo" to -1L }

    private fun pintarDestinos() {
        lista.removeAllViews()
        val n = Nube(this)
        val conSesion = n.conSesion && n.esTiempos
        val yo = n.uid.orEmpty()
        val cs = if (conSesion) conversaciones(with(Charla) { mensajesGuardados }, yo, nombreDelTelefono(this)) else emptyList()
        val destinos = destinosDeCompartir(cs, yo, EnlaceServicio.cercano(), uris.isNotEmpty(), conSesion)
        if (destinos.isEmpty()) {
            dice.text = if (!EnlaceAjustes.prendido(this)) "Para mandar sin internet prendé el enlace cerca; para mandar lejos, entrá con tu cuenta de Tiempos."
                else "No hay ninguna Pizarra cerca todavía (buscando…), y para mandar lejos hace falta entrar con la cuenta de Tiempos."
            lista.postDelayed({ if (!mandando && !isFinishing) pintarDestinos() }, 3_000)
            return
        }
        for (d in destinos) lista.addView(Button(this).apply {
            text = d.etiqueta; isAllCaps = false; gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { mandar(d) }
        })
        if (EnlaceServicio.cercano() == null && uris.isNotEmpty() && EnlaceAjustes.prendido(this))
            lista.postDelayed({ if (!mandando && !isFinishing && EnlaceServicio.cercano() != null) pintarDestinos() }, 3_000)
    }

    private fun mandar(d: Destino) {
        if (mandando) return
        mandando = true
        lista.removeAllViews()
        dice.text = "Mandando…"
        val app = applicationContext
        thread {
            val error = try {
                if (d.cerca) uris.forEachIndexed { k, u -> mandarCerca(u, info[k], k) }
                else {
                    for (u in uris) {
                        val tam = info[uris.indexOf(u)].second
                        if (tam > LEJOS_MAXIMO && !esImagen(contentResolver.getType(u) ?: "")) throw ErrorNube("Es muy grande para mandar lejos (hasta ${tamTexto(LEJOS_MAXIMO)}). Cerca, con el enlace, va entero.")
                        var err: String? = null
                        val listo = java.util.concurrent.CountDownLatch(1)
                        Archivos.mandar(app, u, d.para) { e -> err = e; listo.countDown() }
                        listo.await()
                        err?.let { throw ErrorNube(it) }
                    }
                    if (texto.isNotBlank()) Nube(app).mandarMensaje(texto, nombreDelTelefono(app), d.para)
                }
                Bitacora.anotar(app, "compartir: ${uris.size} archivo(s)${if (texto.isNotBlank()) " y un texto" else ""} ${if (d.cerca) "cerca" else "por internet"}")
                null
            } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                mandando = false
                if (error == null) { dice.text = if (d.cerca) "✔ Mandado. Esperando que la otra diga que llegó…" else "✔ Mandado por la charla."; esperarYCerrar(d.cerca) }
                else { dice.text = "No salió: $error"; pintarDestinos() }
            }
        }
    }

    /** Un archivo por el enlace, trozo por trozo, esperando cada uno: sin cola en memoria. */
    private fun mandarCerca(u: Uri, datos: Pair<String, Long>, k: Int) {
        val tipo = contentResolver.getType(u) ?: "application/octet-stream"
        val tam = if (datos.second > 0) datos.second else contentResolver.openInputStream(u)?.use { it.readBytes().size.toLong() } ?: 0L
        if (tam <= 0) throw ErrorNube("No se pudo leer «${datos.first}».")
        if (tam > CERCA_MAXIMO) throw ErrorNube("«${datos.first}» pasa de ${tamTexto(CERCA_MAXIMO)}.")
        val id = idNuevo().take(16)
        val total = trozosDe(tam)
        contentResolver.openInputStream(u)?.use { ent ->
            val b = ByteArray(TROZO_ARCHIVO)
            for (n in 0 until total) {
                var leido = 0
                while (leido < b.size) { val r = ent.read(b, leido, b.size - leido); if (r <= 0) break; leido += r }
                if (!EnlaceServicio.mandarCercaYEsperar(mensajeArchivo(id, n, total, datos.first, tipo, tam, b.copyOf(leido))))
                    throw ErrorNube("Se cortó el enlace. Probá de nuevo, o mandalo por internet.")
                if (n % 40 == 0) runOnUiThread { dice.text = "Mandando ${if (uris.size > 1) "${k + 1} de ${uris.size}" else ""} · ${100 * (n + 1) / total} %" }
            }
        } ?: throw ErrorNube("No se pudo leer «${datos.first}».")
    }

    private fun esperarYCerrar(cerca: Boolean) {
        val desde = System.currentTimeMillis()
        fun mirar() {
            if (isFinishing) return
            if (cerca && EnlaceServicio.ultimoAcuseEn >= desde) { dice.text = EnlaceServicio.ultimoAcuse; lista.postDelayed({ finish() }, 1_500); return }
            if (!cerca || System.currentTimeMillis() - desde > 15_000) { lista.postDelayed({ finish() }, 1_200); return }
            lista.postDelayed({ mirar() }, 500)
        }
        mirar()
    }
}
