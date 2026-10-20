package com.maurogasta.pizarra

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

// pizarra-49: la respuesta tiene que coincidir con la ocupación de Casa Verde.
// La ocupación de abajo es la de la base el 20-oct-2026 (sin nombres), y los
// pedidos son los de esa mañana: el del 13 al 21 de enero ofrecía lugar y no había.
class DisponibilidadTest {
    private val hoy = LocalDate.of(2026, 10, 20)
    private fun o(c: String, d: String, h: String) = Ocupacion(c, LocalDate.parse(d), LocalDate.parse(h))
    private val ocup = listOf(
        o("c1", "2026-11-01", "2026-11-30"), o("c1", "2026-12-28", "2027-01-03"), o("c1", "2027-01-03", "2027-01-09"),
        o("c1", "2027-01-09", "2027-01-14"), o("c1", "2027-01-14", "2027-01-24"), o("c1", "2027-02-01", "2027-02-09"),
        o("c2", "2026-11-27", "2026-11-29"), o("c2", "2026-12-20", "2026-12-28"), o("c2", "2026-12-28", "2026-12-30"),
        o("c2", "2027-01-03", "2027-01-12"), o("c2", "2027-01-12", "2027-01-21"), o("c2", "2027-02-01", "2027-02-09"),
        o("c3", "2026-12-20", "2026-12-28"), o("c3", "2026-12-28", "2027-01-03"), o("c3", "2027-01-14", "2027-01-29"),
        o("c3", "2027-02-01", "2027-02-09"))
    private val cabanas = listOf(CabanaCV("c1", 8), CabanaCV("c2", 7), CabanaCV("c3", 5))
    private fun lugar(t: String, personas: Int = cuantasPersonas(t)) = lugarPara(t, personas, ocup, cabanas, hoy)

    @Test fun las_fechas_se_leen_como_las_escribe_la_gente() {
        assertEquals(Pedido.Rango(LocalDate.of(2027, 1, 13), LocalDate.of(2027, 1, 21)), fechasPedidas("canasvieiras del 13.01 al 21.01 para 4", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 23)), fechasPedidas("busco alquiler en enero 10 al 23 7 personas", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 23)), fechasPedidas("del 10 al 23 de enero", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)), fechasPedidas("del 28 de diciembre al 3 de enero", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)), fechasPedidas("28/12 al 03/01", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2027, 3, 16), LocalDate.of(2027, 4, 1)), fechasPedidas("Hola busco alquiler en canas vieiras para la segunda quincena de marzo 2027", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 16)), fechasPedidas("Busco departamento en la primer quincena de marzo", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2027, 1, 5), LocalDate.of(2027, 1, 12)), fechasPedidas("de 5 a 12 de janeiro", hoy))
        assertEquals(Pedido.Mes(YearMonth.of(2027, 2)), fechasPedidas("buscamos casa para febrero", hoy))
        assertEquals(null, fechasPedidas("Busco casa en Canasvieiras, alguien tiene?", hoy))
        // pizarra-50: un año que ya pasó es un error de tipeo, visto el 20-oct («24/01/24 a 31/01/24»)
        assertEquals(Pedido.Rango(LocalDate.of(2027, 1, 24), LocalDate.of(2027, 1, 31)), fechasPedidas("para 2 personas para 24/01/24 a 31/01/24", hoy))
    }

    @Test fun las_fechas_con_nombre() {
        assertEquals(LocalDate.of(2027, 3, 28), pascua(2027)); assertEquals(LocalDate.of(2026, 4, 5), pascua(2026))
        assertEquals(Pedido.Rango(LocalDate.of(2027, 3, 25), LocalDate.of(2027, 3, 29)), fechasPedidas("para semana santa", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2027, 2, 5), LocalDate.of(2027, 2, 10)), fechasPedidas("vamos en carnaval", hoy))
        assertEquals(Pedido.Rango(LocalDate.of(2026, 12, 29), LocalDate.of(2027, 1, 2)), fechasPedidas("para año nuevo", hoy))
    }

    @Test fun el_del_13_al_21_de_enero_no_tiene_lugar() {
        val l = lugar("Busco alquiler en canasvieiras del 13.01 al 21.01 para 4 personas")
        assertTrue(l is Lugar.Lleno); assertFalse((l as Lugar.Lleno).porCupo)
        assertTrue(lugar("busco alquiler en enero 10 al 23 7 personas") is Lugar.Lleno)
        assertTrue(lugar("28/12 al 03/01 somos 2") is Lugar.Lleno)
        assertTrue(lugar("vamos en carnaval, somos 4") is Lugar.Lleno)
    }

    @Test fun cuando_hay_lugar_se_elige_lo_que_alcanza() {
        val santiago = lugar("Busco departamento para alquilar en la primer quincena de marzo, para 2 personas") as Lugar.Hay
        assertEquals(listOf("c3"), santiago.cabanas.map { it.id })               // la más chica que alcanza
        val grupo = lugar("Somos 18 personas, buscamos del 15 al 20 de marzo") as Lugar.Hay
        assertEquals(3, grupo.cabanas.size)                                         // las tres juntas: 8 + 7 + 5
        val l = lugar("Somos 25 personas, del 15 al 20 de marzo")
        assertTrue(l is Lugar.Lleno && l.porCupo)                                   // libres, pero no alcanzan
        assertTrue(lugar("buscamos casa para febrero, somos 4") is Lugar.MesConLugar)
        assertTrue(lugar("Busco casa en Canasvieiras") is Lugar.SinFechas)
    }

    @Test fun la_respuesta_dice_las_fechas_y_la_página() {
        val t = "Hola busco alquiler en canas vieiras para la segunda quincena de marzo 2027"
        val r = respuestaRapida("Susana Azzollini", t, Encaje(true, ""), 0, lugar(t, 0))
        assertTrue(r, r.contains("Tenemos lugar del 16 de marzo al 1 de abril")); assertTrue(r.contains(SITIO_WEB)); assertTrue(r.contains("Susana"))
        val g = "Somos 18 personas, buscamos del 15 al 20 de marzo"
        val rg = respuestaRapida("", g, Encaje(true, "", 18), 1, lugar(g))
        assertTrue(rg, rg.contains("3 cabañas juntas para los 18"))
        val pt = "Olá, estamos procurando casa em Canasvieiras de 5 a 12 de março para 4 pessoas"
        val rp = respuestaRapida("Ana", pt, Encaje(true, "", 4), 0, lugar(pt))
        assertTrue(rp, rp.contains("Temos lugar de 5 a 12 de março")); assertTrue(rp.contains(SITIO_WEB))
    }

    // pizarra-51: sin lugar, la respuesta dice lo que SÍ se puede ofrecer.
    @Test fun sin_lugar_se_ofrece_lo_que_sí_hay() {
        val pub = Publicacion("Juan", "Busco alquiler en canasvieiras del 13.01 al 21.01 para 4 personas, algo de confianza")
        val p = pedidoDeLaElegida(pub, 1, Busqueda(), ocup, cabanas, hoy)
        val l = p.lugar as Lugar.Lleno
        assertEquals(LocalDate.of(2027, 1, 6), l.alt!!.desde); assertEquals(LocalDate.of(2027, 1, 14), l.alt!!.hasta)   // las mismas 8 noches, una semana antes, en la 3
        assertTrue(p.respuesta, p.respuesta.contains("Del 13 al 21 de enero estamos completos, pero tenemos lugar del 6 al 14 de enero"))
        assertTrue(p.respuesta.contains(SITIO_WEB))
        assertTrue(tituloDeEleccion(p).startsWith("⛔ Sin lugar del 13 al 21 de enero"))
        // lo que no alcanza es el cupo: para cuántos sí hay
        val g = pedidoDeLaElegida(Publicacion("", "Somos 25 personas, del 15 al 20 de marzo"), 1, Busqueda(), ocup, cabanas, hoy)
        assertTrue(g.respuesta, g.respuesta.contains("tenemos lugar para hasta 20 personas"))
        // la parte libre de lo pedido: del 10 al 23 de enero para 5 sólo está libre la 3 del 10 al 14
        val parc = alternativa(LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 9), 5, ocup, cabanas, hoy)
        assertTrue(parc != null)
        // un mes completo para muchos, sin nada después: lo dice sin inventar
        val m = pedidoDeLaElegida(Publicacion("", "Buscamos casa para 30 personas para enero"), 1, Busqueda(), ocup, cabanas, hoy)
        assertTrue(m.respuesta, m.respuesta.contains("En enero estamos completos en Canasvieiras"))
    }

    @Test fun con_lugar_el_aviso_dice_hay_lugar_y_sin_ocupación_la_respuesta_de_siempre() {
        val q = pedidoDeLaElegida(Publicacion("Juan", "Busco alquiler en canasvieiras del 13.03 al 21.03 para 4 personas"), 1, Busqueda(), ocup, cabanas, hoy)
        assertTrue(q.respuesta.contains("del 13 al 21 de marzo")); assertTrue(tituloDeEleccion(q).startsWith("✅ Hay lugar del 13 al 21 de marzo"))
        val r = pedidoDeLaElegida(Publicacion("Juan", "del 13.01 al 21.01 para 4"), 1, Busqueda(), emptyList(), emptyList(), hoy)
        assertFalse(r.respuesta.contains("Tenemos lugar")); assertTrue(tituloDeEleccion(r).startsWith("📋"))
    }

    @Test fun la_base_se_lee_con_sus_formas() {
        val c1 = "c1" to JSONObject("""{"capacidad":{"integerValue":"1"},"activa":{"booleanValue":true}}""")
        val c2 = "c2" to JSONObject("""{"capacidad":{"mapValue":{"fields":{"max":{"integerValue":"7"},"base":{"integerValue":"5"}}}}}""")
        val c4 = "c4" to JSONObject("""{"capacidad":{"integerValue":"6"},"activa":{"booleanValue":false}}""")
        assertEquals(listOf(CabanaCV("c1", 8), CabanaCV("c2", 7)), cabanasDe(listOf(c1, c2, c4)))
        val d = "x" to JSONObject("""{"cabanaId":{"stringValue":"c1"},"desde":{"stringValue":"2027-01-14"},"hasta":{"stringValue":"2027-01-24"}}""")
        val mala = "y" to JSONObject("""{"cabanaId":{"stringValue":"c1"},"desde":{"stringValue":"2027-01-24"},"hasta":{"stringValue":"2027-01-14"}}""")
        assertEquals(listOf(o("c1", "2027-01-14", "2027-01-24")), ocupacionesDe(listOf(d, mala)))
        assertEquals(1, documentosDeConsulta("""[{"document":{"name":"p/d/x","fields":{}}},{"readTime":"t"}]""").size)
    }
}
