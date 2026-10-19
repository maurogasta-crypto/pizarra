package com.maurogasta.pizarra

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-42: TODAS las notificaciones van a Casa Verde para que la ronda las
// clasifique. Lo que importa: que un código de verificación no viaje nunca, que
// no se tape lo que no es un código, y que el documento tenga la forma exacta
// que deja la regla.
class NotificacionesTest {
    @Test fun unCódigo_seTapa_yQuedaQueHabíaUno() {
        assertEquals("Tu código de verificación es 48••••", taparCodigos("Tu código de verificación es 482913"))
        assertEquals("G-48•••• is your Google verification code", taparCodigos("G-482913 is your Google verification code"))
        assertEquals("Código Airbnb: 12 •••", taparCodigos("Código Airbnb: 12 345"))
        assertEquals("Seu código de acesso: 98••", taparCodigos("Seu código de acesso: 9876"))
        assertEquals("Use 55••••• como senha temporária", taparCodigos("Use 5512345 como senha temporária"))
    }

    @Test fun loQueNoEsUnCódigo_noSeToca() {
        // sin la palabra al lado, un número es un número
        assertEquals("Llegamos a las 1930 al aeropuerto", taparCodigos("Llegamos a las 1930 al aeropuerto"))
        assertEquals("Pagaste 1250 en el súper", taparCodigos("Pagaste 1250 en el súper"))
        // con la palabra, un monto con puntos o una hora no se tapan
        assertEquals("Código de compra: total R$ 1.250,00 a las 14:30", taparCodigos("Código de compra: total R$ 1.250,00 a las 14:30"))
        assertEquals("Tu clave vence en 30 días", taparCodigos("Tu clave vence en 30 días"))
    }

    @Test fun seToma_todo_menos_loQueNoTieneNada_yLaPropia() {
        assertNull(notifDe("com.maurogasta.pizarra", "Pizarra", "Al día", emptyList(), 1, "com.maurogasta.pizarra"))
        assertNull(notifDe("com.whatsapp", "", "  ", listOf(" "), 1, "x"))
        assertNull(notifDe(null, "Juan", "Hola", emptyList(), 1, "x"))
        val w = notifDe("com.whatsapp", "Juan", "Hola, ¿tienen lugar en enero?", emptyList(), 5, "x")!!
        assertEquals("com.whatsapp", w.app); assertEquals("Juan", w.titulo)
        // un banco entra igual, con el código tapado
        val b = notifDe("com.itau", "Itaú", "Seu código é 774411", emptyList(), 5, "x")!!
        assertFalse(b.texto.contains("774411")); assertTrue(b.texto.startsWith("Seu código é 77"))
        // también en las líneas y en el título
        val l = notifDe("com.google.android.gm", "Código 123456", "x", listOf("verification code 998877"), 5, "x")!!
        assertFalse(l.titulo.contains("123456")); assertFalse(l.lineas[0].contains("998877"))
    }

    @Test fun losLargosSeCortan_yLaHoraNoEntraEnLaHuella() {
        val n = notifDe("a.b", "x".repeat(500), "y".repeat(9000), List(30) { "z".repeat(2000) }, 7, "x")!!
        assertEquals(200, n.titulo.length); assertEquals(4000, n.texto.length)
        assertEquals(20, n.lineas.size); assertEquals(1000, n.lineas[0].length)
        assertEquals(notifDe("a.b", "J", "H", emptyList(), 1, "x")!!.id, notifDe("a.b", "J", "H", emptyList(), 9, "x")!!.id)
    }

    @Test fun elDocumento_llevaSóloLosCamposDeLaRegla_yNoPisa() {
        val n = notifDe("com.whatsapp", "Juan", "Hola", listOf("a", "b"), 1791000000000, "x")!!
        val w = cuerpoNotificacion("projects/p/databases/(default)/documents", "uid1", n, "Xiaomi 23100RN82L", 5)
            .getJSONArray("writes").getJSONObject(0)
        val f = w.getJSONObject("update").getJSONObject("fields")
        val campos = f.keys().asSequence().toList() + "creadoEn"
        assertTrue(CAMPOS_NOTIFICACION.containsAll(campos)); assertTrue(campos.containsAll(CAMPOS_NOTIFICACION))
        assertTrue(w.getJSONObject("update").getString("name").endsWith("/$COLECCION_NOTIFICACIONES/${n.id}"))
        assertFalse(w.getJSONObject("currentDocument").getBoolean("exists"))
        assertEquals("1791000000000", f.getJSONObject("cuando").getString("integerValue"))
        assertEquals(2, f.getJSONObject("lineas").getJSONObject("arrayValue").getJSONArray("values").length())
    }

    @Test fun losPendientes_noSeRepiten_yTienenTecho() {
        val a = notifDe("a", "J", "1", emptyList(), 1, "x")!!; val b = notifDe("a", "J", "2", emptyList(), 1, "x")!!
        val una = sumarPendientes(JSONArray(), listOf(a, b, a))
        assertEquals(2, una.length())
        assertEquals(2, sumarPendientes(una, listOf(b)).length())
        val muchas = (1..50).map { notifDe("a", "J", "$it", emptyList(), 1, "x")!! }
        val cortada = sumarPendientes(JSONArray(), muchas, techo = 10)
        assertEquals(10, cortada.length()); assertEquals("50", cortada.getJSONObject(9).getString("texto"))
        assertEquals(a.id, notifDeJson(notifAJson(a))!!.id)
    }

    @Test fun laRegla_deCasaVerde_dejaCrearEsosCampos_siElSitioEstáAlLado() {
        val f = java.io.File("../../casaverdecanas/interno/firestore.rules")
        if (!f.exists()) return
        val bloque = f.readText().substringAfter("match /notificaciones/{id}").substringBefore("\n    }")
        assertTrue("falta el bloque de notificaciones en la regla", bloque.length > 20)
        for (c in CAMPOS_NOTIFICACION) assertTrue("la regla no nombra «$c»", bloque.contains("'$c'"))
    }
}
