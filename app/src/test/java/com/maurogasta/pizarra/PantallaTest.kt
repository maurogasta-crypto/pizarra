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

    @Test fun al_texto_se_le_saca_el_hace_cuánto() {
        assertEquals("Ahora en Alquiler: \"Busco casa...\"", sinHaceCuanto("Ahora en Alquiler: \"Busco casa...\". 3 min. Leída"))
    }

    @Test fun canasvieiras_mal_escrito_también_es_canasvieiras() {
        assertTrue(esPedidoDeAlquiler("busco alquilar en casnavieras para enero"))
        assertTrue(esPedidoDeAlquiler("aluguel em Canavieiras"))
        assertFalse(esPedidoDeAlquiler("alquiler en Bombinhas"))
    }

    @Test fun el_lector_no_tiene_avisos_flotantes_ni_manda_lo_que_nadie_eligió() {
        val codigo = java.io.File("src/main/java/com/maurogasta/pizarra/Pantalla.kt").readText()
        assertFalse(codigo.contains("IMPORTANCE_HIGH")); assertFalse(codigo.contains("avisarEnPantalla"))
        assertFalse(codigo.contains("Notificaciones.mandar"))
        // pizarra-51: el único aviso es el de la elección (al abrir el comentario), no el del recorrido
        assertEquals(1, Regex("Responder\\.avisarEleccion\\(").findAll(codigo).count())
        val xml = java.io.File("src/main/res/xml/lector_pantalla.xml").readText()
        assertTrue(xml.contains("typeViewClicked|typeViewFocused")); assertFalse(xml.contains("typeViewScrolled"))
    }

    // pizarra-48: sólo la publicación que la persona elige responder (toca «Comentar» o el cuadro).
    private val p1 = "Hola, busco casa en Canasvieiras para 4 personas en enero, alguien tiene?"
    private val p2 = "Buscamos departamento en Ingleses para 2 personas en marzo, alguna recomendación?"
    private fun feed(focoEn: Int) = Nodo(hijos = listOf(
        Nodo(desc = "Más opciones para la publicación de Ana Souza"), Nodo(texto = p1), Nodo(texto = "Comentar", foco = focoEn == 1),
        Nodo(desc = "Más opciones para la publicación de Juan Pérez"), Nodo(texto = p2), Nodo(texto = "Comentar", foco = focoEn == 2),
        Nodo(texto = "Escribí un comentario…", editable = true, foco = focoEn == 3)))

    @Test fun la_elegida_es_la_publicación_de_arriba_de_lo_que_se_tocó() {
        assertEquals(p1, publicacionElegida(feed(1))!!.texto)
        assertEquals(p2, publicacionElegida(feed(2))!!.texto)
        assertEquals(p1, publicacionElegida(feed(3))!!.texto)          // pizarra-53: desde el cuadro, la publicación de arriba de todo
        assertEquals(null, publicacionElegida(feed(0)))                  // sin toque, nada
        // tocar el texto mismo de la publicación también la elige
        val t = Nodo(hijos = listOf(Nodo(texto = p1), Nodo(texto = p2, foco = true)))
        assertEquals(p2, publicacionElegida(t)!!.texto)
    }

    @Test fun la_elegida_lleva_autor_y_respuesta_con_la_página_y_los_códigos_tapados() {
        val p = pedidoDeLaElegida(publicacionElegida(feed(1))!!, 1_000, Busqueda())
        assertEquals("Ana Souza", p.autor); assertTrue(p.respuesta.startsWith("¡Hola Ana!") || p.respuesta.contains("Ana"))
        assertTrue(p.respuesta.contains(SITIO_WEB)); assertEquals("facebook", p.m.app); assertEquals(4, p.encaje.personas)
        val t = "Alquilo depto en Canasvieiras, reservá acá https://reservas.ejemplo.com/r?token=AbCdEf123456XyZ y comentá"
        val q = pedidoDeLaElegida(Publicacion("", t), 1, Busqueda())
        assertFalse(q.m.texto.contains("AbCdEf123456XyZ"))
        // la misma publicación elegida dos veces es la misma (la huella no lleva la hora)
        assertEquals(p.m.id, pedidoDeLaElegida(publicacionElegida(feed(1))!!, 99_000, Busqueda()).m.id)
    }

    @Test fun sólo_reacciona_a_lo_que_es_para_responder() {
        assertTrue(esParaResponder("Comentar", false)); assertTrue(esParaResponder("Responder", false))
        assertTrue(esParaResponder("Comentário", false)); assertTrue(esParaResponder("", true))
        assertFalse(esParaResponder("Me gusta", false)); assertFalse(esParaResponder("Compartir", false))
        assertFalse(esParaResponder("Ana Souza", false))
    }

    // pizarra-53: la publicación abierta, con un comentario debajo y el cuadro al final (20-oct).
    private val pedidoAbierto = "Hola soy de buenos aires y busco alquiler en Canasvieiras, me gustaría saber precios y como es la cosa alla ?"
    private val comentario = "Seu refúgio pertinho do mar! Apartamento a apenas 200 metros da praia dos Ingleses em Florianópolis, com garagem"
    private fun abierta(firma: String?) = Nodo(hijos = listOfNotNull(
        Nodo(texto = "Argentinos en Floripa"), firma?.let { Nodo(desc = "Más opciones para la publicación de $it") }, Nodo(texto = "11 oct"),
        Nodo(texto = pedidoAbierto), Nodo(texto = "Me gusta"),
        Nodo(desc = "Foto del perfil de Ana Lucía Pereira"), Nodo(texto = "Ana Lucía Pereira"), Nodo(texto = "1 sem"), Nodo(texto = comentario),
        Nodo(texto = "Escribe un comentario público…", editable = true, foco = true)))

    @Test fun desde_el_cuadro_se_responde_la_publicación_y_no_el_comentario() {
        val e = publicacionElegida(abierta(null))!!
        assertEquals(pedidoAbierto, e.texto); assertFalse(e.esAutor)
        val r = pedidoDeLaElegida(e, 1, Busqueda()).respuesta
        assertTrue(r, r.startsWith("¡Hola!") || r.startsWith("Hola!") || r.startsWith("¡Buenas!"))  // sin nombre, y en castellano
        assertFalse(r.contains("Ana")); assertFalse(r.contains("Argentinos"))
    }

    @Test fun los_textos_de_los_botones_y_lo_que_no_es_alojamiento_no_se_eligen() {
        val p = publicacionesDe(Nodo(hijos = listOf(Nodo(texto = "Se muestran Más relevantes comentarios. Toca para cambiar el filtro de comentarios."))))
        assertTrue(p.isEmpty())
        assertFalse(hablaDeAlojamiento("Musicien, Pianiste, Guitariste, Chanteur en un grupo de rock de la ciudad"))
        assertTrue(hablaDeAlojamiento(pedidoAbierto))
        // pizarra-54: un mensaje de Marketplace (visto el 20-oct, guardado por la 52) no es un pedido
        assertFalse(hablaDeAlojamiento("Antonella está esperando tu respuesta sobre Patines. Ver publicación"))
    }

    @Test fun el_nombre_sólo_si_firma_una_persona() {
        assertTrue(publicacionElegida(abierta("Rocío Gómez"))!!.esAutor)
        assertFalse(publicacionElegida(abierta("Argentinos en Floripa"))!!.esAutor)   // un grupo no es una persona
        assertTrue(esNombreDePersona("Juan Darío Aquino")); assertTrue(esNombreDePersona("Maria da Silva"))
        assertFalse(esNombreDePersona("Canasvieiras Hospedagens")); assertFalse(esNombreDePersona("Rocío"))
        assertFalse(esNombreDePersona("EN AUTO A FLORIPA Alquileres")); assertFalse(esNombreDePersona("Alquiler en Florianópolis"))
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
