package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-63: las llamadas entre Pizarras (general:C2, Fase B).
class LlamadaTest {
    private val docs = "projects/tiempos-71d42/databases/(default)/documents"
    private val ahora = instante("2026-10-24T20:00:30Z")!!
    private fun l(para: String, de: String = "yo", tel: String = "Redmi 15", estado: String = "llamando", creado: String = "2026-10-24T20:00:10Z") =
        Llamada("l1", de, "Mauro", tel, para, estado, "v=0", "", "", creado)

    @Test fun suenaEnElTelefonoQueCorresponde_yEnNingunOtro() {
        assertTrue(suenaAca(l("flor|*"), "flor", "A15", ahora))                      // a la persona: en todos sus teléfonos
        assertTrue(suenaAca(l("flor|A15"), "flor", "A15", ahora))                    // a ese teléfono
        assertFalse(suenaAca(l("flor|A15"), "flor", "Otro", ahora))                   // a otro teléfono de ella
        assertTrue(suenaAca(l("yo|Redmi 13C"), "yo", "Redmi 13C", ahora))             // de mi 15 a mi 13C
        assertFalse(suenaAca(l("yo|Redmi 13C"), "yo", "Redmi 15", ahora))             // no en el que llama
        assertFalse(suenaAca(l("yo|*", de = "yo"), "yo", "Redmi 13C", ahora))         // «a mi persona» desde mí: no
        assertFalse(suenaAca(l("flor|*", estado = "atendida"), "flor", "A15", ahora)) // ya la atendieron
        assertFalse(suenaAca(l("flor|*", creado = "2026-10-24T19:58:00Z"), "flor", "A15", ahora)) // pasaron los 45 s
    }

    @Test fun laLlamada_tieneLaFormaQueDejaLaReglaV19() {
        val w = cuerpoLlamada(docs, "l1", "yo", "Mauro", "Redmi 15", "flor|*", "v=0 sdp").getJSONArray("writes").getJSONObject(0)
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertEquals(setOf("de", "nombre", "dispositivo", "para", "estado", "oferta"), f.keys().asSequence().toSet())
        assertEquals("llamando", f.getJSONObject("estado").getString("stringValue"))
        assertEquals(false, w.getJSONObject("currentDocument").getBoolean("exists"))
        val r = java.io.File("../../tiempos/firestore.rules")
        if (!r.exists()) return
        val b = r.readText().substringAfter("match /llamadas/{id}").substringBefore("\n    }")
        for (k in f.keys().asSequence() + "creadoEn") assertTrue("la regla no nombra «$k»", b.contains("'$k'"))
    }

    @Test fun atenderRechazarYCortar_tocanSoloSusCampos() {
        fun mascara(o: JSONObject) = o.getJSONArray("writes").getJSONObject(0).getJSONObject("updateMask").getJSONArray("fieldPaths")
            .let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        assertEquals(setOf("estado", "atendio", "respuesta"), mascara(cuerpoAtender(docs, "l1", "A15", "v=0 resp")))
        assertEquals(setOf("estado", "atendio"), mascara(cuerpoAtender(docs, "l1", "A15", null)))
        val c = cuerpoCortar(docs, "l1", "cortada").getJSONArray("writes").getJSONObject(0)
        assertEquals(setOf("estado"), mascara(cuerpoCortar(docs, "l1", "cortada")))
        assertEquals("fin", c.getJSONArray("updateTransforms").getJSONObject(0).getString("fieldPath"))
    }

    @Test fun lasQueSuenan_seLeenConDesconfianza() {
        val r = JSONArray("""[{"document":{"name":"x/llamadas/a","fields":{"de":{"stringValue":"yo"},"para":{"stringValue":"flor|*"},"estado":{"stringValue":"llamando"},"oferta":{"stringValue":"v=0"},"creadoEn":{"timestampValue":"2026-10-24T20:00:00Z"}}}},
            {"document":{"name":"x/llamadas/b","fields":{"para":{"stringValue":"flor|*"}}}},{"readTime":"x"}]""")
        assertEquals(listOf("a"), llamadasDeConsulta(r).map { it.id })
        assertEquals("EQUAL", consultaSonando().getJSONObject("structuredQuery").getJSONObject("where").getJSONObject("fieldFilter").getString("op"))
    }

    @Test fun losServidoresDeConexion_conTurnSiVienen_ySoloStunSiFalla() {
        val h = hieloDeRespuesta("""{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},{"urls":["turn:t:80","http://no"],"username":"u","credential":"c"}]}""")
        assertEquals(2, h.size); assertEquals(listOf("turn:t:80"), h[1].urls); assertEquals("u", h[1].usuario)
        assertEquals(STUN_SI_FALLA, hieloDeRespuesta("no es json"))
        assertEquals(STUN_SI_FALLA, hieloDeRespuesta("""{"iceServers":[]}"""))
    }

    @Test fun laPantallaDiceQuePasa() {
        assertEquals("En llamada · 1:05", textoDeEstado("en_llamada", 65))
        assertTrue(textoDeEstado("fallo").contains("TURN"))
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains(".LlamadaActivity") && m.contains("android:foregroundServiceType=\"microphone\"") && m.contains("FOREGROUND_SERVICE_MICROPHONE"))
        // Una llamada no espera a que se ponga al día todo lo demás.
        assertTrue(java.io.File("src/main/java/com/maurogasta/pizarra/Instantaneo.kt").readText().contains("\"llamadas\") { Llamadas.revisar"))
        val f = java.io.File("../../casaverdecanas/netlify/functions/despertar.mjs")
        if (f.exists()) assertTrue(f.readText().contains("\"llamadas\""))
    }

    // pizarra-68: «la llamada es como una notificación, no suena como una alarma».
    @Test fun laQueYaNoLlama_secalla_yLaQueSigue_no() {
        assertEquals(setOf("a", "c"), paraCallar(setOf("a", "b", "c"), setOf("b", "d")))
        assertEquals(emptySet<String>(), paraCallar(emptySet(), setOf("b")))
    }

    @Test fun elAvisoSuenaComoUnTelefono_conAtenderYRechazar() {
        val k = java.io.File("src/main/java/com/maurogasta/pizarra/Llamada.kt").readText()
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(k.contains("TYPE_RINGTONE") && k.contains("USAGE_NOTIFICATION_RINGTONE"))
        assertTrue(k.contains("FLAG_INSISTENT"))
        assertTrue(k.contains("Notification.Builder(c, CANAL_TIMBRE)"))
        assertTrue(k.contains("\"Rechazar\"") && k.contains("\"📞 Atender\""))
        assertTrue(m.contains(".RechazarReceptor"))
        assertTrue(CANAL_TIMBRE != CANAL_LLAMADAS)
    }
}
