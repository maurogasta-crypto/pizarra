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
        assertEquals(1, pedidos.size); assertEquals("facebook", pedidos[0].m.app); assertTrue(pedidos[0].m.texto.contains("Canasvieiras"))
        assertTrue(pedidos[0].respuesta.contains("casaverdecanas.com.br"))
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
        assertFalse(n[0].texto.contains("AbCdEf123456XyZ")); assertFalse(p[0].m.texto.contains("AbCdEf123456XyZ"))
    }

    @Test fun un_árbol_enorme_tiene_techo() {
        val grande = Nodo(hijos = List(10_000) { Nodo(texto = "x$it") })
        assertTrue(enOrden(grande).size < 4_001)
    }

    // pizarra-45: lo que mostró el primer recorrido real, el 20-oct.
    @Test fun el_autor_sale_del_botón_de_la_tarjeta_y_los_botones_no_son_publicaciones() {
        val t = "Estoy buscando un hospedaje en ingléses o alrededores para 10 adultos y 3 criaturas 04/01 al 12/01"
        val p = publicacionesDe(Nodo(hijos = listOf(
            Nodo(desc = "Más opciones para la publicación de Pablo Gimenez"), Nodo(texto = "3 h"), Nodo(texto = t),
            Nodo(desc = "Botón \"Compartir\". Toca dos veces para compartir la publicación."),
            Nodo(desc = "Botón Me gusta para el comentario de NAthii. Toca dos veces y mantén presionado para mostrar la bandeja de reacciones."),
            Nodo(texto = "Más opciones para la publicación de Canasvieiras Hospedagens, que es un nombre larguísimo"))))
        assertEquals(1, p.size); assertEquals("Pablo Gimenez", p[0].encabezado); assertEquals(t, p[0].texto)
    }

    @Test fun lo_que_se_escribe_en_el_buscador_no_sale() {
        val p = publicacionesDe(Nodo(hijos = listOf(Nodo(texto = "alquiler canasvieiras", editable = true),
            Nodo(texto = "Buscar en EN AUTO A FLORIPA Alquileres en Florianópolis, el grupo de siempre"))))
        assertTrue(p.isEmpty())
    }

    @Test fun una_notificación_de_la_campana_es_la_misma_aunque_pase_el_tiempo() {
        val a = "Ahora en Alquiler en Florianópolis, Brasil: \"Busco casa en Canasvieiras...\". 1 min. No leída"
        val b = "Ahora en Alquiler en Florianópolis, Brasil: \"Busco casa en Canasvieiras...\". 3 min. Leída"
        val na = queSaleDe("com.facebook.katana", publicacionesDe(Nodo(hijos = listOf(Nodo(texto = a)))), 1, "x").first
        val nb = queSaleDe("com.facebook.katana", publicacionesDe(Nodo(hijos = listOf(Nodo(texto = b)))), 2, "x").first
        assertEquals(na.single().id, nb.single().id); assertFalse(na.single().texto.contains("min"))
        assertEquals("Ahora en Alquiler en Florianópolis, Brasil: \"Busco casa en Canasvieiras...\"", sinHaceCuanto(b))
    }

    @Test fun canasvieiras_mal_escrito_también_es_canasvieiras() {
        assertTrue(esPedidoDeAlquiler("busco alquilar en casnavieras para enero"))
        assertTrue(esPedidoDeAlquiler("aluguel em Canavieiras"))
        assertFalse(esPedidoDeAlquiler("alquiler en Bombinhas"))
    }

    // pizarra-47: sin avisos flotantes; la respuesta se copia sola, y sólo si hay UN pedido a la vista.
    @Test fun se_copia_sola_la_respuesta_del_único_pedido_a_la_vista() {
        val p1 = "Hola, busco casa en Canasvieiras para 4 personas en enero, alguien tiene?"
        val p2 = "Buscamos departamento en Ingleses para 2 personas en marzo, alguna recomendación?"
        fun pantalla(v1: Boolean, v2: Boolean) = publicacionesDe(Nodo(hijos = listOf(
            Nodo(desc = "Más opciones para la publicación de Ana Souza"), Nodo(texto = p1, visible = v1),
            Nodo(desc = "Más opciones para la publicación de Juan Pérez"), Nodo(texto = p2, visible = v2))))
        val solo = queSaleDe("com.facebook.katana", pantalla(true, false), 1, "x", Busqueda()).second
        assertEquals(2, solo.size)                                   // los dos van a la lista…
        val c = aCopiar(solo, "")!!                                  // …pero se copia el que se ve
        assertEquals("Ana Souza", c.autor); assertTrue(c.respuesta.contains("Ana")); assertTrue(c.respuesta.contains(SITIO_WEB))
        assertEquals(null, aCopiar(solo, c.m.id))                   // el mismo no se vuelve a copiar
        val dos = queSaleDe("com.facebook.katana", pantalla(true, true), 1, "x", Busqueda()).second
        assertEquals(null, aCopiar(dos, ""))                         // con dos a la vista, no se adivina
        assertEquals(null, aCopiar(queSaleDe("com.facebook.katana", pantalla(false, false), 1, "x", Busqueda()).second, ""))
    }

    @Test fun el_lector_ya_no_tiene_avisos_flotantes() {
        val codigo = java.io.File("src/main/java/com/maurogasta/pizarra/Pantalla.kt").readText()
        assertFalse(codigo.contains("IMPORTANCE_HIGH")); assertFalse(codigo.contains("avisarEnPantalla"))
        assertTrue(codigo.contains("IMPORTANCE_LOW"))
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
