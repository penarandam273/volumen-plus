/**
 * Volumen+ Pro — puente para que el Agente DJ aprenda de YouTube (versión 2.0.22).
 *
 * Solo hace falta si YouTube no responde desde el teléfono. Este script busca en YouTube y devuelve
 * título, canal, vistas y duración de los videos (NUNCA audio).
 *
 * Cómo publicarlo:
 *  1. Entra a https://script.google.com con tu cuenta y toca "Nuevo proyecto".
 *  2. Borra lo que trae, pega todo este archivo y guarda (nombre: "Volumen+ aprende").
 *  3. Implementar → Nueva implementación → tipo "Aplicación web".
 *     Ejecutar como: "Yo".  Quién tiene acceso: "Cualquier usuario".
 *  4. Autoriza los permisos (pide "conectarse a un servicio externo": es para leer YouTube).
 *  5. Copia la dirección que termina en /exec.
 *  6. En Volumen+: tarjeta del DJ → "Puente (opcional)" → pega la dirección → Guardar.
 *
 * Prueba: abre en el navegador  TU_DIRECCION/exec?q=vallenato  y debe salir una lista {"videos":[...]}.
 */
function doGet(e) {
  var q = (e && e.parameter && e.parameter.q) ? String(e.parameter.q) : '';
  var salida = { videos: [] };
  if (!q) return json_(salida);
  var cache = CacheService.getScriptCache();
  var clave = 'q:' + q.toLowerCase().slice(0, 200);
  var guardado = cache.get(clave);
  if (guardado) return ContentService.createTextOutput(guardado).setMimeType(ContentService.MimeType.JSON);
  try {
    var r = UrlFetchApp.fetch('https://www.youtube.com/results?search_query=' + encodeURIComponent(q) + '&hl=es&gl=CO', {
      muteHttpExceptions: true,
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36',
        'Accept-Language': 'es-CO,es;q=0.9',
        'Cookie': 'CONSENT=YES+cb; SOCS=CAI'
      }
    });
    var html = r.getContentText();
    var i = html.indexOf('ytInitialData');
    var ini = html.indexOf('{', i);
    var fin = html.indexOf(';</script>', ini);
    if (i < 0 || ini < 0 || fin < 0) { salida.error = 'YouTube cambió su página'; return json_(salida); }
    var datos = JSON.parse(html.substring(ini, fin));
    var lista = [];
    juntar_(datos, lista, 0);
    lista.forEach(function (v) {
      salida.videos.push({
        t: texto_(v.title),
        a: texto_(v.ownerText),
        v: numero_(texto_(v.viewCountText)),
        d: segundos_(texto_(v.lengthText))
      });
    });
    var txt = JSON.stringify(salida);
    if (salida.videos.length) cache.put(clave, txt, 6 * 3600);
    return ContentService.createTextOutput(txt).setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    salida.error = String(err);
    return json_(salida);
  }
}

function juntar_(o, r, prof) {
  if (prof > 30 || r.length > 40 || o === null || typeof o !== 'object') return;
  if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) juntar_(o[i], r, prof + 1); return; }
  if (o.videoRenderer) { r.push(o.videoRenderer); return; }
  for (var k in o) juntar_(o[k], r, prof + 1);
}

function texto_(o) {
  if (!o) return '';
  if (o.simpleText) return o.simpleText;
  return (o.runs || []).map(function (x) { return x.text || ''; }).join('');
}

function numero_(s) {
  var d = String(s || '').replace(/[^0-9]/g, '');
  return d ? Number(d.slice(0, 15)) : 0;
}

function segundos_(s) {
  if (!s) return 0;
  var t = 0, p = String(s).split(':');
  for (var i = 0; i < p.length; i++) { var n = parseInt(p[i], 10); if (isNaN(n)) return 0; t = t * 60 + n; }
  return t;
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
