package com.maurogasta.pizarra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** pizarra-82: el servidor del ecosistema, escrito UNA vez y fuera de Casa Verde. */
class ServidorTest {
    @Test fun lasTresFunciones_salenDeSERVIDOR() {
        assertEquals("$SERVIDOR/despertar", URL_DESPERTAR)
        assertEquals("$SERVIDOR/hielo", URL_HIELO)
        assertEquals("$SERVIDOR/luces-remate", PUENTE_LUCES)
        assertTrue(SERVIDOR.startsWith("https://") && SERVIDOR.endsWith("/.netlify/functions"))
    }

    @Test fun ningunArchivo_vuelveAEscribirUnaDireccionDeNetlify() {
        val dir = java.io.File("src/main/java/com/maurogasta/pizarra")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".kt") && it.name != "Servidor.kt" }) {
            val codigo = f.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }.joinToString("\n")
            assertFalse("${f.name} escribe una dirección de Netlify: va por SERVIDOR", codigo.contains(".netlify.app"))
        }
    }

    @Test fun esLaMismaDireccionQueLaDeLosSitios() {
        val f = java.io.File("../../datos/servidor-1.js")
        if (!f.exists()) return
        val suya = Regex("""export const SERVIDOR = "([^"]+)"""").find(f.readText())!!.groupValues[1]
        assertEquals(suya, SERVIDOR)
    }
}
