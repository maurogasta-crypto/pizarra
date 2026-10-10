package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// El banco de la pizarra: `./gradlew test`. Prueba sobre todo lo que NO hace —
// mostrar lo que no se eligió, mostrar lo que eligió el otro, escribir algo
// distinto de lo que escribe el sitio, crear una tarea que alguien borró.
class LogicaTest {
    private val YO = "uidMauro"
    private val OTRA = "9uidFlorencia"

    private fun doc(id: String, titulo: String, hecho: Boolean = false, pizarra: Map<String, Boolean> = emptyMap(),
                    parent: String? = null, alcance: String = "comun", tipo: String? = null): JSONObject {
        val f = JSONObject()
            .put("titulo", JSONObject().put("stringValue", titulo))
            .put("hecho", JSONObject().put("booleanValue", hecho))
            .put("alcance", JSONObject().put("stringValue", alcance))
            .put("duenio", JSONObject().put("stringValue", YO))
        if (parent != null) f.put("parentId", JSONObject().put("stringValue", parent))
        if (tipo != null) f.put("tipo", JSONObject().put("stringValue", tipo))
        if (pizarra.isNotEmpty()) {
            val m = JSONObject()
            pizarra.forEach { (k, v) -> m.put(k, JSONObject().put("booleanValue", v)) }
            f.put("pizarra", JSONObject().put("mapValue", JSONObject().put("fields", m)))
        }
        return JSONObject().put("name", "$BASE_DOCS/tareas/$id").put("fields", f)
    }

    private fun respuesta(vararg d: JSONObject) = JSONArray().apply {
        d.forEach { put(JSONObject().put("document", it)) }
        put(JSONObject().put("readTime", "2026-10-02T00:00:00Z"))
    }

    @Test fun sóloVaLoQueYoElegí_noLoQueEligióElOtro() {
        val t = tareasDeConsulta(respuesta(
            doc("a", "Mía", pizarra = mapOf(YO to true)),
            doc("b", "De ella", pizarra = mapOf(OTRA to true)),
            doc("c", "De nadie"),
        ), YO)
        assertEquals(3, t.size)
        assertEquals(listOf("a"), paraLaPizarra(t).map { it.id })
    }

    @Test fun pendientesPrimero_tachadasAlFinal_ySubtareaConSuProyecto() {
        val t = tareasDeConsulta(respuesta(
            doc("p", "Galpón"),
            doc("x", "Zócalo", hecho = true, pizarra = mapOf(YO to true)),
            doc("y", "Comprar tornillos", parent = "p", pizarra = mapOf(YO to true)),
            doc("z", "Arreglar canilla", pizarra = mapOf(YO to true)),
        ), YO)
        val f = paraLaPizarra(t)
        assertEquals(listOf("z", "y", "x"), f.map { it.id })
        assertEquals("Galpón › Comprar tornillos", f[1].texto)
        assertTrue(f[2].hecho)
    }

    @Test fun loQueNoSeEntiendeNoEntra() {
        assertNull(tareaDe(JSONObject().put("name", "$BASE_DOCS/tareas/sin").put("fields", JSONObject()), YO))
        assertNull(tareaDe(JSONObject().put("fields", JSONObject()), YO))
        assertEquals(0, tareasDeConsulta(JSONArray().put(JSONObject().put("readTime", "x")), YO).size)
    }

    @Test fun tacharEsLoMismoQueElBotónDelSitio() {
        val w = cuerpoTachar("a", true, YO).getJSONArray("writes").getJSONObject(0)
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertTrue(f.getJSONObject("hecho").getBoolean("booleanValue"))
        assertEquals(YO, f.getJSONObject("hechoPor").getString("stringValue"))
        assertEquals(listOf("hecho", "hechoPor"), lista(w.getJSONObject("updateMask").getJSONArray("fieldPaths")))
        assertEquals("$BASE_DOCS/tareas/a", w.getJSONObject("update").getString("name"))
        // Si alguien la borró mientras tanto, no se crea una tarea fantasma.
        assertTrue(w.getJSONObject("currentDocument").getBoolean("exists"))
        assertEquals("actualizadoEn", w.getJSONArray("updateTransforms").getJSONObject(0).getString("fieldPath"))
    }

    @Test fun destacharSóloTocaHecho_comoVolverAPendiente() {
        val w = cuerpoTachar("a", false, YO).getJSONArray("writes").getJSONObject(0)
        assertEquals(listOf("hecho"), lista(w.getJSONObject("updateMask").getJSONArray("fieldPaths")))
        assertFalse(w.getJSONObject("update").getJSONObject("fields").getJSONObject("hecho").getBoolean("booleanValue"))
    }

    @Test fun fijarTocaSóloMiLugarEnLaPizarra_yConAcentosGraves() {
        val w = cuerpoFijar("a", true, OTRA).getJSONArray("writes").getJSONObject(0)
        assertEquals(listOf("pizarra.`$OTRA`"), lista(w.getJSONObject("updateMask").getJSONArray("fieldPaths")))
        val m = w.getJSONObject("update").getJSONObject("fields").getJSONObject("pizarra")
            .getJSONObject("mapValue").getJSONObject("fields")
        assertEquals(setOf(OTRA), m.keySet())
        val sacar = cuerpoFijar("a", false, YO).getJSONArray("writes").getJSONObject(0)
        assertFalse(sacar.getJSONObject("update").getJSONObject("fields").has("pizarra"))
        assertTrue(sacar.getJSONObject("currentDocument").getBoolean("exists"))
    }

    @Test fun lasConsultasSonLasDelSitio() {
        val c = consultaComunes().getJSONObject("structuredQuery")
        assertEquals("tareas", c.getJSONArray("from").getJSONObject(0).getString("collectionId"))
        assertEquals("comun", c.getJSONObject("where").getJSONObject("fieldFilter").getJSONObject("value").getString("stringValue"))
        val m = consultaMias(YO).getJSONObject("structuredQuery").getJSONObject("where").getJSONObject("compositeFilter")
        val vals = (0 until 2).map { m.getJSONArray("filters").getJSONObject(it).getJSONObject("fieldFilter").getJSONObject("value").getString("stringValue") }
        assertEquals(listOf("personal", YO), vals)
    }

    @Test fun laCachéSobreviveIdaYVuelta_yUnaRotaQuedaVacía() {
        val f = listOf(Fila("a", "Uno", false), Fila("b", "Dos ★ ñ", true))
        assertEquals(f, filasDeJson(filasAJson(f)))
        assertEquals(emptyList<Fila>(), filasDeJson("{roto"))
        assertEquals(emptyList<Fila>(), filasDeJson(null))
    }

    @Test fun tacharAlInstanteReordena() {
        val f = conTachada(listOf(Fila("a", "Alfa", false), Fila("b", "Beta", false)), "a", true)
        assertEquals(listOf("b", "a"), f.map { it.id })
        assertTrue(f[1].hecho)
    }

    @Test fun paraElegirMuestraPendientesYLasYaFijadas() {
        val t = tareasDeConsulta(respuesta(
            doc("a", "Pendiente"),
            doc("b", "Hecha y fijada", hecho = true, pizarra = mapOf(YO to true)),
            doc("c", "Hecha suelta", hecho = true),
        ), YO)
        assertEquals(listOf("b", "a"), paraElegir(t).map { it.id })
    }

    // pizarra-15: por categoría, la de la tarea de más arriba (como Tiempos).
    @Test fun porCategoria_heredaDelProyecto_yVaEnElOrdenDeTiempos() {
        val t = tareasDeConsulta(respuesta(
            doc("p", "Dgo Aramburú", tipo = "produccion"),
            doc("h", "Fotos", parent = "p", tipo = "personal"),
            doc("c", "Compras para limpieza", tipo = "mantenimiento", pizarra = mapOf(YO to true)),
            doc("s", "Sin tipo"),
            doc("r", "Tipo raro", tipo = "inventado"),
            doc("x", "Hecha suelta", hecho = true, tipo = "produccion"),
        ), YO)
        val g = paraElegirPorCategoria(t)
        assertEquals(listOf("produccion", "mantenimiento", "personal"), g.map { it.first })
        assertEquals(setOf("p", "h"), g[0].second.map { it.id }.toSet())
        assertEquals(setOf("s", "r"), g[2].second.map { it.id }.toSet())
        assertEquals(paraElegir(t).map { it.id }.toSet(), g.flatMap { p -> p.second.map { it.id } }.toSet())
        assertEquals(listOf("produccion", "mantenimiento", "ninos", "casa", "personal"), CATEGORIAS.keys.toList())
    }

    @Test fun losErroresSeDicenEnCastellano() {
        assertEquals("Mail o contraseña equivocados.", motivo(400, """{"error":{"message":"INVALID_LOGIN_CREDENTIALS"}}"""))
        assertEquals("Esa tarea ya no existe.", motivo(404, """{"error":{"message":"no document"}}"""))
        assertTrue(motivo(403, """{"error":{"message":"Missing or insufficient permissions.","status":"PERMISSION_DENIED"}}""").contains("aprobada"))
        assertEquals("La base contestó 500.", motivo(500, "basura"))
    }

    private fun lista(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
}
