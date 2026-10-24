package com.maurogasta.pizarra

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Archivos.kt — Fotos y documentos por la charla. Sello: pizarra-64
//
// Mauro, 24-oct-2026: «que se puedan por el mismo chat enviar archivos, pueden
// ser fotos o algún documento que se vaya a guardar temporalmente en la base».
//
// ── DÓNDE Y CUÁNTO TIEMPO ────────────────────────────────────────────────────
// En la base de Tiempos, `archivos/` (reglas v20), como los audios del handy y
// por el mismo motivo: Cloudinary es la cuenta de las fotos de Casa Verde y
// borrar ahí pide el secreto. Un documento de Firestore no pasa de 1 MB, así
// que un archivo va en PARTES de hasta 700 KB (`partir`), hasta 10: unos 6 MB.
// Una foto se achica en el teléfono antes de salir (`achicarFoto`, 1600 px,
// JPEG 80): queda en 200 a 400 KB, una sola parte. Es TEMPORAL: cada Pizarra
// borra lo suyo a los 7 días (`Archivos.limpiar`); el mensaje queda con el
// nombre y dice que ya no está. Un instalador de Android no entra (la regla).
//
// ── LO QUE GASTA (PROTOCOLO-DESARROLLO § 13) ─────────────────────────────────
// 10 personas, 10 fotos por día: unos 3 MB por día, 21 MB guardados (se van a
// los 7 días) contra 1 GB; una lectura por parte al abrir. Nada.
//
// ── ABRIR ────────────────────────────────────────────────────────────────────
// Lo que llega se baja al tocarlo, se guarda en la caché de la app y se abre
// con la app que el teléfono tenga para ese tipo, por `Entrega` (un proveedor
// de pocas líneas: la app no tiene androidx, y no hace falta).
// ─────────────────────────────────────────────────────────────────────────────

const val COLECCION_ARCHIVOS = "archivos"
const val PARTE_MAX = 700_000
const val PARTES_MAX = 10
private const val VIDA_ARCHIVO_MS = 7L * 24 * 3600_000L
const val AUTORIDAD_ENTREGA = "com.maurogasta.pizarra.entrega"

/** Corta los bytes en partes de hasta 700 KB; null si no entra en 10. */
fun partir(datos: ByteArray, max: Int = PARTE_MAX): List<ByteArray>? {
    if (datos.isEmpty()) return null
    val l = (datos.indices step max).map { datos.copyOfRange(it, minOf(it + max, datos.size)) }
    return if (l.size > PARTES_MAX) null else l
}

fun juntar(partes: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply { partes.forEach { write(it) } }.toByteArray()

fun idDeParte(archivo: String, n: Int) = "$archivo-$n"

fun tamTexto(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${(b + 512) / 1024} KB"
    else -> String.format(java.util.Locale("es"), "%.1f MB", b / 1048576.0)
}

fun esImagen(tipo: String) = tipo.startsWith("image/")

/** El texto del mensaje: lo que se ve donde no hay botón para abrir. */
fun textoDeArchivo(nombre: String, tipo: String, tam: Long) =
    (if (esImagen(tipo)) "📷 Foto" else "📎 ${nombre.take(80)}") + " (${tamTexto(tam)})"

/** Una parte nueva, con la forma EXACTA que deja crear la regla v20. */
fun cuerpoParte(docs: String, archivo: String, n: Int, total: Int, uid: String, datos: ByteArray, nombre: String, tipo: String): JSONObject =
    JSONObject().put("writes", JSONArray().put(JSONObject()
        .put("update", JSONObject().put("name", "$docs/$COLECCION_ARCHIVOS/${idDeParte(archivo, n)}").put("fields", JSONObject()
            .put("de", JSONObject().put("stringValue", uid))
            .put("datos", JSONObject().put("bytesValue", java.util.Base64.getEncoder().encodeToString(datos)))
            .put("parte", JSONObject().put("integerValue", n.toString()))
            .put("partes", JSONObject().put("integerValue", total.toString()))
            .put("nombre", JSONObject().put("stringValue", nombre.take(120)))
            .put("tipo", JSONObject().put("stringValue", tipo.take(100)))))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "creadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", false))))

/** Lo que contesta `batchGet` de las partes, en orden; null si falta alguna (ya se borró). */
fun partesDeRespuesta(r: JSONArray, total: Int): List<ByteArray>? {
    val porNumero = mutableMapOf<Int, ByteArray>()
    for (i in 0 until r.length()) {
        val f = r.optJSONObject(i)?.optJSONObject("found")?.optJSONObject("fields") ?: continue
        val n = f.optJSONObject("parte")?.optString("integerValue")?.toIntOrNull() ?: continue
        val b = f.optJSONObject("datos")?.optString("bytesValue").orEmpty()
        if (b.isNotEmpty()) porNumero[n] = try { java.util.Base64.getDecoder().decode(b) } catch (e: Exception) { continue }
    }
    return if ((0 until total).all { it in porNumero }) (0 until total).map { porNumero.getValue(it) } else null
}

/** Una foto, achicada: el lado mayor a 1600 px, JPEG 80. */
fun achicarFoto(datos: ByteArray, lado: Int = 1600): ByteArray {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(datos, 0, datos.size, o)
    var paso = 1
    while (maxOf(o.outWidth, o.outHeight) / (paso * 2) >= lado) paso *= 2
    val b = BitmapFactory.decodeByteArray(datos, 0, datos.size, BitmapFactory.Options().apply { inSampleSize = paso }) ?: return datos
    val escala = minOf(1f, lado.toFloat() / maxOf(b.width, b.height))
    val c = if (escala < 1f) Bitmap.createScaledBitmap(b, (b.width * escala).toInt(), (b.height * escala).toInt(), true) else b
    return ByteArrayOutputStream().also { c.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
}

object Archivos {
    private fun prefs(c: Context) = c.getSharedPreferences("archivos", Context.MODE_PRIVATE)

    /** Lee lo elegido, achica si es foto, lo parte y lo manda. En su hilo; `listo` recibe el error o null. */
    fun mandar(c: Context, uri: Uri, para: String, listo: (String?) -> Unit) {
        val app = c.applicationContext
        thread {
            val error = try {
                val cr = app.contentResolver
                var nombre = "archivo"
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) nombre = it.getString(0) ?: nombre }
                var tipo = cr.getType(uri) ?: "application/octet-stream"
                var datos = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw ErrorNube("No se pudo leer el archivo.")
                if (esImagen(tipo) && tipo != "image/gif") { datos = achicarFoto(datos); tipo = "image/jpeg"; nombre = nombre.substringBeforeLast('.') + ".jpg" }
                val partes = partir(datos) ?: throw ErrorNube("Es muy grande: hasta ${tamTexto((PARTE_MAX * PARTES_MAX).toLong())}.")
                val id = Nube(app).mandarArchivo(partes, nombre, tipo, datos.size.toLong(), nombreDelTelefono(app), para)
                val p = prefs(app)
                val mios = JSONObject(p.getString("mios", "{}") ?: "{}").put(id, JSONObject().put("en", System.currentTimeMillis()).put("partes", partes.size))
                p.edit().putString("mios", mios.toString()).apply()
                Charla.ponerAlDia(app); null
            } catch (e: Throwable) {
                Bitacora.anotar(app, "archivos: no salió: " + (e.message ?: e.javaClass.simpleName))
                val m = e.message ?: "no se pudo"
                if (m.startsWith("La base no deja")) "Para mandar archivos hay que publicar las reglas v20 de Tiempos." else m
            }
            listo(error)
        }
    }

    /** Baja (si hace falta) y abre con la app del teléfono. En su hilo. */
    fun abrir(c: Context, id: String, partes: Int, nombre: String, dice: (String) -> Unit) {
        val app = c.applicationContext
        thread {
            try {
                val dir = File(app.cacheDir, "entrega").apply { mkdirs() }
                val f = File(dir, "$id-" + nombre.replace(Regex("[^\\w.\\-]"), "_").take(80))
                if (!f.exists()) {
                    val b = Nube(app).bajarArchivo(id, partes) ?: run { dice("Ese archivo ya no está (se borran a los 7 días)."); return@thread }
                    f.writeBytes(b)
                }
                val tipo = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                val ver = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://$AUTORIDAD_ENTREGA/${f.name}"), tipo)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try { app.startActivity(Intent.createChooser(ver, nombre).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Throwable) { dice("No hay una app para abrir este tipo de archivo.") }
            } catch (e: Throwable) { dice("No se pudo abrir: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** Una vez por día: borra mis archivos de más de 7 días, y lo bajado a la caché. */
    fun limpiar(c: Context, nube: Nube) {
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("limpio", 0) < 24 * 3600_000L) return
        val mios = JSONObject(p.getString("mios", "{}") ?: "{}")
        val viejos = mios.keys().asSequence().filter { System.currentTimeMillis() - mios.getJSONObject(it).optLong("en") > VIDA_ARCHIVO_MS }.toList()
        nube.borrarArchivos(viejos.flatMap { a -> (0 until mios.getJSONObject(a).optInt("partes", 1)).map { idDeParte(a, it) } })
        viejos.forEach { mios.remove(it) }
        File(c.cacheDir, "entrega").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > VIDA_ARCHIVO_MS }?.forEach { it.delete() }
        p.edit().putString("mios", mios.toString()).putLong("limpio", System.currentTimeMillis()).apply()
    }
}

/** Entrega a la app que abre un archivo SÓLO lo que se bajó a `cache/entrega`, de lectura y con permiso por toque. */
class Entrega : ContentProvider() {
    private fun archivo(uri: Uri): File? {
        val dir = File(context!!.cacheDir, "entrega")
        val f = File(dir, uri.lastPathSegment ?: return null)
        return if (f.canonicalFile.parentFile == dir.canonicalFile && f.exists()) f else null
    }
    override fun onCreate() = true
    override fun openFile(uri: Uri, modo: String): ParcelFileDescriptor? {
        if (modo != "r") throw SecurityException("sólo lectura")
        return archivo(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    }
    override fun getType(uri: Uri): String? = archivo(uri)?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val f = archivo(uri) ?: return null
        return android.database.MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name.substringAfter('-'), f.length())) }
    }
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
