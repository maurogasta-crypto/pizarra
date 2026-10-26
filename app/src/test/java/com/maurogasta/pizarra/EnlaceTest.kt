package com.maurogasta.pizarra

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

// pizarra-70: el enlace cerca (Enlace.kt). Lo que se prueba sobre todo es lo
// que NO se lee: sin la firma del código, viejo, repetido, con coordenadas.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnlaceTest {
    private val ahora = 1_800_000_000_000L
    private val llave = llaveDe("ABCDEFGHJK")

    private fun mensaje(t: Long = ahora, de: String = "Redmi Note 9", tipo: String = "evento") =
        JSONObject().put("v", VERSION_ENLACE).put("app", "hilux").put("tipo", tipo).put("t", t).put("de", de).put("texto", "hola").toString()

    @Test fun elCodigo_tieneDiezLetrasSinLasQueSeConfunden() {
        repeat(50) {
            val c = codigoNuevo()
            assertEquals(LARGO_CODIGO, c.length)
            assertFalse(c.any { it in "01OI" })
        }
    }

    @Test fun elCodigo_seLeeComoSeDicta() {
        assertEquals("ABCDEFGHJK", normalizarCodigo(" abcde-fghjk "))
        assertEquals("ABCDE-FGHJK", codigoParaLeer("ABCDEFGHJK"))
        assertNull(normalizarCodigo("ABCDE"))                 // corto
        assertNull(normalizarCodigo("ABCDEFGHJ0"))            // un cero no es del alfabeto
    }

    @Test fun laLlave_noEsElCodigo() {
        assertFalse(String(llave, Charsets.ISO_8859_1).contains("ABCDEFGHJK"))
        assertEquals(32, llave.size)
    }

    @Test fun unRenglonFirmado_seLee() {
        val ultimo = mutableMapOf<String, Long>()
        val o = leerRenglon(llave, renglonFirmado(llave, mensaje()), ahora, ultimo)
        assertNotNull(o)
        assertEquals("hola", o!!.getString("texto"))
        assertEquals(ahora, ultimo["Redmi Note 9"])
    }

    @Test fun conOtroCodigo_noSeLee() {
        val r = renglonFirmado(llaveDe("ZZZZZZZZZZ"), mensaje())
        assertNull(leerRenglon(llave, r, ahora, mutableMapOf()))
    }

    @Test fun tocadoEnElCamino_noSeLee() {
        val r = renglonFirmado(llave, mensaje()).replace("hola", "chau")
        assertNull(leerRenglon(llave, r, ahora, mutableMapOf()))
    }

    @Test fun viejoOAdelantado_noSeLee() {
        assertNull(leerRenglon(llave, renglonFirmado(llave, mensaje(t = ahora - VENTANA_MS - 1)), ahora, mutableMapOf()))
        assertNull(leerRenglon(llave, renglonFirmado(llave, mensaje(t = ahora + VENTANA_MS + 1)), ahora, mutableMapOf()))
    }

    @Test fun repetido_noSeLeeDosVeces() {
        val ultimo = mutableMapOf<String, Long>()
        val r = renglonFirmado(llave, mensaje())
        assertNotNull(leerRenglon(llave, r, ahora, ultimo))
        assertNull(leerRenglon(llave, r, ahora + 1000, ultimo))
        // de otro teléfono, el mismo instante sí
        assertNotNull(leerRenglon(llave, renglonFirmado(llave, mensaje(de = "Redmi 15")), ahora, ultimo))
    }

    @Test fun sinFormaDeRenglon_noSeLee() {
        assertNull(leerRenglon(llave, "basura", ahora, mutableMapOf()))
        assertNull(leerRenglon(llave, "x".repeat(LARGO_MAXIMO + 10), ahora, mutableMapOf()))
        val sinDe = JSONObject().put("v", VERSION_ENLACE).put("tipo", "evento").put("t", ahora).toString()
        assertNull(leerRenglon(llave, renglonFirmado(llave, sinDe), ahora, mutableMapOf()))
    }

    @Test fun loDeLaHilux_salesinCoordenadasNiTiposRaros() {
        val o = mensajeDeApp("hilux", """{"tipo":"vivo","kmh":82.4,"km":34.2,"lat":-34.9,"lon":-56.1,"Latitud":1,"posicion":"x","objeto":{"a":1}}""", "Note 9", ahora)!!
        assertFalse(o.has("lat")); assertFalse(o.has("lon")); assertFalse(o.has("Latitud")); assertFalse(o.has("posicion"))
        assertFalse(o.has("objeto"))
        assertEquals("hilux", o.getString("app"))
        assertEquals(ahora, o.getLong("t"))
        assertEquals("Note 9", o.getString("de"))
        assertNull(mensajeDeApp("hilux", """{"tipo":"borrar_todo"}""", "x", ahora))
        assertNull(mensajeDeApp("hilux", "no es json", "x", ahora))
    }

    @Test fun loDeLaHilux_noPisaQuienLoManda() {
        val o = mensajeDeApp("hilux", """{"tipo":"evento","de":"otro","t":1,"app":"falsa","v":9}""", "Note 9", ahora)!!
        assertEquals("Note 9", o.getString("de")); assertEquals(ahora, o.getLong("t"))
        assertEquals("hilux", o.getString("app")); assertEquals(VERSION_ENLACE, o.getInt("v"))
    }

    @Test fun elTextoEnVivo() {
        val o = JSONObject().put("app", "hilux").put("kmh", 82.4).put("rpm", 2104.0).put("marcha", 5).put("km", 34.25)
        assertEquals("🚙 82 km/h · 2.100 RPM · 5.ª · 34,3 km", textoVivo(o))
        assertEquals("📡 en vivo", textoVivo(JSONObject()))
    }

    @Test fun suenaSoloUnEvento() {
        assertTrue(suena(JSONObject().put("tipo", "evento")))
        assertFalse(suena(JSONObject().put("tipo", "vivo")))
        assertFalse(suena(JSONObject().put("tipo", "prueba")))
    }

    @Test fun laHuella_tieneLaFormaDeLaDeLaHilux() {
        val h = huellaDe(byteArrayOf(1, 2, 3))
        assertEquals(64, h.length)
        assertTrue(h.all { it in "0123456789ABCDEF" })
        assertEquals(64, HUELLA_HILUX.length)
    }

    @Test fun elManifiesto_declaraElServicioYLaPuerta() {
        val m = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map { File(it) }.first { it.exists() }.readText()
        assertTrue(m.contains("android:name=\".EnlaceServicio\""))
        assertTrue(m.contains("android:foregroundServiceType=\"connectedDevice\""))
        assertTrue(m.contains("android:authorities=\"$AUTORIDAD_ENLACE\""))
        assertTrue(m.contains("<package android:name=\"$PAQUETE_HILUX\""))
        // la puerta no se abre a la lectura: sólo insert
        assertFalse(m.contains("android:grantUriPermissions=\"true\" />\n        <!-- pizarra-70"))
    }

    @Test fun laPuerta_noAtiendeAOtraApp() {
        val p = Robolectric.buildContentProvider(EnlaceProveedor::class.java).create().get()
        assertNull(p.insert(android.net.Uri.parse("content://$AUTORIDAD_ENLACE/mensajes"),
            android.content.ContentValues().apply { put("mensaje", """{"tipo":"evento","texto":"x"}""") }))
        assertNull(p.query(android.net.Uri.parse("content://$AUTORIDAD_ENLACE/mensajes"), null, null, null, null))
    }

    @Test fun laPantalla_muestraElEnlaceYElCodigo() {
        Nube.sinRed = true
        val c = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("sesion", Context.MODE_PRIVATE).edit()
            .putString("refresh", "x").putString("uid", "uidPrueba").putString("mail", "a@b.c").commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        fun textos(v: View): List<String> = when (v) {
            is android.widget.TextView -> listOf(v.text.toString())
            is ViewGroup -> (0 until v.childCount).flatMap { textos(v.getChildAt(it)) }
            else -> emptyList()
        }
        fun botones(v: View): List<Button> = when (v) {
            is Button -> listOf(v)
            is ViewGroup -> (0 until v.childCount).flatMap { botones(v.getChildAt(it)) }
            else -> emptyList()
        }
        val t = textos(a.window.decorView)
        assertTrue(t.any { it.contains("📡 Enlace cerca") })
        assertTrue(t.any { it.startsWith("El código de este teléfono: ") && it.contains("-") })
        // Probar sin prender no manda nada y lo dice
        botones(a.window.decorView).first { it.text == "Probar" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(textos(a.window.decorView).any { it.contains("Primero prendé el enlace") })
        // Un código mal escrito no se guarda
        val antes = EnlaceAjustes.codigo(a)
        botones(a.window.decorView).first { it.text == "Usar ese código" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(antes, EnlaceAjustes.codigo(a))
    }
}
