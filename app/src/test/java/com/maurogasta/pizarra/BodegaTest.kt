package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-9: la Pizarra hace lo que hacía `telefono-whatsapp.mjs` en Termux.
// Lo que importa es que la ronda no note la diferencia (mismo formato, misma
// huella) y que no suba ni anote lo que no tiene que subir.
class BodegaTest {
    @Test fun sóloAirbnb_yNuncaUnResumen() {
        assertNull(mensajeDe("com.whatsapp", "Juan", "Hola", emptyList(), 1))
        assertNull(mensajeDe(PAQUETE_AIRBNB, "Airbnb", "Tenés novedades", emptyList(), 1))
        assertNull(mensajeDe(PAQUETE_AIRBNB, "Juan", "3 mensajes de 2 chats", emptyList(), 1))
        assertNull(mensajeDe(PAQUETE_AIRBNB, "Juan", "", emptyList(), 1))
        assertNull(mensajeDe(PAQUETE_AIRBNB, "Juan", "Buscando nuevos mensajes", emptyList(), 1))
        val m = mensajeDe(PAQUETE_AIRBNB, "Juan", "Hola", emptyList(), 1791000000000)!!
        assertEquals("airbnb", m.app); assertEquals("1791000000000", m.cuando)
    }

    @Test fun laHuella_esLaMismaQueLaDeTermux() {
        // Calculadas con `huella` de datos/herramientas/telefono-whatsapp.mjs.
        assertEquals("66e4c11e6e9731dd57bd", huellaMensaje("airbnb", "Natalia", "¿A qué hora es el check-in?", listOf("línea 1", "línea 2")))
        assertEquals("93fdda9d6acd25e7e72e", mensajeDe(PAQUETE_AIRBNB, "Juan", "Hola", emptyList(), 5)!!.id)
        // la hora no entra: Airbnb reescribe la misma notificación con otra hora
        assertEquals(mensajeDe(PAQUETE_AIRBNB, "Juan", "Hola", emptyList(), 5)!!.id, mensajeDe(PAQUETE_AIRBNB, "Juan", "Hola", emptyList(), 9)!!.id)
    }

    @Test fun losLargosSeCortan_comoEnTermux() {
        val m = mensajeDe(PAQUETE_AIRBNB, "x".repeat(500), "y".repeat(9000), List(30) { "z".repeat(2000) }, 0)!!
        assertEquals(120, m.chat.length); assertEquals(4000, m.texto.length)
        assertEquals(20, m.lineas.size); assertEquals(1000, m.lineas[0].length); assertEquals("", m.cuando)
    }

    @Test fun alArchivoDelDía_seSuma_sinRepetir() {
        val a = mensajeDe(PAQUETE_AIRBNB, "Juan", "Hola", emptyList(), 1)!!
        val b = mensajeDe(PAQUETE_AIRBNB, "Ana", "Llegamos 19 h", emptyList(), 2)!!
        val previos = JSONArray().put(mensajeAJson(a, "2026-10-06T10:00:00.000Z"))
        val (todo, n) = unirMensajes(previos, listOf(a, b, b), "2026-10-06T21:00:00.000Z")
        assertEquals(1, n); assertEquals(2, todo.length())
        val o = todo.getJSONObject(1)
        for (k in listOf("id", "chat", "texto", "lineas", "cuando", "app", "captado")) assertTrue(k, o.has(k))
        assertEquals("2026-10-06T21:00:00.000Z", o.getString("captado"))
        assertEquals(0, unirMensajes(todo, listOf(a, b), "x").second)
    }

    @Test fun elLatido_tieneLaFormaQueLeeLaRonda() {
        val ok = JSONObject(latidoJson("2026-10-06T21:00:00.000Z"))
        assertEquals("2026-10-06T21:00:00.000Z", ok.getString("ultimo"))
        assertEquals("airbnb", ok.getJSONArray("lee").getString(0))
        assertFalse(ok.has("falla"))
        val mal = JSONObject(latidoJson("x", "no deja " + "a".repeat(400)))
        assertEquals(200, mal.getString("falla").length)
        assertEquals("2026-10-06T21:00:00.000Z", isoUtc(java.time.Instant.parse("2026-10-06T21:00:00Z").toEpochMilli()))
        assertEquals("2026-10-06T21:00:00.123Z", isoUtc(java.time.Instant.parse("2026-10-06T21:00:00.123Z").toEpochMilli()))
    }

    @Test fun elLector_estáDeclaradoComoPideAndroid() {
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        val bloque = m.substringAfter("android:name=\".LectorAirbnb\"").substringBefore("</service>")
        assertTrue(bloque.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(bloque.contains("android.service.notification.NotificationListenerService"))
    }

    // El texto de un mensaje es de un huésped: a la bitácora (que viaja en el
    // diagnóstico) va cuántos y si subió, nunca qué decía.
    @Test fun laBitácora_nuncaLlevaElTextoDeUnMensaje() {
        val src = java.io.File("src/main/java/com/maurogasta/pizarra/Bodega.kt").readLines()
        val anota = src.filter { it.contains("Bitacora.anotar") }
        assertTrue(anota.isNotEmpty())
        for (l in anota) assertFalse(l, Regex("\\.(texto|chat|lineas)\\b|titulo|\\bms\\b(?!\\.size)").containsMatchIn(l))
    }

    @Test fun elTokenVaAUnRepositorioSolo() {
        assertEquals("maurogasta-crypto/bodega", REPO_BODEGA)
    }
}
