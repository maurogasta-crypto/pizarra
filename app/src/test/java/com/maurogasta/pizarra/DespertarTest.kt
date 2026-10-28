package com.maurogasta.pizarra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-74, tiempos:A42: un aviso trae SÓLO lo que cambió, y cada 10 minutos sólo lo barato.
class DespertarTest {
    @Test fun unMensaje_traeSoloLaCharla() {
        assertEquals(setOf("mensajes"), partesDe("mensajes"))
        assertEquals(setOf("llamadas"), partesDe("llamadas"))
        assertFalse("un mensaje no relee las tareas", "tareas" in partesDe("mensajes"))
        assertEquals(setOf("tareas", "agenda"), partesDe("tareas"))
        assertEquals(setOf("alarmas", "agenda"), partesDe("alertas"))
        assertEquals("el enlace lejos no trae las tareas", setOf("enlace", "mensajes"), partesDe("todo"))
    }

    @Test fun loDesconocido_traeTodo() {
        assertEquals(PARTES.toSet(), partesDe(null))
        assertEquals(PARTES.toSet(), partesDe("algo-nuevo"))
    }

    @Test fun cadaQueDelServidor_tieneSuParte() {
        // Los `que` de despertar.mjs de Casa Verde (QUE_CAMBIO acá): ninguno cae en «todo» sin querer.
        for (q in QUE_CAMBIO) assertTrue("$q", partesDe(q).isNotEmpty())
        assertTrue(QUE_CAMBIO.filter { it != "todo" }.all { partesDe(it).size < PARTES.size })
    }

    @Test fun cadaDiezMinutos_loBarato_yTodoCadaSeisHoras() {
        val t = 1_800_000_000_000L
        val barato = partesPeriodicas(t, t - 3600_000L)
        assertFalse("tareas" in barato); assertFalse("agenda" in barato); assertFalse("alarmas" in barato)
        assertTrue("mensajes" in barato && "enlace" in barato && "llamadas" in barato)
        assertEquals(PARTES.toSet(), partesPeriodicas(t, t - TODO_CADA_MS))
        assertEquals(PARTES.toSet(), partesPeriodicas(t, 0L))
    }

    @Test fun unaRafaga_seJuntaEnUnaVueltaMas() {
        val t = 1_800_000_000_000L
        assertEquals(0L, demora(t, null))
        assertEquals(0L, demora(t, t - ESPACIO_MS))
        assertEquals(ESPACIO_MS - 5_000L, demora(t, t - 5_000L))
    }

    @Test fun laPizarraYLaAgenda_compartenLasTareas() {
        val d = java.io.File("src/main/java/com/maurogasta/pizarra/Despertar.kt").readText()
        assertTrue(d.contains("actualizarDesdeLaRed(app, null, conLuces = false, tareas = tareas)"))
        assertTrue(d.contains("AgendaWidget.actualizar(app, null, tareas = tareas)"))
        val n = java.io.File("src/main/java/com/maurogasta/pizarra/Nube.kt").readText()
        assertTrue(n.contains("tareas ?: tareas()"))
    }
}
