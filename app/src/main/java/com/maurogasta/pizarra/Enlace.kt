package com.maurogasta.pizarra

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ─────────────────────────────────────────────────────────────────────────────
// Enlace.kt — El enlace CERCA entre Pizarras, sin datos. Sello: pizarra-71
//
// Mauro, 26-oct-2026 (general:C4): «me interesa que se pueda utilizar sin
// consumir datos, para que ande offline en el caso de tener dos dispositivos
// cercanos dentro del mismo auto o dentro de la misma casa, y que pueda
// despertar al teléfono». Y la primera app que lo usa es la de la Hilux: el
// Note 9 de la cabina le pasa a su Pizarra el estado del viaje y lo que pasa
// (EnlaceProveedor.kt), y su Pizarra lo manda a la tuya.
//
// ── CÓMO ANDA ────────────────────────────────────────────────────────────────
// Sin biblioteca nueva: todo viene con Android.
//   · En la CASA, los dos en el mismo wi-fi: cada Pizarra se anuncia en la red
//     local (NSD, el mDNS de Android) y se conectan directo. No sale a internet.
//   · En el AUTO, sin wi-fi: Bluetooth clásico, con los teléfonos EMPAREJADOS
//     una vez desde los ajustes de Android. Sólo con emparejados: la Pizarra
//     no busca aparatos desconocidos.
// La Pizarra que tiene el enlace prendido ESCUCHA siempre (EnlaceServicio, con
// su notificación fija): así lo que llega la despierta sin internet.
//
// ── LA LLAVE ─────────────────────────────────────────────────────────────────
// Cualquiera en el wi-fi de la casa puede tocar la puerta. Por eso cada
// mensaje va FIRMADO (HMAC-SHA256) con una llave que sale de un CÓDIGO de 10
// letras: se genera en una Pizarra y se escribe una vez en la otra. Lo que no
// trae la firma de ese código, no se lee. El código no sale nunca del teléfono
// por la red: sólo viaja la firma. Además se descarta lo viejo (más de
// VENTANA_MS) y lo repetido (un instante que no avanza), para que grabar un
// mensaje y volver a mandarlo no sirva.
//
// ── LO QUE VIAJA ─────────────────────────────────────────────────────────────
// Un renglón por mensaje: «<firma>\t<json>\n». El JSON lleva v, app, tipo
// («vivo» o «evento»), t, de (el teléfono que lo manda) y lo de cada tipo. Un
// «vivo» actualiza una notificación quieta; un «evento» suena.
// ─────────────────────────────────────────────────────────────────────────────

const val VERSION_ENLACE = 1
const val TIPO_NSD = "_pizarra._tcp."
/** El servicio de Bluetooth de la Pizarra: un número fijo, el mismo en todas. */
const val UUID_BT = "5a1c7e4e-2d3b-4f6a-9c1e-70a12f0b6c55"
/** Más viejo o más adelantado que esto, no se lee: dos relojes de teléfono no difieren tanto. */
const val VENTANA_MS = 10 * 60_000L
/** Un renglón más largo que esto no es un mensaje del enlace. */
const val LARGO_MAXIMO = 4096
const val LARGO_CODIGO = 10

/** La app de la Hilux: el único que puede hablarle a la puerta local (EnlaceProveedor). */
const val PAQUETE_HILUX = "uy.gasta.sitd_hilux"
/** La huella SHA-256 de la firma propia de la Hilux (`empaquetado.huella` de `proyectos/hilux` del panel). */
const val HUELLA_HILUX = "E494A3F3F8D349E4FED167FFF6AB2684B0F7C577E1F2E348FDF858266D8425F2"

private const val LETRAS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // sin 0/O ni 1/I: se dictan sin dudas

/** Un código nuevo, al azar. */
fun codigoNuevo(azar: SecureRandom = SecureRandom()): String =
    (1..LARGO_CODIGO).map { LETRAS[azar.nextInt(LETRAS.length)] }.joinToString("")

/** Lo que alguien tecleó, en la forma del código: mayúsculas, sin espacios ni guiones. `null` si no es un código. */
fun normalizarCodigo(t: String): String? {
    val c = t.uppercase().filter { !it.isWhitespace() && it != '-' && it != '·' && it != '.' }
    return if (c.length == LARGO_CODIGO && c.all { it in LETRAS }) c else null
}

/** El código en grupos, para leerlo: «ABCDE-FGHJK». */
fun codigoParaLeer(c: String) = if (c.length == LARGO_CODIGO) c.substring(0, 5) + "-" + c.substring(5) else c

/** La llave de firmar sale del código, nunca es el código. */
fun llaveDe(codigo: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest("pizarra-enlace:$codigo".toByteArray())

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

fun firmar(llave: ByteArray, json: String): String {
    val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(llave, "HmacSHA256"))
    return hex(mac.doFinal(json.toByteArray()))
}

/** El renglón que viaja. */
fun renglonFirmado(llave: ByteArray, json: String): String = firmar(llave, json) + "\t" + json + "\n"

/**
 * Lee un renglón con desconfianza: firma, forma, edad y que no sea repetido.
 * [ultimoDe] dice el último instante leído de cada teléfono; si pasa, se actualiza.
 */
fun leerRenglon(llave: ByteArray, renglon: String, ahora: Long, ultimoDe: MutableMap<String, Long>): JSONObject? {
    val r = renglon.trimEnd('\n', '\r')
    if (r.length > LARGO_MAXIMO) return null
    val tab = r.indexOf('\t')
    if (tab != 64) return null
    val firma = r.substring(0, tab); val json = r.substring(tab + 1)
    if (!MessageDigest.isEqual(firma.toByteArray(), firmar(llave, json).toByteArray())) return null
    val o = try { JSONObject(json) } catch (e: Throwable) { return null }
    if (o.optInt("v") != VERSION_ENLACE) return null
    if (o.optString("tipo") !in TIPOS_ENLACE) return null
    val t = o.optLong("t", 0L)
    if (t <= 0L || kotlin.math.abs(ahora - t) > VENTANA_MS) return null
    val de = o.optString("de").take(60)
    if (de.isEmpty()) return null
    if (t <= (ultimoDe[de] ?: 0L)) return null
    ultimoDe[de] = t
    return o
}

/** Los tipos que hay. Lo demás no se manda ni se lee. «voz» es el handy cerca (pizarra-71, Radio.kt). */
val TIPOS_ENLACE = setOf("vivo", "evento", "prueba", "voz")
/** Lo que puede mandar otra app por la puerta local: la voz, no (es de la Pizarra). */
val TIPOS_DE_APP = setOf("vivo", "evento", "prueba")

/**
 * Lo que manda la app de la Hilux, leído con desconfianza: sólo `tipo` conocido,
 * campos de texto y números chicos, y SIN coordenadas aunque vinieran (la posición
 * de la camioneta no viaja por acá). Le pone `v`, `app`, `t` y `de`.
 */
fun mensajeDeApp(app: String, json: String, de: String, ahora: Long): JSONObject? {
    if (json.length > LARGO_MAXIMO) return null
    val o = try { JSONObject(json) } catch (e: Throwable) { return null }
    val tipo = o.optString("tipo")
    if (tipo !in TIPOS_DE_APP) return null
    val salida = JSONObject().put("v", VERSION_ENLACE).put("app", app).put("tipo", tipo).put("t", ahora).put("de", de.take(60))
    for (k in o.keys()) {
        if (k in setOf("v", "app", "tipo", "t", "de") || k.lowercase() in setOf("lat", "lon", "latitud", "longitud", "posicion")) continue
        when (val x = o.get(k)) {
            is String -> salida.put(k.take(30), x.take(300))
            is Number, is Boolean -> salida.put(k.take(30), x)
        }
    }
    return salida
}

/** El renglón de la notificación quieta de un «vivo»: «🚙 82 km/h · 2.100 RPM · 5.ª · 34,2 km». */
fun textoVivo(o: JSONObject): String {
    val partes = mutableListOf<String>()
    if (o.has("kmh")) partes.add("${o.optDouble("kmh").let { Math.round(it) }} km/h")
    if (o.has("rpm")) partes.add("${o.optDouble("rpm").let { (Math.round(it / 10) * 10).toInt() }.let { "%,d".format(it).replace(',', '.') }} RPM")
    if (o.has("marcha")) partes.add("${o.optInt("marcha")}.ª")
    if (o.has("km")) partes.add("%.1f km".format(java.util.Locale.ROOT, o.optDouble("km")).replace('.', ','))
    val titulo = if (o.optString("app") == "hilux") "🚙" else "📡"
    return "$titulo " + (if (partes.isEmpty()) o.optString("texto", "en vivo").take(120) else partes.joinToString(" · "))
}

/** El texto de un «evento», para la notificación que suena. */
fun textoEvento(o: JSONObject): Pair<String, String> {
    val titulo = o.optString("titulo").ifEmpty { if (o.optString("app") == "hilux") "🚙 Hilux" else "📡 Enlace" }.take(80)
    val cuerpo = o.optString("texto").take(300)
    return titulo to (if (o.optString("de").isNotEmpty()) "$cuerpo\n· desde ${o.optString("de")}" else cuerpo)
}

/** Si un evento es de los que tienen que sonar (todos, salvo una prueba: ésa avisa sin hacer ruido de más). */
fun suena(o: JSONObject): Boolean = o.optString("tipo") == "evento"

/** La huella SHA-256 de un certificado, en mayúsculas y sin dos puntos, como `HUELLA_HILUX`. */
fun huellaDe(certificado: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(certificado).joinToString("") { "%02X".format(it) }
