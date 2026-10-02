# Pizarra

Widget de Android con las tareas de **Tiempos** que elegiste, para verlas en la
pantalla de inicio y tacharlas con un toque. Tachar acá es lo mismo que «✔
Hecha» en Tiempos: lo ve todo el que comparte esa tarea.

| | |
|---|---|
| APK | release `ultimo` de este repositorio → `pizarra.apk` |
| Base | Firebase `tiempos-71d42`, la de Tiempos, por REST |
| Sello | `pizarra-1` (en cada archivo de `app/src/main/java/…`) |
| Pruebas | `./gradlew test` — 11 casos, sin red; + uno opcional contra una respuesta real |

## Cómo se usa

1. Instalar `pizarra.apk` y abrir **Pizarra**.
2. Entrar con la misma cuenta de Tiempos. La contraseña se usa una vez y no se
   guarda: queda el `refreshToken` de esa entrada.
3. Tildar las tareas que van a la pizarra (comunes y tus personales).
4. **Poner el widget** (o mantener apretado el inicio → Widgets → Pizarra).
5. En el widget: tocar una tarea la tacha; tocarla tachada la vuelve a
   pendiente. ⟳ actualiza, ✎ abre la app.

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
| `MainActivity.kt` | entrar y elegir qué va a la pizarra |
| `.github/workflows/apk.yml` | banco + APK + release `ultimo` |
