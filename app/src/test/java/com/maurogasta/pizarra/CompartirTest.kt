package com.maurogasta.pizarra

import android.content.Intent
import android.net.Uri
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

// pizarra-73, general:C5: «Compartir → Mandar a otro teléfono».
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompartirTest {
    private val llave = llaveDe("ABCDEFGHJK")

    @Test fun losTrozos_secuentanBien() {
        assertEquals(0, trozosDe(0))
        assertEquals(1, trozosDe(1))
        assertEquals(1, trozosDe(TROZO_ARCHIVO.toLong()))
        assertEquals(2, trozosDe(TROZO_ARCHIVO + 1L))
    }

    @Test fun unTrozo_entraEnUnRenglonFirmadoYVuelveIgual() {
        val datos = ByteArray(TROZO_ARCHIVO) { (it % 256).toByte() }
        val o = mensajeArchivo("abc123", 0, 3, "una foto muy larga ".repeat(10) + ".jpg", "image/jpeg", TROZO_ARCHIVO * 3L - 10, datos)
            .put("t", 5L).put("de", "Redmi 15")
        val r = renglonFirmado(llave, o.toString())
        assertTrue("entra en el renglón: ${r.length}", r.length < LARGO_MAXIMO)
        val t = trozoDe(leerRenglon(llave, r, 5L, mutableMapOf())!!)!!
        assertArrayEquals(datos, t.datos)
        assertEquals(3, t.total); assertEquals(0, t.n)
    }

    @Test fun unTrozo_seLeeConDesconfianza() {
        fun o(n: Int, total: Int, tam: Long, datos: ByteArray, id: String = "abc") = mensajeArchivo(id, n, total, "x.pdf", "application/pdf", tam, datos)
        assertNull("total que no cierra con el tamaño", trozoDe(o(0, 5, 10, ByteArray(10))))
        assertNull("número fuera de rango", trozoDe(o(3, 1, 10, ByteArray(10))))
        assertNull("un trozo del medio corto", trozoDe(o(0, 2, TROZO_ARCHIVO + 5L, ByteArray(10))))
        assertNull("más grande de lo permitido", trozoDe(o(0, trozosDe(CERCA_MAXIMO + 1), CERCA_MAXIMO + 1, ByteArray(TROZO_ARCHIVO))))
        assertNull("un id con barras", trozoDe(o(0, 1, 10, ByteArray(10), id = "../x")))
        assertNotNull(trozoDe(o(0, 1, 10, ByteArray(10))))
        assertNull("otro tipo", trozoDe(JSONObject().put("tipo", "voz")))
    }

    @Test fun elNombre_noSaleDeSuCarpeta() {
        assertEquals("passwd", nombreSeguro("../../etc/passwd"))
        assertEquals("foto 1.jpg", nombreSeguro("foto 1.jpg"))
        assertEquals("archivo", nombreSeguro(""))
        assertFalse(nombreSeguro("a/b\\c").contains("/"))
    }

    @Test fun otraApp_noMandaArchivosPorLaPuerta() {
        assertNull(mensajeDeApp("hilux", """{"tipo":"archivo","b64":"AAAA"}""", "x", 1L))
        assertTrue("archivo" in TIPOS_ENLACE)
    }

    @Test fun losDestinos_cercaPrimero_yCercaSoloConArchivos() {
        val cs = listOf(Conversacion("u2|*", "u2", "*", "Florencia", "2026-10-27"), Conversacion("u1|Redmi Note 9", "u1", "Redmi Note 9", "Yo", ""))
        val d = destinosDeCompartir(cs, "u1", "Redmi Note 9", hayArchivos = true, conSesion = true)
        assertTrue(d[0].cerca); assertTrue(d[0].etiqueta.contains("cerca, sin datos"))
        assertEquals(listOf("u2|*", "u1|Redmi Note 9", ""), d.drop(1).map { it.para })
        assertTrue("un texto va por la charla", destinosDeCompartir(cs, "u1", "Redmi Note 9", hayArchivos = false, conSesion = true).none { it.cerca })
        assertEquals("sin sesión, sólo cerca", 1, destinosDeCompartir(cs, "u1", "Redmi Note 9", hayArchivos = true, conSesion = false).size)
        assertTrue(destinosDeCompartir(cs, "u1", null, hayArchivos = true, conSesion = false).isEmpty())
    }

    @Test fun elResumen() {
        assertEquals("📎 a.pdf (2 KB)", resumenCompartido(listOf("a.pdf" to 2048L), ""))
        assertTrue(resumenCompartido(listOf("a" to 1L, "b" to 1L), "").startsWith("📎 2 archivos"))
        assertEquals("📝 hola", resumenCompartido(emptyList(), "hola"))
    }

    @Test fun recibir_arma_elArchivo_yUnoPerdidoLoDescarta() {
        val c = RuntimeEnvironment.getApplication()
        val datos = ByteArray(TROZO_ARCHIVO * 2 + 7) { (it * 7).toByte() }
        val total = trozosDe(datos.size.toLong())
        fun trozo(n: Int) = mensajeArchivo("ok1", n, total, "doc.pdf", "application/pdf", datos.size.toLong(),
            datos.copyOfRange(n * TROZO_ARCHIVO, minOf(datos.size, (n + 1) * TROZO_ARCHIVO))).put("de", "Note 9")
        assertNull(RecepcionCerca.recibir(c, trozo(0)))
        assertNull(RecepcionCerca.recibir(c, trozo(1)))
        assertEquals("doc.pdf", RecepcionCerca.recibir(c, trozo(2)))
        assertArrayEquals(datos, File(File(c.cacheDir, "entrega"), "ok1-doc.pdf").readBytes())
        // Si falta uno del medio, no queda un archivo roto.
        fun trozo2(n: Int) = JSONObject(trozo(n).toString()).put("id", "mal1")
        assertNull(RecepcionCerca.recibir(c, trozo2(0)))
        assertNull(RecepcionCerca.recibir(c, trozo2(2)))
        assertFalse(File(File(c.cacheDir, "entrega"), "mal1-doc.pdf").exists())
    }

    @Test fun laVentana_abreConUnArchivoCompartido() {
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://x/y.pdf"))
        val a = Robolectric.buildActivity(EnviarActivity::class.java, i).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        fun textos(v: android.view.View): List<String> = when (v) {
            is android.widget.TextView -> listOf(v.text.toString())
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { textos(v.getChildAt(it)) }
            else -> emptyList()
        }
        assertTrue(textos(a.window.decorView).any { it.contains("Mandar a otro teléfono") })
        assertFalse(a.isFinishing)
    }

    @Test fun elManifiesto_ofreceLaPizarraParaCualquierCosa() {
        val m = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map { File(it) }.first { it.exists() }.readText()
        val b = m.substring(m.indexOf("android:name=\".EnviarActivity\""), m.indexOf("</activity>", m.indexOf("android:name=\".EnviarActivity\"")))
        assertTrue(b.contains("android.intent.action.SEND\""))
        assertTrue(b.contains("android.intent.action.SEND_MULTIPLE"))
        assertTrue(b.contains("android:mimeType=\"*/*\""))
    }
}
