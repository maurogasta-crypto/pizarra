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
// Sello: pizarra-45
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
// ADÓNDE VA:
//   · una publicación que pide alquilar en Canasvieiras → `pedidos_alquiler/`
//     de Casa Verde, igual que si hubiera llegado por notificación (la ronda le
//     prepara la respuesta);
//   · TODA publicación que hable de alquileres, temporada o la zona →
//     `notificaciones/` de Casa Verde, como una notificación más (`app` con
//     «·pantalla»): la ronda la clasifica y borra lo que no sirve, y vence a
//     los 7 días. Es el «máximo de información»: el filtro fino es de la ronda,
//     no del teléfono. Con los códigos y los enlaces tapados (`taparCodigos`).
// Lo que no habla de eso (un cumpleaños, una publicidad de zapatillas) no sale
// del teléfono: no hay por qué mandar el inicio de Facebook de nadie.
//
// LA BITÁCORA dice cuántas leyó y cuántas mandó, nunca el texto.
// ─────────────────────────────────────────────────────────────────────────────

/** Un nodo de la pantalla, sin Android: lo que el banco puede armar a mano. */
data class Nodo(val texto: String = "", val desc: String = "", val hijos: List<Nodo> = emptyList(), val editable: Boolean = false)

/** Una publicación leída: quién o qué grupo (el texto corto de antes) y lo que dice. */
data class Publicacion(val encabezado: String, val texto: String)

private const val LARGO_MINIMO = 60       // menos que eso es un botón, un nombre o una hora
private const val MAX_NODOS = 4000        // una pantalla de Facebook anda por 300 a 1500

/** El texto de un nodo: el escrito, o la descripción si no tiene (Facebook arma muchos así). */
private fun dicho(n: Nodo): String = n.texto.trim().ifEmpty { n.desc.trim() }

/** El árbol en el orden en que se lee, de arriba abajo, con un techo. */
fun enOrden(raiz: Nodo, techo: Int = MAX_NODOS): List<String> {
    val out = ArrayList<String>()
    val pila = ArrayDeque<Nodo>().apply { add(raiz) }
    var vistos = 0
    while (pila.isNotEmpty() && vistos < techo) {
        val n = pila.removeLast(); vistos++
        if (n.editable) continue      // pizarra-45: lo que la persona escribe (el buscador) no es una publicación
        val t = dicho(n); if (t.isNotEmpty()) out.add(t)
        // También la descripción cuando trae MÁS que el texto: en Facebook la
        // tarjeta de una publicación suele describirse entera.
        val d = n.desc.trim(); if (d.length > n.texto.trim().length + 20 && d != t) out.add(d)
        for (h in n.hijos.asReversed()) pila.add(h)
    }
    return out
}

/* Lo que Facebook pone en cada tarjeta y no es la publicación: los botones y
   sus instrucciones para quien usa un lector de pantalla (pizarra-45, visto en
   el primer recorrido real: «Botón "Compartir". Toca dos veces…»). */
private val RELLENO = Regex(
    "^(me gusta|comentar|compartir|enviar|responder|seguir|unirte|ver más|ver menos|curtir|comentário|compartilhar|like|comment|share|reply|" +
        "bot[oó]n|bot[aã]o|button|buscar en|pesquisar em|search in|m[aá]s opciones|mais op[cç][oõ]es|more options|foto del perfil|foto do perfil|" +
        "profile picture|administrar configuraci[oó]n|agregar a amigos|adicionar amigo|add friend)\\b|toca dos veces|toque duas vezes|double.tap",
    RegexOption.IGNORE_CASE)

/* El autor sale de la etiqueta del botón «⋮» de la tarjeta, que es lo único
   que Facebook rotula con el nombre: «Más opciones para la publicación de Ana». */
private val DE_AUTOR = Regex(
    "^(?:m[aá]s opciones para la publicaci[oó]n de|mais op[cç][oõ]es para a publica[cç][aã]o de|more options for (?:the )?post by|" +
        "foto del perfil de|foto do perfil de|profile picture of)\\s+(.{2,80}?)\\.?$",
    RegexOption.IGNORE_CASE)

/* Una notificación de la campana de Facebook trae al final hace cuánto y si se
   leyó («… 1 min. No leída»): eso cambia cada minuto y haría que la misma
   notificación entre una vez por minuto. Se saca antes de la huella. */
private val HACE_CUANTO = Regex(
    "[.\\s]*\\b\\d+\\s*(?:s|seg|min|h|d|sem|a)\\.?\\s*(?:no le[ií]da|le[ií]da|n[aã]o lida|lida|unread|read)?\\.?\\s*$",
    RegexOption.IGNORE_CASE)

fun sinHaceCuanto(t: String): String = t.replace(HACE_CUANTO, "").trim()

/** Las publicaciones de una pantalla: los textos largos, sin repetir, y sin el que ya está adentro de otro. */
fun publicacionesDe(raiz: Nodo): List<Publicacion> {
    val textos = enOrden(raiz)
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
        val autor = cerca.firstNotNullOfOrNull { DE_AUTOR.find(it)?.groupValues?.get(1) }
        val antes = autor ?: cerca.firstOrNull {
            it.length in 3..80 && !RELLENO.containsMatchIn(it) && !Regex("^\\d").containsMatchIn(it)
        }.orEmpty()
        Publicacion(antes, sinHaceCuanto(t).take(4000))
    }
}

private fun sinTildes(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/* Lo que vale la pena que vea la ronda: alquileres, temporada, la zona. Amplio
   a propósito —la ronda descarta—, pero no el inicio de Facebook entero. */
private val DE_INTERES = Regex(
    "\\b(alquil\\w*|alug\\w*|arriend\\w*|temporada|hosped\\w*|hospeda\\w*|vacacion\\w*|ferias|veraneo|verano|verao|" +
        "departamento|depto|apartamento|apto|kitnet|quarto|habitacion\\w*|cabana\\w*|pousada|posada|diaria\\w*|" +
        "canasvieiras|canas|jurere|ingleses|cachoeira|florianopolis|floripa|santa catarina|praia|playa)\\b")

fun esDeInteres(texto: String): Boolean = DE_INTERES.containsMatchIn(sinTildes(texto))

/** Lo que sale de una pantalla: las notificaciones para la ronda y los pedidos para la lista. */
fun queSaleDe(paquete: String, pubs: List<Publicacion>, ahora: Long, propio: String): Pair<List<Notif>, List<MensajeAirbnb>> {
    val notifs = ArrayList<Notif>(); val pedidos = ArrayList<MensajeAirbnb>()
    for (p in pubs) {
        if (!esDeInteres(p.encabezado + " " + p.texto)) continue
        // La huella no lleva la hora (Notif.id): la misma publicación leída diez veces es una.
        notifDe("$paquete·pantalla", p.encabezado.ifEmpty { "Facebook" }, p.texto, emptyList(), ahora, propio)?.let { notifs.add(it) }
        if (esPedidoDeAlquiler(p.encabezado + " " + p.texto))
            pedidos.add(MensajeAirbnb(taparCodigos(p.encabezado).take(120), taparCodigos(p.texto).take(4000), emptyList(), ahora.toString(), app = "facebook"))
    }
    return notifs to pedidos
}

/** El que lee. Android lo conecta cuando la persona lo enciende en Accesibilidad. */
class LectorPantalla : AccessibilityService() {
    private var ultima = 0L
    private val yaMandados = LinkedHashSet<String>()

    override fun onServiceConnected() {
        Pantalla.anotar(this, conectado = true)
        Bitacora.anotar(this, "pantalla: lector de Facebook CONECTADO")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val paquete = e?.packageName?.toString() ?: return
        if (paquete !in PAQUETES_FACEBOOK) return
        val ahora = System.currentTimeMillis()
        if (ahora - ultima < 1200) return          // Facebook cambia la pantalla decenas de veces por segundo
        ultima = ahora
        try {
            val raiz = rootInActiveWindow ?: return
            val pubs = publicacionesDe(aNodo(raiz, 0, intArrayOf(0)))
            val (notifs, pedidos) = queSaleDe(paquete, pubs, ahora, packageName)
            val nuevas = synchronized(yaMandados) {
                notifs.filter { yaMandados.add(it.id) }.also { while (yaMandados.size > 2000) yaMandados.remove(yaMandados.first()) }
            }
            val nuevosPedidos = synchronized(yaMandados) { pedidos.filter { yaMandados.add("p" + it.id) } }
            Pantalla.anotar(this, leidas = pubs.size, mandadas = nuevas.size, pedidos = nuevosPedidos.size)
            if (nuevas.isNotEmpty()) Notificaciones.mandar(this, nuevas)
            if (nuevosPedidos.isNotEmpty()) Pantalla.cargarPedidos(this, nuevosPedidos)
        } catch (t: Throwable) {
            Bitacora.anotar(this, "pantalla: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Del árbol de Android al nuestro. Sólo se LEE: ni una acción sobre el nodo. */
    private fun aNodo(n: AccessibilityNodeInfo, hondo: Int, cuenta: IntArray): Nodo {
        cuenta[0]++
        val hijos = if (hondo > 60 || cuenta[0] > MAX_NODOS) emptyList() else
            (0 until n.childCount).mapNotNull { i -> n.getChild(i)?.let { aNodo(it, hondo + 1, cuenta) } }
        return Nodo(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty(), hijos, n.isEditable)
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
        if (mandadas > 0 || pedidos > 0) Bitacora.anotar(c, "pantalla: $mandadas publicaciones para la ronda, $pedidos pedidos (de $leidas leídas)")
    }

    /** Para la pantalla de la app y el diagnóstico. Sin un solo texto leído. */
    fun estado(c: Context): String {
        if (!encendido(c)) return "apagado (Ajustes → Accesibilidad → Pizarra)"
        val pr = p(c); val hoy = isoUtc(System.currentTimeMillis()).take(10)
        if (pr.getString("dia", "") != hoy) return "encendido · hoy todavía no leyó Facebook"
        return "encendido · hoy: ${pr.getInt("leidas", 0)} publicaciones vistas, ${pr.getInt("mandadas", 0)} a la ronda, " +
            "${pr.getInt("pedidos", 0)} pedidos · última ${pr.getString("ultima", "")} UTC"
    }

    /** Los pedidos, a «Pedidos de alquiler» de Casa Verde, por el mismo camino que los de las notificaciones. */
    fun cargarPedidos(c: Context, ms: List<MensajeAirbnb>) {
        val app = c.applicationContext
        thread(name = "pantalla") {
            val cv = CuentaCasaVerde(app)
            if (!cv.conSesion) { cv.ultimo = "falta entrar con la cuenta de Casa Verde"; return@thread }
            for (m in ms) try {
                val nuevo = cv.crearPedido(m)
                cv.ultimo = isoUtc(System.currentTimeMillis()).take(16) + (if (nuevo) " · pedido cargado (pantalla)" else " · ya estaba")
            } catch (t: Throwable) {
                cv.ultimo = "no se pudo cargar: " + (t.message ?: t.javaClass.simpleName)
                Bitacora.anotar(app, "pantalla: el pedido no llegó a Casa Verde: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
