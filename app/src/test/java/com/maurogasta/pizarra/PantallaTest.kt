package com.maurogasta.pizarra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pizarra-44: el lector de Facebook por accesibilidad. Lo que importa: que saque
// las publicaciones de un árbol como el de Facebook (con la tarjeta descripta
// entera y el párrafo suelto adentro), que no mande lo que no interesa, que un
// pedido de Canasvieiras vaya también a la lista, y que el archivo no tenga UNA
// sola acción sobre la pantalla: tocar dentro de Facebook es lo que bloquea la cuenta.
class PantallaTest {
    private val pedido = "Hola! Busco alquilar casa o depto en Canasvieiras del 3 al 10 de enero, somos 4 adultos y un perro"
    private val otro = "Feliz cumpleaños a mi hermana querida, que la pases hermoso hoy con toda la familia y amigos"
    private val zona = "Alguien recomienda un restaurante en Jurerê para ir con chicos? Vamos a estar en febrero unos días"

    // Como lo arma Facebook: la tarjeta trae la descripción entera, y adentro el autor, el texto y los botones.
    private fun tarjeta(autor: String, texto: String, donde: String = " en Aluguel Floripa") = Nodo(desc = "$autor publicó$donde: $texto", hijos = listOf(
        Nodo(texto = autor), Nodo(texto = "2 h"), Nodo(texto = texto), Nodo(texto = "Me gusta"), Nodo(texto = "Comentar")))

    private val pantalla = Nodo(hijos = listOf(
        Nodo(texto = "Aluguel de Temporada Florianópolis Alquiler Vacaciones"),
        tarjeta("Juana Pérez", pedido),
        tarjeta("Pedro Gómez", otro, donde = ""),   // del inicio, no de un grupo
        Nodo(hijos = listOf(Nodo(texto = "Ana Souza"), Nodo(texto = zona))),   // sin descripción: sólo el párrafo
    ))

    @Test fun saca_las_publicaciones_sin_repetir_la_tarjeta_y_su_párrafo() {
        val p = publicacionesDe(pantalla)
        assertEquals(3, p.size)
        assertTrue(p[0].texto.startsWith("Juana Pérez publicó en Aluguel Floripa")); assertTrue(p[0].texto.contains("perro"))
        assertEquals("Ana Souza", p[2].encabezado)
        assertTrue(p.none { it.texto == pedido })     // el párrafo suelto quedó adentro de la tarjeta
    }

    @Test fun los_botones_y_las_horas_no_son_publicaciones() {
        val p = publicacionesDe(Nodo(hijos = listOf(Nodo(texto = "Me gusta"), Nodo(texto = "Ver más comentarios de esta publicación y de otras parecidas del grupo"))))
        assertTrue(p.isEmpty())
    }

    @Test fun lo_de_interés_va_a_la_ronda_y_el_pedido_además_a_la_lista() {
        val (notifs, pedidos) = queSaleDe("com.facebook.katana", publicacionesDe(pantalla), 1_000, "com.maurogasta.pizarra")
        assertEquals(2, notifs.size)                     // el pedido y lo de Jurerê; el cumpleaños del inicio no sale del teléfono
        assertTrue(notifs.all { it.app == "com.facebook.katana·pantalla" })
        assertFalse(notifs.any { it.texto.contains("cumpleaños") })
        assertEquals(1, pedidos.size); assertEquals("facebook", pedidos[0].app); assertTrue(pedidos[0].texto.contains("Canasvieiras"))
    }

    @Test fun en_un_grupo_de_alquileres_sale_todo_lo_del_grupo() {
        // El nombre del grupo viene en la tarjeta: lo que se publica ahí le interesa a la ronda, sea lo que sea.
        val (n, _) = queSaleDe("com.facebook.katana", publicacionesDe(Nodo(hijos = listOf(tarjeta("Pedro Gómez", otro)))), 1, "x")
        assertEquals(1, n.size)
    }

    @Test fun la_misma_publicación_leída_dos_veces_es_una() {
        val a = queSaleDe("com.facebook.katana", publicacionesDe(pantalla), 1_000, "x").first
        val b = queSaleDe("com.facebook.katana", publicacionesDe(pantalla), 99_000, "x").first
        assertEquals(a.map { it.id }, b.map { it.id })
    }

    @Test fun lo_que_sale_lleva_los_códigos_y_los_enlaces_tapados() {
        val t = "Alquilo depto en Canasvieiras, reservá acá https://reservas.ejemplo.com/r?token=AbCdEf123456XyZ"
        val (n, p) = queSaleDe("com.facebook.katana", publicacionesDe(Nodo(hijos = listOf(Nodo(texto = t)))), 1, "x")
        assertFalse(n[0].texto.contains("AbCdEf123456XyZ")); assertFalse(p[0].texto.contains("AbCdEf123456XyZ"))
    }

    @Test fun un_árbol_enorme_tiene_techo() {
        val grande = Nodo(hijos = List(10_000) { Nodo(texto = "x$it") })
        assertTrue(enOrden(grande).size < 4_001)
    }

    @Test fun el_lector_SÓLO_LEE_y_sólo_dentro_de_Facebook() {
        val codigo = java.io.File("src/main/java/com/maurogasta/pizarra/Pantalla.kt").readText()
        for (accion in listOf("performAction", "dispatchGesture", "performGlobalAction", "ACTION_CLICK", "ACTION_SCROLL", "ACTION_SET_TEXT"))
            assertFalse("el lector no puede tener «$accion»", codigo.contains(accion))
        val xml = java.io.File("src/main/res/xml/lector_pantalla.xml").readText()
        assertTrue(xml.contains("android:packageNames=\"com.facebook.katana,com.facebook.lite,com.facebook.groups\""))
        assertFalse(xml.contains("canPerformGestures"))
    }
}
