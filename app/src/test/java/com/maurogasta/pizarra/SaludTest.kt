package com.maurogasta.pizarra

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-78 (28-oct, Mauro: «confirmar que no se estén perdiendo datos, mensajes o transmisiones de handy entre
// uno y otro, como mantenimiento»): el parte de salud que cada Pizarra sube y `charla.mjs salud` audita.
class SaludTest {
    private val ahora = java.time.Instant.parse("2026-10-28T18:00:00Z").toEpochMilli()

    private fun m(id: String, de: String, tel: String, creado: String, para: String = "") =
        Mensaje(id, de, "x", tel, "texto", creado, para = para)

    @Test fun huecosDeVoz_cuentaRondasYTrozosPerdidos() {
        val v = mutableMapOf<String, Pair<String, Int>>()
        assertEquals(true to 0, huecosDeVoz(v, "flor", "r1", 0))
        assertEquals(false to 0, huecosDeVoz(v, "flor", "r1", 1))
        assertEquals("faltaron el 2 y el 3", false to 2, huecosDeVoz(v, "flor", "r1", 4))
        assertEquals("ronda nueva que empieza por el 1: se perdió el 0", true to 1, huecosDeVoz(v, "flor", "r2", 1))
        assertEquals("otro que habla lleva su cuenta", true to 0, huecosDeVoz(v, "mauro", "r9", 0))
        assertEquals("un trozo repetido no resta", false to 0, huecosDeVoz(v, "flor", "r2", 1))
    }

    @Test fun acuses_vanASuContador() {
        assertEquals("voz.acuse.escucho", contadorDeAcuse(JSONObject().put("que", "voz").put("como", "la escuchó")))
        assertEquals("voz.acuse.noSono", contadorDeAcuse(JSONObject().put("que", "voz").put("como", "no sonó: en llamada")))
        assertEquals("archivo.acuse", contadorDeAcuse(JSONObject().put("que", "archivo")))
        assertEquals("acuse.otro", contadorDeAcuse(JSONObject()))
    }

    @Test fun recibidos_sonLosParaEsteAparatoDeLaSemana() {
        val ms = listOf(
            m("a", "flor", "Note", "2026-10-28T17:00:00Z"),                          // para todos
            m("b", "flor", "Note", "2026-10-28T17:00:00Z", para = "yo|Redmi"),       // para mí
            m("c", "flor", "Note", "2026-10-28T17:00:00Z", para = "otra|13C"),       // para otra persona
            m("d", "yo", "Redmi", "2026-10-28T17:00:00Z"),                           // mío
            m("e", "flor", "Note", "2026-10-10T17:00:00Z"))                          // viejo
        val r = recibidosPara(ms, "yo", "Redmi", ahora)
        assertTrue(r.containsAll(listOf("a", "b")))
        assertFalse(r.contains("c")); assertFalse(r.contains("d")); assertFalse(r.contains("e"))
    }

    @Test fun elParte_tieneLaFormaDeLaRegla_ySinContenido() {
        val w = cuerpoSalud("projects/p/databases/(default)/documents", "yo", "Redmi/15", "pizarra-78",
            mapOf("voz.rondasMandadas" to 3), listOf("a"), listOf("enlace: conectado"), "2026-10-28T17:00:00Z", "apagado")
            .getJSONArray("writes").getJSONObject(0)
        assertTrue(w.getJSONObject("update").getString("name").endsWith("/salud/yo|Redmi-15"))
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertEquals(setOf("de", "dispositivo", "sello", "contadores", "recibidos", "eventos", "charlaHasta", "enlace"), f.keys().asSequence().toSet())
        assertEquals("actualizado", w.getJSONArray("updateTransforms").getJSONObject(0).getString("fieldPath"))
        assertFalse(w.toString().contains("texto"))
    }

    @Test fun soloLoDeComunicacion_vaAlParte() {
        assertTrue(esDeComunicacion("enlace: se cortó con Note"))
        assertTrue(esDeComunicacion("handy: no salió"))
        assertFalse(esDeComunicacion("alarma: sonó"))
        val b = java.io.File("src/main/java/com/maurogasta/pizarra/Bitacora.kt").readText()
        assertTrue(b.contains("Salud.evento(c, t)"))
    }
}
