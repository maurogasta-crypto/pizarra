package com.maurogasta.pizarra

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// pizarra-84, plan «Timón» etapa 5: el widget «Hoy» pinta `hoy/{uid}` y nada más.
// pizarra-85: y el renglón puede traer el NOMBRE de su proyecto (nucleo-48), que sólo se pinta.
// Lo que cuida es lo que NO tiene que pasar: que el widget calcule, ordene o
// invente una escritura que Timón no le dio (regla 3 de ARQUITECTURA.md).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HoyTest {
    private fun s(v: String) = JSONObject().put("stringValue", v)
    private fun m(vararg pares: Pair<String, JSONObject>) =
        JSONObject().put("mapValue", JSONObject().put("fields", JSONObject().apply { for ((k, v) in pares) put(k, v) }))

    /** Un renglón como lo arma `materializarHoy` de nucleo.js de Timón (nucleo-47). */
    private fun renglon(clave: String, dia: String, texto: String = "Tarea $clave", espacio: String = "mio", hora: String? = null) = m(
        "dia" to s(dia), "texto" to s(texto), "espacio" to s(espacio),
        "hora" to (if (hora == null) JSONObject().put("nullValue", JSONObject.NULL) else s(hora)),
        "clave" to s("it:$clave"),
        "accion" to m("coleccion" to s("items"), "doc" to s(clave),
            "campos" to m("hecho" to JSONObject().put("booleanValue", true), "hechoPor" to s("U1")),
            "deshacer" to m("hecho" to JSONObject().put("booleanValue", false), "hechoPor" to JSONObject().put("nullValue", JSONObject.NULL))),
    )

    private fun respuesta(desde: String, vararg rs: JSONObject) = JSONArray().put(JSONObject().put("found", JSONObject()
        .put("name", "$BASE_DOCS/hoy/U1")
        .put("fields", JSONObject()
            .put("desde", s(desde)).put("hasta", s("2026-11-23"))
            .put("actualizadoEn", JSONObject().put("timestampValue", "2026-11-17T12:00:00Z"))
            .put("renglones", JSONObject().put("arrayValue", JSONObject().put("values", JSONArray().apply { rs.forEach { put(it) } }))))))

    @Test fun leeElDocumento_enElOrdenDeTimon_yEligeElDiaPorFecha() {
        val doc = hoyDeRespuesta(respuesta("2026-11-17",
            renglon("b", "2026-11-17", hora = "10:00"), renglon("a", "2026-11-17", espacio = "fam", hora = "vencido"),
            renglon("c", "2026-11-18")))!!
        assertEquals("2026-11-17T12:00:00Z", doc.armado)
        val f = filasDeHoy(doc, emptyMap(), "2026-11-17")
        assertEquals("el orden es el de Timón: el widget no ordena", listOf("it:b", "it:a"), f.map { it.clave })
        assertEquals("fam", f[1].espacio); assertEquals("vencido", f[1].hora)
        assertFalse("lo de mañana no va hoy", f.any { it.clave == "it:c" })
        // Un documento de ayer que Timón todavía no rearmó: lo de ayer sigue (está pendiente) y lo de hoy aparece.
        assertEquals(listOf("it:b", "it:a", "it:c"), filasDeHoy(doc, emptyMap(), "2026-11-18").map { it.clave })
    }

    private fun conProyecto(r: JSONObject, nombre: String?): JSONObject {
        r.getJSONObject("mapValue").getJSONObject("fields")
            .put("proyecto", if (nombre == null) JSONObject().put("nullValue", JSONObject.NULL) else s(nombre))
        return r
    }

    @Test fun elProyectoDelRenglon_viajaTalCual_yNoMueveElOrden() {
        val doc = hoyDeRespuesta(respuesta("2026-11-17",
            conProyecto(renglon("b", "2026-11-17", hora = "10:00"), "Casa Verde"),
            conProyecto(renglon("a", "2026-11-17"), null),
            renglon("c", "2026-11-17")))!!
        val f = filasDeHoy(doc, emptyMap(), "2026-11-17")
        assertEquals("el orden sigue siendo el de Timón", listOf("it:b", "it:a", "it:c"), f.map { it.clave })
        assertEquals("Casa Verde", f[0].proyecto)
        assertEquals("un nullValue es «sin proyecto»", "", f[1].proyecto)
        assertEquals("un renglón de un Timón viejo, sin el campo, también", "", f[2].proyecto)
        assertEquals("lo tildado acá conserva su proyecto", "Casa Verde",
            filasDeHoy(doc, mapOf("it:b" to doc.renglones[0]), "2026-11-17")[0].proyecto)
    }

    @Test fun sinDocumento_nadaYLoDice() {
        assertNull(hoyDeRespuesta(JSONArray().put(JSONObject().put("missing", "$BASE_DOCS/hoy/U1"))))
        assertEquals(emptyList<FilaHoy>(), filasDeHoy(null, emptyMap(), "2026-11-17"))
        assertTrue(estadoDeHoy(null, "2026-11-17", "10:00").contains("abrí Timón"))
        val viejo = hoyDeRespuesta(respuesta("2026-11-16"))
        assertTrue(estadoDeHoy(viejo, "2026-11-17", "10:00").contains("armado el 16/11"))
        assertEquals("al día · 10:00", estadoDeHoy(hoyDeRespuesta(respuesta("2026-11-17")), "2026-11-17", "10:00"))
    }

    @Test fun tildar_escribeLaAccionTalCual_ySacaElRenglonDeHoy() {
        val r = hoyDeRespuesta(respuesta("2026-11-17", renglon("b", "2026-11-17")))!!.renglones[0]
        val c = cuerpoTildar(r, true, "U1")!!
        val w = c.getJSONArray("writes")
        assertEquals(2, w.length())
        val accion = w.getJSONObject(0)
        assertEquals("$BASE_DOCS/items/b", accion.getJSONObject("update").getString("name"))
        assertEquals("los campos son los del renglón, ni uno más",
            r.campos.toString(), accion.getJSONObject("update").getJSONObject("fields").toString())
        assertEquals(setOf("hecho", "hechoPor"), accion.getJSONObject("updateMask").getJSONArray("fieldPaths").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
        assertTrue("un ítem borrado no se resucita", accion.getJSONObject("currentDocument").getBoolean("exists"))
        val enHoy = w.getJSONObject(1).getJSONObject("transform")
        assertEquals("$BASE_DOCS/hoy/U1", enHoy.getString("document"))
        val t = enHoy.getJSONArray("fieldTransforms").getJSONObject(0)
        assertEquals("renglones", t.getString("fieldPath"))
        assertEquals("se saca EXACTAMENTE el renglón que vino", r.crudo.toString(),
            t.getJSONObject("removeAllFromArray").getJSONArray("values").getJSONObject(0).toString())
    }

    @Test fun destildar_escribeElDeshacerDeTimon_yDevuelveElRenglon() {
        val r = hoyDeRespuesta(respuesta("2026-11-17", renglon("b", "2026-11-17")))!!.renglones[0]
        val w = cuerpoTildar(r, false, "U1")!!.getJSONArray("writes")
        val campos = w.getJSONObject(0).getJSONObject("update").getJSONObject("fields")
        assertFalse(campos.getJSONObject("hecho").getBoolean("booleanValue"))
        assertTrue(campos.getJSONObject("hechoPor").has("nullValue"))
        assertTrue(w.getJSONObject(1).getJSONObject("transform").getJSONArray("fieldTransforms").getJSONObject(0).has("appendMissingElements"))
    }

    @Test fun unRenglonSinAccion_oConUnaDireccionRara_noEscribeNada() {
        val sinAccion = renglonDeValor(m("dia" to s("2026-11-17"), "texto" to s("x"), "clave" to s("it:x")))!!
        assertNull(cuerpoTildar(sinAccion, true, "U1"))
        val raro = renglonDeValor(m("dia" to s("2026-11-17"), "texto" to s("x"), "clave" to s("it:x"),
            "accion" to m("coleccion" to s("items/../claves"), "doc" to s("x"), "campos" to m("hecho" to JSONObject().put("booleanValue", true)))))!!
        assertNull("una colección con barras no se escribe", cuerpoTildar(raro, true, "U1"))
    }

    @Test fun loTildadoAca_seVeTachado_yLoQueYaSalioDelDocumento_tambien() {
        val doc = hoyDeRespuesta(respuesta("2026-11-17", renglon("a", "2026-11-17"), renglon("b", "2026-11-17")))!!
        val b = doc.renglones[1]
        val antesDeVolver = filasDeHoy(doc, mapOf(b.clave to b), "2026-11-17")
        assertEquals(listOf(false, true), antesDeVolver.map { it.hecho })
        // Ya salió de hoy/ (lo sacó el commit): sigue tachado, al final, para poder destildarlo.
        val sinB = hoyDeRespuesta(respuesta("2026-11-17", renglon("a", "2026-11-17")))!!
        val despues = filasDeHoy(sinB, mapOf(b.clave to b), "2026-11-17")
        assertEquals(listOf("it:a", "it:b"), despues.map { it.clave })
        assertTrue(despues[1].hecho)
    }

    @Test fun laDireccionDelItem_esLaQueTimonAbre() {
        assertEquals("https://maurogasta-crypto.github.io/tiempos/?solapa=inicio&abrir=it%3Ab", urlItem("it:b"))
        assertTrue(esDeTiempos(urlItem("it:b")))
    }

    @Test fun elToque_elCirculoTildaEnHoy_yElTextoAbre() {
        val app = RuntimeEnvironment.getApplication()
        val a = Robolectric.buildActivity(ToqueActivity::class.java, Intent(app, ToqueActivity::class.java)
            .putExtra("que", "hoy").putExtra("clave", "it:b").putExtra("tildar", true)).setup().get()
        val b = shadowOf(app).broadcastIntents.last()
        assertEquals(ACCION_HOY_TILDAR, b.action)
        assertEquals("it:b", b.getStringExtra("clave"))
        assertTrue(b.getBooleanExtra("tildar", false))
        assertEquals(null, shadowOf(a).nextStartedActivity)
        val antes = shadowOf(app).broadcastIntents.size
        val t = Robolectric.buildActivity(ToqueActivity::class.java, Intent(app, ToqueActivity::class.java)
            .putExtra("que", "abrir_item").putExtra("clave", "it:b")).setup().get()
        assertEquals(MainActivity::class.java.name, shadowOf(t).nextStartedActivity.component?.className)   // sin sesión: a entrar
        assertEquals(antes, shadowOf(app).broadcastIntents.size)
    }

    @Test fun unaTareaQueCambia_traeTambienHoy_yHoyEsBarato() {
        assertTrue("hoy" in partesDe("tareas"))
        assertTrue("hoy" in PARTES)
        assertTrue("una lectura: va cada 10 minutos", "hoy" in partesPeriodicas(1_800_000_000_000L, 1_800_000_000_000L - 60_000L))
        assertFalse("un mensaje no trae hoy", "hoy" in partesDe("mensajes"))
    }

    @Test fun elWidgetNoConoceLaFormaDeUnItem() {
        // Regla 3: «tachar viaja adentro del renglón; la Pizarra no conoce la forma de una tarea».
        val h = java.io.File("src/main/java/com/maurogasta/pizarra/Hoy.kt").readText()
            .lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }.joinToString("\n")
        for (campo in listOf("\"hecho\"", "\"hechoPor\"", "\"titulo\"", "\"importancia\"", "\"items\"")) assertFalse(campo, h.contains(campo))
        assertFalse("no ordena", h.contains("sortedBy") || h.contains("sortedWith"))
    }
}
