package com.maurogasta.pizarra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// pizarra-24: las luces de remate desde el widget. Lo que se le manda al
// puente y al registro tiene que ser lo mismo que manda `luces.html`.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LucesTest {
    @Test fun laRespuestaDelPuente() {
        val l = lucesDeRespuesta("""{"ok":true,"luces":[
            {"alias":"entrada","label":"Entrada y taller","encendida":true,"enLinea":true},
            {"alias":"deposito","label":"Luces depósito","encendida":null,"enLinea":false},
            {"label":"sin alias"}]}""")
        assertEquals(2, l.size)
        assertEquals(true, l[0].encendida)
        assertNull("sin estado no es «apagada»", l[1].encendida)
        assertEquals(false, l[1].enLinea)
        assertTrue(lucesDeRespuesta("""{"ok":true}""").isEmpty())
    }

    @Test fun loQueSeLeMandaAlPuente() {
        val c = cuerpoLuz("deposito", true)
        assertEquals("deposito", c.getString("luz"))
        assertTrue(c.getBoolean("encender"))
        assertEquals(setOf("luz", "encender"), c.keys().asSequence().toSet())
    }

    @Test fun elRenglonDelRegistroEsElDeLucesHtml() {
        val docs = "projects/remate-acbc9/databases/(default)/documents"
        val w = cuerpoRegistroLuz(docs, "x1", "u1", "m@x", Luz("entrada", "Entrada y taller", false, true), true)
            .getJSONArray("writes").getJSONObject(0)
        val f = w.getJSONObject("update").getJSONObject("fields")
        assertEquals("$docs/lucesRegistro/x1", w.getJSONObject("update").getString("name"))
        assertEquals(setOf("uid", "nombre", "luz", "label", "encendida"), f.keys().asSequence().toSet())
        assertEquals("u1", f.getJSONObject("uid").getString("stringValue"))
        assertTrue(f.getJSONObject("encendida").getBoolean("booleanValue"))
        assertEquals("cuando", w.getJSONArray("updateTransforms").getJSONObject(0).getString("fieldPath"))
        assertFalse("nunca pisa un renglón: el registro es evidencia", w.getJSONObject("currentDocument").getBoolean("exists"))
    }

    @Test fun lasDosDelWidget() {
        assertEquals(listOf("entrada", "deposito"), LUCES_WIDGET.map { it.first })
        assertEquals("https://rematetaller.github.io", ORIGEN_REMATE)
        assertTrue(PUENTE_LUCES.endsWith("/.netlify/functions/luces-remate"))
    }

    // pizarra-25: el toque no pregunta antes si ya se sabe cómo estaba.
    @Test fun elToqueRapidoNoPreguntaSiYaSeSabe() {
        val l = Luces(RuntimeEnvironment.getApplication())
        assertNull("nunca se supo: el primer toque pregunta", l.proximo("entrada"))
        l.marcar("entrada", true)
        assertEquals(false, l.proximo("entrada"))
        assertEquals(false, l.aEncender("entrada"))   // sin red: no hizo falta preguntar
        l.marcar("entrada", false)
        assertEquals(true, l.aEncender("entrada"))
    }

    @Test fun laConexionNoSeCorta() {
        val f = java.io.File("src/main/java/com/maurogasta/pizarra/Luces.kt").readText()
        assertFalse("sin disconnect(): la conexión se reusa", f.contains("c.disconnect()"))
    }
}
