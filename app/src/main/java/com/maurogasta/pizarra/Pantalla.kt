package com.maurogasta.pizarra

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// Pantalla.kt — Lo que Facebook muestra, leído mientras alguien lo mira.
// Sello: pizarra-56
//
// 20-oct-2026, Mauro: «no funcionan las notificaciones push de facebook… armá
// la lectura pasiva dentro de mi app… una vez abierta la APK de facebook puede
// levantar todo inclusive lo que no se ve en la pantalla. Buscá el máximo de
// información con el mínimo de intervención».
//
// QUÉ ES: un servicio de ACCESIBILIDAD que Android deja ver SÓLO adentro de las
// apps de Facebook (`packageNames` en res/xml/lector_pantalla.xml: el sistema
// no le pasa nada de otra app). Cada vez que la pantalla de Facebook cambia o
// se desliza, recorre el árbol ENTERO de la ventana —también lo que quedó
// arriba o abajo y todavía está armado, que es más que lo visible— y saca las
// publicaciones.
//
// LO QUE NO HACE NUNCA, y es la regla del ecosistema («automatizar toques
// dentro de la app de Facebook» está prohibido, porque es lo que hace que
// bloqueen la cuenta): no toca, no desliza, no abre «Ver más», no escribe, no
// pide nada a Facebook. Sólo LEE lo que la app ya armó para la persona que la
// está mirando. Para Facebook, es una persona mirando el grupo. El banco busca
// en este archivo cualquier acción sobre la pantalla y falla si aparece.
//
// ADÓNDE VA (desde pizarra-48, 20-oct, Mauro: «así sólo me guarda las que entro
// y me prepara la respuesta automáticamente»): mientras se recorre, a NINGÚN
// lado. Cuando la persona toca «Comentar» o el cuadro de texto de una
// publicación, ésa —la última antes de lo que tocó (`publicacionElegida`)— va a
// `pedidos_alquiler/` de Casa Verde con su respuesta rápida, y la respuesta
// queda copiada: el teclado la ofrece arriba y se pega con un toque. Pegarla
// sola en el cuadro sería escribir dentro de Facebook, y eso no se hace.
// Hasta pizarra-47 mandaba además a `notificaciones/` todo lo que hablaba de
// alquileres: era ruido, y guardaba lo que nadie eligió.
//
// LA BITÁCORA dice cuántos pedidos se eligieron, nunca el texto.
// ─────────────────────────────────────────────────────────────────────────────

/** Un nodo de la pantalla, sin Android: lo que el banco puede armar a mano. */
/** `foco`: el nodo que la persona acaba de tocar o donde va a escribir (pizarra-48). */
data class Nodo(val texto: String = "", val desc: String = "", val hijos: List<Nodo> = emptyList(), val editable: Boolean = false, val visible: Boolean = true, val foco: Boolean = false)

/** Una publicación leída: quién o qué grupo (el texto corto de antes) y lo que dice. */
data class Publicacion(val encabezado: String, val texto: String, val esAutor: Boolean = false, val visible: Boolean = true, val pos: Int = 0)

/** Un pedido que cuadra con la búsqueda, con la respuesta rápida ya armada (pizarra-46). */
/** pizarra-56: con las fechas y cuántos son ya leídos (`desde`/`hasta` o `mes`, `personas`), para que
 *  Casa Verde calcule si el pedido cae en una fecha libre sin volver a leer el texto. */
data class PedidoPantalla(val m: MensajeAirbnb, val respuesta: String, val encaje: Encaje, val visible: Boolean = true, val autor: String = "",
                          val lugar: Lugar = Lugar.SinFechas, val desde: String = "", val hasta: String = "", val mes: String = "", val personas: Int = 0)

private const val LARGO_MINIMO = 60       // menos que eso es un botón, un nombre o una hora
private const val MAX_NODOS = 4000        // una pantalla de Facebook anda por 300 a 1500

/** El texto de un nodo: el escrito, o la descripción si no tiene (Facebook arma muchos así). */
private fun dicho(n: Nodo): String = n.texto.trim().ifEmpty { n.desc.trim() }

/** El árbol en el orden en que se lee, de arriba abajo, con un techo; cada texto con si está a la vista. */
fun enOrdenConVista(raiz: Nodo, techo: Int = MAX_NODOS): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    val pila = ArrayDeque<Nodo>().apply { add(raiz) }
    var vistos = 0
    while (pila.isNotEmpty() && vistos < techo) {
        val n = pila.removeLast(); vistos++
        if (n.foco) out.add(FOCO to true)   // pizarra-48: dónde tocó la persona, en el orden de lectura
        if (n.editable) continue      // pizarra-45: lo que la persona escribe (el buscador) no es una publicación
        val t = dicho(n); if (t.isNotEmpty()) out.add(t to n.visible)
        // También la descripción cuando trae MÁS que el texto: en Facebook la
        // tarjeta de una publicación suele describirse entera.
        val d = n.desc.trim(); if (d.length > n.texto.trim().length + 20 && d != t) out.add(d to n.visible)
        for (h in n.hijos.asReversed()) pila.add(h)
    }
    return out
}

fun enOrden(raiz: Nodo, techo: Int = MAX_NODOS): List<String> = enOrdenConVista(raiz, techo).map { it.first }.filter { it != FOCO }

private const val FOCO = "\u0000foco"

/* Lo que Facebook pone en cada tarjeta y no es la publicación: los botones y
   sus instrucciones para quien usa un lector de pantalla (pizarra-45, visto en
   el primer recorrido real: «Botón "Compartir". Toca dos veces…»). */
private val RELLENO = Regex(
    "^(me gusta|comentar|compartir|enviar|responder|seguir|unirte|ver más|ver menos|curtir|comentário|compartilhar|like|comment|share|reply|" +
        "bot[oó]n|bot[aã]o|button|buscar en|pesquisar em|search in|m[aá]s opciones|mais op[cç][oõ]es|more options|foto del perfil|foto do perfil|" +
        "profile picture|administrar configuraci[oó]n|agregar a amigos|adicionar amigo|add friend|se muestran|mostrando|showing)\\b|toca dos veces|toque duas vezes|double.tap|toca para|toque para|tap to",
    RegexOption.IGNORE_CASE)

/* El autor sale de la etiqueta del botón «⋮» de la tarjeta, que es lo único
   que Facebook rotula con el nombre: «Más opciones para la publicación de Ana». */
/* pizarra-53 (Mauro, 20-oct: «no proponga responder con el nombre cuando al principio del
   pedido no figure con claridad la firma de la persona… para no tomar los nombres de las
   personas que hicieron el comentario»): SÓLO el botón ⋮ de la publicación. «Foto del perfil
   de…» quedó afuera: también la llevan los comentarios, y de ahí salió el nombre equivocado. */
private val DE_AUTOR = Regex(
    "^(?:m[aá]s opciones para la publicaci[oó]n de|mais op[cç][oõ]es para a publica[cç][aã]o de|more options for (?:the )?post by)\\s+(.{2,80}?)\\.?$",
    RegexOption.IGNORE_CASE)

/* Un grupo o una página también firman con ⋮ («Más opciones para la publicación de Argentinos
   en Floripa»): eso no es una persona y no va en el saludo. */
private val NO_ES_PERSONA = Regex(
    "\\b(alquiler\\w*|aluguel|alugu\\w*|grupo|floripa|florian[oó]polis|brasil|argentinos|uruguayos|paraguayos|chilenos|" +
        "canasvieiras|ingleses|temporada|hospedaje\\w*|hospedage\\w*|apartamentos?|departamentos?|inmobiliaria|imobili[aá]ria|" +
        "turismo|viajes?|praia|playa|casa verde|hostel|pousada|posada|en auto|publicidad)\\b", RegexOption.IGNORE_CASE)

/** ¿Es la firma de una persona? Nombre y apellido, con mayúscula, sin palabras de grupo o de lugar. */
fun esNombreDePersona(n: String): Boolean {
    val ps = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (ps.size !in 2..5 || NO_ES_PERSONA.containsMatchIn(n)) return false
    return ps.first()[0].isUpperCase() && ps.last()[0].isUpperCase() && ps.all { it.length <= 20 && it.none { c -> c.isDigit() } }
}

/* Una notificación de la campana de Facebook trae al final hace cuánto y si se
   leyó («… 1 min. No leída»): eso cambia cada minuto y haría que la misma
   notificación entre una vez por minuto. Se saca antes de la huella. */
private val HACE_CUANTO = Regex(
    "[.\\s]*\\b\\d+\\s*(?:s|seg|min|h|d|sem|a)\\.?\\s*(?:no le[ií]da|le[ií]da|n[aã]o lida|lida|unread|read)?\\.?\\s*$",
    RegexOption.IGNORE_CASE)

fun sinHaceCuanto(t: String): String = t.replace(HACE_CUANTO, "").trim()

/** Las publicaciones de una pantalla: los textos largos, sin repetir, y sin el que ya está adentro de otro. */
fun publicacionesDe(raiz: Nodo): List<Publicacion> {
    val conVista = enOrdenConVista(raiz)
    val textos = conVista.map { it.first }
    val largos = LinkedHashMap<String, Int>()      // texto → posición
    textos.forEachIndexed { i, t ->
        val x = t.replace(Regex("\\s+"), " ").trim()
        if (x.length >= LARGO_MINIMO && !RELLENO.containsMatchIn(x) && x !in largos) largos[x] = i
    }
    val todos = largos.keys.toList()
    // Si la tarjeta entera viene descripta y además el párrafo suelto, queda la
    // tarjeta: trae el autor y el grupo adentro.
    val sueltos = todos.filter { a -> todos.none { b -> b !== a && b.length > a.length && b.contains(a) } }
    return sueltos.map { t ->
        val i = largos.getValue(t)
        val cerca = (i - 1 downTo maxOf(0, i - 8)).map { textos[it].trim() }
        val autor = cerca.firstNotNullOfOrNull { DE_AUTOR.find(it)?.groupValues?.get(1) }?.takeIf { esNombreDePersona(it) }
        val antes = autor ?: cerca.firstOrNull {
            it.length in 3..80 && !RELLENO.containsMatchIn(it) && !Regex("^\\d").containsMatchIn(it)
        }.orEmpty()
        Publicacion(antes, sinHaceCuanto(t).take(4000), autor != null, conVista[i].second, i)
    }
}

private fun sinTildes(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** pizarra-48: la publicación que la persona eligió responder. Es la última que aparece
 *  ANTES del lugar que tocó («Comentar», o el cuadro para escribir): en Facebook el botón
 *  y el cuadro van debajo de su publicación. Si tocó el texto mismo de una, es ésa. */
fun publicacionElegida(raiz: Nodo): Publicacion? {
    val orden = enOrdenConVista(raiz)
    val foco = orden.indexOfFirst { it.first == FOCO }
    if (foco < 0) return null
    val pubs = publicacionesDe(raiz)
    val propio = orden.getOrNull(foco + 1)?.first?.let { t -> pubs.firstOrNull { it.pos == foco + 1 && t.length >= LARGO_MINIMO } }
    if (propio != null) return propio
    val antes = pubs.filter { it.pos < foco }
    // pizarra-53: desde el CUADRO de texto de una publicación abierta, arriba están la publicación
    // y después sus comentarios: es la PRIMERA. Desde el botón «Comentar» del inicio, la de arriba
    // del botón: la ÚLTIMA. El 20-oct se respondió a un comentario en vez de a la publicación.
    return if (focoEnCuadro(raiz)) antes.minByOrNull { it.pos } else antes.maxByOrNull { it.pos }
}

/** ¿Lo que se tocó es el cuadro para escribir? */
fun focoEnCuadro(n: Nodo): Boolean = (n.foco && n.editable) || n.hijos.any { focoEnCuadro(it) }

/** El pedido de la elegida, con la respuesta rápida armada según lo que se busca y, desde
 *  pizarra-49, según la ocupación de Casa Verde que se trajo al empezar la sesión. */
fun pedidoDeLaElegida(p: Publicacion, ahora: Long, busqueda: Busqueda, ocup: List<Ocupacion> = emptyList(),
                      cabanas: List<CabanaCV> = emptyList(), hoy: java.time.LocalDate = java.time.LocalDate.now()): PedidoPantalla {
    // Cuántos son, el tipo y el público se leen siempre: el encaje corta antes cuando la zona no
    // es la buscada, y la persona igual eligió responder (pizarra-51).
    val n = cuantasPersonas(p.texto)
    val e = encaje(p.texto, busqueda).copy(personas = n, tipo = tipoDe(p.texto), publico = publicoDe(p.texto, n))
    val m = MensajeAirbnb(taparCodigos(p.encabezado).take(120), taparCodigos(p.texto).take(4000), emptyList(), ahora.toString(), app = "facebook")
    val autor = if (p.esAutor) p.encabezado else ""
    val lugar = if (cabanas.isEmpty()) Lugar.SinFechas else lugarPara(p.texto, e.personas, ocup, cabanas, hoy)
    val f = fechasPedidas(p.texto, hoy)
    return PedidoPantalla(m, respuestaRapida(autor, p.texto, e, m.id.hashCode(), lugar), e, true, autor, lugar,
        desde = (f as? Pedido.Rango)?.desde?.toString().orEmpty(), hasta = (f as? Pedido.Rango)?.hasta?.toString().orEmpty(),
        mes = (f as? Pedido.Mes)?.mes?.toString().orEmpty(), personas = n)
}

/** pizarra-56 (Mauro, 21-oct: «guarda los registros de todas las publicaciones»): mientras se recorre,
 *  se registra todo pedido de alojamiento de la zona —quien BUSCA, no quien ofrece—, sea del tipo,
 *  del tamaño y del público que sea: si cae en una fecha libre lo decide Casa Verde, no el teléfono. */
fun esPedidoParaRegistrar(texto: String, b: Busqueda): Boolean =
    hablaDeAlojamiento(texto) && encaje(texto, b.copy(tipos = setOf("diaria", "mensual", "anual"), personasMax = 60, publico = emptySet(), activa = true)).ok

/** Los pedidos de una pantalla, listos para registrar (sin aviso ni copia). */
fun registrosDe(pubs: List<Publicacion>, ahora: Long, b: Busqueda, ocup: List<Ocupacion>, cabanas: List<CabanaCV>,
                hoy: java.time.LocalDate = java.time.LocalDate.now()): List<PedidoPantalla> =
    pubs.filter { esPedidoParaRegistrar(it.texto, b) }.map { pedidoDeLaElegida(it, ahora, b, ocup, cabanas, hoy) }



/** pizarra-55 (Mauro, 21-oct: «los avisos que ya se comentaron tienen que advertirse»): ¿ya hay
 *  un comentario NUESTRO debajo de la publicación elegida? Todas nuestras respuestas llevan la
 *  página, así que alcanza con buscarla en lo que viene después de la publicación. */
fun yaComentadoEnPantalla(raiz: Nodo, elegida: Publicacion): Boolean {
    val orden = enOrdenConVista(raiz).map { it.first }
    // En una publicación abierta (se tocó el cuadro) todo lo de abajo son sus comentarios. En el
    // inicio, sólo hasta la tarjeta siguiente, que empieza con su ⋮ («Más opciones para la publicación de…»).
    val fin = if (focoEnCuadro(raiz)) orden.size
        else orden.withIndex().firstOrNull { (i, t) -> i > elegida.pos && Regex("^(m[aá]s opciones para la publicaci|mais op[cç][oõ]es para a publica|more options for)", RegexOption.IGNORE_CASE).containsMatchIn(t) }?.index ?: orden.size
    return (elegida.pos + 1 until fin).any { Regex("casaverdecanas", RegexOption.IGNORE_CASE).containsMatchIn(orden[it]) }
}

/** Cómo se enteró de que ya se respondió: en la pantalla, o porque se eligió antes en este teléfono (la fecha). */
data class YaRespondido(val enPantalla: Boolean, val antes: String = "")

/** El título del aviso que sale al abrir el comentario (pizarra-51): hay lugar o no, y qué quedó copiado. */
fun tituloDeEleccion(p: PedidoPantalla, ya: YaRespondido? = null): String = when {
    ya?.enPantalla == true -> "⚠️ YA COMENTADA: hay una respuesta nuestra · no copié nada"
    ya != null && ya.antes.isNotEmpty() -> "⚠️ Ya la elegiste el ${ya.antes} · " + tituloDeEleccion(p)
    else -> tituloSegunLugar(p)
}

private fun tituloSegunLugar(p: PedidoPantalla): String = when (val l = p.lugar) {
    is Lugar.Hay -> "✅ Hay lugar " + rangoEnPalabras(l.desde, l.hasta, false) + (if (l.cabanas.size > 1) " (${l.cabanas.size} cabañas)" else "") +
        (if (l.conExtra) " · pasa la base: " + (l.cabanas.firstNotNullOfOrNull { it.condicion.takeIf { c -> c.isNotBlank() } } ?: "con extra por persona") else "") + " · respuesta copiada"
    is Lugar.MesConLugar -> "✅ Hay fechas libres en " + mesEnPalabras(l.mes, false) + " · respuesta copiada"
    is Lugar.Lleno -> "⛔ Sin lugar " + rangoEnPalabras(l.desde, l.hasta, false) + (if (l.alt != null) " · copié lo que sí hay" else " · copié una respuesta")
    is Lugar.MesLleno -> "⛔ " + mesEnPalabras(l.mes, false).replaceFirstChar { it.uppercase() } + " completo" + (if (l.alt != null) " · copié lo que sí hay" else "")
    Lugar.SinFechas -> "📋 No dice fechas · respuesta copiada"
}

/* pizarra-53: lo elegido tiene que hablar de alojarse o de la zona. Un perfil, un botón o una
   publicidad cualquiera no se guardan aunque se haya tocado «Comentar» cerca. */
private val DE_ALOJAMIENTO = Regex(
    "\\b(alquil\\w*|alug\\w*|arriend\\w*|temporada|hosped\\w*|aloja\\w*|vacacion\\w*|ferias|verano|verao|" +
        "departamento|depto|apartamento|apto|kitnet|kitinete|quarto|habitacion\\w*|cabana\\w*|casa|pousada|posada|diaria\\w*|" +
        "canasvieiras?|canas|jurere|ingleses|cachoeira|florianopolis|floripa|praia|playa)\\b")

fun hablaDeAlojamiento(texto: String): Boolean =
    DE_ALOJAMIENTO.containsMatchIn(java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase())

/* Lo que toca la persona cuando va a responder: el botón «Comentar» (o «Responder») o el cuadro de texto. */
private val PARA_RESPONDER = Regex("^(comentar|comment|coment[aá]rio|comentario|responder|reply|escrib[ei]|escreva|write)", RegexOption.IGNORE_CASE)

fun esParaResponder(rotulo: String, editable: Boolean): Boolean = editable || PARA_RESPONDER.containsMatchIn(rotulo.trim())

/** El que lee. Android lo conecta cuando la persona lo enciende en Accesibilidad. */
class LectorPantalla : AccessibilityService() {
    private var ultimaElegida = ""
    private var ultimoRecorrido = 0L
    private val registrados = LinkedHashSet<String>()

    /** Un cartel que se va solo: no queda en la barra ni gasta una notificación (pizarra-56). */
    private fun cartel(t: String) = try { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() } catch (e: Throwable) {}

    /** Al recorrer: registra en Casa Verde los pedidos que pasan por la pantalla, en silencio. */
    private fun registrar(raiz: AccessibilityNodeInfo) {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoRecorrido < 1500) return          // Facebook cambia la pantalla decenas de veces por segundo
        ultimoRecorrido = ahora
        val pubs = publicacionesDe(aNodo(raiz, 0, intArrayOf(0), null))
        val nuevos = registrosDe(pubs, ahora, Buscador.actual(this), Ocupado.ocupaciones(this), Ocupado.cabanas(this))
            .filter { synchronized(registrados) { registrados.add(it.m.id).also { while (registrados.size > 2000) registrados.remove(registrados.first()) } } }
        if (nuevos.isNotEmpty()) { Pantalla.cargarPedidos(this, nuevos); Pantalla.anotar(this, leidas = pubs.size, pedidos = nuevos.size) }
    }

    override fun onServiceConnected() {
        Pantalla.anotar(this, conectado = true)
        val app = applicationContext
        thread(name = "busqueda") { Buscador.traer(app) }
        Bitacora.anotar(this, "pantalla: lector de Facebook CONECTADO")
    }

    /* pizarra-48 (Mauro, 20-oct: «así sólo me guarda las que entro y me prepara la respuesta
       automáticamente»). pizarra-56 (21-oct: «guarda los registros de todas las publicaciones»):
       al recorrer se REGISTRA en silencio cada pedido de alojamiento que pasa, con sus fechas y
       cuántos son, para que Casa Verde diga cuáles caen en fechas libres. Cuando la persona toca
       «Comentar» o el cuadro de texto, ESA publicación va a la lista con su respuesta, y la
       respuesta queda copiada: el teclado la ofrece arriba y se pega con un toque. */
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val paquete = e?.packageName?.toString() ?: return
        if (paquete !in PAQUETES_FACEBOOK) return
        // pizarra-49: al empezar una sesión de Facebook, UNA vez, la ocupación de Casa Verde.
        if (Ocupado.empiezaSesion(this)) { val app = applicationContext; thread(name = "ocupacion") { Ocupado.traer(app); Buscador.traer(app) } }
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED || e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            try { rootInActiveWindow?.let { registrar(it) } } catch (t: Throwable) { Bitacora.anotar(this, "pantalla: ${t.message ?: t.javaClass.simpleName}") }
            return
        }
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED && e.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
        try {
            val fuente = e.source ?: return
            val rotulo = (fuente.text ?: fuente.contentDescription ?: e.text?.joinToString(" ") ?: "").toString()
            if (!esParaResponder(rotulo, fuente.isEditable)) return
            val raiz = rootInActiveWindow ?: return
            val arbol = aNodo(raiz, 0, intArrayOf(0), fuente)
            val elegida = publicacionElegida(arbol) ?: return
            if (!hablaDeAlojamiento(elegida.texto)) { cartel("No reconocí un pedido de alojamiento acá: no copié nada."); return }
            val p = pedidoDeLaElegida(elegida, System.currentTimeMillis(), Buscador.actual(this), Ocupado.ocupaciones(this), Ocupado.cabanas(this))
            if (p.m.id == ultimaElegida) return
            ultimaElegida = p.m.id
            // pizarra-55: ¿ya se comentó? Si la respuesta nuestra está a la vista, no se copia nada y
            // en la lista queda respondida; si sólo se eligió antes en este teléfono, se avisa y se copia.
            val ya = YaRespondido(yaComentadoEnPantalla(arbol, elegida), Pantalla.elegidaAntes(this, p.m.id))
            Pantalla.recordarElegida(this, p.m.id)
            if (!ya.enPantalla) Pantalla.copiar(this, p)
            // pizarra-56 (Mauro, 21-oct: «que proponga un texto de respuesta al momento de poner para escribir
            // comentarios, pero que no malgaste notificaciones»): un cartel que se va solo, no una notificación.
            cartel(tituloDeEleccion(p, ya))
            Pantalla.cargarPedidos(this, listOf(p), respondido = ya.enPantalla)
            Pantalla.anotar(this, leidas = 1, pedidos = 1)
        } catch (t: Throwable) {
            Bitacora.anotar(this, "pantalla: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Del árbol de Android al nuestro. Sólo se LEE: ni una acción sobre el nodo. */
    private fun aNodo(n: AccessibilityNodeInfo, hondo: Int, cuenta: IntArray, tocado: AccessibilityNodeInfo?): Nodo {
        cuenta[0]++
        val hijos = if (hondo > 60 || cuenta[0] > MAX_NODOS) emptyList() else
            (0 until n.childCount).mapNotNull { i -> n.getChild(i)?.let { aNodo(it, hondo + 1, cuenta, tocado) } }
        return Nodo(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty(), hijos, n.isEditable, n.isVisibleToUser, tocado != null && n == tocado)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Pantalla.anotar(this, conectado = false)
        Bitacora.anotar(this, "pantalla: lector de Facebook desconectado")
        return super.onUnbind(intent)
    }
}

object Pantalla {
    private fun p(c: Context) = c.getSharedPreferences("pantalla", Context.MODE_PRIVATE)

    /** ¿Está encendido en Accesibilidad? */
    fun encendido(c: Context): Boolean {
        val lista = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val yo = ComponentName(c, LectorPantalla::class.java)
        return lista.split(':').any { ComponentName.unflattenFromString(it) == yo }
    }

    @Synchronized fun anotar(c: Context, conectado: Boolean? = null, leidas: Int = 0, mandadas: Int = 0, pedidos: Int = 0) {
        val pr = p(c); val hoy = isoUtc(System.currentTimeMillis()).take(10)
        val mismo = pr.getString("dia", "") == hoy
        val ed = pr.edit().putString("dia", hoy)
        if (conectado != null) ed.putBoolean("conectado", conectado)
        if (leidas > 0) ed.putString("ultima", isoUtc(System.currentTimeMillis()).take(16).replace('T', ' '))
        ed.putInt("leidas", (if (mismo) pr.getInt("leidas", 0) else 0) + leidas)
        ed.putInt("mandadas", (if (mismo) pr.getInt("mandadas", 0) else 0) + mandadas)
        ed.putInt("pedidos", (if (mismo) pr.getInt("pedidos", 0) else 0) + pedidos)
        ed.apply()
        if (pedidos > 0) Bitacora.anotar(c, "pantalla: un pedido elegido para responder, a la lista con su respuesta")
    }

    /** Para la pantalla de la app y el diagnóstico. Sin un solo texto leído. */
    fun estado(c: Context): String {
        if (!encendido(c)) return "apagado (Ajustes → Accesibilidad → Pizarra)"
        val pr = p(c); val hoy = isoUtc(System.currentTimeMillis()).take(10)
        if (pr.getString("dia", "") != hoy) return "encendido · hoy todavía no elegiste ningún pedido"
        return "encendido · hoy: ${pr.getInt("pedidos", 0)} pedidos elegidos para responder · último ${pr.getString("ultima", "")} UTC"
    }

    /** Los pedidos, a «Pedidos de alquiler» de Casa Verde, con su respuesta rápida. Sin aviso
     *  flotante (pizarra-47): la respuesta se copia sola con `copiar`. */
    /** Las publicaciones elegidas en este teléfono, con el día (pizarra-55). Techo de 500. */
    fun elegidaAntes(c: Context, id: String): String = try {
        org.json.JSONObject(p(c).getString("elegidas", "{}")!!).optString(id)
    } catch (e: Exception) { "" }

    @Synchronized fun recordarElegida(c: Context, id: String) {
        try {
            val o = org.json.JSONObject(p(c).getString("elegidas", "{}")!!)
            if (!o.has(id)) {
                val d = java.time.LocalDate.now(); o.put(id, "${d.dayOfMonth}/${d.monthValue}")
                while (o.length() > 500) o.remove(o.keys().next())
                p(c).edit().putString("elegidas", o.toString()).apply()
            }
        } catch (e: Exception) {}
    }

    fun cargarPedidos(c: Context, ps: List<PedidoPantalla>, respondido: Boolean = false) {
        val app = c.applicationContext
        thread(name = "pantalla") {
            Buscador.alDia(app)
            val cv = CuentaCasaVerde(app)
            for (p in ps) {
                var nuevo = true
                if (!cv.conSesion) cv.ultimo = "falta entrar con la cuenta de Casa Verde"
                else try {
                    nuevo = cv.crearPedido(p.m, respuesta = p.respuesta, fuente = "pantalla",
                        datos = DatosPedido(p.desde, p.hasta, p.mes, p.personas))
                    // Ya había una respuesta nuestra en la publicación: queda respondida en la lista.
                    if (respondido) cv.marcar(p.m.id, "respondido")
                    cv.ultimo = isoUtc(System.currentTimeMillis()).take(16) + (if (nuevo) " · pedido cargado (pantalla)" else " · ya estaba")
                } catch (t: Throwable) {
                    cv.ultimo = "no se pudo cargar: " + (t.message ?: t.javaClass.simpleName)
                    Bitacora.anotar(app, "pantalla: el pedido no llegó a Casa Verde: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }


    /** Copia la respuesta: el teclado la ofrece arriba y se pega con un toque. El aviso lo da
     *  un cartel (`tituloDeEleccion`), que dice además si hay lugar (pizarra-51, -56). */
    fun copiar(c: Context, p: PedidoPantalla) {
        try {
            c.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("Respuesta", p.respuesta))
        } catch (t: Throwable) {
            Bitacora.anotar(c, "pantalla: no se pudo copiar: ${t.javaClass.simpleName}")
        }
    }
}
