package com.maurogasta.pizarra

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

// ─────────────────────────────────────────────────────────────────────────────
// EnlaceServicio.kt — La escucha y el envío del enlace cerca. Sello: pizarra-75
// Ver Enlace.kt: qué es, por qué así y qué viaja.
//
// Mientras el enlace está prendido, este servicio:
//   · ESCUCHA por la red local (un puerto anunciado con NSD) y por Bluetooth
//     (un servicio con UUID_BT, sólo para emparejados);
//   · cuando tiene algo que mandar y no hay nadie conectado, BUSCA: la otra
//     Pizarra en la red local y, si no, entre los teléfonos emparejados;
//   · lo que llega lo muestra: un «vivo» en una notificación quieta, un
//     «evento» en una que suena, y la «voz» del handy cerca al parlante
//     (pizarra-71, Radio.kt). Eso es lo que despierta sin internet;
//   · si no hay nadie cerca y hay una cuenta de Tiempos, un evento va por
//     internet, por la base (pizarra-71, EnlaceLejos.kt), y el vivo también,
//     pero sólo mientras alguien lo mira. La voz no: es cerca o nada.
// Gasta poco: un puerto y un Bluetooth esperando. Buscar se hace cuando hay
// algo para mandar (como mucho cada BUSCAR_CADA_MS) y, sin nadie conectado,
// cada BUSCAR_SOLO_MS.
//
// pizarra-75 (28-oct, Mauro: «funciona aleatoriamente: de rato se conecta y
// después se desconecta»): una conexión que muere sin avisar —el wi-fi que
// duerme con la pantalla apagada, un cambio de red— se veía VIVA: el teléfono
// seguía mandando la voz a un enchufe muerto y no buscaba de nuevo. Ahora cada
// conexión manda un LATIDO cada LATIDO_MS, la que calla más de MUDA_MS se
// cierra (`vigia`), sin nadie se vuelve a buscar sola, y mientras hay una
// conexión por wi-fi el wi-fi no se duerme (`despierto`).
// ─────────────────────────────────────────────────────────────────────────────

const val CANAL_ENLACE = "enlace"
const val CANAL_ENLACE_EVENTOS = "enlace_eventos"
private const val ID_SERVICIO = 7070
private const val ID_VIVO = 7071
private const val BUSCAR_CADA_MS = 30_000L
private const val VIVO_SE_VA_MS = 30_000L
private const val PENDIENTES_MAX = 20
const val LATIDO_MS = 10_000L
const val MUDA_MS = 35_000L
const val BUSCAR_SOLO_MS = 60_000L

/** Una conexión que calla más de MUDA_MS está muerta, aunque el enchufe diga que no. */
fun estaMuda(ultimo: Long, ahora: Long): Boolean = ahora - ultimo > MUDA_MS
/** Lo que espera a que aparezca alguien: un vivo viejo o una voz de hace un rato no sirven. */
private val TIPOS_QUE_ESPERAN = setOf("evento", "prueba")

/** El enlace prendido o no, y el código. Vive en «enlace», fuera de las copias. */
object EnlaceAjustes {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("enlace", Context.MODE_PRIVATE)
    fun prendido(c: Context) = prefs(c).getBoolean("prendido", false)
    fun prender(c: Context, v: Boolean) = prefs(c).edit().putBoolean("prendido", v).apply()
    /**
     * El código con el que firma este teléfono. Desde pizarra-75, el de la CUENTA (`porCuenta`, traído de
     * Tiempos por `traerDeLaCuenta`); si no hay cuenta de Tiempos, el de a mano de siempre.
     */
    fun codigo(c: Context): String = porCuenta(c) ?: aMano(c)
    /** El de a mano (Avanzado): el de antes de pizarra-75, para un teléfono sin cuenta de Tiempos. */
    fun aMano(c: Context): String = prefs(c).getString("codigo", null) ?: codigoNuevo().also { prefs(c).edit().putString("codigo", it).apply() }
    fun porCuenta(c: Context): String? = if (prefs(c).getBoolean("usarAMano", false)) null else prefs(c).getString("codigoCuenta", null)
    /** Usar el código a mano aunque haya cuenta (para hablar con un teléfono sin cuenta de Tiempos). */
    fun usarAMano(c: Context, v: Boolean) = prefs(c).edit().putBoolean("usarAMano", v).apply()
    fun usaAMano(c: Context) = prefs(c).getBoolean("usarAMano", false)
    /** Trae el código de la cuenta y lo guarda. En un hilo; devuelve null si salió bien o el motivo. */
    fun traerDeLaCuenta(c: Context): String? = try {
        val n = Nube(c)
        if (!n.conSesion || !n.esTiempos) "Sin cuenta de Tiempos: queda el código a mano."
        else { prefs(c).edit().putString("codigoCuenta", n.llaveFamilia()).apply(); null }
    } catch (e: Throwable) { e.message ?: "No se pudo traer la llave del enlace." }
    /** Usar el código de la otra Pizarra. `false` si no es un código. */
    fun usarCodigo(c: Context, t: String): Boolean {
        val n = normalizarCodigo(t) ?: return false
        // pizarra-75: escribir el código de otra es elegir el código a mano en éste.
        prefs(c).edit().putString("codigo", n).putBoolean("usarAMano", true).apply(); return true
    }
    fun llave(c: Context) = llaveDe(codigo(c))
    /** pizarra-71: escuchar el handy cerca de la otra Pizarra (sí, salvo que se apague). */
    fun radio(c: Context) = prefs(c).getBoolean("radio", true)
    fun radioSiNo(c: Context, v: Boolean) = prefs(c).edit().putBoolean("radio", v).apply()
}

class EnlaceServicio : Service() {
    companion object {
        @Volatile private var activo: EnlaceServicio? = null
        /** Lo que se esperaba mandar antes de que el servicio estuviera listo. */
        private val antes = CopyOnWriteArrayList<JSONObject>()

        /** Lo que pasa ahora, en palabras, para la pantalla de ajustes. */
        @Volatile var estado: String = "apagado"; private set

        fun arrancar(c: Context) {
            if (!EnlaceAjustes.prendido(c)) return
            // pizarra-75: la llave de la cuenta, si todavía no está (una vez; después vive en el teléfono).
            if (EnlaceAjustes.porCuenta(c) == null && !EnlaceAjustes.usaAMano(c)) thread(name = "enlace-llave") {
                try { EnlaceAjustes.traerDeLaCuenta(c)?.let { Bitacora.anotar(c, "enlace: llave de la cuenta: ${it.take(80)}") } } catch (e: Throwable) { }
            }
            try { c.startForegroundService(Intent(c, EnlaceServicio::class.java)) }
            catch (e: Throwable) { Bitacora.anotar(c, "enlace: no arrancó (${e.javaClass.simpleName})") }
        }

        fun detener(c: Context) { c.stopService(Intent(c, EnlaceServicio::class.java)); estado = "apagado" }

        /** pizarra-72: lo último que contestó la otra Pizarra («✔ recibió la prueba», «📻 la escuchó»). */
        @Volatile var ultimoAcuse: String? = null; private set
        @Volatile var ultimoAcuseEn = 0L; private set

        /** pizarra-73: el teléfono que está conectado cerca ahora (lo que dice su `de`), o null. */
        fun cercano(): String? = activo?.let { s -> if (s.conexiones.isEmpty()) null else s.ultimoDeCerca }

        /** pizarra-75: los teléfonos conectados cerca ahora mismo, por su nombre (el `de` de lo que mandan). */
        fun cercanos(): Set<String> = activo?.conexiones?.mapNotNull { it.de }?.toSet() ?: emptySet()

        /**
         * pizarra-73: manda [o] cerca y ESPERA a que salga, en el mismo orden que todo lo demás.
         * Es para un archivo grande: trozo por trozo, sin llenar la memoria con una cola de miles.
         * `false` si no hay nadie cerca o se cortó.
         */
        fun mandarCercaYEsperar(o: JSONObject): Boolean = activo?.mandarYEsperar(o) ?: false

        /** El último «vivo» que llegó cerca (para «🚙 En vivo»). */
        @Volatile var ultimoVivo: JSONObject? = null; private set

        /** Manda [o] a las Pizarras cerca. Si el servicio no está, lo arranca y lo manda cuando esté. */
        fun enviar(c: Context, o: JSONObject) {
            val s = activo
            if (s != null) { s.mandar(o); return }
            if (o.optString("tipo") in TIPOS_QUE_ESPERAN && antes.size < PENDIENTES_MAX) antes.add(o)
            arrancar(c)
        }

        /** pizarra-75: la voz y el latido, SÓLO a los conectados: no esperan, no van por internet. */
        fun enviarSoloCerca(o: JSONObject) { activo?.mandarSoloCerca(o) }

        /** Buscar a la otra Pizarra ya (al abrir la radio o «En vivo»), sin esperar a tener algo para mandar. */
        fun buscarYa(c: Context) { activo?.buscar(forzar = true) ?: arrancar(c) }
    }

    private class Conexion(val socket: java.io.Closeable, val entrada: InputStream, val salida: OutputStream, val por: String, val quien: String) {
        /** pizarra-75: cuándo llegó lo último (un latido alcanza) y quién es, por lo que firma. */
        @Volatile var ultimo = System.currentTimeMillis()
        @Volatile var de: String? = null
    }

    private val conexiones = CopyOnWriteArrayList<Conexion>()
    /** pizarra-78: por quien habla, su ronda de voz y el trozo que se esperaba (Salud.huecosDeVoz). */
    private val vozVista = ConcurrentHashMap<String, Pair<String, Int>>()
    private val pendientes = CopyOnWriteArrayList<JSONObject>()
    private val ultimoDe = ConcurrentHashMap<String, Long>()
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var vivo = true
    private var servidorTcp: ServerSocket? = null
    private var servidorBt: BluetoothServerSocket? = null
    private var nsd: NsdManager? = null
    private var registro: NsdManager.RegistrationListener? = null
    @Volatile private var nombrePropio: String? = null
    @Volatile private var ultimaBusqueda = 0L
    private var candado: WifiManager.MulticastLock? = null
    private var despierto: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanales(this)
        val n = Notification.Builder(this, CANAL_ENLACE).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("📡 Enlace cerca").setContentText("Escuchando por wi-fi y Bluetooth, sin datos (lejos, por internet)").setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            // pizarra-71: la radio y lo que manda el detector, a un toque desde la barra.
            .addAction(Notification.Action.Builder(null, "📻 Hablar",
                PendingIntent.getActivity(this, 7073, Intent(this, RadioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build())
            .addAction(Notification.Action.Builder(null, "🚙 En vivo",
                PendingIntent.getActivity(this, 7074, Intent(this, EnVivoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_SERVICIO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID_SERVICIO, n)
        } catch (e: Throwable) { Bitacora.anotar(this, "enlace: sin servicio (${e.javaClass.simpleName})"); stopSelf(); return }
        activo = this
        estado = "escuchando"
        pendientes.addAll(antes); antes.clear()
        thread(name = "enlace-tcp") { escucharRed() }
        thread(name = "enlace-bt") { escucharBluetooth() }
        thread(name = "enlace-vigia") { vigia() }
        Bitacora.anotar(this, "enlace: prendido")
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int = START_STICKY

    override fun onDestroy() {
        vivo = false
        activo = null
        estado = "apagado"
        try { registro?.let { nsd?.unregisterService(it) } } catch (e: Throwable) { }
        try { servidorTcp?.close() } catch (e: Throwable) { }
        try { servidorBt?.close() } catch (e: Throwable) { }
        for (c in conexiones) cerrar(c)
        try { salida.shutdown() } catch (e: Throwable) { }
        try { candado?.release() } catch (e: Throwable) { }
        try { if (despierto?.isHeld == true) despierto?.release() } catch (e: Throwable) { }
        Bitacora.anotar(this, "enlace: apagado")
        super.onDestroy()
    }

    /* ── Escuchar ─────────────────────────────────────────────────────────── */

    private fun escucharRed() = try {
        val s = ServerSocket(0); servidorTcp = s
        val info = NsdServiceInfo().apply { serviceName = "Pizarra ${nombreDelTelefono(this@EnlaceServicio)}".take(60); serviceType = TIPO_NSD; port = s.localPort }
        val m = getSystemService(Context.NSD_SERVICE) as NsdManager; nsd = m
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { nombrePropio = i.serviceName }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Bitacora.anotar(this@EnlaceServicio, "enlace: la red local no anunció ($e)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        registro = l
        m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        while (vivo) {
            val c = s.accept()
            agregar(Conexion(c, c.getInputStream(), c.getOutputStream(), "wi-fi", c.inetAddress.hostAddress ?: "?"))
        }
    } catch (e: Throwable) { if (vivo) Bitacora.anotar(this, "enlace: la red local se cortó (${e.javaClass.simpleName})"); Unit }

    private fun puedeBluetooth() = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")   // puedeBluetooth() lo comprueba antes de cada uso
    private fun escucharBluetooth() = try {
        val a = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (a == null || !puedeBluetooth() || !a.isEnabled) { Bitacora.anotar(this, "enlace: sin Bluetooth (apagado o sin permiso)") }
        else {
            val s = a.listenUsingRfcommWithServiceRecord("Pizarra", UUID.fromString(UUID_BT)); servidorBt = s
            while (vivo) {
                val c = s.accept()
                c.remoteDevice?.address?.let { getSharedPreferences("enlace", Context.MODE_PRIVATE).edit().putString("btConocido", it).apply() }
                agregar(Conexion(c, c.inputStream, c.outputStream, "Bluetooth", c.remoteDevice?.name ?: "?"))
            }
        }
    } catch (e: Throwable) { if (vivo) Bitacora.anotar(this, "enlace: el Bluetooth se cortó (${e.javaClass.simpleName})"); Unit }

    /**
     * pizarra-75: el vigía. Cada LATIDO_MS dice «sigo acá» por cada conexión; cierra la que calló más de
     * MUDA_MS (así no se habla a un enchufe muerto); y sin nadie conectado busca solo cada BUSCAR_SOLO_MS.
     */
    private fun vigia() {
        var ultimoLatido = 0L
        while (vivo) {
            try {
                Thread.sleep(2_000)
                val ahora = System.currentTimeMillis()
                for (c in conexiones) if (estaMuda(c.ultimo, ahora)) {
                    Bitacora.anotar(this, "enlace: se cortó con ${c.de ?: c.quien} (${c.por}, sin latido)")
                    Salud.contar(this, "enlace.cortesSinLatido")
                    cerrar(c)
                }
                if (conexiones.isNotEmpty() && ahora - ultimoLatido >= LATIDO_MS) {
                    ultimoLatido = ahora
                    mandarSoloCerca(JSONObject().put("tipo", "latido"))
                }
                // Sola, por Bluetooth sólo prueba el teléfono con el que ya se habló: tocar todos los emparejados
                // cada minuto molestaría al auto y a los auriculares.
                if (conexiones.isEmpty() && ahora - ultimaBusqueda >= BUSCAR_SOLO_MS) buscar(forzar = true, btSoloConocido = true)
                wifiDespierto(conexiones.any { it.por == "wi-fi" })
            } catch (e: InterruptedException) { return } catch (e: Throwable) { }   // un hilo nunca tira
        }
    }

    /** Mientras hay una conexión por wi-fi, el wi-fi no se duerme: dormido, la corta sin avisar. */
    private fun wifiDespierto(si: Boolean) {
        try {
            if (si) {
                if (despierto == null) {
                    val w = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    despierto = w?.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pizarra-enlace")
                        ?.apply { setReferenceCounted(false) }
                }
                if (despierto?.isHeld == false) despierto?.acquire()
            } else if (despierto?.isHeld == true) despierto?.release()
        } catch (e: Throwable) { }
    }

    private fun agregar(c: Conexion) {
        conexiones.add(c)
        Salud.contar(this, "enlace.conexiones")
        estado = "conectado por ${c.por} con ${c.quien}"
        Bitacora.anotar(this, "enlace: $estado")
        thread(name = "enlace-lee") { leer(c) }
        // Lo que esperaba, sale ahora.
        val ya = pendientes.toList(); pendientes.clear()
        for (o in ya) mandar(o)
    }

    private fun leer(c: Conexion) {
        try {
            val r = BufferedReader(InputStreamReader(c.entrada, Charsets.UTF_8))
            while (vivo) {
                val renglon = r.readLine() ?: break
                c.ultimo = System.currentTimeMillis()
                val llave = EnlaceAjustes.llave(this)
                val o = leerRenglon(llave, renglon, System.currentTimeMillis(), ultimoDe)
                if (o == null) {
                    // pizarra-72: conectados pero con distinto código no se entienden; ahora se dice.
                    if (esDeOtroCodigo(llave, renglon) && !estado.contains("OTRO código")) {
                        Salud.contar(this, "enlace.otroCodigo")
                        estado = "conectado con ${c.quien}, pero con OTRO código: las dos Pizarras tienen que mostrar el mismo"
                        Bitacora.anotar(this, "enlace: la otra Pizarra tiene otro código")
                    }
                    continue
                }
                ultimoDeCerca = o.optString("de").ifBlank { null }
                if (c.de == null || estado.contains("OTRO código")) { c.de = ultimoDeCerca; estado = "conectado por ${c.por} con ${c.de ?: c.quien}" }
                when (o.optString("tipo")) {
                    "latido" -> { }   // pizarra-75: sólo dice que sigue: ya quedó en c.ultimo
                    "voz" -> {
                        // pizarra-75: la voz de una conversación es sólo para ese teléfono (o esa persona).
                        if (!vozParaMi(o.optString("para"), Nube(this).uid, nombreDelTelefono(this))) continue
                        // pizarra-78: rondas oídas y trozos perdidos, para el parte de salud.
                        val (nueva, perdidos) = huecosDeVoz(vozVista, o.optString("de"), o.optString("ronda"), o.optInt("n"))
                        if (nueva) Salud.contar(this, "voz.rondasOidas")
                        if (perdidos > 0) Salud.contar(this, "voz.trozosPerdidos", perdidos)
                        Altavoz.tocar(this, o)                // en este hilo: el sonido no espera
                        if (o.optBoolean("fin")) mandar(acuse(nombreDelTelefono(this), "voz", Altavoz.comoFue.ifBlank { "llegó" }))
                    }
                    "recibido" -> { ultimoAcuse = textoDeAcuse(o); ultimoAcuseEn = System.currentTimeMillis(); Salud.contar(this, contadorDeAcuse(o)) }
                    // pizarra-73: un archivo que alguien compartió, en trozos; al completarse avisa y contesta.
                    "archivo" -> RecepcionCerca.recibir(this, o)?.let { nombre -> mandar(acuse(nombreDelTelefono(this), "archivo", "llegó $nombre")) }
                    else -> {
                        if (o.optString("tipo") == "vivo") ultimoVivo = o
                        if (o.optString("tipo") == "prueba") mandar(acuse(nombreDelTelefono(this), "prueba", "llegó"))
                        principal.post { AvisosEnlace.mostrar(this, o) }
                    }
                }
            }
        } catch (e: Throwable) { }
        cerrar(c)
    }

    private fun cerrar(c: Conexion) {
        conexiones.remove(c)
        try { c.socket.close() } catch (e: Throwable) { }
        if (conexiones.isEmpty() && activo != null) estado = "escuchando"
    }

    /* ── Mandar ───────────────────────────────────────────────────────────── */

    @Volatile private var ultimoT = 0L
    /** pizarra-73: quién es el de cerca, por el `de` de lo último que llegó firmado. */
    @Volatile var ultimoDeCerca: String? = null; private set
    /**
     * pizarra-72: TODO lo que sale, en orden y por un solo hilo. Hasta pizarra-71 cada mensaje salía
     * en su hilo, la voz (diez trozos por segundo) llegaba desordenada y la otra Pizarra tiraba
     * todo lo que no avanzaba: la radio transmitía y del otro lado no se oía nada.
     */
    private val salida = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun mandar(o: JSONObject) {
        if (!o.has("de")) o.put("de", nombreDelTelefono(this))
        if (!o.has("v")) o.put("v", VERSION_ENLACE)
        // El instante siempre AVANZA: la otra Pizarra tira lo que no avanza, y diez trozos de voz por segundo se pisarían.
        synchronized(this) { ultimoT = maxOf(System.currentTimeMillis(), ultimoT + 1); o.put("t", ultimoT) }
        val renglon = renglonFirmado(EnlaceAjustes.llave(this), o.toString()).toByteArray(Charsets.UTF_8)
        if (conexiones.isEmpty()) {
            // Nadie cerca: un evento va por internet si se puede (pizarra-71) y si no espera; el vivo,
            // por internet sólo si alguien mira; la voz se pierde (es en vivo).
            val tipo = o.optString("tipo")
            val porInternet = tipo != "voz" && EnlaceLejos.mandar(this, o)
            if (!porInternet && tipo in TIPOS_QUE_ESPERAN && pendientes.size < PENDIENTES_MAX) pendientes.add(o)
            buscar(); return
        }
        try {
            salida.execute {
                for (c in conexiones) try { synchronized(c) { c.salida.write(renglon); c.salida.flush() } } catch (e: Throwable) { cerrar(c) }
            }
        } catch (e: Throwable) { }   // apagándose: lo que no salió, no sale
    }

    /** pizarra-75: el latido y la voz van SÓLO a quien está conectado: no esperan, no van por internet. */
    fun mandarSoloCerca(o: JSONObject) {
        if (conexiones.isEmpty()) return
        if (!o.has("de")) o.put("de", nombreDelTelefono(this))
        if (!o.has("v")) o.put("v", VERSION_ENLACE)
        synchronized(this) { ultimoT = maxOf(System.currentTimeMillis(), ultimoT + 1); o.put("t", ultimoT) }
        val renglon = renglonFirmado(EnlaceAjustes.llave(this), o.toString()).toByteArray(Charsets.UTF_8)
        try {
            salida.execute {
                for (c in conexiones) try { synchronized(c) { c.salida.write(renglon); c.salida.flush() } } catch (e: Throwable) { cerrar(c) }
            }
        } catch (e: Throwable) { }
    }

    fun mandarYEsperar(o: JSONObject): Boolean {
        if (conexiones.isEmpty()) return false
        if (!o.has("de")) o.put("de", nombreDelTelefono(this))
        if (!o.has("v")) o.put("v", VERSION_ENLACE)
        synchronized(this) { ultimoT = maxOf(System.currentTimeMillis(), ultimoT + 1); o.put("t", ultimoT) }
        val renglon = renglonFirmado(EnlaceAjustes.llave(this), o.toString()).toByteArray(Charsets.UTF_8)
        return try {
            salida.submit<Boolean> {
                var salio = false
                for (c in conexiones) try { synchronized(c) { c.salida.write(renglon); c.salida.flush() }; salio = true } catch (e: Throwable) { cerrar(c) }
                salio
            }.get()
        } catch (e: Throwable) { false }
    }

    /** La otra Pizarra: primero en la red local; si en 8 s no apareció, entre los emparejados. */
    fun buscar(forzar: Boolean = false, btSoloConocido: Boolean = false) {
        val ahora = System.currentTimeMillis()
        if (conexiones.isNotEmpty() || (!forzar && ahora - ultimaBusqueda < BUSCAR_CADA_MS) || ahora - ultimaBusqueda < 8_000L) return
        ultimaBusqueda = ahora
        estado = "buscando"
        thread(name = "enlace-busca") {
            try {
                try { buscarEnLaRed() } catch (e: Throwable) { }
                Thread.sleep(8_000)
                if (conexiones.isEmpty()) try { buscarBluetooth(btSoloConocido) } catch (e: Throwable) { }
                if (conexiones.isEmpty() && activo != null) estado = "nadie cerca · escuchando"
            } catch (e: Throwable) { }   // un hilo nunca tira
        }
    }

    private fun buscarEnLaRed() {
        val m = nsd ?: return
        val w = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (candado == null) candado = w?.createMulticastLock("pizarra-enlace")?.apply { setReferenceCounted(false) }
        try { candado?.acquire() } catch (e: Throwable) { }
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == nombrePropio || !i.serviceType.startsWith("_pizarra")) return
                @Suppress("DEPRECATION")
                m.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val host = r.host ?: return
                        thread(name = "enlace-conecta") {
                            try {
                                val s = Socket(); s.connect(InetSocketAddress(host, r.port), 4_000)
                                agregar(Conexion(s, s.getInputStream(), s.getOutputStream(), "wi-fi", r.serviceName))
                            } catch (e: Throwable) { }
                        }
                    }
                })
            }
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) { try { candado?.release() } catch (e: Throwable) { } }
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        m.discoverServices(TIPO_NSD, NsdManager.PROTOCOL_DNS_SD, l)
        principal.postDelayed({ try { m.stopServiceDiscovery(l) } catch (e: Throwable) { } }, 7_000)
    }

    @SuppressLint("MissingPermission")   // puedeBluetooth() lo comprueba antes
    private fun buscarBluetooth(soloConocido: Boolean = false) {
        val a = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!puedeBluetooth() || !a.isEnabled) return
        val conocido = getSharedPreferences("enlace", Context.MODE_PRIVATE).getString("btConocido", null)
        val candidatos = a.bondedDevices.orEmpty().filter { !soloConocido || it.address == conocido }
        for (d in candidatos) {
            if (conexiones.isNotEmpty() || !vivo) return
            var s: BluetoothSocket? = null
            try {
                s = d.createRfcommSocketToServiceRecord(UUID.fromString(UUID_BT))
                s.connect()
                getSharedPreferences("enlace", Context.MODE_PRIVATE).edit().putString("btConocido", d.address).apply()
                agregar(Conexion(s, s.inputStream, s.outputStream, "Bluetooth", d.name ?: "?"))
            } catch (e: Throwable) { try { s?.close() } catch (x: Throwable) { } }
        }
    }

}

/** Lo que llega de la otra Pizarra, cerca o por internet: un vivo quieto, un evento que suena. */
object AvisosEnlace {
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var nm: NotificationManager? = null
    private val sacarVivo = Runnable { try { nm?.cancel(ID_VIVO) } catch (e: Throwable) { } }

    fun mostrar(c: Context, o: JSONObject) {
        try {
            crearCanales(c)
            val m = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm = m
            if (o.optString("tipo") == "vivo") {
                val enVivo = PendingIntent.getActivity(c, 7071, Intent(c, EnVivoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                m.notify(ID_VIVO, Notification.Builder(c, CANAL_ENLACE).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle(textoVivo(o)).setContentText("En vivo · desde ${o.optString("de")}").setOngoing(true)
                    .setOnlyAlertOnce(true).setContentIntent(enVivo).build())
                principal.removeCallbacks(sacarVivo); principal.postDelayed(sacarVivo, VIVO_SE_VA_MS)
                return
            }
            val abrir = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val (titulo, cuerpo) = textoEvento(o)
            m.notify(("enlace:" + o.optLong("t")).hashCode(), Notification.Builder(c, if (suena(o)) CANAL_ENLACE_EVENTOS else CANAL_ENLACE)
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(titulo).setContentText(cuerpo.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(cuerpo)).setAutoCancel(true).setContentIntent(abrir).build())
            Bitacora.anotar(c, "enlace: llegó un ${o.optString("tipo")} de ${o.optString("app")} desde ${o.optString("de")}${if (o.optString("por") == "internet") " (por internet)" else ""}")
        } catch (e: Throwable) { }
    }
}

fun crearCanales(c: Context) {
    val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel(CANAL_ENLACE, "Enlace cerca (en vivo)", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(NotificationChannel(CANAL_ENLACE_EVENTOS, "Enlace cerca · lo que pasa", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Lo que manda la otra Pizarra cerca: un viaje que terminó, algo raro en la camioneta. Suena aunque no haya internet."
    })
}
