package com.maurogasta.pizarra

import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// Logica.kt — Lo que se puede probar sin teléfono. Sello: pizarra-1
//
// Traducir las tareas de Tiempos (Firestore REST) a la lista de la pizarra, y
// armar lo que se le manda a la base al tachar o al fijar una tarea. No toca
// red ni Android: lo corre el banco (`./gradlew test`).
//
// ── QUÉ ESCRIBE, Y POR QUÉ ESO Y NADA MÁS ────────────────────────────────────
// Tachar es EXACTAMENTE el botón «✔ Hecha» de Tiempos: `hecho: true` y
// `hechoPor: <uid>`. Destachar es «Volver a pendiente»: `hecho: false`. Así lo
// que se marca acá se ve igual en el sitio, para los dos.
//
// Qué tareas van a la pizarra lo dice el campo `pizarra` de la propia tarea:
// un mapa uid → true. Vive en la tarea y no en el teléfono para que reinstalar
// la app no la vacíe, y es por persona para que la de Mauro no sea la de
// Florencia. La regla de `tareas` ya lo permite: no hizo falta tocarla.
// ─────────────────────────────────────────────────────────────────────────────

const val PROYECTO = "tiempos-71d42"
const val BASE_DOCS = "projects/$PROYECTO/databases/(default)/documents"

data class Tarea(
    val id: String,
    val titulo: String,
    val hecho: Boolean,
    val alcance: String,
    val duenio: String,
    val parentId: String?,
    val enMiPizarra: Boolean,
    val color: String?,
)

/** Lee una tarea del formato de Firestore REST. Lo que no entiende, null. */
fun tareaDe(doc: JSONObject, uid: String): Tarea? {
    val nombre = doc.optString("name", "")
    val id = nombre.substringAfterLast('/', "")
    if (id.isEmpty()) return null
    val f = doc.optJSONObject("fields") ?: JSONObject()
    val titulo = texto(f, "titulo")?.trim().orEmpty()
    if (titulo.isEmpty()) return null
    val pizarra = f.optJSONObject("pizarra")?.optJSONObject("mapValue")?.optJSONObject("fields")
    val mia = pizarra?.optJSONObject(uid)?.optBoolean("booleanValue", false) ?: false
    return Tarea(
        id = id,
        titulo = titulo.take(120),
        hecho = f.optJSONObject("hecho")?.optBoolean("booleanValue", false) ?: false,
        alcance = texto(f, "alcance") ?: "comun",
        duenio = texto(f, "duenio").orEmpty(),
        parentId = texto(f, "parentId"),
        enMiPizarra = mia,
        color = texto(f, "color"),
    )
}

private fun texto(f: JSONObject, campo: String): String? =
    f.optJSONObject(campo)?.let { if (it.has("stringValue")) it.optString("stringValue") else null }

/** La respuesta de runQuery: una lista de { document } (o sólo { readTime }). */
fun tareasDeConsulta(respuesta: JSONArray, uid: String): List<Tarea> =
    (0 until respuesta.length()).mapNotNull { i ->
        respuesta.optJSONObject(i)?.optJSONObject("document")?.let { tareaDe(it, uid) }
    }

/**
 * Lo que muestra el widget: las tareas que la persona fijó, pendientes
 * primero y tachadas al final. Una subtarea lleva el nombre de su proyecto
 * adelante, porque «Comprar tornillos» sola no dice de qué.
 */
fun paraLaPizarra(todas: List<Tarea>): List<Fila> {
    val porId = todas.associateBy { it.id }
    return todas.filter { it.enMiPizarra }
        .sortedWith(compareBy<Tarea>({ it.hecho }, { it.titulo.lowercase() }))
        .map { t ->
            val padre = t.parentId?.let { porId[it]?.titulo }
            Fila(t.id, if (padre != null) "$padre › ${t.titulo}" else t.titulo, t.hecho)
        }
}

data class Fila(val id: String, val texto: String, val hecho: Boolean)

/** Las que se pueden ofrecer para fijar: pendientes y las ya fijadas. */
fun paraElegir(todas: List<Tarea>): List<Tarea> =
    todas.filter { !it.hecho || it.enMiPizarra }
        .sortedWith(compareBy<Tarea>({ !it.enMiPizarra }, { it.hecho }, { it.titulo.lowercase() }))

/* ── Lo que se escribe ─────────────────────────────────────────────────────
   Un `commit` con UNA escritura: sólo los campos de la máscara, la tarea tiene
   que existir (no se crea una tarea fantasma si alguien la borró en el medio),
   y `actualizadoEn` con la hora del servidor, como `serverTimestamp()` del
   sitio. */

private fun escritura(id: String, campos: JSONObject, mascara: List<String>): JSONObject =
    JSONObject()
        .put("update", JSONObject().put("name", "$BASE_DOCS/tareas/$id").put("fields", campos))
        .put("updateMask", JSONObject().put("fieldPaths", JSONArray(mascara)))
        .put("updateTransforms", JSONArray().put(
            JSONObject().put("fieldPath", "actualizadoEn").put("setToServerValue", "REQUEST_TIME")))
        .put("currentDocument", JSONObject().put("exists", true))

/** Tachar (hecho) o destachar (pendiente). Igual que los botones del sitio. */
fun cuerpoTachar(id: String, hecho: Boolean, uid: String): JSONObject {
    val campos = JSONObject().put("hecho", JSONObject().put("booleanValue", hecho))
    val mascara = mutableListOf("hecho")
    if (hecho) {
        campos.put("hechoPor", JSONObject().put("stringValue", uid))
        mascara.add("hechoPor")
    }
    return JSONObject().put("writes", JSONArray().put(escritura(id, campos, mascara)))
}

/**
 * Fijar o sacar una tarea de MI pizarra. Toca sólo `pizarra.<mi uid>`: la
 * del otro queda como está. Sacar es dejar el campo en la máscara y afuera de
 * los datos, que en Firestore quiere decir borrarlo.
 */
fun cuerpoFijar(id: String, fijar: Boolean, uid: String): JSONObject {
    val campos = JSONObject()
    if (fijar) campos.put("pizarra", JSONObject().put("mapValue",
        JSONObject().put("fields", JSONObject().put(uid, JSONObject().put("booleanValue", true)))))
    // Entre acentos graves: un uid puede empezar con un número, y entonces no
    // es un nombre de campo válido sin ellos.
    return JSONObject().put("writes", JSONArray().put(escritura(id, campos, listOf("pizarra.`$uid`"))))
}

/** Las dos consultas del sitio: las comunes, y las personales mías. */
fun consultaComunes(): JSONObject = consulta(filtro("alcance", "comun"))

fun consultaMias(uid: String): JSONObject = consulta(JSONObject().put("compositeFilter", JSONObject()
    .put("op", "AND")
    .put("filters", JSONArray().put(filtro("alcance", "personal")).put(filtro("duenio", uid)))))

private fun filtro(campo: String, valor: String) = JSONObject().put("fieldFilter", JSONObject()
    .put("field", JSONObject().put("fieldPath", campo))
    .put("op", "EQUAL")
    .put("value", JSONObject().put("stringValue", valor)))

private fun consulta(where: JSONObject) = JSONObject().put("structuredQuery", JSONObject()
    .put("from", JSONArray().put(JSONObject().put("collectionId", "tareas")))
    .put("where", where))

/* ── La caché del widget ───────────────────────────────────────────────────
   El widget no puede esperar a la red para dibujarse: dibuja lo último que
   se guardó, y la red lo corrige después. */

fun filasAJson(filas: List<Fila>): String = JSONArray().apply {
    filas.forEach { put(JSONObject().put("id", it.id).put("texto", it.texto).put("hecho", it.hecho)) }
}.toString()

fun filasDeJson(texto: String?): List<Fila> = try {
    val a = JSONArray(texto ?: "[]")
    (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { o ->
            val id = o.optString("id", "")
            if (id.isEmpty()) null else Fila(id, o.optString("texto", ""), o.optBoolean("hecho", false))
        }
    }
} catch (e: Exception) {
    emptyList()
}

/** Tachar sin esperar a la red: la fila cambia ya, y la red lo confirma. */
fun conTachada(filas: List<Fila>, id: String, hecho: Boolean): List<Fila> =
    filas.map { if (it.id == id) it.copy(hecho = hecho) else it }
        .sortedWith(compareBy<Fila>({ it.hecho }, { it.texto.lowercase() }))
