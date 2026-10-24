package com.maurogasta.pizarra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-61/62: las conversaciones (por persona; mis teléfonos, uno por uno) y las pestañas del widget.
class ConversacionesTest {
    private val docs = "projects/tiempos-71d42/databases/(default)/documents"
    private fun m(id: String, de: String, nombre: String, tel: String, para: String, t: String) =
        Mensaje(id, de, nombre, tel, "texto $id", "2026-10-24T12:$t:00Z", para)

    // Mauro tiene el 15 y el 13C; Florencia, el Note.
    private val ms = listOf(
        m("1", "flor", "Flor", "Note", "", "00"),                         // a todos
        m("2", "flor", "Flor", "Note", "yo|Redmi 15", "01"),              // a mi 15
        m("3", "yo", "Mauro", "Redmi 13C", "flor|Note", "02"),            // del 13C a Flor
        m("4", "yo", "Mauro", "Redmi 15", "flor|Note", "03"),             // del 15 a Flor
        m("5", "yo", "Mauro", "Redmi 13C", "yo|Redmi 15", "04"),          // del 13C a mi 15
        m("6", "yo", "Mauro", "Redmi 15", "yo|Redmi 13C", "05"),          // del 15 a mi 13C
    ).sortedByDescending { it.creado }

    @Test fun conFlorencia_esLaMismaDesdeCualquieraDeMisTelefonos() {
        val desde15 = conversaciones(ms, "yo", "Redmi 15")
        val flor = desde15.first { it.clave == "flor|*" }
        assertEquals(listOf("2", "3", "4"), ms.filter { enConversacion(it, flor, "yo", "Redmi 15") }.map { it.id }.sorted())
        val flor13 = conversaciones(ms, "yo", "Redmi 13C").first { it.clave == "flor|*" }
        assertEquals(listOf("2", "3", "4"), ms.filter { enConversacion(it, flor13, "yo", "Redmi 13C") }.map { it.id }.sorted())
    }

    @Test fun conMiOtroTelefono_esSoloEntreEsosDos_yEsteNoEsPestana() {
        val cs = conversaciones(ms, "yo", "Redmi 15")
        assertEquals(setOf("flor|*", "yo|Redmi 13C"), cs.map { it.clave }.toSet())
        val trece = cs.first { it.clave == "yo|Redmi 13C" }
        assertEquals(listOf("5", "6"), ms.filter { enConversacion(it, trece, "yo", "Redmi 15") }.map { it.id }.sorted())
        assertEquals("📱 Redmi 13C", etiquetaConversacion(trece, cs, "yo"))
        assertEquals("Flor", etiquetaConversacion(cs.first { it.clave == "flor|*" }, cs, "yo"))
    }

    @Test fun desdeElLadoDeFlorencia_MauroEsUnaSolaPestana_conTodosSusTelefonos() {
        val cs = conversaciones(ms, "flor", "Note")
        assertEquals(setOf("yo|*"), cs.map { it.clave }.toSet())
        assertEquals("Mauro", etiquetaConversacion(cs.single(), cs, "flor"))
        // Lo que Mauro le escribió desde los dos teléfonos y lo que ella le mandó; lo de Mauro con su 13C no es de ella.
        assertEquals(listOf("2", "3", "4"), ms.filter { enConversacion(it, cs.single(), "flor", "Note") }.map { it.id }.sorted())
    }

    @Test fun loQueLeEscriboALaPersona_leLlegaATodosSusTelefonos() {
        val a = Mensaje("x", "yo", "Mauro", "Redmi 15", "hola", "2026-10-24T12:30:00Z", "flor|*")
        assertTrue(esParaMi(a, "flor", "Note")); assertTrue(esParaMi(a, "flor", "Otro"))
        assertFalse(esParaMi(a, "yo", "Redmi 13C"))
        assertEquals("Yo · Redmi 15 → Flor", firmaMensaje(a, "yo", "Redmi 13C", mapOf("flor" to "Flor")))
        assertEquals("Mauro · Redmi 15", firmaMensaje(a, "flor", "Note"))
    }

    @Test fun seAvisaSoloLoQueEsParaMi() {
        assertTrue(esParaMi(ms.first { it.id == "1" }, "yo", "Redmi 13C"))      // a todos
        assertTrue(esParaMi(ms.first { it.id == "2" }, "yo", "Redmi 13C"))      // Flor a mi otro teléfono: también es para mí
        assertTrue(esParaMi(ms.first { it.id == "6" }, "yo", "Redmi 13C"))      // mi 15 a este
        assertFalse(esParaMi(ms.first { it.id == "5" }, "yo", "Redmi 13C"))     // este a mi 15
        assertFalse(esParaMi(ms.first { it.id == "3" }, "flor2", "Otro"))
        val nuevos = mensajesParaAvisar(ms, "yo", "Redmi 13C", emptySet(), instante("2026-10-24T13:00:00Z")!!)
        assertEquals(listOf("1", "2", "6"), nuevos.map { it.id }.sorted())
    }

    @Test fun lasPestanasDelWidget_sonTresYLaElegidaSiempreEsta() {
        val cs = (1..5).map { Conversacion("u$it|t", "u$it", "t", "P$it", "2026-10-24T12:0$it:00Z") }.sortedByDescending { it.ultima }
        assertEquals(listOf("u5|t", "u4|t", "u3|t"), pestanasDelWidget(cs, "").map { it.clave })
        assertEquals(listOf("u5|t", "u4|t", "u1|t"), pestanasDelWidget(cs, "u1|t").map { it.clave })
    }

    @Test fun elMensajeConPara_llevaElCampo_ySinParaNo() {
        val con = cuerpoMensaje(docs, "m1", "yo", "Mauro", "Redmi 15", "hola", "flor|Note").getJSONArray("writes").getJSONObject(0)
            .getJSONObject("update").getJSONObject("fields")
        assertEquals("flor|Note", con.getJSONObject("para").getString("stringValue"))
        val sin = cuerpoMensaje(docs, "m1", "yo", "Mauro", "Redmi 15", "hola").getJSONArray("writes").getJSONObject(0)
            .getJSONObject("update").getJSONObject("fields")
        assertFalse(sin.has("para"))
        val r = java.io.File("../../tiempos/firestore.rules")
        if (!r.exists()) return
        val bloque = r.readText().substringAfter("match /mensajes/{id}").substringBefore("\n    }")
        assertTrue("la regla v17 no nombra «para»", bloque.contains("'para'"))
    }

    @Test fun laFirma_diceAQuienVa() {
        assertEquals("Flor · Note → este teléfono", firmaMensaje(ms.first { it.id == "2" }, "yo", "Redmi 15"))
        assertEquals("Flor · Note → Redmi 15", firmaMensaje(ms.first { it.id == "2" }, "yo", "Redmi 13C"))
    }

    @Test fun laCharlaSeAcomodaAlTeclado_yLosBotonesNoSeDeforman() {
        // pizarra-65 (Mauro: «cuando aparece el teclado no se reajusta… el botón de mandar quedó deformado»).
        val src = java.io.File("src/main/java/com/maurogasta/pizarra/Mensajes.kt").readText().substringAfter("class CharlaActivity").substringBefore("private fun pintar()")
        assertTrue("la lista toma lo que sobra", src.contains("caja.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))"))
        assertTrue("la ventana tiene alto fijo", !src.contains("WindowManager.LayoutParams.WRAP_CONTENT)"))
        assertTrue(src.contains("SOFT_INPUT_ADJUST_RESIZE"))
        assertTrue("botones de ancho fijo", src.contains("LinearLayout.LayoutParams(dp(44), dp(44))"))
        assertTrue(!src.contains("text = \"Mandar\"") && !src.contains("text = \"Cerrar\""))
    }
}
