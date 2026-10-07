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

    @Test fun unaFallaDeLaApp_tieneLaFormaQuePideLaRegla() {
        val w = cuerpoFalla("abc", "uidMauro", "m@x.y", "  La app se CERRÓ  ", "no-anda").getJSONArray("writes").getJSONObject(0)
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertEquals("$BASE_DOCS/reportes/abc", w.getJSONObject("update").getString("name"))
        assertEquals("uidMauro", f.getJSONObject("uid").getString("stringValue"))
        assertEquals("falla", f.getJSONObject("tipo").getString("stringValue"))
        assertEquals("nuevo", f.getJSONObject("estado").getString("stringValue"))
        assertEquals("La app se CERRÓ", f.getJSONObject("texto").getString("stringValue"))
        assertTrue(!f.has("urgencia"))                      // una falla tiene gravedad, nunca urgencia
        assertEquals(false, w.getJSONObject("currentDocument").getBoolean("exists"))   // no pisa un reporte
        assertEquals(2000, cuerpoFalla("a", "u", "", "x".repeat(5000), "traba").getJSONArray("writes").getJSONObject(0)
            .getJSONObject("update").getJSONObject("fields").getJSONObject("texto").getString("stringValue").length)
        assertEquals(20, idNuevo().length)
    }

    @Test fun compartirDeInstagram_soloLink_quedaElLinkLimpioYUnaLíneaQueLoDice() {
        val t = textoCompartido(null, "https://www.instagram.com/reel/DeFp9ixx41BF/?igsh=MW5jeGU5NjkxNG5nbw==")
        assertEquals("Algo que vi en Instagram y quiero agendar o guardar (sin texto: mirá la captura).\n" +
            "Link (Instagram): https://www.instagram.com/reel/DeFp9ixx41BF/", t)
    }

    @Test fun compartirConTexto_vaElTextoYElLinkAparte() {
        val t = textoCompartido("Feria de diseño", "Feria de diseño este sábado 15 h en el Mercado https://fb.me/e/abc?utm_source=x")
        assertTrue(t.startsWith("Feria de diseño\nFeria de diseño este sábado 15 h en el Mercado"))
        assertTrue(t.endsWith("Link (Facebook): https://fb.me/e/abc?utm_source=x") || t.contains("Link (Facebook)"))
        assertEquals(listOf("https://www.tiktok.com/@x/video/1"), linksDe("mirá https://www.tiktok.com/@x/video/1?is_from_webapp=1."))
        assertEquals("", textoCompartido(null, null))
    }

    @Test fun laCaptura_viajaSóloSiEsDeNuestraCuentaDeCloudinary() {
        val nuestra = "https://res.cloudinary.com/$CLOUDINARY_CUENTA/image/upload/v1/tiempos/a.jpg"
        assertTrue(urlDictado("x", nuestra).contains("&imagen=" + java.net.URLEncoder.encode(nuestra, "UTF-8")))
        assertTrue(!urlDictado("x", "https://evil.example/a.jpg").contains("imagen="))
        assertTrue(!urlDictado("x", "https://res.cloudinary.com/otra/image/upload/a.jpg").contains("imagen="))
    }

    @Test fun alPonerseAlDía_sóloSeCancelaLoBorradoDeLaBase() {
        assertEquals(setOf("borrada"), aCancelar(setOf("pasada", "borrada", "futura"), setOf("pasada", "futura")))
        assertEquals(emptySet<String>(), aCancelar(setOf("a"), setOf("a", "b")))
    }

    // pizarra-11: «Claude propone», con el filtro de `mias()` de propone.js.
    private fun prop(id: String, clase: String, estado: String, para: Any, titulo: String, dia: String = "", hi: String = ""): JSONObject {
        val p = if (para is List<*>) JSONObject().put("arrayValue", JSONObject().put("values", JSONArray().apply { para.forEach { put(s(it as String)) } })) else s(para as String)
        val datos = JSONObject().put("para", p).put("titulo", s(titulo)).put("dia", s(dia)).put("hi", s(hi))
        return JSONObject().put("name", "$BASE_DOCS/propuestas/$id").put("fields", JSONObject()
            .put("clase", s(clase)).put("estado", s(estado)).put("resumen", s("r-$id"))
            .put("datos", JSONObject().put("mapValue", JSONObject().put("fields", datos))))
    }

    @Test fun claudePropone_sóloLoMíoPendienteDeAgendaOConsulta_enOrden() {
        val r = propuestasDeConsulta(resp(
            prop("b", "agenda", "pendiente", "yo", "Dentista", "2026-10-09", "10:00"),
            prop("a", "agenda", "pendiente", listOf("otro", "yo"), "Básquet", "2026-10-08", "18:00"),
            prop("c", "consulta", "pendiente", "yo", "¿Te quedás con los chicos?"),
            prop("x", "agenda", "pendiente", "otro", "De Flor"),
            prop("y", "agenda", "aprobada", "yo", "Ya decidida"),
            prop("z", "gasto", "pendiente", "yo", "Un gasto"),
        ), "yo")
        assertEquals(listOf("a", "b", "c"), r.map { it.id })
        assertEquals("Básquet", r[0].titulo); assertEquals("18:00", r[0].hora)
        assertTrue(consultaPropuestas().toString().contains("\"stringValue\":\"pendiente\""))
    }
}
