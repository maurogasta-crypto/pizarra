package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-14 (tiempos:V10): los avisos del ecosistema, en el teléfono. Lo que
// importa: pedir SÓLO los míos, leer con desconfianza, no repetir una
// notificación, no llenar la barra con lo viejo, y marcar leído tocando un
// solo campo —lo único que la regla deja—.
class AvisosTest {
    private fun doc(id: String, texto: String, creado: String, leido: Boolean = false, sitio: String = "casaverde") = JSONObject()
        .put("document", JSONObject().put("name", "projects/x/databases/(default)/documents/avisos/$id").put("fields", JSONObject()
            .put("uid", JSONObject().put("stringValue", "u1")).put("sitio", JSONObject().put("stringValue", sitio))
            .put("tema", JSONObject().put("stringValue", "llegada")).put("texto", JSONObject().put("stringValue", texto))
            .put("creadoEn", JSONObject().put("timestampValue", creado)).put("leido", JSONObject().put("booleanValue", leido))))

    @Test fun sePidenSóloLosMíos() {
        val q = consultaAvisos("u1").getJSONObject("structuredQuery")
        assertEquals("avisos", q.getJSONArray("from").getJSONObject(0).getString("collectionId"))
        val f = q.getJSONObject("where").getJSONObject("fieldFilter")
        assertEquals("uid", f.getJSONObject("field").getString("fieldPath"))
        assertEquals("u1", f.getJSONObject("value").getString("stringValue"))
    }

    @Test fun seLeenConDesconfianza_yLosNuevosPrimero() {
        val r = JSONArray().put(doc("a", "viejo", "2026-10-01T10:00:00Z")).put(doc("b", "nuevo", "2026-10-09T10:00:00Z"))
            .put(doc("c", "   ", "2026-10-09T11:00:00Z")).put(JSONObject().put("readTime", "x"))
        val a = avisosDeConsulta(r)
        assertEquals(listOf("b", "a"), a.map { it.id })
        assertEquals("casaverde", a[0].sitio); assertFalse(a[0].leido)
    }

    @Test fun seNotificaUnaVez_ySinLoViejo() {
        val ahora = java.time.Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
        val a = avisosDeConsulta(JSONArray().put(doc("a", "hace 5 días", "2026-10-04T10:00:00Z"))
            .put(doc("b", "recién", "2026-10-09T11:00:00Z")).put(doc("c", "leído", "2026-10-09T11:00:00Z", leido = true))
            .put(doc("d", "ya mostrado", "2026-10-09T11:00:00Z")))
        assertEquals(listOf("b"), paraNotificar(a, setOf("d"), ahora).map { it.id })
    }

    @Test fun marcarLeído_tocaUnSoloCampo() {
        val w = cuerpoLeido("projects/p/databases/(default)/documents", "a").getJSONArray("writes").getJSONObject(0)
        assertEquals("projects/p/databases/(default)/documents/avisos/a", w.getJSONObject("update").getString("name"))
        assertEquals(1, w.getJSONObject("updateMask").getJSONArray("fieldPaths").length())
        assertEquals("leido", w.getJSONObject("updateMask").getJSONArray("fieldPaths").getString(0))
        assertTrue(w.getJSONObject("currentDocument").getBoolean("exists"))
    }

    @Test fun losSitios_yUnaSesiónViejaEsDeTiempos() {
        assertEquals("tiempos", sitio(null).id)
        assertEquals("tiempos", sitio("panel").id)       // el panel no tiene cuenta: no se entra con él
        assertEquals("remate-acbc9", sitio("remate").proyecto)
        assertEquals(listOf("tiempos", "casaverde", "casayourte", "remate"), SITIOS.filter { it.cuenta }.map { it.id })
        assertEquals(CLAVE_WEB, sitio("tiempos").clave)
        for (s in SITIOS.filter { it.cuenta }) assertTrue(s.id, s.referer.startsWith("https://") && s.clave.startsWith("AIza"))
        assertEquals("CasaYourte", nombreSitio("casayourte")); assertEquals("gestos", nombreSitio("gestos"))
    }

    // Las claves web son las de cada sitio: si un sitio cambia la suya, esto
    // falla en vez de dejar a su gente sin avisos (sólo si el sitio está al lado).
    @Test fun lasClavesSonLasDeCadaSitio() {
        val archivos = mapOf("casaverde" to "../../casaverdecanas/interno/firebase-init.js", "casayourte" to "../../CasaYourte/firebase-init.js",
            "remate" to "../../remate/interno/utils.js")
        for ((id, ruta) in archivos) {
            val f = java.io.File(ruta)
            if (f.exists()) assertTrue("$id: la clave no coincide con $ruta", f.readText().contains(sitio(id).clave))
        }
    }
}
