package com.maurogasta.pizarra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
// Imagenes.kt — Subir la captura de un reel o un flyer. Sello: pizarra-11
//
// La misma cuenta y el mismo preset SIN FIRMA que `CV2.subirImagen` de Casa
// Verde (públicos por diseño: identifican, no abren nada), a la carpeta
// `tiempos`. Se sube al tocar «Seguir en Tiempos», que es mandar —nunca al
// elegirla—, achicada a 1600 px como `comprimirImagen` del sitio.
// ─────────────────────────────────────────────────────────────────────────────

private const val PRESET = "preset-comprobantes"
private const val LADO = 1600

object Imagenes {
    /** La imagen achicada, en JPEG. */
    fun comprimir(c: Context, uri: Uri): ByteArray {
        val r = c.contentResolver
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
        var paso = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / (paso * 2) >= LADO) paso *= 2
        val bmp = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = paso }) }
            ?: throw ErrorNube("No pude leer la imagen.")
        val escala = minOf(1f, LADO.toFloat() / maxOf(bmp.width, bmp.height))
        val chica = if (escala < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * escala).toInt(), (bmp.height * escala).toInt(), true) else bmp
        return ByteArrayOutputStream().also { chica.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }

    /** Sube y devuelve la dirección de entrega. */
    fun subir(c: Context, uri: Uri): String {
        if (Nube.sinRed) throw ErrorNube("Sin conexión (banco de pruebas).")
        val datos = comprimir(c, uri)
        val borde = "----tiempos" + System.nanoTime()
        val cuerpo = ByteArrayOutputStream().apply {
            fun campo(n: String, v: String) = write("--$borde\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
            campo("upload_preset", PRESET)
            campo("asset_folder", "tiempos")
            write("--$borde\r\nContent-Disposition: form-data; name=\"file\"; filename=\"captura.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            write(datos)
            write("\r\n--$borde--\r\n".toByteArray())
        }.toByteArray()
        val con = URL("https://api.cloudinary.com/v1_1/$CLOUDINARY_CUENTA/image/upload").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"; con.doOutput = true
            con.connectTimeout = 15_000; con.readTimeout = 30_000
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$borde")
            con.outputStream.use { it.write(cuerpo) }
            val ok = con.responseCode in 200..299
            val t = (if (ok) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw ErrorNube("Cloudinary contestó ${con.responseCode}.")
            val u = JSONObject(t).optString("secure_url")
            if (!esImagenNuestra(u)) throw ErrorNube("Cloudinary no devolvió la dirección.")
            return u
        } catch (e: ErrorNube) { throw e } catch (e: Exception) {
            throw ErrorNube("Sin conexión para subir la captura.")
        } finally { con.disconnect() }
    }
}
