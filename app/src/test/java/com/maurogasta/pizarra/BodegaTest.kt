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

    @Test fun unTokenPegadoDeTermux_enRenglones_seJunta() {
        assertEquals("github_pat_11ABCdef_xyz", limpiarToken(" github_pat_11AB\nCdef_\r\nxyz \u200B"))
    }

    @Test fun elTokenVaAUnRepositorioSolo() {
        assertEquals("maurogasta-crypto/bodega", REPO_BODEGA)
    }

    // pizarra-34: los pedidos de alquiler en Canasvieiras de los grupos de Facebook.
    @Test fun facebook_sóloSiPideAlquilarEnCanasvieiras() {
        val fb = "com.facebook.katana"
        val p = pedidoDeFacebook(fb, "Aluguel Floripa", "Ana publicó: Busco aluguel em Canasvieiras para janeiro", emptyList(), 7)!!
        assertEquals("facebook", p.app)
        assertEquals("Aluguel Floripa", p.chat)
        // Las dos cosas, en cualquier idioma y con o sin acento.
        assertTrue(esPedidoDeAlquiler("ALQUILER en Canasvieiras"))
        assertTrue(esPedidoDeAlquiler("Alguém aluga casa em Canas?"))
        assertTrue(esPedidoDeAlquiler("Quero alugar perto de CANASVIEIRAS"))
        assertTrue(esPedidoDeAlquiler("alquilo depto, Canas, enero"))
        // Una sola no alcanza.
        assertFalse(esPedidoDeAlquiler("Fotos de Canasvieiras al atardecer"))
        assertFalse(esPedidoDeAlquiler("Busco alquiler en Ingleses"))
        // «canasta» no es Canas.
        assertFalse(esPedidoDeAlquiler("alquilo canastas para eventos"))
        // Lo que no es de Facebook no pasa por acá, y lo de Facebook no pasa por Airbnb.
        assertNull(pedidoDeFacebook("com.whatsapp", "x", "alquiler en Canasvieiras", emptyList(), 1))
        assertNull(mensajeDe(fb, "x", "alquiler en Canasvieiras", emptyList(), 1))
        assertNull(pedidoDeFacebook(fb, "Grupo", "", emptyList(), 1))
        // El grupo puede venir en el título y el pedido en las líneas.
        assertEquals("facebook", pedidoDeFacebook("com.facebook.lite", "Canasvieiras · alquileres", "Nueva publicación",
            listOf("Busco alquiler 2 personas"), 1)!!.app)
    }

    @Test fun facebook_mismaHuellaQueUnMensaje_peroOtraApp() {
        val fb = pedidoDeFacebook("com.facebook.katana", "Grupo", "aluguel Canasvieiras", emptyList(), 1)!!
        assertEquals(huellaMensaje("facebook", "Grupo", "aluguel Canasvieiras", emptyList()), fb.id)
        val j = mensajeAJson(fb, "2026-10-18T00:00:00.000Z")
        assertEquals("facebook", j.getString("app"))
        // El de Airbnb no cambió (la ronda lo reconoce por la misma huella).
        assertEquals("airbnb", mensajeDe(PAQUETE_AIRBNB, "Juan", "Hola", emptyList(), 5)!!.app)
        assertEquals("facebook", JSONObject(latidoJson("x")).getJSONArray("lee").getString(1))
    }

    // pizarra-36: el pedido va directo a pedidos_alquiler/ de Casa Verde, con
    // la forma exacta que deja crear la regla, y sin pisar uno que ya esté.
    @Test fun pedidoDeFacebook_vaALaListaDeCasaVerde_conLaFormaDeLaRegla() {
        val m = pedidoDeFacebook("com.facebook.katana", "Aluguel Floripa", "Ana publicou: Busco aluguel em Canasvieiras para janeiro, 4 pessoas!", listOf("Somos 2"), 1792300000000)!!
        val docs = "projects/casaverde-20/databases/(default)/documents"
        val w = cuerpoPedidoCasaVerde(docs, "u1", m, 5).getJSONArray("writes").getJSONObject(0)
        assertEquals("$docs/$COLECCION_PEDIDOS/${m.id}", w.getJSONObject("update").getString("name"))
        val f = w.getJSONObject("update").getJSONObject("fields")
        // Sólo los campos que la regla deja crear (creadoEn va por la transformación).
        assertTrue(CAMPOS_PEDIDO.containsAll(f.keys().asSequence().toList()))
        assertEquals("nuevo", f.getJSONObject("estado").getString("stringValue"))
        assertEquals("u1", f.getJSONObject("uid").getString("stringValue"))
        assertEquals("1792300000000", f.getJSONObject("cuando").getString("integerValue"))
        assertTrue(f.getJSONObject("texto").getString("stringValue").endsWith("Somos 2"))
        assertEquals(false, w.getJSONObject("currentDocument").getBoolean("exists"))
        // Sin hora en la notificación, la de ahora.
        val sinHora = pedidoDeFacebook("com.facebook.katana", "G", "alquiler Canasvieiras", emptyList(), 0)!!
        assertEquals("5", cuerpoPedidoCasaVerde(docs, "u1", sinHora, 5).getJSONArray("writes").getJSONObject(0)
            .getJSONObject("update").getJSONObject("fields").getJSONObject("cuando").getString("integerValue"))
    }

    // La MISMA búsqueda que enlaceBusqueda de datos/herramientas/facebook.mjs.
    @Test fun laBusqueda_esLaMismaQueLaDeDatos() {
        val u = enlaceBusqueda("Ana publicou: Busco aluguel em Canasvieiras para janeiro, 4 pessoas!")
        assertEquals("https://www.facebook.com/search/posts/?q=Busco%20aluguel%20em%20Canasvieiras%20para%20janeiro%204%20pessoas", u)
        assertEquals("", enlaceBusqueda("!!!"))
        assertEquals(12, java.net.URLDecoder.decode(enlaceBusqueda("a ".repeat(40)).substringAfter("q="), "UTF-8").split(" ").size)
    }

    @Test fun laRegla_deCasaVerde_dejaCrearEsosCampos_siElSitioEstáAlLado() {
        val f = java.io.File("../../casaverdecanas/interno/firestore.rules")
        if (!f.exists()) return
        val bloque = f.readText().substringAfter("match /pedidos_alquiler/{id}").substringBefore("\n    }")
        for (c in CAMPOS_PEDIDO) assertTrue("la regla no nombra «$c»", bloque.contains("'$c'"))
    }
}
