package com.maurogasta.pizarra

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-40: los mensajes entre las Pizarras y el widget de novedades.
class MensajesTest {
    private val docs = "projects/tiempos-71d42/databases/(default)/documents"

    @Test fun elMensaje_tieneLaFormaQueDejaLaRegla() {
        val w = cuerpoMensaje(docs, "m1", "uidM", "Mauro", "Redmi 13C", "  hola  ").getJSONArray("writes").getJSONObject(0)
        assertEquals("$docs/mensajes/m1", w.getJSONObject("update").getString("name"))
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertEquals(setOf("de", "nombre", "dispositivo", "texto"), f.keys().asSequence().toSet())
        assertEquals("hola", f.getJSONObject("texto").getString("stringValue"))
        assertEquals("creadoEn", w.getJSONArray("updateTransforms").getJSONObject(0).getString("fieldPath"))
        assertEquals(false, w.getJSONObject("currentDocument").getBoolean("exists"))
        val r = java.io.File("../../tiempos/firestore.rules")
        if (!r.exists()) return
        val bloque = r.readText().substringAfter("match /mensajes/{id}").substringBefore("\n    }")
        for (k in f.keys().asSequence() + "creadoEn") assertTrue("la regla no nombra «$k»", bloque.contains("'$k'"))
    }

    @Test fun laConsulta_traeLosMasNuevos_yLaRespuestaSeLeeConDesconfianza() {
        val q = consultaMensajes().getJSONObject("structuredQuery")
        assertEquals("DESCENDING", q.getJSONArray("orderBy").getJSONObject(0).getString("direction"))
        val r = JSONArray("""[{"document":{"name":"x/mensajes/a","fields":{"de":{"stringValue":"u1"},"nombre":{"stringValue":"Flor"},"dispositivo":{"stringValue":"Redmi Note 13"},"texto":{"stringValue":"hola"},"creadoEn":{"timestampValue":"2026-10-18T16:00:00Z"}}}},
            {"document":{"name":"x/mensajes/b","fields":{"de":{"stringValue":"u1"},"texto":{"stringValue":"  "}}}},{"readTime":"x"}]""")
        val l = mensajesDeConsulta(r)
        assertEquals(1, l.size); assertEquals("Flor", l[0].nombre); assertEquals("Redmi Note 13", l[0].dispositivo)
    }

    @Test fun laFirma_diceQuienYDesdeQueTelefono() {
        val m = Mensaje("a", "yo", "Mauro", "Redmi 13C", "x", "2026-10-18T16:00:00Z")
        assertEquals("Yo", firmaMensaje(m, "yo", "Redmi 13C"))                 // mío, de este teléfono
        assertEquals("Yo · Redmi 13C", firmaMensaje(m, "yo", "Redmi 15"))      // mío, de OTRO teléfono
        assertEquals("Mauro · Redmi 13C", firmaMensaje(m, "flor", "Redmi Note"))
    }

    @Test fun avisa_loDelOtro_yLoMioDeOtroTelefono_nuncaLoDeEste() {
        val ahora = instante("2026-10-18T17:00:00Z")!!
        val l = listOf(
            Mensaje("a", "flor", "Flor", "Note", "hola", "2026-10-18T16:00:00Z"),
            Mensaje("b", "yo", "Mauro", "Redmi 13C", "desde el 13C", "2026-10-18T16:10:00Z"),
            Mensaje("c", "yo", "Mauro", "Redmi 15", "desde acá", "2026-10-18T16:20:00Z"),
            Mensaje("d", "flor", "Flor", "Note", "viejo", "2026-10-15T16:00:00Z"))
        assertEquals(listOf("a", "b"), mensajesParaAvisar(l, "yo", "Redmi 15", emptySet(), ahora).map { it.id })
        assertEquals(listOf("b"), mensajesParaAvisar(l, "yo", "Redmi 15", setOf("a"), ahora).map { it.id })
    }

    @Test fun lasNovedades_juntanTodoPorFecha_yCasaVerdeSoloSiHayAlgo() {
        val ms = listOf(Mensaje("a", "flor", "Flor", "Note", "hola", "2026-10-18T16:00:00Z"))
        val av = listOf(Aviso("x", "casaverde", "pedido", "3 pedidos", "2026-10-18T15:00:00Z", false))
        val n = armarNovedades(ms, av, 2, 1, "2026-10-18T17:00:00Z", "yo", "Redmi 15")
        assertEquals(listOf("cv", "m:a", "a:x"), n.map { it.id })
        assertTrue(n[0].texto.contains("2 búsquedas de Facebook que coinciden") && n[0].texto.contains("1 cambio de"))
        assertEquals(2, armarNovedades(ms, av, 0, 0, "z", "yo", "Redmi 15").size)
    }

    // pizarra-58: cada toque lleva a algún lado claro.
    @Test fun cadaAviso_llevaALoQueDice() {
        fun av(sitio: String, tema: String, texto: String) = Aviso("x", sitio, tema, texto, "2026-10-21T10:00:00Z", false)
        assertEquals("https://casaverdecanas.com.br/interno/llegada.html?r=abc",
            destinoDeAviso(av("casaverde", "llegada", "Llega Ana: https://casaverdecanas.com.br/interno/llegada.html?r=abc.")))
        assertEquals(URL_RESERVAS_CV, destinoDeAviso(av("casaverde", "llegada", "Llega un huésped mañana")))
        assertEquals(TIEMPOS_URL + "?solapa=agenda", destinoDeAviso(av("tiempos", "recordatorio", "Hoy: odontólogo")))
        assertEquals(URL_PANEL, destinoDeAviso(av("panel", "novedades", "3 cosas")))
        assertEquals("https://casayourte.com/admin.html", destinoDeAviso(av("casayourte", "pedido", "Quedó hecho")))
        assertEquals("", destinoDeAviso(av("hilux", "urgente", "El teléfono no sube")))
        assertEquals("https://casaverdecanas.com.br/interno/x", destinoDeAviso(av("casaverde", "pedido", "Mirá «https://casaverdecanas.com.br/interno/x»")))
    }

    @Test fun lasNovedades_sabenAdóndeLlevan() {
        val av = listOf(Aviso("x", "casaverde", "llegada", "Llega Ana", "2026-10-18T15:00:00Z", false))
        val n = armarNovedades(emptyList(), av, 3, 0, "2026-10-18T17:00:00Z", "yo", "Redmi 15")
        assertEquals(URL_PEDIDOS_CV, n.first { it.id == "cv" }.url)
        assertEquals("x", n.first { it.id == "a:x" }.aviso); assertEquals(URL_RESERVAS_CV, n.first { it.id == "a:x" }.url)
        assertEquals(URL_RESERVAS_CV, armarNovedades(emptyList(), emptyList(), 3, 1, "z", "yo", "R").first().url)
        // el widget va a la pantalla que decide, no a la charla ni a la app
        val codigo = java.io.File("src/main/java/com/maurogasta/pizarra/Mensajes.kt").readText()
        assertTrue(codigo.contains("Intent(c, AbrirActivity::class.java)"))
        val avisos = java.io.File("src/main/java/com/maurogasta/pizarra/Avisos.kt").readText()
        assertTrue(avisos.contains("Intent(c, AbrirActivity::class.java)")); assertFalse(avisos.contains("PendingIntent.getActivity(c, 20,"))
    }

    @Test fun lasRespuestasNoGastanNotificaciones() {
        val codigo = java.io.File("src/main/java/com/maurogasta/pizarra/Responder.kt").readText()
        assertFalse(codigo.contains("Respuesta lista ·")); assertFalse(codigo.contains("fun avisarLista"))
        assertFalse(codigo.contains("Responder.cerrar(this, id, \"respondido\")"))
    }
}
