package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

// El banco de pizarra-2: las alertas que se programan, «hoy se puede» y el
// dictado. Sobre todo lo que NO tiene que pasar: sonar algo que ya pasó,
// programar algo sin día, mostrar un descartado, mandar en el dictado algo
// que no sea el texto.
class TiemposTest {
    private val MVD = ZoneId.of("America/Montevideo")

    private fun s(v: String) = JSONObject().put("stringValue", v)
    private fun alerta(id: String, tipo: String, texto: String, dia: String, hora: String) = JSONObject()
        .put("name", "$BASE_DOCS/alertas/$id")
        .put("fields", JSONObject().put("uid", s("yo")).put("tipo", s(tipo)).put("texto", s(texto)).put("dia", s(dia)).put("hora", s(hora)))

    private fun deseo(id: String, titulo: String, dias: List<Int> = emptyList(), fecha: String = "", hi: String = "",
                      estado: String = "deseo"): JSONObject {
        val f = JSONObject().put("titulo", s(titulo)).put("estado", s(estado)).put("hi", s(hi)).put("fecha", s(fecha))
        f.put("dias", JSONObject().put("arrayValue", JSONObject().put("values",
            JSONArray().apply { dias.forEach { put(JSONObject().put("integerValue", it.toString())) } })))
        return JSONObject().put("name", "$BASE_DOCS/deseos/$id").put("fields", f)
    }

    private fun resp(vararg d: JSONObject) = JSONArray().apply { d.forEach { put(JSONObject().put("document", it)) } }

    @Test fun elGimnasio_recordatorioTempranoYAlarmaAntes_enOrden() {
        val a = alertasDeConsulta(resp(
            alerta("s", "alarma", "Salir para el gimnasio con los títulos", "2026-10-06", "17:30"),
            alerta("r", "recordatorio", "Llevar los títulos del auto a Pedro", "2026-10-06", "08:00"),
        ))
        val ahora = java.time.ZonedDateTime.of(2026, 10, 5, 22, 0, 0, 0, MVD).toInstant().toEpochMilli()
        val p = paraProgramar(a, ahora, MVD)
        assertEquals(listOf("r", "s"), p.map { it.first.id })
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 6, 17, 30, 0, 0, MVD).toInstant().toEpochMilli(), p[1].second)
    }

    @Test fun loQueYaPasó_oEstáLejos_noSeProgramá() {
        val a = alertasDeConsulta(resp(
            alerta("vieja", "alarma", "Ya fue", "2026-10-05", "09:00"),
            alerta("lejos", "recordatorio", "En un mes", "2026-11-20", "09:00"),
            alerta("ok", "recordatorio", "Mañana", "2026-10-06", "09:00"),
        ))
        val ahora = java.time.ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, MVD).toInstant().toEpochMilli()
        assertEquals(listOf("ok"), paraProgramar(a, ahora, MVD).map { it.first.id })
    }

    @Test fun unaAlertaSinForma_seDescarta_yLaHoraFaltanteEsLas8() {
        val a = alertasDeConsulta(resp(
            alerta("1", "borrar-todo", "x", "2026-10-06", "09:00"),
            alerta("2", "alarma", "Sin día", "mañana", "09:00"),
            alerta("3", "alarma", "", "2026-10-06", "09:00"),
            alerta("4", "recordatorio", "Sin hora", "2026-10-06", "5pm"),
        ))
        assertEquals(listOf("4"), a.map { it.id })
        assertEquals("08:00", a[0].hora)
        // y lo guardado vuelve igual
        assertEquals(a, alertasDeJson(alertasAJson(a)))
        assertEquals(emptyList<Alerta>(), alertasDeJson("basura"))
    }

    @Test fun consultaAlertas_pideSóloLasMías() {
        val q = consultaAlertas("uidMauro").toString()
        assertTrue(q.contains("\"collectionId\":\"alertas\""))
        assertTrue(q.contains("\"fieldPath\":\"uid\"") && q.contains("\"stringValue\":\"uidMauro\""))
    }

    @Test fun hoySePuede_semanalDeEseDíaYLaFecha_sinDescartados_porHora() {
        val d = deseosDeConsulta(resp(
            deseo("b", "Básquet", dias = listOf(2, 4), hi = "18:00"),
            deseo("f", "Feria", fecha = "2026-10-06", hi = "10:00"),
            deseo("n", "Natación", dias = listOf(2), estado = "descartado"),
            deseo("t", "Teatro", dias = listOf(2), estado = "agendado"),
            deseo("y", "Yoga"),
        ))
        assertEquals(2, diaSemana("2026-10-06"))     // martes
        assertEquals(listOf("f", "b", "t"), posiblesDelDia(d, "2026-10-06").map { it.id })
        assertEquals(emptyList<String>(), posiblesDelDia(d, "2026-10-07").map { it.id })
        assertEquals(0, diaSemana("2026-10-11"))     // domingo es 0, como en el sitio
    }

    @Test fun unDíaFueraDeRango_seIgnora() {
        val d = deseoDe(deseo("x", "Raro", dias = listOf(9, 3, 3)))!!
        assertEquals(listOf(3), d.dias)
        assertNull(deseoDe(deseo("v", "")))
    }

    @Test fun elDictado_viajaSóloComoTexto_yCodificado() {
        val u = urlDictado("  el jueves llevo los niños a básquet & más  ")
        assertTrue(u.startsWith("https://maurogasta-crypto.github.io/tiempos/?dictar="))
        val q = u.substringAfter("?dictar=")
        assertEquals("el jueves llevo los niños a básquet & más", java.net.URLDecoder.decode(q, "UTF-8"))
        assertTrue(!q.contains("&") && !q.contains(" "))
        assertEquals(2000, java.net.URLDecoder.decode(urlDictado("a".repeat(5000)).substringAfter("="), "UTF-8").length)
    }
}
