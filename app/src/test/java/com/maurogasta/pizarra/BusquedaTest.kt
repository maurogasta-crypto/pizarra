package com.maurogasta.pizarra

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-46: «Qué busco ahora». Los casos son las publicaciones que trajo el
// primer recorrido real (20-oct), reescritas sin nombres: lo que importa es que
// el lector separe lo que Mauro busca de lo que no, sin preguntar nada.
class BusquedaTest {
    private val siempre = Busqueda()     // por días, hasta 20, Canasvieiras y el norte, cualquiera

    @Test fun cuadra_lo_que_se_busca_y_dice_por_qué() {
        val e = encaje("Estoy buscando un hospedaje en ingléses o alrededores para 4 adultos y 3 criaturas 04/01 al 12/01", siempre)
        assertTrue(e.motivo, e.ok); assertEquals(7, e.personas); assertEquals("familias", e.publico)
        assertTrue(encaje("Buenas, que recomiendan para familia casnavieras? Ingleses? Para semana santa, alojamiento", siempre).ok)
    }

    @Test fun lo_que_no_cuadra_no_es_pedido() {
        assertEquals("otra zona", encaje("Busco departamento para bombinhas, me pueden pasar más información", siempre).motivo)
        assertEquals("son 13, más de 12", encaje("Buscamos casa en Ingleses para 10 adultos y 3 criaturas", siempre.copy(personasMax = 12)).motivo)
        assertEquals("busca alquiler anual", encaje("BUSCO ALQUILER ANUAL EN CANASVIEIRAS O ALREDEDORES a partir de noviembre", siempre).motivo)
        assertFalse(encaje("Alquilo mi casa en Canasvieiras del 1 de noviembre al 20, consulte disponibilidad", siempre).ok)
        assertFalse(encaje("Busco alquilar una H1 o Voxy para viajar a Floripa", siempre).ok)
        assertFalse(encaje("Alguien sabe cómo está el tráfico camino a Canasvieiras en noviembre?", siempre).ok)
    }

    @Test fun lo_que_elige_Mauro_manda() {
        val anual = "Busco alquiler anual en Canasvieiras a partir de fines de noviembre"
        assertTrue(encaje(anual, siempre.copy(tipos = setOf("diaria", "anual"))).ok)
        val jovenes = "Somos 6 amigos y buscamos casa en Canasvieiras para carnaval"
        assertEquals("público: jovenes", encaje(jovenes, siempre.copy(publico = setOf("familias"))).motivo)
        assertTrue(encaje(jovenes, siempre.copy(publico = setOf("familias", "jovenes"))).ok)
        assertEquals("otra zona", encaje("Busco depto en Campeche para enero", siempre).motivo)
        assertTrue(encaje("Busco depto en Florianópolis para enero, somos 4", siempre.copy(zonas = setOf("florianopolis"))).ok)
        assertFalse(encaje("Busco casa en Canasvieiras", siempre.copy(activa = false)).ok)
    }

    @Test fun lo_que_no_se_sabe_no_descarta() {
        val e = encaje("Algún contacto de dueño de departamento o casa en Canasvieiras?", siempre.copy(publico = setOf("familias"), personasMax = 5))
        assertTrue(e.motivo, e.ok); assertEquals(0, e.personas)
    }

    @Test fun cuántos_son() {
        assertEquals(13, cuantasPersonas("somos 3 familias.. 7 adultos 6 niños"))
        assertEquals(15, cuantasPersonas("Somos una familia de 15 personas"))
        assertEquals(4, cuantasPersonas("somos 4 y un perro"))
        assertEquals(0, cuantasPersonas("del 18 al 25 de enero"))
        // pizarra-54: con letras
        assertEquals(2, cuantasPersonas("Hola, busco alquiler para dos personas en canasvieiras"))
        assertEquals(8, cuantasPersonas("somos oito pessoas"))
    }

    @Test fun la_respuesta_rápida_siempre_lleva_la_página_y_no_se_repite() {
        val e = Encaje(true, "cuadra", 7, "diaria", "familias")
        val rs = (0..2).map { respuestaRapida("Pablo Gimenez", "Busco hospedaje en Ingleses para 7", e, it) }
        assertTrue(rs.all { it.contains(SITIO_WEB) }); assertTrue(rs.all { it.contains("Pablo") })
        assertEquals(3, rs.toSet().size); assertEquals(3, rs.map { it.take(6) }.toSet().size)
        val grande = respuestaRapida("", "Buscamos casa para 18 personas en Canasvieiras", Encaje(true, "", 18), 1)
        assertTrue(grande.contains("hasta 20")); assertFalse(grande.contains("Hola !"))
        val pt = respuestaRapida("Ana", "Olá, estamos procurando casa em Canasvieiras para 4 pessoas", Encaje(true, "", 4), 0)
        assertTrue(pt.startsWith("Olá Ana!")); assertTrue(pt.contains("pessoas")); assertTrue(pt.contains(SITIO_WEB))
        // un encabezado que no es un nombre (un grupo, un botón) no va como saludo
        assertTrue(respuestaRapida("aluguel temporada", "x", e, 0).startsWith("¡Hola!"))
    }

    @Test fun la_búsqueda_se_lee_del_documento_y_sin_documento_queda_la_de_siempre() {
        val f = JSONObject("""{"tipos":{"arrayValue":{"values":[{"stringValue":"mensual"}]}},"personasMax":{"integerValue":"12"},
            "publico":{"arrayValue":{}},"zonas":{"arrayValue":{"values":[{"stringValue":"canasvieiras"}]}},"activa":{"booleanValue":true}}""")
        val b = busquedaDeCampos(f)
        assertEquals(setOf("mensual"), b.tipos); assertEquals(12, b.personasMax); assertTrue(b.publico.isEmpty()); assertEquals(setOf("canasvieiras"), b.zonas)
        assertEquals(Busqueda(), busquedaDeCampos(null))
        assertEquals(null, camposDeBatchGet("""[{"missing":"projects/p/databases/(default)/documents/pedidos_busqueda/actual"}]"""))
        assertEquals("12", camposDeBatchGet("""[{"found":{"fields":{"personasMax":{"integerValue":"12"}}}}]""")!!.getJSONObject("personasMax").getString("integerValue"))
    }

    @Test fun la_regla_de_Casa_Verde_conoce_los_mismos_campos_si_el_sitio_está_al_lado() {
        val f = java.io.File("../../casaverdecanas/interno/firestore.rules")
        if (!f.exists()) return
        val bloque = f.readText().substringAfter("match /pedidos_busqueda/{id}").substringBefore("\n    }")
        for (c in listOf("tipos", "personasMax", "publico", "zonas", "nota", "activa")) assertTrue("la regla no nombra «$c»", bloque.contains("'$c'"))
        val pagina = java.io.File("../../casaverdecanas/interno/pedidos-alquiler.html").readText()
        for (v in listOf("diaria", "mensual", "anual", "familias", "parejas", "jovenes", "grupos", "canasvieiras", "norte", "florianopolis"))
            assertTrue("la página no ofrece «$v»", pagina.contains("data-v=\"$v\""))
    }
}
