package com.maurogasta.pizarra

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// El banco de pizarra-20: el widget de la agenda de la semana. Lo que tiene
// que decir lo mismo que la agenda de Tiempos —las tres clases, una que se
// repite, una sin día, una tarea hecha— y que el widget se pueda poner.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgendaTest {
    private fun s(v: String) = JSONObject().put("stringValue", v)
    private fun m(vararg p: Pair<String, JSONObject>) = JSONObject().put("mapValue", JSONObject().put("fields", JSONObject().apply { p.forEach { put(it.first, it.second) } }))

    private val hoy = "2026-10-12"                       // lunes
    private val agenda = JSONObject()
        .put("actividades", m(
            "a1" to m("titulo" to s("Gimnasio"), "dia" to s("2026-10-12"), "desde" to s("2026-10-12T18:00"), "hasta" to s("2026-10-12T19:00"), "hf" to s("19:00"), "tipo" to s("personal"), "lugar" to s("Club")),
            "a2" to m("titulo" to s("Cena afuera"), "dia" to s("2026-10-13"), "desde" to s("2026-10-13T21:00"), "hasta" to s("2026-10-14T07:00"), "hf" to s(""), "tipo" to s("personal")),
            "a3" to m("titulo" to s("Del mes que viene"), "dia" to s("2026-11-20"), "desde" to s("2026-11-20T10:00"), "tipo" to s("trabajo"))))
        .put("items", m(
            "f:t1" to m("dia" to s("2026-10-14"), "hora" to s("09:00"), "hasta" to s("11:00")),
            "f:t2" to m(),                                   // sin día: flota en hoy
            "f:t3" to m("dia" to s("2026-10-12")),            // hecha: no va
            "cv:c1" to m("dia" to s("2026-10-12"))))           // de Casa Verde: vive en otra base

    private fun campos() = agenda

    private val tareas = listOf(
        Tarea("p", "Santa fe", false, "comun", "yo", null, false, null, "mantenimiento"),
        Tarea("t1", "Cortinas", false, "comun", "yo", "p", false, null),
        Tarea("t2", "Llamar al plomero", false, "comun", "yo", null, false, null),
        Tarea("t3", "Ya hecha", true, "comun", "yo", null, false, null))

    private fun ev(id: String, titulo: String, fecha: String, hora: String, semanal: Boolean = false, excepto: List<String> = emptyList()) = JSONObject()
        .put("document", JSONObject().put("name", "$BASE_DOCS/eventos/$id").put("fields", JSONObject()
            .put("titulo", s(titulo)).put("fecha", s(fecha)).put("hora", s(hora)).put("horaFin", s(""))
            .put("semanal", JSONObject().put("booleanValue", semanal))
            .put("excepto", JSONObject().put("arrayValue", JSONObject().put("values", JSONArray().apply { excepto.forEach { put(s(it)) } })))))

    private val eventos = eventosDeConsulta(JSONArray()
        .put(ev("e1", "Psicóloga", "2026-10-05", "16:00", semanal = true, excepto = listOf("2026-10-19")))
        .put(ev("e2", "Odontólogo", "2026-10-14", "19:00"))
        .put(JSONObject().put("readTime", "x")))

    @Test fun laSemanaTieneSieteDiasConEncabezado() {
        val l = agendaDeLaSemana(hoy, campos(), eventos, tareas)
        val dias = l.filter { it.clase == "dia" }
        assertEquals(7, dias.size)
        assertTrue(dias[0].hoy && dias[0].titulo.startsWith("Hoy · lunes 12/10"))
        assertTrue(dias[1].titulo.startsWith("Mañana · martes 13/10"))
        assertTrue("un día sin nada dice que está libre", dias.any { it.titulo.endsWith("· libre") })
    }

    @Test fun lasTresClasesEnSuDiaYPorHora() {
        val l = agendaDeLaSemana(hoy, campos(), eventos, tareas)
        val lunes = l.filter { it.dia == hoy && it.clase != "dia" }
        // La psicóloga se repite los lunes; la tarea sin día flota en hoy, sin hora, al final.
        assertEquals(listOf("Psicóloga", "Gimnasio", "Llamar al plomero"), lunes.map { it.titulo })
        assertEquals("18:00\n19:00", lunes[1].hora)
        assertEquals("Personal · Club", lunes[1].sub)
        assertTrue(lunes[2].sub.contains("sin día"))
        val miercoles = l.filter { it.dia == "2026-10-14" && it.clase != "dia" }
        assertEquals(listOf("Santa fe › Cortinas", "Odontólogo"), miercoles.map { it.titulo })
        assertEquals("09:00\n11:00", miercoles[0].hora)
        assertEquals("chicos", miercoles[1].clase)
        // Una noche sin vuelta: sólo la hora de salida.
        assertEquals("21:00", l.first { it.titulo == "Cena afuera" }.hora)
    }

    @Test fun loQueNoVa() {
        val l = agendaDeLaSemana(hoy, campos(), eventos, tareas)
        assertFalse("una tarea hecha no va", l.any { it.titulo == "Ya hecha" })
        assertFalse("lo de fuera de la semana no va", l.any { it.titulo == "Del mes que viene" })
        assertFalse("lo de Casa Verde no va: vive en su base", l.any { it.titulo.contains("c1") })
        // La psicóloga no va el lunes 19 (`excepto`), y está fuera de la semana igual; el 12 sí.
        assertTrue(eventoEnDia(eventos.first { it.id == "e1" }, "2026-10-12"))
        assertFalse(eventoEnDia(eventos.first { it.id == "e1" }, "2026-10-19"))
        assertFalse("antes de su fecha no se repite", eventoEnDia(eventos.first { it.id == "e1" }, "2026-09-28"))
        assertEquals(2, eventos.size)
    }

    @Test fun lasAlarmasVanEnSuDiaYASuHora() {
        val al = listOf(Alerta("x1", "alarma", "Odontólogo", "2026-10-14", "18:30"),
            Alerta("x2", "recordatorio", "Llamar a la escuela", "2026-10-12", "08:00"),
            Alerta("x3", "alarma", "Pasada", "2026-10-11", "09:00"))
        val l = agendaDeLaSemana(hoy, campos(), eventos, tareas, alertas = al)
        val mie = l.filter { it.dia == "2026-10-14" && it.clase != "dia" }
        assertEquals(listOf("Santa fe › Cortinas", "⏰ Odontólogo", "Odontólogo"), mie.map { it.titulo })
        assertEquals("alerta", mie[1].clase)
        assertEquals("Alarma", mie[1].sub)
        assertEquals("🔔 Llamar a la escuela", l.first { it.dia == hoy && it.clase != "dia" }.titulo)
        assertFalse("una de antes de hoy no va", l.any { it.titulo.contains("Pasada") })
    }

    @Test fun tocarAbreLaPiezaYUnaAlarmaLaSuya() {
        val al = listOf(Alerta("x1", "alarma", "Odontólogo", "2026-10-14", "18:30", sobre = "ev:e2|2026-10-14"),
            Alerta("x2", "recordatorio", "Suelto", "2026-10-12", "08:00"))
        val l = agendaDeLaSemana(hoy, campos(), eventos, tareas, alertas = al)
        assertEquals("act:a1", l.first { it.titulo == "Gimnasio" }.clave)
        assertEquals("f:t1", l.first { it.titulo == "Santa fe › Cortinas" }.clave)
        assertEquals("ev:e1|2026-10-12", l.first { it.titulo == "Psicóloga" && it.dia == hoy }.clave)
        // pizarra-29: la alarma de una pieza va en el renglón de la pieza, no aparte.
        assertFalse(l.any { it.titulo == "⏰ Odontólogo" })
        assertTrue(l.first { it.titulo == "Odontólogo" }.sub.endsWith("⏰ 18:30 · Odontólogo"))
        assertEquals("", l.first { it.titulo == "🔔 Suelto" }.clave)
        assertEquals("https://maurogasta-crypto.github.io/tiempos/?solapa=agenda&dia=2026-10-14&abrir=ev%3Ae2%7C2026-10-14",
            urlPieza("2026-10-14", "ev:e2|2026-10-14"))
        assertEquals("https://maurogasta-crypto.github.io/tiempos/?solapa=agenda&dia=2026-10-12", urlPieza("2026-10-12", ""))
        assertTrue(esDeTiempos(urlPieza("2026-10-12", "act:a1")))
        // La alarma trae su pieza desde la base.
        val doc = JSONObject().put("name", "$BASE_DOCS/alertas/z").put("fields", JSONObject().put("tipo", s("alarma"))
            .put("texto", s("x")).put("dia", s("2026-10-14")).put("hora", s("10:00")).put("sobre", s("act:a1")))
        assertEquals("act:a1", alertaDe(doc)!!.sobre)
    }

    @Test fun laAlarmaVaEnSuPiezaConLaNota() {
        // El caso del 13-oct: una actividad con nota y su alarma de salir.
        val ag = JSONObject().put("actividades", m("r1" to m("titulo" to s("Atención · Aguada"), "dia" to s("2026-10-13"),
            "desde" to s("2026-10-13T16:00"), "hasta" to s("2026-10-13T17:00"), "hf" to s("17:00"), "tipo" to s("personal"),
            "lugar" to s("Aguada"), "nota" to s("Serie C, número 1"))))
        val al = listOf(Alerta("x1", "alarma", "Salir para Aguada", "2026-10-13", "15:30", sobre = "act:r1"),
            Alerta("x2", "recordatorio", "El día antes", "2026-10-12", "20:00", sobre = "act:r1"),
            Alerta("x3", "alarma", "De otra cosa", "2026-10-13", "09:00", sobre = "act:noesta"))
        val l = agendaDeLaSemana(hoy, ag, emptyList(), emptyList(), alertas = al)
        val f = l.first { it.clave == "act:r1" }
        assertEquals("16:00\n17:00", f.hora)
        assertEquals("Personal · Aguada\n📝 Serie C, número 1\n🔔 lunes 12/10 20:00 · El día antes\n⏰ 15:30 · Salir para Aguada".split("\n").toSet(), f.sub.split("\n").toSet())
        assertEquals(1, l.count { it.clave == "act:r1" })
        assertTrue("la que no tiene su pieza en la semana va aparte", l.any { it.titulo == "⏰ De otra cosa" })
    }

    @Test fun sinAgendaTodaviaSeVeLaSemanaLibre() {
        val l = agendaDeLaSemana(hoy, null, emptyList(), emptyList())
        assertEquals(7, l.size)
        assertTrue(l.all { it.clase == "dia" && it.titulo.endsWith("· libre") })
    }

    @Test fun laRespuestaDelBatchGet() {
        val r = JSONArray().put(JSONObject().put("found", JSONObject().put("name", "x").put("fields", campos())))
        assertTrue(agendaDeRespuesta(r)!!.has("actividades"))
        assertNull(agendaDeRespuesta(JSONArray().put(JSONObject().put("missing", "x"))))
        assertEquals("$BASE_DOCS/agendas/yo", pedidoAgenda("yo").getJSONArray("documents").getString(0))
    }

    @Test fun laCacheVuelveIgual() {
        val l = agendaDeLaSemana(hoy, campos(), eventos, tareas)
        assertEquals(l, filasAgendaDeJson(filasAgendaAJson(l)))
        assertEquals(emptyList<FilaAgenda>(), filasAgendaDeJson("no es json"))
    }

    @Test fun losDosWidgetsSePuedenPoner() {
        val c: Context = RuntimeEnvironment.getApplication()
        val am = shadowOf(AppWidgetManager.getInstance(c))
        val p = am.createWidget(PizarraWidget::class.java, R.layout.widget)
        val a = am.createWidget(AgendaWidget::class.java, R.layout.widget_agenda)
        assertTrue(p > 0 && a > 0)
        AgendaWidget.dibujar(c)
        assertEquals(1, AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, AgendaWidget::class.java)).size)
        assertEquals(URL_AGENDA, "https://maurogasta-crypto.github.io/tiempos/?solapa=agenda")
    }
}
