package com.maurogasta.pizarra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// Busqueda.kt — Qué pedidos de alquiler se buscan AHORA, y si una publicación
// cuadra. Sello: pizarra-49
//
// 20-oct-2026, Mauro: «en la misma aplicación de Casa Verde, para ver los
// pedidos, tiene que haber una configuración de los parámetros que yo estoy
// queriendo buscar en ese momento: temporada diaria, mensual o anual, para
// tantas personas, si tengo una cabaña libre o dos… si es grupo de jóvenes o
// familias». Y: «lo más automáticamente posible el registro, y lo más rápido
// de dónde va a ir a hacer la respuesta… se pueden alquilar varias cabañas al
// mismo tiempo, el máximo unas 20 personas. En la respuesta debe sugerir
// siempre visitar nuestra página web casaverdecanas.com.br».
//
// La búsqueda vive en `pedidos_busqueda/actual` de Casa Verde (la edita quien
// tiene el permiso de reservas, en «Pedidos de alquiler»). La Pizarra la trae
// con la cuenta de Casa Verde del teléfono y la usa al leer la pantalla.
//
// QUÉ ES UN PEDIDO para el lector: alguien que BUSCA (no que ofrece) dónde
// alojarse, en la zona elegida, del tipo elegido, para no más personas que el
// máximo, y del público elegido. Lo que no se puede saber del texto (cuántos
// son, si son familia) no descarta: lo decide la persona al leerlo.
// ─────────────────────────────────────────────────────────────────────────────

const val SITIO_WEB = "casaverdecanas.com.br"
const val COLECCION_BUSQUEDA = "pedidos_busqueda"

data class Busqueda(
    val tipos: Set<String> = setOf("diaria"),
    val personasMax: Int = 20,
    val publico: Set<String> = emptySet(),          // vacío = cualquiera
    val zonas: Set<String> = setOf("canasvieiras", "norte"),
    val activa: Boolean = true,
)

/** Lo que salió de mirar una publicación contra la búsqueda. */
data class Encaje(val ok: Boolean, val motivo: String, val personas: Int = 0, val tipo: String = "diaria", val publico: String = "")

private fun plano(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/* Alguien que BUSCA. «Alquilo mi casa» o «reservas por aquí» es una oferta. */
private val BUSCA = Regex("\\b(busc\\w*|procur\\w*|necesit\\w*|precis\\w*|recomiend\\w*|recomend\\w*|alguien|alguem|algun contacto|" +
    "sugerencia\\w*|sugest\\w*|consulta|cotizac\\w*|orcamento|donde (me )?(alojar|quedar|hosped)|onde (ficar|hosped))")
private val OFRECE = Regex("\\b(alquilo|alugo|ofrec\\w*|ofere\\w*|disponibles? (para|desde|en)|temos disponib\\w*|tenemos disponib\\w*|" +
    "reserva(s)? (por aqui|ya)|reserve ja|consulte disponib\\w*|inmobiliaria|imobiliaria|anunci\\w*)")
private val ALOJARSE = Regex("\\b(alquil\\w*|alug\\w*|hosped\\w*|aloja\\w*|casa|depto|departamento|apartamento|apto|kitnet|kitinete|" +
    "cabana\\w*|quarto|habitacion\\w*|lugar para|donde quedar|onde ficar|posada|pousada)")

private val ZONAS = mapOf(
    "canasvieiras" to Regex("\\b(canasvieiras?|canavieiras?|canasvieras|casnavieras?|canas)\\b"),
    "norte" to Regex("\\b(ingleses|jurere|cachoeira|ponta das canas|praia brava|lagoinha|santinho|daniela|norte da ilha|norte de la isla|norte de floripa|norte de florianopolis)\\b"),
    "florianopolis" to Regex("\\b(florianopolis|floripa|ilha da magia|isla de santa catarina)\\b"),
)

private val ANUAL = Regex("\\b(anual|todo el ano|ano entero|largo plazo|longo prazo|fixo|para vivir|para morar|contrato)\\b")
private val MENSUAL = Regex("\\b(mensual|por mes|un mes|1 mes|dos meses|2 meses|tres meses|3 meses|mes entero|temporada completa|mensal)\\b")

private val JOVENES = Regex("\\b(jovenes|amigos|amigas|despedida|egresad\\w*|estudiantes|chicas|chicos de \\d+|galera|turma)\\b")
private val FAMILIAS = Regex("\\b(familia\\w*|ninos?|ninas?|criaturas|criancas?|hijos?|hijas?|bebes?|nenes?|menores|filhos?)\\b")
private val PAREJAS = Regex("\\b(pareja|casal|novios|luna de miel|lua de mel)\\b")

/* Cuántos son: «7 adultos y 6 niños» suma 13; «somos 4»; «familia de 5». */
private val CUENTA = Regex("(\\d{1,2})\\s*(adult\\w*|personas?|pessoas?|ninos?|ninas?|criaturas|criancas?|chicos|menores|bebes?|pax|huespedes|hospedes|integrantes)")
private val SOMOS = Regex("\\b(somos|seriamos|seremos|familia de|grupo de|para)\\s+(\\d{1,2})\\b")

fun cuantasPersonas(texto: String): Int {
    val t = plano(texto)
    val sumadas = CUENTA.findAll(t).sumOf { it.groupValues[1].toInt() }
    if (sumadas > 0) return sumadas
    return SOMOS.findAll(t).map { it.groupValues[2].toInt() }.maxOrNull() ?: 0
}

fun tipoDe(texto: String): String { val t = plano(texto); return if (ANUAL.containsMatchIn(t)) "anual" else if (MENSUAL.containsMatchIn(t)) "mensual" else "diaria" }

fun publicoDe(texto: String, personas: Int): String {
    val t = plano(texto)
    return when {
        JOVENES.containsMatchIn(t) -> "jovenes"
        FAMILIAS.containsMatchIn(t) -> "familias"
        PAREJAS.containsMatchIn(t) || personas == 2 -> "parejas"
        personas >= 8 -> "grupos"
        else -> ""
    }
}

/** ¿Esta publicación es un pedido que hoy se busca? Con el motivo, para la bitácora y el banco. */
fun encaje(texto: String, b: Busqueda): Encaje {
    val t = plano(texto)
    if (!b.activa) return Encaje(false, "búsqueda apagada")
    val busca = BUSCA.containsMatchIn(t) || Regex("\\?").containsMatchIn(t)
    if (!busca || (OFRECE.containsMatchIn(t) && !BUSCA.containsMatchIn(t))) return Encaje(false, "no busca: ofrece o comenta")
    if (!ALOJARSE.containsMatchIn(t)) return Encaje(false, "no habla de alojarse")
    if (b.zonas.isNotEmpty() && b.zonas.none { ZONAS[it]?.containsMatchIn(t) == true }) return Encaje(false, "otra zona")
    val tipo = tipoDe(texto)
    if (tipo !in b.tipos) return Encaje(false, "busca alquiler $tipo")
    val n = cuantasPersonas(texto)
    if (n > b.personasMax) return Encaje(false, "son $n, más de ${b.personasMax}", n, tipo)
    val pub = publicoDe(texto, n)
    if (b.publico.isNotEmpty() && pub.isNotEmpty() && pub !in b.publico) return Encaje(false, "público: $pub", n, tipo, pub)
    return Encaje(true, "cuadra", n, tipo, pub)
}

/** El documento de Firestore (`fields`) a una Busqueda; lo que falte toma el valor de siempre. */
fun busquedaDeCampos(f: JSONObject?): Busqueda {
    if (f == null) return Busqueda()
    fun lista(k: String, def: Set<String>): Set<String> {
        val o = f.optJSONObject(k) ?: return def
        val a = o.optJSONObject("arrayValue")?.optJSONArray("values") ?: return emptySet()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("stringValue")?.takeIf { s -> s.isNotEmpty() } }.toSet()
    }
    val max = f.optJSONObject("personasMax")?.optString("integerValue")?.toIntOrNull() ?: 20
    val activa = f.optJSONObject("activa")?.optBoolean("booleanValue", true) ?: true
    return Busqueda(lista("tipos", setOf("diaria")), max.coerceIn(1, 40), lista("publico", emptySet()),
        lista("zonas", setOf("canasvieiras", "norte")), activa)
}

fun enPortugues(texto: String): Boolean =
    Regex("\\b(voce|voces|procuro|procurando|alguem|pessoas|obrigad\\w*|criancas|estamos|gostaria|aluguel|ficar|praia)\\b").containsMatchIn(plano(texto)) &&
        !Regex("\\b(busco|buscamos|personas|gracias|ninos|alquiler|playa)\\b").containsMatchIn(plano(texto))

/** La respuesta rápida que la Pizarra deja lista en el acto. Corta, distinta según quién, y SIEMPRE con la página. */
fun respuestaRapida(nombre: String, texto: String, e: Encaje, semilla: Int, lugar: Lugar = Lugar.SinFechas): String {
    val pila = nombre.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.length in 2..20 && it[0].isUpperCase() }
    val pt = enPortugues(texto)
    val k = Math.floorMod(semilla, 3)
    if (pt) {
        val ola = listOf("Olá", "Oi", "Olá, tudo bem?")[k] + (if (pila != null) " $pila" else "") + "!"
        val frase = when {
            lugar is Lugar.Hay && lugar.cabanas.size > 1 -> " Temos lugar ${rangoEnPalabras(lugar.desde, lugar.hasta, true)} em Canasvieiras: ${lugar.cabanas.size} cabanas juntas para os ${e.personas}"
            lugar is Lugar.Hay -> " Temos lugar ${rangoEnPalabras(lugar.desde, lugar.hasta, true)} nas nossas cabanas em Canasvieiras" + (if (e.personas > 0) ", para os ${e.personas}" else "")
            lugar is Lugar.MesConLugar -> " Em ${mesEnPalabras(lugar.mes, true)} ainda temos datas livres nas nossas cabanas em Canasvieiras"
            e.personas > 7 -> " Temos cabanas em Canasvieiras e alugamos várias juntas, até 20 pessoas"
            e.personas > 0 -> " Temos cabanas em Canasvieiras com lugar para ${e.personas} pessoas"
            else -> " Temos cabanas em Canasvieiras, no norte da ilha"
        } + (if (e.tipo == "mensual") ", também por mês" else if (e.tipo == "anual") ", também por ano" else "") + "."
        val fim = listOf(" Veja fotos e disponibilidade em $SITIO_WEB e chama no privado 😊",
            " Dá uma olhada em $SITIO_WEB e me escreve no privado.",
            " Fotos e datas livres em $SITIO_WEB; qualquer dúvida, no privado!")[k]
        return ola + frase + fim
    }
    val hola = listOf("¡Hola", "Hola", "¡Buenas")[k] + (if (pila != null) " $pila" else "") + "!"
    val frase = when {
        lugar is Lugar.Hay && lugar.cabanas.size > 1 -> " Tenemos lugar ${rangoEnPalabras(lugar.desde, lugar.hasta, false)} en Canasvieiras: ${lugar.cabanas.size} cabañas juntas para los ${e.personas}"
        lugar is Lugar.Hay -> " Tenemos lugar ${rangoEnPalabras(lugar.desde, lugar.hasta, false)} en nuestras cabañas de Canasvieiras" + (if (e.personas > 0) ", para los ${e.personas}" else "")
        lugar is Lugar.MesConLugar -> " En ${mesEnPalabras(lugar.mes, false)} todavía tenemos fechas libres en nuestras cabañas de Canasvieiras"
        e.personas > 7 -> " Tenemos cabañas en Canasvieiras y alquilamos varias juntas, hasta 20 personas"
        e.personas > 0 -> " Tenemos cabañas en Canasvieiras con lugar para ${e.personas}"
        e.publico == "familias" -> " Tenemos cabañas en Canasvieiras, ideales para ir en familia"
        else -> " Tenemos cabañas en Canasvieiras, al norte de Floripa"
    } + (if (e.tipo == "mensual") ", también por mes" else if (e.tipo == "anual") ", también por año" else "") + "."
    val fin = listOf(" Mirá fotos y disponibilidad en $SITIO_WEB y escribinos por privado 😊",
        " Pegale una mirada a $SITIO_WEB y escribime por privado.",
        " Fotos y fechas libres en $SITIO_WEB; cualquier duda, por privado.")[k]
    return hola + frase + fin
}

/** La búsqueda guardada en el teléfono, y traerla de Casa Verde. */
object Buscador {
    private fun p(c: Context) = c.getSharedPreferences("busqueda", Context.MODE_PRIVATE)

    /** La última que se trajo; sin traer, la de siempre (Canasvieiras y el norte, por días, hasta 20). */
    fun actual(c: Context): Busqueda = try {
        p(c).getString("campos", null)?.let { busquedaDeCampos(JSONObject(it)) } ?: Busqueda()
    } catch (e: Exception) { Busqueda() }

    fun traida(c: Context): Long = p(c).getLong("en", 0)

    /** Trae `pedidos_busqueda/actual`. Nunca tira: si no se puede, queda la que había. */
    fun traer(c: Context) {
        try {
            val cv = CuentaCasaVerde(c)
            if (!cv.conSesion) return
            val f = cv.busqueda()
            p(c).edit().putString("campos", (f ?: JSONObject()).toString()).putLong("en", System.currentTimeMillis()).apply()
        } catch (t: Throwable) {
            Bitacora.anotar(c, "búsqueda: no se pudo traer: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Si la que hay tiene más de un cuarto de hora, la vuelve a traer (en el hilo de quien llama). */
    fun alDia(c: Context, ahora: Long = System.currentTimeMillis()) { if (ahora - traida(c) > 15 * 60_000L) traer(c) }
}

/** Para leer `batchGet`: los `fields` del documento, o null si no existe. */
fun camposDeBatchGet(texto: String): JSONObject? = try {
    val a = JSONArray(texto)
    (0 until a.length()).firstNotNullOfOrNull { a.optJSONObject(it)?.optJSONObject("found")?.optJSONObject("fields") }
} catch (e: Exception) { null }
