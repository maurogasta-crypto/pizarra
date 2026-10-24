package com.maurogasta.pizarra

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-62: el handy (mensajes de voz).
class HandyTest {
    private val docs = "projects/tiempos-71d42/databases/(default)/documents"

    @Test fun elAudio_tieneLaFormaQueDejaLaReglaV18_yVuelveIgual() {
        val datos = ByteArray(300) { (it % 251).toByte() }
        val w = cuerpoAudio(docs, "a1", "yo", datos, 75, "audio/ogg").getJSONArray("writes").getJSONObject(0)
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertEquals(setOf("de", "datos", "dur", "tipo"), f.keys().asSequence().toSet())
        assertEquals("60", f.getJSONObject("dur").getString("integerValue"))      // nunca más de un minuto
        assertEquals(false, w.getJSONObject("currentDocument").getBoolean("exists"))
        val r = JSONArray().put(org.json.JSONObject().put("found", org.json.JSONObject().put("fields", f)))
        assertArrayEquals(datos, bytesDeAudio(r))
        assertNull(bytesDeAudio(JSONArray().put(org.json.JSONObject().put("missing", "x"))))
        val reglas = java.io.File("../../tiempos/firestore.rules")
        if (!reglas.exists()) return
        val bloque = reglas.readText().substringAfter("match /audios/{id}").substringBefore("\n    }")
        for (k in f.keys().asSequence() + "creadoEn") assertTrue("la regla no nombra «$k»", bloque.contains("'$k'"))
    }

    @Test fun elMensajeDeVoz_nombraAlAudio_yTieneTextoParaQuienNoTieneBoton() {
        val f = cuerpoMensaje(docs, "m1", "yo", "Mauro", "Redmi 15", textoDeAudio(12), "flor|*", "a1", 12)
            .getJSONArray("writes").getJSONObject(0).getJSONObject("update").getJSONObject("fields")
        assertEquals("a1", f.getJSONObject("audio").getString("stringValue"))
        assertEquals("12", f.getJSONObject("dur").getString("integerValue"))
        assertEquals("🎙 Mensaje de voz (0:12)", f.getJSONObject("texto").getString("stringValue"))
        val r = java.io.File("../../tiempos/firestore.rules")
        if (!r.exists()) return
        val b = r.readText().substringAfter("match /mensajes/{id}").substringBefore("\n    }")
        assertTrue(b.contains("'audio'") && b.contains("'dur'"))
    }

    @Test fun seEscuchaSolo_loRecienLlegadoParaMi_yNadaMas() {
        val ahora = instante("2026-10-24T12:05:00Z")!!
        val a = Mensaje("1", "flor", "Flor", "Note", "🎙", "2026-10-24T12:04:00Z", "yo|*", "a1", 5)
        assertTrue(debeSonarSolo(a, "yo", "Redmi 15", ahora, true))
        assertFalse(debeSonarSolo(a, "yo", "Redmi 15", ahora, false))                                    // lo apagó
        assertFalse(debeSonarSolo(a.copy(creado = "2026-10-24T11:40:00Z"), "yo", "Redmi 15", ahora, true)) // viejo
        assertFalse(debeSonarSolo(a.copy(audio = ""), "yo", "Redmi 15", ahora, true))                     // texto
        assertFalse(debeSonarSolo(a.copy(de = "yo", dispositivo = "Redmi 15"), "yo", "Redmi 15", ahora, true)) // mío
        assertFalse(debeSonarSolo(a.copy(para = "otro|X"), "yo", "Redmi 15", ahora, true))                // no es para mí
    }

    @Test fun seBorranSoloMisAudiosDeMasDe30Dias() {
        val ahora = 100L * 24 * 3600_000L
        val mios = mapOf("viejo" to ahora - 31L * 24 * 3600_000L, "nuevo" to ahora - 2L * 24 * 3600_000L)
        assertEquals(listOf("viejo"), audiosParaBorrar(mios, ahora))
    }

    @Test fun losMensajesNuevos_seSumanSinRepetir_yLaConsultaPideSoloLoNuevo() {
        val viejo = Mensaje("a", "yo", "M", "T", "x", "2026-10-24T10:00:00Z")
        val nuevo = Mensaje("b", "flor", "F", "N", "y", "2026-10-24T11:00:00Z")
        assertEquals(listOf("b", "a"), mezclarMensajes(listOf(viejo), listOf(nuevo, viejo)).map { it.id })
        val q = consultaMensajes(desde = "2026-10-24T10:00:00Z").getJSONObject("structuredQuery")
        assertEquals("GREATER_THAN", q.getJSONObject("where").getJSONObject("fieldFilter").getString("op"))
        assertFalse(consultaMensajes().getJSONObject("structuredQuery").has("where"))
    }

    @Test fun elMicrofonoSePideYElHandyEstaRegistrado() {
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android.permission.RECORD_AUDIO"))
        assertTrue(m.contains(".HandyActivity"))
        assertEquals("1:05", duracionTexto(65))
        // Quien escribe o graba sabe, antes, que Claude lo lee y lo escucha.
        for (f in listOf("Mensajes.kt", "Handy.kt")) assertTrue(java.io.File("src/main/java/com/maurogasta/pizarra/$f").readText().contains("AVISO_CLAUDE_LEE"))
    }
}
