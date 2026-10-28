package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-77 (28-oct, Mauro: «limpiar todas las pruebas de comunicación que quedaron en registros de todos los
// teléfonos»): cuando el gestor de Claude limpia la charla, cada Pizarra la trae entera y lo borrado se va.
class LimpiezaTest {
    private val t = 1_800_000_000_000L

    @Test fun traeTodo_laPrimeraVez_cadaSeisHoras_yDespuesDeUnaLimpieza() {
        assertTrue(hayQueTraerTodo(0, t, t, "", ""))
        assertTrue(hayQueTraerTodo(10, t, t - 6 * 3600_000L - 1, "", ""))
        assertFalse(hayQueTraerTodo(10, t, t - 60_000L, "", ""))
        assertTrue("limpió después de lo último visto", hayQueTraerTodo(10, t, t - 60_000L, "2026-10-28T15:00:00Z", "2026-10-27T10:00:00Z"))
        assertTrue("nunca vista", hayQueTraerTodo(10, t, t - 60_000L, "2026-10-28T15:00:00Z", ""))
        assertFalse("esa limpieza ya se trajo", hayQueTraerTodo(10, t, t - 60_000L, "2026-10-28T15:00:00Z", "2026-10-28T15:00:00Z"))
    }

    @Test fun laMarca_seLeeDeLaRespuesta() {
        val r = JSONArray().put(JSONObject().put("found", JSONObject().put("fields", JSONObject()
            .put("limpiadoEn", JSONObject().put("stringValue", "2026-10-28T15:00:00Z")))))
        assertEquals("2026-10-28T15:00:00Z", limpiezaDeRespuesta(r))
        assertEquals("", limpiezaDeRespuesta(JSONArray().put(JSONObject().put("missing", "x"))))
    }

    @Test fun ponerAlDia_miraLaMarca() {
        val m = java.io.File("src/main/java/com/maurogasta/pizarra/Mensajes.kt").readText()
        assertTrue(m.contains("val limpiado = try { nube.limpiezaCharla() }"))
        assertTrue(m.contains(".putString(\"limpiezaVista\", limpiado)"))
    }
}
