# Pizarra — la app de Tiempos en el teléfono

**Desde `pizarra-2` (5-oct-2026) es la app de Tiempos en Android** —se llama
«Tiempos» en el teléfono; el widget sigue siendo «Pizarra»—. Lo decidió
Mauro: «que la Pizarra sea la app de Tiempos». Hace lo que el sitio no puede
con el teléfono bloqueado (**sonar a una hora**) y lo que el teléfono hace
mejor (**dictar**); lo demás sigue en el sitio.

Widget de Android con las tareas de **Tiempos** que elegiste, para verlas en la
pantalla de inicio y tacharlas con un toque. Tachar acá es lo mismo que «✔
Hecha» en Tiempos: lo ve todo el que comparte esa tarea.

| | |
|---|---|
| APK | release `ultimo` de este repositorio → `pizarra.apk` |
| Base | Firebase `tiempos-71d42`, la de Tiempos, por REST |
| Sello | `pizarra-2` (en cada archivo de `app/src/main/java/…`) |
| Pruebas | `./gradlew test` — 18 casos (11 de la pizarra, 7 de Tiempos), sin red; + uno opcional contra una respuesta real |

## Cómo se usa

1. Instalar `pizarra.apk` y abrir **Pizarra**.
2. Entrar con la misma cuenta de Tiempos. La contraseña se usa una vez y no se
   guarda: queda el `refreshToken` de esa entrada.
3. Tildar las tareas que van a la pizarra (comunes y tus personales).
4. **Poner el widget** (o mantener apretado el inicio → Widgets → Pizarra).
5. En el widget: tocar una tarea la tacha; tocarla tachada la vuelve a
   pendiente. ⟳ actualiza, ✎ abre la app.

## Lo de Tiempos (pizarra-2)

- **🎙 Dictar.** Arriba de todo en la app, y como 🎙 en el widget. Usa el
  reconocedor de voz de Android —el mismo del micrófono del teclado; el del
  navegador no le anduvo a Mauro—. El texto queda en una caja para corregir,
  se puede dictar en dos tandas, y **✨ Seguir en Tiempos** abre el sitio con
  `?dictar=<texto>`: Tiempos lo precarga con su IA y muestra el plan para
  marcar (app-14). La app no llama a la IA: no tiene claves ni las necesita.
- **⏰ Alarmas y 🔔 recordatorios.** Lee MIS `alertas/` (reglas v9), las
  guarda y las programa con el AlarmManager. Un recordatorio es una
  notificación; una alarma es la sirena: suena con el sonido de alarma y se
  repite hasta que se la toca, y con el teléfono bloqueado abre una pantalla
  con «Apagar». Se pone al día al abrir la app, con el widget (media hora), con
  un trabajo de Android cada hora y al prender el teléfono. Lo que ya pasó no
  se programa; lo que se borró en Tiempos se desprograma. Necesita los
  permisos de **notificaciones** y de **alarmas exactas**; si faltan, la app
  lo dice y un toque lleva a Ajustes.
- **⭐ Hoy se puede.** Los deseos de los dos que caen hoy —lo semanal de este
  día y lo de esta fecha—, la misma cuenta que `posiblesDelDia` del sitio
  (deseos-1). Se editan en Tiempos.

No escribe nada nuevo en la base: alertas y deseos sólo se leen.

## Qué escribe en la base

| Acción | Campos de `tareas/{id}` | Igual que en Tiempos |
|---|---|---|
| Tachar | `hecho: true`, `hechoPor: <uid>`, `actualizadoEn` | «✔ Hecha» |
| Destachar | `hecho: false`, `actualizadoEn` | «Volver a pendiente» |
| Fijar / sacar | `pizarra.<uid>: true` / se borra | campo nuevo, por persona |

Ninguna regla nueva: la de `tareas` ya deja tocar una común a cualquiera de
los dos y una personal a su dueño. Todo con `currentDocument.exists`, para no
crear una tarea que alguien borró.

## Lo que no hace (todavía)

- **No se actualiza en vivo** lo que tacha el otro: llega al tocar ⟳, al tachar
  algo, al abrir la app o cada media hora.
- **No muestra las actividades de Casa Verde** ni la lista de compras: sólo las
  tareas de la familia (`tareas/`).

## Mapa

| Archivo | Qué es |
|---|---|
| `Logica.kt` | traducir Firestore ↔ pizarra; lo que se escribe. Sin Android ni red |
| `Nube.kt` | entrar, renovar el token, leer y escribir por REST |
| `PizarraWidget.kt` | el widget, la caché y las filas |
| `MainActivity.kt` | entrar, dictar, «hoy se puede», las próximas alarmas y elegir qué va a la pizarra |
| `Alarmas.kt` | traer `alertas/`, programarlas, la notificación, la pantalla de «Apagar», el trabajo de cada hora |
| `.github/workflows/apk.yml` | banco + APK + release `ultimo` |
