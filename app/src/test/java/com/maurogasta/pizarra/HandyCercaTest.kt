package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-75 (28-oct-2026, Mauro): la conexión cerca que no se muere callada, el enlace sin código, el
// nombre y el color de cada teléfono, y el handy que elige solo: cerca en vivo, si no por internet.
class HandyCercaTest {
    @Test fun unaConexionQueCalla_seDaPorMuerta() {
        val t = 1_800_000_000_000L
        assertFalse(estaMuda(t - LATIDO_MS, t))
        assertFalse(estaMuda(t - MUDA_MS, t))
        assertTrue(estaMuda(t - MUDA_MS - 1, t))
        assertTrue("el latido llega varias veces antes de darla por muerta", MUDA_MS >= 3 * LATIDO_MS)
        assertTrue("latido" in TIPOS_ENLACE); assertFalse("otra app no manda latidos", "latido" in TIPOS_DE_APP)
    }

    @Test fun elVigia_existe_yBuscaSoloAlConocidoPorBluetooth() {
        val s = java.io.File("src/main/java/com/maurogasta/pizarra/EnlaceServicio.kt").readText()
        assertTrue(s.contains("thread(name = \"enlace-vigia\") { vigia() }"))
        assertTrue(s.contains("buscar(forzar = true, btSoloConocido = true)"))
        assertTrue("el wi-fi no se duerme con una conexión", s.contains("WIFI_MODE_FULL_LOW_LATENCY"))
    }

    @Test fun elHandy_vaCercaSoloSiElOtroEstaConectado() {
        val de = mapOf("flor" to setOf("Galaxy A15"), "yo" to setOf("Redmi 15", "Note 9"))
        val tel = { u: String -> de[u].orEmpty() }
        assertFalse("nadie cerca", destinoCerca("flor|*", emptySet(), tel))
        assertTrue("la persona, por cualquiera de sus teléfonos", destinoCerca("flor|*", setOf("Galaxy A15"), tel))
        assertFalse("cerca está OTRO teléfono", destinoCerca("flor|*", setOf("Note 9"), tel))
        assertTrue("un teléfono mío", destinoCerca("yo|Note 9", setOf("Note 9"), tel))
        assertFalse(destinoCerca("yo|Note 9", setOf("Redmi 15"), tel))
        assertTrue("todos: alcanza con uno", destinoCerca("", setOf("Note 9"), tel))
    }

    @Test fun laVozDeUnaConversacion_sueneSoloDondeVa() {
        assertTrue(vozParaMi("", null, "Note 9"))
        assertTrue(vozParaMi("yo|Note 9", "yo", "Note 9"))
        assertTrue(vozParaMi("yo|*", "yo", "Note 9"))
        assertFalse(vozParaMi("yo|Redmi 15", "yo", "Note 9"))
        assertFalse(vozParaMi("flor|*", "yo", "Note 9"))
        assertFalse("sin cuenta, sólo lo que va a todos", vozParaMi("yo|Note 9", null, "Note 9"))
        val v = mensajeVoz("r1", 0, ByteArray(4), false, "flor|*")
        assertEquals("flor|*", v.getString("para")); assertFalse(mensajeVoz("r1", 0, ByteArray(4), false).has("para"))
    }

    @Test fun elHandyPorInternet_llevaSuMarca_ySuenaAunqueNoSeHayaElegido() {
        val f = cuerpoMensaje("docs", "m1", "yo", "Mauro", "Redmi 15", textoDeHandy(7), "flor|*", "a1", 7, handy = true)
            .getJSONArray("writes").getJSONObject(0).getJSONObject("update").getJSONObject("fields")
        assertTrue(f.getJSONObject("handy").getBoolean("booleanValue"))
        assertEquals("📻 Handy (0:07)", f.getJSONObject("texto").getString("stringValue"))
        val sinAudio = cuerpoMensaje("docs", "m2", "yo", "Mauro", "Redmi 15", "hola", handy = true)
            .getJSONArray("writes").getJSONObject(0).getJSONObject("update").getJSONObject("fields")
        assertFalse("sin audio no hay handy (la regla lo pide)", sinAudio.has("handy"))
        val ahora = System.currentTimeMillis()
        val creado = java.time.Instant.ofEpochMilli(ahora - 30_000).toString()
        val m = Mensaje("1", "flor", "Flor", "Galaxy A15", "📻", creado, "yo|*", "a1", 5, handy = true)
        assertTrue("el handy suena aunque «escuchar solos» esté apagado", debeSonarSolo(m, "yo", "Note 9", ahora, activado = false))
        assertFalse("un audio común, no", debeSonarSolo(m.copy(handy = false), "yo", "Note 9", ahora, activado = false))
        val leido = mensajesDeConsulta(JSONArray().put(JSONObject().put("document", JSONObject().put("name", "x/mensajes/1")
            .put("fields", JSONObject().put("de", JSONObject().put("stringValue", "flor")).put("texto", JSONObject().put("stringValue", "📻"))
                .put("audio", JSONObject().put("stringValue", "a1")).put("handy", JSONObject().put("booleanValue", true))))))
        assertTrue(leido.single().handy)
    }

    @Test fun laLlave_saleDeLaCuenta_yNoSePisa() {
        val w = cuerpoLlaveFamilia("projects/p/databases/(default)/documents", "yo", "ABCDEFGHJK").getJSONArray("writes").getJSONObject(0)
        assertTrue(w.getJSONObject("update").getString("name").endsWith("/enlace_llave/familia"))
        assertFalse("nunca pisa la que ya está", w.getJSONObject("currentDocument").getBoolean("exists"))
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertEquals(setOf("codigo", "creadoPor"), f.keys().asSequence().toSet())
        assertEquals("ABCDEFGHJK", codigoDeLlave(JSONArray().put(JSONObject().put("found", JSONObject().put("fields",
            JSONObject().put("codigo", JSONObject().put("stringValue", "ABCDEFGHJK")))))))
        assertNull(codigoDeLlave(JSONArray().put(JSONObject().put("missing", "x"))))
        assertNull("un código sin forma no se usa", codigoDeLlave(JSONArray().put(JSONObject().put("found", JSONObject().put("fields",
            JSONObject().put("codigo", JSONObject().put("stringValue", "0000")))))))
        // La regla v22 de Tiempos, si está al lado: el mismo alfabeto que codigoNuevo.
        val r = java.io.File("../../tiempos/firestore.rules")
        if (r.exists()) assertTrue(r.readText().contains("codigo.matches('[A-HJ-NP-Z2-9]{10}')"))
        repeat(20) { assertTrue(Regex("[A-HJ-NP-Z2-9]{10}").matches(codigoNuevo())) }
    }

    @Test fun cadaTelefono_tieneSuColor_yUnNombreQueSePuedeUsar() {
        val conocidos = listOf("Redmi 15", "Note 9", "Galaxy A15", "Redmi 13C")
        val colores = conocidos.map { colorDeTelefono(it, conocidos) }
        assertEquals("cuatro teléfonos, cuatro colores", 4, colores.toSet().size)
        assertEquals("el mismo color mire desde donde mire", colorDeTelefono("Note 9", conocidos), colorDeTelefono("Note 9", conocidos.reversed()))
        assertEquals("Note 9 · Hilux", aliasValido("  Note 9 · Hilux "))
        assertEquals("a b", aliasValido("a|b"))
        assertNull(aliasValido("x")); assertNull(aliasValido("x".repeat(41)))
    }

    @Test fun laCharla_tieneElHandyAparte_yDiceEnQueTelefonoEstas() {
        val src = java.io.File("src/main/java/com/maurogasta/pizarra/Mensajes.kt").readText().substringAfter("class CharlaActivity").substringBefore("class EscribirActivity")
        assertTrue(src.contains("handy = botonPulsador()"))
        assertTrue(src.contains("LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64))"))
        for (t in listOf("tecla(\"🎙\", \"Audio\"", "tecla(\"📎\", \"Foto o archivo\"", "tecla(\"📞\", \"Llamar\"", "tecla(\"✎\", \"Escribir\""))
            assertTrue("falta $t", src.contains(t))
        assertTrue(src.contains("\"📱 Estás en: \$tel  ✎\""))
        assertTrue(src.contains("Pulsador.apretar(") && src.contains("Pulsador.soltar("))
    }
}
