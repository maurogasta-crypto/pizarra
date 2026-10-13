package com.maurogasta.pizarra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

// pizarra-26: tibia sólo de 8 a 23, cada 10 minutos; fuera de horario, a las 8.
class TibioTest {
    private fun t(s: String) = LocalDateTime.parse(s)

    @Test fun dentroDelHorarioCadaDiezMinutos() {
        assertEquals(t("2026-10-13T08:10"), proximoTibio(t("2026-10-13T08:00")))
        assertEquals(t("2026-10-13T22:55"), proximoTibio(t("2026-10-13T22:45")))
    }


    @Test fun elCorteDeLaNoche() {
        assertEquals("a las 22:55 el próximo sería 23:05: va mañana a las 8", t("2026-10-14T08:00"), proximoTibio(t("2026-10-13T22:55")))
        assertEquals(t("2026-10-14T08:00"), proximoTibio(t("2026-10-13T23:30")))
        assertEquals(t("2026-10-13T08:00"), proximoTibio(t("2026-10-13T03:00")))
        assertEquals(t("2026-10-13T08:00"), proximoTibio(t("2026-10-13T07:55")))
    }

    @Test fun queEsHorario() {
        assertTrue(enHorario(t("2026-10-13T08:00")))
        assertTrue(enHorario(t("2026-10-13T22:59")))
        assertFalse(enHorario(t("2026-10-13T23:00")))
        assertFalse(enHorario(t("2026-10-13T07:59")))
    }
}
