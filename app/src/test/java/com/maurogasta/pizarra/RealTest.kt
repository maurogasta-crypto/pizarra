package com.maurogasta.pizarra

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Con una respuesta REAL de la base, cuando la hay: PIZARRA_RESPUESTA=<archivo>
// apunta a lo que devolvió runQuery. Sin esa variable se saltea: el banco
// normal no depende de la red ni de credenciales.
class RealTest {
    @Test fun unaRespuestaRealSeLeeEntera() {
        val ruta = System.getenv("PIZARRA_RESPUESTA")
        assumeTrue(ruta != null && File(ruta).exists())
        val crudo = JSONArray(File(ruta!!).readText())
        val documentos = (0 until crudo.length()).count { crudo.getJSONObject(it).has("document") }
        val tareas = tareasDeConsulta(crudo, "nadie")
        println("REAL: $documentos documentos, ${tareas.size} tareas leídas, ${tareas.count { it.hecho }} hechas, ${tareas.count { it.parentId != null }} subtareas")
        assertEquals(documentos, tareas.size)
        assertEquals(0, paraLaPizarra(tareas).size)
    }
}
