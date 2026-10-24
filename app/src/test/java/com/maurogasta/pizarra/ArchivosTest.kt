package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-64: fotos y documentos por la charla.
class ArchivosTest {
    private val docs = "projects/tiempos-71d42/databases/(default)/documents"

    @Test fun seParteEnPedazosDe700KB_yVuelveIgual_hastaDiez() {
        val datos = ByteArray(1_500_000) { (it % 253).toByte() }
        val p = partir(datos)!!
        assertEquals(3, p.size); assertTrue(p.all { it.size <= PARTE_MAX })
        assertArrayEquals(datos, juntar(p))
        assertNull(partir(ByteArray(PARTE_MAX * PARTES_MAX + 1)))
        assertNull(partir(ByteArray(0)))
    }

    @Test fun laParte_tieneLaFormaQueDejaLaReglaV20_yLasPartesSeArmanEnOrden() {
        val w = cuerpoParte(docs, "f1", 1, 2, "yo", byteArrayOf(9, 8, 7), "plano.pdf", "application/pdf").getJSONArray("writes").getJSONObject(0)
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertTrue(w.getJSONObject("update").getString("name").endsWith("/archivos/f1-1"))
        assertEquals(setOf("de", "datos", "parte", "partes", "nombre", "tipo"), f.keys().asSequence().toSet())
        val f0 = cuerpoParte(docs, "f1", 0, 2, "yo", byteArrayOf(1, 2), "plano.pdf", "application/pdf").getJSONArray("writes").getJSONObject(0).getJSONObject("update").getJSONObject("fields")
        val r = JSONArray().put(JSONObject().put("found", JSONObject().put("fields", f))).put(JSONObject().put("found", JSONObject().put("fields", f0)))
        assertArrayEquals(byteArrayOf(1, 2, 9, 8, 7), juntar(partesDeRespuesta(r, 2)!!))
        assertNull(partesDeRespuesta(JSONArray().put(JSONObject().put("found", JSONObject().put("fields", f))), 2))   // falta una: ya se borró
        val reglas = java.io.File("../../tiempos/firestore.rules")
        if (!reglas.exists()) return
        val b = reglas.readText().substringAfter("match /archivos/{id}").substringBefore("\n    }")
        for (k in f.keys().asSequence() + "creadoEn") assertTrue("la regla no nombra «$k»", b.contains("'$k'"))
    }

    @Test fun elMensaje_nombraAlArchivo_yDiceQueEs() {
        val f = cuerpoMensaje(docs, "m1", "yo", "Mauro", "Redmi 15", textoDeArchivo("plano.pdf", "application/pdf", 1_300_000), "flor|*",
            archivo = "f1", nombreArchivo = "plano.pdf", partes = 2).getJSONArray("writes").getJSONObject(0).getJSONObject("update").getJSONObject("fields")
        assertEquals("f1", f.getJSONObject("archivo").getString("stringValue"))
        assertEquals("2", f.getJSONObject("partes").getString("integerValue"))
        assertEquals("📎 plano.pdf (1,2 MB)", f.getJSONObject("texto").getString("stringValue"))
        assertEquals("📷 Foto (240 KB)", textoDeArchivo("IMG.jpg", "image/jpeg", 240L * 1024))
    }

    @Test fun laEntregaEsPrivadaYDeLectura() {
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        val p = m.substringAfter("android:name=\".Entrega\"").substringBefore("/>")
        assertTrue(p.contains("android:exported=\"false\"") && p.contains("grantUriPermissions=\"true\""))
        val src = java.io.File("src/main/java/com/maurogasta/pizarra/Archivos.kt").readText()
        assertTrue(src.contains("if (modo != \"r\") throw SecurityException"))
        assertTrue(src.contains("canonicalFile.parentFile == dir.canonicalFile"))   // nada fuera de cache/entrega
    }
}
