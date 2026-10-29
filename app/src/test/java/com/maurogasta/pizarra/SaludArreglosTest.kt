package com.maurogasta.pizarra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-79 (29-oct): lo que encontró la primera auditoría de salud de la charla (general:C6).
class SaludArreglosTest {
    private val ahora = java.time.Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
    private fun ll(id: String, de: String, tel: String, estado: String, creado: String) =
        Llamada(id, de, "x", tel, "otro|Tel", estado, "", "", "", creado)

    @Test fun lasMiasColgadas_seCancelan_lasDeOtrosYLasRecientesNo() {
        val ls = listOf(
            ll("a", "yo", "Note", "llamando", "2026-10-28T13:30:00Z"),   // mía, de ayer: colgada
            ll("b", "yo", "Note", "llamando", "2026-10-29T11:59:30Z"),   // mía, recién: todavía puede sonar
            ll("c", "yo", "Redmi", "llamando", "2026-10-28T13:30:00Z"),  // de mi otro teléfono: la cancela ése
            ll("d", "otra", "A15", "llamando", "2026-10-28T13:30:00Z"),  // de otra persona
            ll("e", "yo", "Note", "atendida", "2026-10-28T13:30:00Z"))   // ya atendida
        assertEquals(listOf("a"), colgadasMias(ls, "yo", "Note", ahora).map { it.id })
    }

    @Test fun elMotivo_vaCorto_yEnUnRenglon() {
        assertEquals("ErrorNube: Sin conexión. Probá de nuevo cuando haya señal.", motivoDeError(ErrorNube("Sin conexión. Probá de nuevo cuando haya señal.")))
        assertTrue(motivoDeError(RuntimeException("x".repeat(300) + "\nmás")).length <= 100)
        assertFalse(motivoDeError(RuntimeException("a\nb")).contains("\n"))
    }

    @Test fun elVigia_mideConElRelojQueNoCorreDormido() {
        val f = java.io.File("src/main/java/com/maurogasta/pizarra/EnlaceServicio.kt").readText()
        assertTrue(f.contains("fun relojDelVigia(): Long = SystemClock.uptimeMillis()"))
        assertTrue(f.contains("val ahora = relojDelVigia()"))
        assertTrue(f.contains("c.ultimo = relojDelVigia()"))
        assertFalse("el vigía no vuelve al reloj de pared", Regex("estaMuda\\(c\\.ultimo, System").containsMatchIn(f))
    }
}
