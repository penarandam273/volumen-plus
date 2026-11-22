package com.luis.volumenplus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El Agente DJ aprende de YouTube / YouTube Music (version 2.0.22).
 * Mira las busquedas de YouTube (titulo, artista y cuantas vistas tiene cada video) y arma rankings de lo mas
 * sonado por genero: Valledupar, vallenato nuevo, reggaeton, champeta y pico, salsa, clasicos.
 * Con eso escoge las canciones de las listas de fiesta y las busca completas y gratis (Audius / Archive).
 * NO reproduce audio de YouTube: solo aprende titulos y popularidad. Lo aprendido se refresca cada semana.
 * Si YouTube no responde desde el telefono, puede usar un puente de Apps Script (apps-script/YouTubeAprende.gs).
 */
public final class AprendeYouTube {
    private AprendeYouTube() { }

    public static final String[] TEMAS = {"Lo más sonado en Valledupar", "Vallenato nuevo", "Reggaetón", "Champeta y picó", "Salsa", "Clásicos del reggaetón"};
    private static final String[][] CONSULTAS = {
            {"vallenato lo mas sonado valledupar", "vallenatos mas escuchados 2026", "silvestre dangond", "peter manjarres", "farid ortiz", "vallenato parranda"},
            {"vallenato nuevo 2026", "estrenos vallenatos", "jorge celedon nuevo", "felipe pelaez nuevo", "nelson velasquez nuevo"},
            {"reggaeton nuevo 2026", "reggaeton mas escuchado", "reggaeton para bailar", "feid", "karol g"},
            {"champeta nueva 2026", "champeta pico exitos", "champeta urbana", "mr black champeta", "kevin florez champeta"},
            {"salsa bailable exitos", "salsa nueva 2026", "salsa choke", "grupo niche exitos"},
            {"don omar exitos", "daddy yankee exitos", "wisin y yandel exitos", "reggaeton viejo exitos"},
    };
    /** Que temas aprendidos le sirven a cada fiesta (en el orden de EnLinea.FIESTAS). */
    private static final int[][] TEMAS_FIESTA = {
            {0, 2, 3, 4, 5}, // Crossover
            {2, 5},          // Reggaeton
            {4},             // Salsa
            {0, 1},          // Vallenato
            {3},             // Champeta
            {},              // Electronica
            {},              // Bachata y merengue
            {2, 3, 5},       // Hora loca
    };
    private static final String PREFS = "volumen_aprende";
    private static final long SEMANA = 7L * 24 * 3600 * 1000;

    public static class Tema {
        public final String titulo, artista;
        public final long vistas;
        Tema(String t, String a, long v) { titulo = t; artista = a; vistas = v; }
    }

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static long fecha(Context c) { return p(c).getLong("fecha", 0); }
    public static boolean tocaAprender(Context c) { return System.currentTimeMillis() - fecha(c) > SEMANA; }
    public static String puente(Context c) { return p(c).getString("puente", ""); }
    public static void setPuente(Context c, String url) { p(c).edit().putString("puente", url == null ? "" : url.trim()).apply(); }

    /** Aprende todo (llamar fuera de la pantalla). Devuelve un resumen para el DJ. */
    public static String aprender(Context c) throws Exception {
        String puente = puente(c);
        JSONObject todo = new JSONObject();
        int total = 0, fallos = 0;
        Exception ultimo = null;
        for (int t = 0; t < TEMAS.length; t++) {
            Map<String, Tema> unicos = new LinkedHashMap<>();
            for (String q : CONSULTAS[t]) {
                List<Tema> l;
                try {
                    l = buscar(q, puente);
                } catch (Exception e) { ultimo = e; fallos++; continue; }
                for (Tema x : l) {
                    String k = EnLinea.normalPublico(x.titulo) + "|" + EnLinea.normalPublico(x.artista);
                    Tema ya = unicos.get(k);
                    if (ya == null || ya.vistas < x.vistas) unicos.put(k, x);
                }
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            }
            List<Tema> lista = new ArrayList<>(unicos.values());
            Collections.sort(lista, (a, b) -> Long.compare(b.vistas, a.vistas));
            JSONArray a = new JSONArray();
            for (int i = 0; i < Math.min(40, lista.size()); i++) {
                Tema x = lista.get(i);
                a.put(new JSONObject().put("t", x.titulo).put("a", x.artista).put("v", x.vistas));
            }
            todo.put(String.valueOf(t), a);
            total += a.length();
        }
        if (total == 0) throw ultimo != null ? ultimo : new Exception("YouTube no respondió");
        p(c).edit().putString("datos", todo.toString()).putLong("fecha", System.currentTimeMillis()).apply();
        return "Aprendí " + total + " canciones de YouTube en " + TEMAS.length + " temas" + (fallos > 0 ? " (" + fallos + " búsquedas no respondieron)" : "") + ".";
    }

    public static List<Tema> top(Context c, int tema, int n) {
        List<Tema> r = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(p(c).getString("datos", "{}")).optJSONArray(String.valueOf(tema));
            for (int i = 0; a != null && i < a.length() && r.size() < n; i++) {
                JSONObject o = a.getJSONObject(i);
                r.add(new Tema(o.optString("t"), o.optString("a"), o.optLong("v")));
            }
        } catch (Exception ignored) { }
        return r;
    }

    /** Canciones aprendidas para una fiesta: {titulo, artista}, las mas vistas primero, sin repetir artista mas de 2 veces. */
    public static List<String[]> paraFiesta(Context c, int estilo, int n) {
        List<Tema> todas = new ArrayList<>();
        if (estilo < 0 || estilo >= TEMAS_FIESTA.length) return new ArrayList<>();
        for (int t : TEMAS_FIESTA[estilo]) todas.addAll(top(c, t, 25));
        Collections.sort(todas, (a, b) -> Long.compare(b.vistas, a.vistas));
        List<String[]> r = new ArrayList<>();
        Map<String, Integer> porArtista = new HashMap<>();
        java.util.Set<String> vistos = new java.util.HashSet<>();
        for (Tema x : todas) {
            String k = EnLinea.normalPublico(x.titulo);
            if (!vistos.add(k)) continue;
            String a = EnLinea.normalPublico(x.artista);
            int ya = porArtista.containsKey(a) ? porArtista.get(a) : 0;
            if (ya >= 2) continue;
            porArtista.put(a, ya + 1);
            r.add(new String[]{x.titulo, x.artista});
            if (r.size() >= n) break;
        }
        return r;
    }

    /** "1234567" -> "1,2 M vistas". */
    public static String vistas(long v) {
        if (v >= 1_000_000_000L) return String.format(Locale.US, "%.1f mil M", v / 1e9).replace('.', ',');
        if (v >= 1_000_000L) return String.format(Locale.US, "%.1f M", v / 1e6).replace('.', ',');
        if (v >= 1_000L) return (v / 1000) + " mil";
        return String.valueOf(v);
    }

    // ---------- YouTube ----------
    private static List<Tema> buscar(String q, String puente) throws Exception {
        Exception error = null;
        try {
            List<Tema> l = desdeYouTube(q);
            if (!l.isEmpty()) return l;
        } catch (Exception e) { error = e; }
        if (puente != null && puente.startsWith("https://script.google.com/")) {
            JSONArray a = new JSONObject(leer(puente + (puente.contains("?") ? "&" : "?") + "q=" + enc(q), false)).optJSONArray("videos");
            List<Tema> l = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tema x = limpiar(o.optString("t"), o.optString("a"), o.optLong("v"), o.optInt("d", 0));
                if (x != null) l.add(x);
            }
            return l;
        }
        if (error != null) throw error;
        return new ArrayList<>();
    }

    private static List<Tema> desdeYouTube(String q) throws Exception {
        String html = leer("https://www.youtube.com/results?search_query=" + enc(q) + "&hl=es&gl=CO", true);
        int i = html.indexOf("ytInitialData");
        if (i < 0) throw new Exception("YouTube cambió su página");
        int ini = html.indexOf('{', i);
        int fin = html.indexOf(";</script>", ini);
        if (ini < 0 || fin < 0) throw new Exception("YouTube cambió su página");
        JSONObject datos = new JSONObject(html.substring(ini, fin));
        List<JSONObject> videos = new ArrayList<>();
        juntar(datos, videos, 0);
        List<Tema> r = new ArrayList<>();
        for (JSONObject v : videos) {
            String titulo = texto(v.optJSONObject("title"));
            String canal = texto(v.optJSONObject("ownerText"));
            long vistas = numero(texto(v.optJSONObject("viewCountText")));
            int dur = segundos(texto(v.optJSONObject("lengthText")));
            Tema x = limpiar(titulo, canal, vistas, dur);
            if (x != null) r.add(x);
        }
        return r;
    }

    /** Recorre el JSON de YouTube y junta cada "videoRenderer". */
    private static void juntar(Object o, List<JSONObject> r, int prof) {
        if (prof > 30 || r.size() > 40) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONObject v = j.optJSONObject("videoRenderer");
            if (v != null) { r.add(v); return; }
            JSONArray nombres = j.names();
            for (int i = 0; nombres != null && i < nombres.length(); i++) juntar(j.opt(nombres.optString(i)), r, prof + 1);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) juntar(a.opt(i), r, prof + 1);
        }
    }

    private static final Pattern ADORNOS = Pattern.compile("(?i)\\s*[\\(\\[][^\\)\\]]*(video|oficial|official|lyric|letra|audio|visualizer|hd|4k|en vivo|live|remaster)[^\\)\\]]*[\\)\\]]");
    private static final Pattern MALOS = Pattern.compile("(?i)(mix|enganchad|completo|full album|álbum completo|album completo|karaoke|tutorial|cover|reaccion|reacción|podcast|entrevista|\\bhoras\\b)");

    /** Deja "titulo" y "artista" limpios; descarta mezclas largas, karaokes y videos que no son canciones. */
    static Tema limpiar(String titulo, String canal, long vistas, int dur) {
        if (titulo == null || titulo.isEmpty()) return null;
        if (dur > 0 && (dur < 90 || dur > 600)) return null;
        if (MALOS.matcher(titulo).find()) return null;
        String t = ADORNOS.matcher(titulo).replaceAll("").replace("\"", "").trim();
        String a = canal == null ? "" : canal.replaceAll("(?i)\\s*-\\s*topic$|vevo$|\\s*oficial$|\\s*official$|\\s*tv$", "").trim();
        int g = t.indexOf(" - ");
        if (g < 0) g = t.indexOf(" – ");
        if (g > 0) { a = t.substring(0, g).trim(); t = t.substring(g + 3).trim(); }
        t = t.replaceAll("(?i)\\s+(ft\\.?|feat\\.?|x)\\s+.*$", "").trim();
        if (t.length() < 2) return null;
        return new Tema(t, a, vistas);
    }

    private static String texto(JSONObject o) {
        if (o == null) return "";
        if (o.has("simpleText")) return o.optString("simpleText");
        JSONArray r = o.optJSONArray("runs");
        StringBuilder b = new StringBuilder();
        for (int i = 0; r != null && i < r.length(); i++) b.append(r.optJSONObject(i).optString("text"));
        return b.toString();
    }

    private static long numero(String s) {
        String d = s == null ? "" : s.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return 0;
        try { return Long.parseLong(d.length() > 15 ? d.substring(0, 15) : d); } catch (NumberFormatException e) { return 0; }
    }

    private static int segundos(String s) {
        if (s == null || s.isEmpty()) return 0;
        int t = 0;
        for (String x : s.split(":")) { try { t = t * 60 + Integer.parseInt(x.trim()); } catch (NumberFormatException e) { return 0; } }
        return t;
    }

    private static String leer(String url, boolean comoNavegador) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        if (comoNavegador) {
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "es-CO,es;q=0.9");
            c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
        } else {
            c.setRequestProperty("User-Agent", "VolumenPlus/2.0 (Android)");
        }
        try {
            int cod = c.getResponseCode();
            if (cod >= 400) throw new Exception("respondió " + cod);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                byte[] b = new byte[16 * 1024];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                return o.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
}
