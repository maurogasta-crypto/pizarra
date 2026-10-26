package com.maurogasta.pizarra

import android.os.Looper
import org.json.JSONArray
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// pizarra-71, general:C4 pasos 3 y 4: el handy cerca (Radio.kt) y el enlace
// lejos por la base de Tiempos (EnlaceLejos.kt).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioLejosTest {
    private val llave = llaveDe("ABCDEFGHJK")

    // ── El handy cerca ──
    @Test fun unTrozoDeVoz_entraEnUnRenglonYVuelveIgual() {
        val pcm = ByteArray(RADIO_TROZO_BYTES) { (it % 251).toByte() }
        val o = mensajeVoz("ronda1", 3, pcm, false).put("t", 1_800_000_000_000L).put("de", "Redmi 15")
        val r = renglonFirmado(llave, o.toString())
        assertTrue("un trozo de 100 ms entra en un renglón: ${r.length}", r.length < LARGO_MAXIMO)
        val leido = leerRenglon(llave, r, 1_800_000_000_000L, mutableMapOf())
        assertNotNull(leido)
        assertArrayEquals(pcm, pcmDeVoz(leido!!))
    }

    @Test fun laVoz_seLeeConDesconfianza() {
        assertNull(pcmDeVoz(JSONObject().put("tipo", "vivo").put("b64", "AAAA")))
        assertNull(pcmDeVoz(JSONObject().put("tipo", "voz").put("b64", "no es base64 ***")))
        val enorme = java.util.Base64.getEncoder().encodeToString(ByteArray(RADIO_TROZO_BYTES * 3))
        assertNull(pcmDeVoz(JSONObject().put("tipo", "voz").put("b64", enorme)))
        assertNull("un byte suelto no es sonido de 16 bits", pcmDeVoz(JSONObject().put("tipo", "voz").put("b64", "AA==")))
    }

    @Test fun otraApp_noPuedeMandarVoz() {
        assertNull(mensajeDeApp("hilux", """{"tipo":"voz","b64":"AAAA"}""", "Note 9", 1L))
        assertTrue("voz" in TIPOS_ENLACE)
    }

    @Test fun unTipoDesconocido_noSeLee() {
        val o = JSONObject().put("v", VERSION_ENLACE).put("tipo", "borrar").put("t", 5L).put("de", "x")
        assertNull(leerRenglon(llave, renglonFirmado(llave, o.toString()), 5L, mutableMapOf()))
    }

    @Test fun suenaSoloSiCorresponde() {
        assertTrue(radioPuedeSonar(activado = true, hablandoYo = false, enLlamada = false, enSilencio = false))
        assertFalse(radioPuedeSonar(activado = false, hablandoYo = false, enLlamada = false, enSilencio = false))
        assertFalse("uno por vez", radioPuedeSonar(activado = true, hablandoYo = true, enLlamada = false, enSilencio = false))
        assertFalse(radioPuedeSonar(activado = true, hablandoYo = false, enLlamada = true, enSilencio = false))
        assertFalse(radioPuedeSonar(activado = true, hablandoYo = false, enLlamada = false, enSilencio = true))
    }

    // ── El enlace lejos ──
    @Test fun elPar_esUnResumenDelCodigo_nuncaElCodigo() {
        val p = parDe("ABCDEFGHJK")
        assertEquals(16, p.length)
        assertEquals(p, parDe("ABCDEFGHJK"))
        assertFalse(p.contains("ABCDE", ignoreCase = true))
        assertFalse(p == parDe("ABCDEFGHJM"))
    }

    @Test fun losDocumentos_tienenSuNombre() {
        assertEquals("e-123", idEnlace(JSONObject().put("tipo", "evento").put("t", 123L)))
        assertEquals("vivo", idEnlace(JSONObject().put("tipo", "vivo")))
        assertEquals("mira", idEnlace(JSONObject().put("tipo", "mira")))
        assertNull("la voz no va por la base", idEnlace(JSONObject().put("tipo", "voz")))
    }

    @Test fun loQueSube_tieneLaFormaDeLaReglaYNingunaCoordenada() {
        val o = mensajeDeApp("hilux", """{"tipo":"evento","titulo":"🚙 Hilux · viaje terminado","texto":"12,3 km","lat":-34.9,"lon":-56.1}""", "Note 9", 1_800_000_000_000L)!!
        val c = cuerpoEnlace(BASE_DOCS, "0123456789abcdef", "uidX", o)!!
        val w = c.getJSONArray("writes").getJSONObject(0)
        val campos = w.getJSONObject("update").getJSONObject("fields").keys().asSequence().toSet() + "creadoEn"
        assertTrue("campos que la regla no deja: ${campos - CAMPOS_ENLACE.toSet()}", CAMPOS_ENLACE.containsAll(campos))
        assertFalse(c.toString().contains("-34.9")); assertFalse(c.toString().contains("lat"))
        assertTrue(w.getJSONObject("update").getString("name").endsWith("/enlace/0123456789abcdef/cosas/e-1800000000000"))
        assertFalse("un evento no pisa", w.getJSONObject("currentDocument").getBoolean("exists"))
        assertEquals("uidX", w.getJSONObject("update").getJSONObject("fields").getJSONObject("de").getString("stringValue"))
        assertEquals("Note 9", w.getJSONObject("update").getJSONObject("fields").getJSONObject("dispositivo").getString("stringValue"))
        // El vivo se pisa.
        val v = cuerpoEnlace(BASE_DOCS, "0123456789abcdef", "uidX", JSONObject().put("tipo", "vivo").put("t", 5L).put("kmh", 82.4).put("rpm", 2100).put("de", "Note 9"))!!
        assertFalse(v.getJSONArray("writes").getJSONObject(0).has("currentDocument"))
        assertNull(cuerpoEnlace(BASE_DOCS, "0123456789abcdef", "uidX", JSONObject().put("tipo", "voz").put("t", 5L)))
    }

    @Test fun laFormaDeLaRegla_esLaMisma_siTiemposEstaAlLado() {
        val r = java.io.File("../../tiempos/firestore.rules")
        if (!r.exists()) return
        val texto = r.readText()
        val esperado = "hasOnly([" + CAMPOS_ENLACE.joinToString(", ") { "'$it'" } + "])"
        assertTrue("la regla v21 de Tiempos y CAMPOS_ENLACE dicen lo mismo", texto.contains(esperado))
        assertTrue(texto.contains("match /$COLECCION_ENLACE/{par}/$SUBCOLECCION_ENLACE/{id}"))
    }

    @Test fun loQueBaja_vuelveComoMensajeDelEnlace() {
        val doc = JSONObject().put("name", "x/enlace/p/cosas/e-77").put("fields", JSONObject()
            .put("de", JSONObject().put("stringValue", "uidX")).put("dispositivo", JSONObject().put("stringValue", "Note 9"))
            .put("tipo", JSONObject().put("stringValue", "evento")).put("t", JSONObject().put("integerValue", "77"))
            .put("titulo", JSONObject().put("stringValue", "🚙 Hilux")).put("kmh", JSONObject().put("doubleValue", 81.5)))
        val o = enlaceDeConsulta(JSONArray().put(JSONObject().put("document", doc)).put(JSONObject().put("readTime", "x")))
        assertEquals(1, o.size)
        assertEquals("Note 9", o[0].getString("de")); assertEquals("uidX", o[0].getString("uid"))
        assertEquals(77L, o[0].getLong("t")); assertEquals("e-77", o[0].getString("id")); assertEquals("internet", o[0].getString("por"))
        assertEquals(81.5, o[0].getDouble("kmh"), 0.001)
    }

    @Test fun seMuestraSoloLoNuevoDeOtroTelefono() {
        val e = JSONObject().put("tipo", "evento").put("t", 100L).put("de", "Note 9")
        assertTrue(esParaMostrar(e, "Redmi 15", 50L))
        assertFalse("lo mío no", esParaMostrar(e, "Note 9", 50L))
        assertFalse("lo ya visto no", esParaMostrar(e, "Redmi 15", 100L))
        assertFalse("un vivo no suena", esParaMostrar(JSONObject().put("tipo", "vivo").put("t", 100L).put("de", "Note 9"), "Redmi 15", 50L))
    }

    @Test fun alguienMira_soloConUnaMiraReciente() {
        val m = JSONObject().put("tipo", "mira").put("t", 1_000_000L).put("de", "Redmi 15")
        assertTrue(alguienMira(m, "Note 9", 1_000_000L + 60_000L))
        assertFalse(alguienMira(m, "Note 9", 1_000_000L + MIRA_VALE_MS + 1))
        assertFalse("mi propia mira no cuenta", alguienMira(m, "Redmi 15", 1_000_000L))
        assertFalse(alguienMira(null, "Note 9", 1L))
    }

    @Test fun laConsulta_esPorT_sinIndiceCompuesto() {
        val q = consultaEnlace(42L).getJSONObject("structuredQuery")
        assertEquals(SUBCOLECCION_ENLACE, q.getJSONArray("from").getJSONObject(0).getString("collectionId"))
        val f = q.getJSONObject("where").getJSONObject("fieldFilter")
        assertEquals("t", f.getJSONObject("field").getString("fieldPath")); assertEquals("GREATER_THAN", f.getString("op"))
        assertEquals("LESS_THAN", consultaEnlace(0, 9L).getJSONObject("structuredQuery").getJSONObject("where").getJSONObject("fieldFilter").getString("op"))
    }

    // ── Las pantallas abren ──
    @Test fun laRadio_abreYConElEnlaceApagadoLoDice() {
        val a = Robolectric.buildActivity(RadioActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        fun textos(v: android.view.View): List<String> = when (v) {
            is android.widget.TextView -> listOf(v.text.toString())
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { textos(v.getChildAt(it)) }
            else -> emptyList()
        }
        assertTrue(textos(a.window.decorView).any { it.contains("está apagado") })
        assertTrue(textos(a.window.decorView).any { it.contains("Mantené apretado") })
    }

    @Test fun enVivo_abre() {
        val c = Robolectric.buildActivity(EnVivoActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(c.get().isFinishing)
        c.destroy()
    }
}
