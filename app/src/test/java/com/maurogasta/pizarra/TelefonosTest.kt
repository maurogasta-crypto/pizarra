package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-80 (29-oct, Mauro: «yo le llamé 15 y 13C, A15 es el de Florencia… los nombres no se actualizan»).
// Los nombres de los teléfonos son inventados de la forma de los reales; los uid, también.
class TelefonosTest {
    private val yo = "UIDMAURO"
    private val flor = "UIDFLOR"
    private val renombres = mapOf("$yo|Redmi 13C" to "13C", "$yo|Redmi 15" to "15", "$flor|Galaxy A15" to "A15")

    private fun m(id: String, de: String, disp: String, para: String = "", creado: String = "2026-10-29T22:32:00Z") =
        Mensaje(id, de, if (de == yo) "Mauro" else "Florencia", disp, "x", creado, para)

    @Test fun elNombreViejoLlevaAlDeHoy_yUnCirculoNoCuelga() {
        assertEquals("$yo|13C", nombreAlDia("$yo|Redmi 13C", renombres))
        assertEquals("$yo|Redmi Note 9", nombreAlDia("$yo|Redmi Note 9", renombres))
        assertEquals("$yo|*", nombreAlDia("$yo|*", renombres))
        assertEquals("encadenado", "$yo|C", nombreAlDia("$yo|A", mapOf("$yo|A" to "B", "$yo|B" to "C")))
        val circulo = nombreAlDia("$yo|A", mapOf("$yo|A" to "B", "$yo|B" to "A"))
        assertTrue(circulo == "$yo|A" || circulo == "$yo|B")
        assertEquals("otra persona con el mismo nombre no se toca", "$flor|Redmi 13C", nombreAlDia("$flor|Redmi 13C", renombres))
    }

    @Test fun laCaptura_elNote9LeMandaAlRedmi13C_yLeLlegaAl13C() {
        val ms = aplicarRenombres(listOf(m("1", yo, "Redmi Note 9", "$yo|Redmi 13C")), renombres)
        assertEquals("$yo|13C", ms[0].para)
        assertTrue("el 13C lo reconoce como suyo", esParaMi(ms[0], yo, "13C"))
        assertFalse(esParaMi(ms[0], yo, "15"))
    }

    @Test fun lasPestanas_tienenLosNombresDeHoy_yLoViejoSeJuntaConLoNuevo() {
        val ms = aplicarRenombres(listOf(
            m("1", yo, "Redmi 13C", "$yo|Redmi Note 9", "2026-10-28T10:00:00Z"),
            m("2", yo, "13C", "$yo|Redmi Note 9", "2026-10-29T10:00:00Z"),
            m("3", flor, "Galaxy A15", "$yo|Redmi Note 9")), renombres)
        val cs = conversaciones(ms, yo, "Redmi Note 9")
        val nombres = cs.map { it.dispositivo }.toSet()
        assertEquals(setOf("13C", "A15"), nombres)
        val con13 = cs.first { it.dispositivo == "13C" }
        assertEquals("los dos mensajes del 13C, el de antes y el de ahora, en la misma", 2,
            ms.count { enConversacion(it, con13, yo, "Redmi Note 9") })
    }

    @Test fun sinCambiosDeNombre_losMismosMensajes() {
        val ms = listOf(m("1", yo, "Redmi 13C"))
        assertTrue(aplicarRenombres(ms, emptyMap()) === ms)
    }

    @Test fun elMapaSeLeeDeLaRespuesta_yLoMalFormadoNoEntra() {
        val f = JSONObject()
            .put("$yo|Redmi 13C", JSONObject().put("stringValue", "13C"))
            .put("sinBarra", JSONObject().put("stringValue", "x"))
            .put("$yo|vacio", JSONObject().put("stringValue", ""))
        val r = JSONArray().put(JSONObject().put("found", JSONObject().put("fields", JSONObject()
            .put("nombres", JSONObject().put("mapValue", JSONObject().put("fields", f))))))
        assertEquals(mapOf("$yo|Redmi 13C" to "13C"), renombresDeRespuesta(r))
        assertEquals(emptyMap<String, String>(), renombresDeRespuesta(JSONArray().put(JSONObject().put("missing", "x"))))
    }

    @Test fun elCuerpo_sumaLasFlechasViejas_borraLaDelNombreNuevo_yNoPisaLasDeOtros() {
        val c = cuerpoRenombre(BASE_DOCS, yo, listOf("Redmi 13C", "Celu `raro`", "13C", ""), "13C")
        val w = c.getJSONArray("writes").getJSONObject(0)
        assertTrue(w.getJSONObject("update").getString("name").endsWith("/familia/telefonos"))
        val campos = w.getJSONObject("update").getJSONObject("fields").getJSONObject("nombres").getJSONObject("mapValue").getJSONObject("fields")
        assertEquals("13C", campos.getJSONObject("$yo|Redmi 13C").getString("stringValue"))
        assertFalse("el nombre nuevo no apunta a sí mismo", campos.has("$yo|13C"))
        val mascara = w.getJSONObject("updateMask").getJSONArray("fieldPaths").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(mascara.contains("nombres.`$yo|Redmi 13C`"))
        assertTrue("el acento grave se escapa", mascara.contains("nombres.`$yo|Celu \\`raro\\``"))
        assertTrue("la flecha vieja del nombre nuevo se borra (en la máscara, sin valor)", mascara.contains("nombres.`$yo|13C`"))
        assertFalse("no exige que exista: la primera vez lo crea", w.has("currentDocument"))
        assertEquals(3, mascara.size)
    }
}
