package com.maurogasta.pizarra

// ─────────────────────────────────────────────────────────────────────────────
// Servidor.kt — EL SERVIDOR DEL ECOSISTEMA, en un solo lugar (pizarra-82).
//
// Plan «Timón» (10-nov-2026): las funciones que usan todas las apps —despertar
// a las Pizarras, los servidores de conexión de las llamadas, las luces de
// remate— viven en `timon-servidor`, no en el Netlify de Casa Verde. Hasta
// pizarra-81 la dirección estaba escrita tres veces (Instantaneo, Llamada y
// Luces) y apuntaba a Casa Verde.
//
// Es la MISMA dirección que `SERVIDOR` de `servidor-1.js` de `datos`: si cambia
// allá, cambia acá en la misma tanda (el banco las compara si `datos` está al
// lado). Las claves están en el servidor; acá no hay ninguna.
// ─────────────────────────────────────────────────────────────────────────────

const val SERVIDOR = "https://timon-servidor.netlify.app/.netlify/functions"
