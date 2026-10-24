package com.maurogasta.pizarra

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-60: el aviso instantáneo (general:C1, fase 2).
class InstantaneoTest {
    private fun src(n: String) = java.io.File("src/main/java/com/maurogasta/pizarra/$n").readText()

    @Test fun losTemas_sonLosDeLaBase_yCasaVerdeSiEntroAhi() {
        assertEquals(setOf("pizarra-tiempos"), temasPara("tiempos", false))
        assertEquals(setOf("pizarra-tiempos", "pizarra-casaverde"), temasPara("tiempos", true))
        assertEquals(setOf("pizarra-casaverde"), temasPara("casaverde", true))
        assertEquals(emptySet<String>(), temasPara(null, false))
    }

    @Test fun elPedido_llevaSoloBaseYQue_nuncaContenido() {
        val j = JSONObject(cuerpoDespertar("tiempos", "mensajes"))
        assertEquals(setOf("base", "que"), j.keys().asSequence().toSet())
    }

    @Test fun losQue_sonLosMismosQueLosDeLaFuncion() {
        val f = java.io.File("../../casaverdecanas/netlify/functions/despertar.mjs")
        if (!f.exists()) return
        val lista = Regex("""export const QUE = \[([^\]]*)]""").find(f.readText())!!.groupValues[1]
        assertEquals(QUE_CAMBIO, Regex("\"([a-z]+)\"").findAll(lista).map { it.groupValues[1] }.toSet())
        assertTrue(f.readText().contains("\"$PROYECTO_AVISOS\""))
    }

    @Test fun guardarAvisa_yRecibirPoneAlDiaTodo() {
        val nube = src("Nube.kt")
        assertTrue(nube.contains("avisarCambio(\"mensajes\")"))
        assertTrue(Regex("fun tachar[^\n]*avisarCambio\\(\"tareas\"\\)").containsMatchIn(nube))
        assertTrue(Regex("fun fijar[^\n]*avisarCambio\\(\"tareas\"\\)").containsMatchIn(nube))
        val i = src("Instantaneo.kt")
        assertTrue(i.substringAfter("class AvisoInstantaneo").contains("Despertar.todo("))
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains(".AvisoInstantaneo") && m.contains("com.google.firebase.MESSAGING_EVENT"))
    }

    @Test fun lasUnicasBibliotecas_sonFirebaseYWebRTC() {
        val g = java.io.File("build.gradle.kts").readText()
        val deps = Regex("""^\s*implementation\("([^"]+)"\)""", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1] }.toList()
        assertEquals(listOf("com.google.firebase:firebase-messaging:24.0.3", "io.getstream:stream-webrtc-android:1.3.10"), deps)
    }
}
