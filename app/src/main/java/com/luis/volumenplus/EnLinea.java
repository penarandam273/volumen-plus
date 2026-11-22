package com.luis.volumenplus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Musica en linea gratis, legal y sin anuncios:
 *  - Internet Archive (archive.org): millones de grabaciones libres, muchas en FLAC (calidad sin perdida).
 *  - Radio Browser (radio-browser.info): directorio abierto de emisoras de radio por internet (filtra 128 kbps o mas).
 * Ninguno pide cuenta ni clave. Todo se llama desde un hilo aparte (nunca desde la pantalla).
 */
public class EnLinea {

    /** Un resultado: un album/grabacion del Archive o una emisora. */
    public static class Resultado {
        public final String titulo;
        public final String detalle;
        public final String id;      // identificador del Archive, o url de la emisora
        public final boolean radio;
        /** true = "id" ya es la direccion de una cancion completa lista para sonar (Audius). */
        public final boolean directo;
        public String artista = "", genero = "", animo = "", tono = "";
        /** Portada real (Audius, logo de la emisora), o vacio para que la app busque o dibuje una. */
        public String imagen = "";
        /** true = la escogio el DJ por lo que aprendio de YouTube. */
        public boolean aprendida = false;
        public double bpm = 0;
        public int plays = 0;
        public long fecha = 0;
        public Resultado(String titulo, String detalle, String id, boolean radio) {
            this(titulo, detalle, id, radio, false);
        }
        public Resultado(String titulo, String detalle, String id, boolean radio, boolean directo) {
            this.titulo = titulo; this.detalle = detalle; this.id = id; this.radio = radio; this.directo = directo;
        }
    }

    /** Una sugerencia del predictor: titulo, artista y una muestra de 30 s. */
    public static class Sugerencia {
        public final String titulo, artista, muestra;
        public Sugerencia(String titulo, String artista, String muestra) {
            this.titulo = titulo; this.artista = artista; this.muestra = muestra;
        }
    }

    private static final String AUDIUS = "https://api.audius.co/v1";
    private static final String APP = "app_name=VolumenPlus";
    private static final java.util.regex.Pattern BAJA = java.util.regex.Pattern.compile(
            "(?i)(\\b(32|48|64|96|128)\\s?k(bps)?\\b|mp3_?128|\\bpreview\\b|\\bsnippet\\b|\\bringtone\\b|tono de llamada|\\bsample\\b)");

    /**
     * Predictor: con un pedazo del titulo, del artista o de la LETRA devuelve las canciones que coinciden.
     * Usa el buscador de Deezer (encuentra por letra) y, si falla, el de Apple (iTunes). Ambos gratis y sin clave.
     */
    public static List<Sugerencia> sugerencias(String q) throws Exception {
        List<Sugerencia> r = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(leer("https://api.deezer.com/search?q=" + enc(q.trim()) + "&limit=8")).optJSONArray("data");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONObject ar = o.optJSONObject("artist");
                agregarSug(r, o.optString("title", ""), ar != null ? ar.optString("name", "") : "", o.optString("preview", ""));
            }
        } catch (Exception ignored) {
            // se intenta con el otro buscador
        }
        if (r.isEmpty()) {
            JSONArray a = new JSONObject(leer("https://itunes.apple.com/search?entity=song&limit=8&country=co&term=" + enc(q.trim())))
                    .optJSONArray("results");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                agregarSug(r, o.optString("trackName", ""), o.optString("artistName", ""), o.optString("previewUrl", ""));
            }
        }
        return r;
    }

    private static void agregarSug(List<Sugerencia> r, String t, String a, String m) {
        if (t.isEmpty()) return;
        for (Sugerencia x : r) if (x.titulo.equalsIgnoreCase(t) && x.artista.equalsIgnoreCase(a)) return;
        r.add(new Sugerencia(t, a, m));
    }

    /** Canciones completas, gratis y sin anuncios de Audius (artistas que suben su musica). */
    public static List<Resultado> buscarAudius(String q) throws Exception {
        String url = q == null || q.trim().isEmpty()
                ? AUDIUS + "/tracks/trending?" + APP + "&time=week&limit=40"
                : AUDIUS + "/tracks/search?query=" + enc(q.trim()) + "&" + APP + "&limit=40";
        List<Resultado> r = buscarAudiusUrl(url);
        return q == null || q.trim().isEmpty() ? r : recientePrimero(r);
    }

    private static List<Resultado> buscarAudiusUrl(String url) throws Exception {
        JSONArray a = new JSONObject(leer(url)).optJSONArray("data");
        List<Resultado> r = new ArrayList<>();
        if (a == null) return r;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (o.has("is_streamable") && !o.optBoolean("is_streamable", true)) continue;
            if (o.optBoolean("is_delete", false) || o.optBoolean("is_unlisted", false)) continue;
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            JSONObject u = o.optJSONObject("user");
            int seg = o.optInt("duration", 0);
            // solo canciones completas: ni muestras ni pedazos (1:30 a 15 minutos)
            if (seg < 90 || seg > 900) continue;
            // fuera copias de baja calidad, muestras y tonos
            if (BAJA.matcher(o.optString("title", "")).find()) continue;
            String det = (u != null ? u.optString("name", "Audius") : "Audius") + " · Audius"
                    + (seg > 0 ? String.format(java.util.Locale.US, " · %d:%02d", seg / 60, seg % 60) : "")
                    + (o.optString("genre", "").isEmpty() ? "" : " · " + o.optString("genre"));
            Resultado x = new Resultado(o.optString("title", "Cancion"), det, AUDIUS + "/tracks/" + enc(id) + "/stream?" + APP, false, true);
            x.artista = u != null ? u.optString("name", "") : "";
            x.genero = o.optString("genre", "");
            x.animo = o.optString("mood", "");
            x.tono = o.optString("musical_key", "");
            x.bpm = o.optDouble("bpm", 0);
            if (Double.isNaN(x.bpm)) x.bpm = 0;
            x.plays = o.optInt("play_count", 0);
            x.fecha = fecha(o.optString("release_date", o.optString("created_at", "")));
            JSONObject art = o.optJSONObject("artwork");
            if (art != null) x.imagen = art.optString("480x480", art.optString("150x150", ""));
            r.add(x);
        }
        return r;
    }

    private static long fecha(String iso) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso.substring(0, 10)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Lo reciente primero (ultimos 2 años), sin perder el orden de relevancia dentro de cada grupo. */
    private static List<Resultado> recientePrimero(List<Resultado> l) {
        long corte = System.currentTimeMillis() - 2L * 365 * 24 * 3600 * 1000;
        List<Resultado> nuevas = new ArrayList<>(), viejas = new ArrayList<>();
        for (Resultado x : l) (x.fecha >= corte ? nuevas : viejas).add(x);
        nuevas.addAll(viejas);
        return nuevas;
    }

    private static void sumarSinRepetir(List<Resultado> destino, List<Resultado> nuevos, int max) {
        for (Resultado x : nuevos) {
            if (destino.size() >= max) return;
            boolean ya = false;
            String nx = normal(x.titulo);
            // misma cancion subida por dos personas = repetida
            for (Resultado y : destino) if (y.id.equals(x.id) || (!nx.isEmpty() && nx.equals(normal(y.titulo)))) { ya = true; break; }
            if (!ya) destino.add(x);
        }
    }

    /** Generos de Audius que se pueden pedir en "lo mas sonado". */
    private static String generoAudius(String g) {
        String[] validos = {"Latin", "Hip-Hop/Rap", "Electronic", "Pop", "R&B/Soul", "Reggae", "Rock", "Alternative",
                "Dancehall", "House", "Moombahton", "Tropical House", "Acoustic", "Jazz", "Classical", "Funk", "Country", "Ambient"};
        for (String v : validos) if (v.equalsIgnoreCase(g)) return v;
        return null;
    }

    /**
     * "Para ti": arma una seleccion con lo que la app aprendio (generos, artistas y busquedas que mas usas),
     * siempre canciones completas y lo mas reciente primero.
     */
    public static List<Resultado> paraTi(List<String> generos, List<String> artistas, List<String> busquedas) throws Exception {
        List<Resultado> r = new ArrayList<>();
        Exception error = null;
        for (String g : generos) {
            String ga = generoAudius(g);
            if (ga == null) continue;
            try {
                sumarSinRepetir(r, buscarAudiusUrl(AUDIUS + "/tracks/trending?" + APP + "&time=week&limit=30&genre=" + enc(ga)), 60);
            } catch (Exception e) { error = e; }
        }
        for (String a : artistas) {
            try { sumarSinRepetir(r, buscarAudius(a), 60); } catch (Exception e) { error = e; }
        }
        for (String q : busquedas) {
            try { sumarSinRepetir(r, buscarAudius(q), 60); } catch (Exception e) { error = e; }
        }
        if (r.isEmpty()) {
            try { r.addAll(buscarAudius("")); } catch (Exception e) { if (error == null) error = e; }
        }
        if (r.isEmpty() && error != null) throw error;
        Collections.shuffle(r);
        return recientePrimero(r);
    }

    /**
     * Exitos de hoy: el Top Colombia de Deezer (se actualiza a diario). Si no aparece, el Top mundial.
     * Solo titulo y artista: al tocar uno se busca la version completa gratis.
     */
    public static List<Sugerencia> exitos() throws Exception {
        List<Sugerencia> r = new ArrayList<>();
        try {
            JSONArray pl = new JSONObject(leer("https://api.deezer.com/search/playlist?q=" + enc("Top Colombia") + "&limit=10")).optJSONArray("data");
            long id = 0;
            if (pl != null) for (int i = 0; i < pl.length(); i++) {
                JSONObject o = pl.getJSONObject(i);
                JSONObject u = o.optJSONObject("user");
                if (o.optString("title").equalsIgnoreCase("Top Colombia") && u != null && u.optString("name").toLowerCase().contains("deezer")) {
                    id = o.optLong("id"); break;
                }
            }
            if (id != 0) {
                JSONArray a = new JSONObject(leer("https://api.deezer.com/playlist/" + id + "/tracks?limit=50")).optJSONArray("data");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    JSONObject ar = o.optJSONObject("artist");
                    agregarSug(r, o.optString("title", ""), ar != null ? ar.optString("name", "") : "", "");
                }
            }
        } catch (Exception ignored) {
            // se usa el top mundial
        }
        if (r.isEmpty()) {
            JSONArray a = new JSONObject(leer("https://api.deezer.com/chart/0/tracks?limit=50")).optJSONArray("data");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONObject ar = o.optJSONObject("artist");
                agregarSug(r, o.optString("title", ""), ar != null ? ar.optString("name", "") : "", "");
            }
        }
        return r;
    }

    public static String normalPublico(String s) { return normal(s); }

    private static String normal(String s) {
        String n = java.text.Normalizer.normalize(s.toLowerCase(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.replaceAll("\\(.*?\\)|\\[.*?\\]", " ").replaceAll("(feat|ft)\\.?.*$", " ");
        return n.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    // =====================================================================
    //  AGENTE DJ: listas de fiesta y musica nueva
    // =====================================================================
    public static final String[] FIESTAS = {"Crossover", "Reggaetón", "Salsa", "Vallenato", "Champeta", "Electrónica",
            "Bachata y merengue", "Hora loca"};
    private static final String[][] BUSQUEDAS_FIESTA = {
            {"reggaeton", "salsa", "merengue", "vallenato", "bachata", "champeta", "dembow", "cumbia"},
            {"reggaeton", "perreo", "dembow", "latin trap", "reggaeton nuevo"},
            {"salsa", "salsa brava", "salsa choke", "timba", "salsa urbana"},
            {"vallenato", "vallenato nuevo", "parranda vallenata", "acordeon"},
            {"champeta", "champeta urbana", "afrobeat", "terapia"},
            {"edm", "house", "tech house", "guaracha", "electro latino"},
            {"bachata", "merengue", "merengue urbano", "bachata urbana"},
            {"guaracha", "aleteo", "dembow", "zapateo", "electro latino", "hora loca"},
    };
    private static final String[][] GENEROS_FIESTA = {
            {"Latin", "Dancehall"}, {"Latin", "Hip-Hop/Rap"}, {"Latin"}, {"Latin"}, {"Latin", "Dancehall"},
            {"Electronic", "House", "Moombahton"}, {"Latin"}, {"Electronic", "House", "Latin"},
    };

    /** Que tan "de fiesta" es el animo que el artista le puso a su cancion (0 a 1). */
    private static double energiaAnimo(String m) {
        switch (m == null ? "" : m) {
            case "Energizing": case "Rowdy": return 1.0;
            case "Excited": case "Upbeat": case "Fiery": return 0.9;
            case "Aggressive": case "Empowering": case "Defiant": return 0.8;
            case "Sensual": case "Cool": case "Stirring": return 0.65;
            case "Romantic": case "Easygoing": case "Other": case "": return 0.5;
            case "Yearning": case "Brooding": return 0.35;
            default: return 0.25; // melancolico, triste, tranquilo: no es para fiesta
        }
    }

    private static double energia(Resultado x) {
        double e = energiaAnimo(x.animo);
        if (x.bpm >= 95 && x.bpm <= 135) e += 0.15;
        else if (x.bpm > 0 && x.bpm < 80) e -= 0.15;
        return e;
    }

    /** Rueda de Camelot: dos canciones con tono vecino se mezclan sin chocar. */
    private static int camelot(String k) {
        if (k == null || k.isEmpty()) return -1;
        String t = k.toLowerCase().replace("flat", "b").replace("sharp", "#").replace(" ", "");
        boolean menor = t.endsWith("minor");
        t = t.replace("minor", "").replace("major", "");
        String[] mayores = {"b", "f#", "db", "ab", "eb", "bb", "f", "c", "g", "d", "a", "e"};
        String[] menores = {"g#", "eb", "bb", "f", "c", "g", "d", "a", "e", "b", "f#", "db"};
        String[] equiv = {"gb", "f#", "c#", "db", "d#", "eb", "a#", "bb", "g#", "ab"};
        for (int i = 0; i + 1 < equiv.length; i += 2) if (t.equals(equiv[i])) { t = equiv[i + 1]; break; }
        String[] tabla = menor ? menores : mayores;
        for (int i = 0; i < 12; i++) if (tabla[i].equals(t) || (menor && t.equals("ab") && tabla[i].equals("g#"))) return i + (menor ? 0 : 12);
        return -1;
    }

    private static double costoMezcla(Resultado a, Resultado b) {
        double c = 0;
        if (a.bpm > 0 && b.bpm > 0) {
            double d = Math.abs(a.bpm - b.bpm);
            d = Math.min(d, Math.abs(a.bpm - 2 * b.bpm));
            d = Math.min(d, Math.abs(2 * a.bpm - b.bpm));
            c += d / 4.0;
        } else c += 4;
        int ka = camelot(a.tono), kb = camelot(b.tono);
        if (ka >= 0 && kb >= 0) {
            int na = ka % 12, nb = kb % 12;
            int dist = Math.min(Math.abs(na - nb), 12 - Math.abs(na - nb));
            boolean mismoTipo = (ka < 12) == (kb < 12);
            if (mismoTipo && dist <= 1) c += 0; else if (!mismoTipo && dist == 0) c += 0.5; else c += 3;
        } else c += 2;
        if (a.artista.equalsIgnoreCase(b.artista)) c += 5; // no dos seguidas del mismo artista
        return c;
    }

    /** Ordena una parte de la fiesta encadenando canciones de tempo y tono compatibles. */
    private static List<Resultado> encadenar(List<Resultado> l) {
        List<Resultado> r = new ArrayList<>();
        if (l.isEmpty()) return r;
        List<Resultado> quedan = new ArrayList<>(l);
        Resultado act = quedan.remove(0);
        r.add(act);
        while (!quedan.isEmpty()) {
            int mejor = 0;
            double c = Double.MAX_VALUE;
            for (int i = 0; i < quedan.size(); i++) {
                double x = costoMezcla(act, quedan.get(i));
                if (x < c) { c = x; mejor = i; }
            }
            act = quedan.remove(mejor);
            r.add(act);
        }
        return r;
    }

    /**
     * Arma la mejor lista de fiesta de un estilo: solo canciones completas (2 a 7 min), animo de fiesta,
     * popularidad y lo reciente, en una curva de DJ (calentamiento, pico, cierre) y mezcladas por tempo y tono.
     */
    public static List<Resultado> listaFiesta(int estilo, int cuantas) throws Exception {
        return listaFiesta(estilo, cuantas, null);
    }

    /**
     * 2.0.22: "aprendidas" = lo mas sonado que el DJ aprendio de YouTube ({titulo, artista}). Se buscan completas y
     * gratis (en paralelo, maximo 20 s) y entran primero a la fiesta; el resto se completa con lo de siempre.
     */
    public static List<Resultado> listaFiesta(int estilo, int cuantas, List<String[]> aprendidas) throws Exception {
        estilo = Math.max(0, Math.min(FIESTAS.length - 1, estilo));
        List<Resultado> todo = new ArrayList<>();
        Exception error = null;
        final java.util.Set<Resultado> deYouTube = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        if (aprendidas != null && !aprendidas.isEmpty()) {
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(4);
            List<java.util.concurrent.Future<Resultado>> fut = new ArrayList<>();
            for (String[] a : aprendidas.subList(0, Math.min(20, aprendidas.size()))) fut.add(ex.submit(() -> versionCompleta(a[0], a[1])));
            long hasta = System.currentTimeMillis() + 20000;
            for (java.util.concurrent.Future<Resultado> f : fut) {
                try {
                    Resultado x = f.get(Math.max(1, hasta - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (x != null) { x.aprendida = true; deYouTube.add(x); }
                } catch (Exception ignored) { }
            }
            ex.shutdownNow();
            sumarSinRepetir(todo, new ArrayList<>(deYouTube), 400);
        }
        for (String g : GENEROS_FIESTA[estilo]) {
            try { sumarSinRepetir(todo, buscarAudiusUrl(AUDIUS + "/tracks/trending?" + APP + "&time=month&limit=60&genre=" + enc(g)), 400); }
            catch (Exception e) { error = e; }
        }
        for (String q : BUSQUEDAS_FIESTA[estilo]) {
            try { sumarSinRepetir(todo, buscarAudius(q), 400); } catch (Exception e) { error = e; }
        }
        if (todo.isEmpty() && error != null) throw error;
        // filtro de fiesta
        List<Resultado> buenas = new ArrayList<>();
        long corte = System.currentTimeMillis() - 3L * 365 * 24 * 3600 * 1000;
        for (Resultado x : todo) {
            String[] d = x.detalle.split(" · ");
            if (!x.aprendida && energiaAnimo(x.animo) < 0.4) continue;
            buenas.add(x);
        }
        if (buenas.size() < 10) buenas = todo;
        // puntaje: energia + popularidad + novedad
        final java.util.Map<Resultado, Double> punt = new java.util.HashMap<>();
        for (Resultado x : buenas) {
            double p = energia(x) * 2 + Math.log10(1 + x.plays) * 0.6 + (x.fecha >= corte ? 0.5 : 0) + (x.aprendida ? 3 : 0);
            punt.put(x, p);
        }
        Collections.sort(buenas, (a, b) -> Double.compare(punt.get(b), punt.get(a)));
        // no mas de 2 por artista
        List<Resultado> elegidas = new ArrayList<>();
        java.util.Map<String, Integer> porArtista = new java.util.HashMap<>();
        for (Resultado x : buenas) {
            String a = x.artista.toLowerCase();
            int n = porArtista.containsKey(a) ? porArtista.get(a) : 0;
            if (n >= 2) continue;
            porArtista.put(a, n + 1);
            elegidas.add(x);
            if (elegidas.size() >= cuantas) break;
        }
        // curva de DJ: 25 % calentamiento, 55 % pico, 20 % cierre
        Collections.sort(elegidas, (a, b) -> Double.compare(energia(a), energia(b)));
        int n = elegidas.size();
        int nCal = n / 4, nCie = n / 5;
        List<Resultado> bajas = new ArrayList<>(elegidas.subList(0, nCal + nCie));
        List<Resultado> pico = new ArrayList<>(elegidas.subList(nCal + nCie, n));
        Collections.shuffle(bajas);
        List<Resultado> cal = new ArrayList<>(), cie = new ArrayList<>();
        for (int i = 0; i < bajas.size(); i++) (i < nCal ? cal : cie).add(bajas.get(i));
        List<Resultado> r = new ArrayList<>();
        r.addAll(encadenar(cal));
        Collections.shuffle(pico);
        r.addAll(encadenar(pico));
        r.addAll(encadenar(cie));
        return r;
    }

    /** Musica nueva: lo mas sonado del mes, solo estrenos (ultimos 60 dias), lo mas nuevo primero. */
    public static List<Resultado> musicaNueva(String genero) throws Exception {
        String g = genero == null ? null : generoAudius(genero);
        List<Resultado> l = new ArrayList<>();
        sumarSinRepetir(l, buscarAudiusUrl(AUDIUS + "/tracks/trending?" + APP + "&time=month&limit=100" + (g != null ? "&genre=" + enc(g) : "")), 100);
        long corte = System.currentTimeMillis() - 60L * 24 * 3600 * 1000;
        List<Resultado> r = new ArrayList<>();
        for (Resultado x : l) if (x.fecha >= corte) r.add(x);
        if (r.size() < 10) r = l;
        Collections.sort(r, (a, b) -> Long.compare(b.fecha, a.fecha));
        return r;
    }

    /** Busca una version completa y gratis de una cancion (mismo titulo). null si no hay. */
    public static Resultado versionCompleta(String titulo, String artista) {
        String t = normal(titulo);
        if (t.isEmpty()) return null;
        try {
            for (String q : new String[]{titulo + " " + artista, titulo}) {
                for (Resultado x : buscarAudius(q)) {
                    String xt = normal(x.titulo);
                    if (xt.equals(t) || xt.startsWith(t + " ") || xt.contains(" " + t + " ") || xt.endsWith(" " + t)) return x;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Busca en todas las fuentes de canciones: Audius (pistas completas) y luego Internet Archive. */
    public static List<Resultado> buscarTodo(String q, boolean conAlbumes) throws Exception {
        List<Resultado> r = new ArrayList<>();
        Exception error = null;
        try { r.addAll(buscarAudius(q)); } catch (Exception e) { error = e; }
        if (conAlbumes) try { r.addAll(buscarCanciones(q)); } catch (Exception e) { if (r.isEmpty()) error = e; }
        if (r.isEmpty() && error != null) throw error;
        return r;
    }

    private static final String[] RADIO_SERVIDORES = {
            "https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info",
            "https://fi1.api.radio-browser.info", "https://all.api.radio-browser.info"};

    private static String leer(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "VolumenPlus/2.0 (Android; reproductor personal)");
        c.setRequestProperty("Accept", "application/json");
        try {
            int cod = c.getResponseCode();
            if (cod >= 400) throw new Exception("El servidor respondio " + cod);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private static String texto(JSONObject o, String k) {
        Object v = o.opt(k);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            return a.length() > 0 ? a.optString(0, "") : "";
        }
        return v == null ? "" : String.valueOf(v);
    }

    /** Busca grabaciones de audio en Internet Archive (las mas escuchadas primero). */
    public static List<Resultado> buscarCanciones(String q) throws Exception {
        // Solo grabaciones recientes (2010 en adelante): nada de discos de 78 rpm, radio antigua ni audiolibros
        String consulta = (q == null || q.trim().isEmpty() ? "music" : "(" + q.trim() + ")")
                + " AND mediatype:audio AND year:[2010 TO 2100] AND (format:FLAC OR format:\"VBR MP3\")"
                + " AND NOT collection:podcasts AND NOT collection:librivoxaudio AND NOT collection:78rpm"
                + " AND NOT collection:oldtimeradio AND NOT collection:audio_bookspoetry";
        String url = "https://archive.org/advancedsearch.php?q=" + enc(consulta)
                + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=year"
                + "&sort%5B%5D=downloads%20desc&rows=30&page=1&output=json";
        JSONArray docs = new JSONObject(leer(url)).getJSONObject("response").getJSONArray("docs");
        List<Resultado> r = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.getJSONObject(i);
            String autor = texto(d, "creator"), anio = texto(d, "year");
            String det = (autor.isEmpty() ? "Internet Archive" : autor) + (anio.isEmpty() ? "" : " · " + anio);
            r.add(new Resultado(texto(d, "title"), det, d.getString("identifier"), false));
        }
        return r;
    }

    /**
     * Pistas de una grabacion del Archive, en orden.
     * @param maxima true = prefiere FLAC (sin perdida); false = MP3 de alta calidad (gasta menos datos)
     */
    public static List<Ajustes.Cancion> pistas(String id, String album, boolean maxima) throws Exception {
        JSONObject meta = new JSONObject(leer("https://archive.org/metadata/" + enc(id)));
        JSONArray files = meta.optJSONArray("files");
        List<String[]> flac = new ArrayList<>(), mp3 = new ArrayList<>(), otros = new ArrayList<>();
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String nombre = f.optString("name", ""), formato = f.optString("format", "").toLowerCase();
                if (nombre.isEmpty() || "metadata".equals(f.optString("source"))) continue;
                String titulo = f.optString("title", "");
                if (titulo.isEmpty()) {
                    titulo = nombre.contains("/") ? nombre.substring(nombre.lastIndexOf('/') + 1) : nombre;
                    int p = titulo.lastIndexOf('.');
                    if (p > 0) titulo = titulo.substring(0, p);
                }
                // solo pistas completas (mas de 1:30)
                double dur = segundos(f.optString("length", ""));
                if (dur > 0 && dur < 90) continue;
                String orden = f.optString("track", "");
                String[] item = {nombre, titulo, orden};
                if (formato.contains("flac")) flac.add(item);
                else if (formato.contains("mp3")) {
                    // de los MP3 se queda con el de mejor calidad de cada pista
                    if (formato.contains("vbr") || formato.contains("320")) mp3.add(0, item); else mp3.add(item);
                } else if (formato.contains("ogg") || formato.contains("aac") || formato.contains("m4a")) otros.add(item);
            }
        }
        List<String[]> elegidos = maxima && !flac.isEmpty() ? flac : !mp3.isEmpty() ? quitarRepetidos(mp3) : !flac.isEmpty() ? flac : otros;
        Collections.sort(elegidos, (a, b) -> {
            int x = numero(a[2]), y = numero(b[2]);
            if (x != y) return x - y;
            return a[0].compareToIgnoreCase(b[0]);
        });
        List<Ajustes.Cancion> r = new ArrayList<>();
        String marca = maxima && elegidos == flac ? " [FLAC]" : "";
        for (String[] e : elegidos) {
            if (r.size() >= 60) break;
            StringBuilder ruta = new StringBuilder();
            for (String parte : e[0].split("/")) {
                if (ruta.length() > 0) ruta.append('/');
                ruta.append(enc(parte));
            }
            r.add(new Ajustes.Cancion("https://archive.org/download/" + enc(id) + "/" + ruta, e[1] + marca + " · " + album));
        }
        return r;
    }

    private static List<String[]> quitarRepetidos(List<String[]> l) {
        List<String[]> r = new ArrayList<>();
        List<String> vistos = new ArrayList<>();
        for (String[] e : l) {
            String base = e[0].replaceAll("(?i)(_vbr|_64kb|_128kb|_320kb)?\\.mp3$", "");
            if (vistos.contains(base)) continue;
            vistos.add(base);
            r.add(e);
        }
        return r;
    }

    private static double segundos(String s) {
        try {
            if (s == null || s.isEmpty()) return 0;
            if (s.contains(":")) {
                double t = 0;
                for (String x : s.split(":")) t = t * 60 + Double.parseDouble(x);
                return t;
            }
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int numero(String s) {
        try {
            String d = s.split("/")[0].replaceAll("[^0-9]", "");
            return d.isEmpty() ? 9999 : Integer.parseInt(d);
        } catch (Exception e) {
            return 9999;
        }
    }

    /** Emisoras de radio de 128 kbps o mas. Sin texto: las mas escuchadas de Colombia. */
    public static List<Resultado> buscarRadios(String q) throws Exception {
        if (q == null || q.trim().isEmpty()) return radios("countrycode=CO");
        // por nombre de la emisora y por genero (salsa, vallenato, reggaeton…)
        List<Resultado> r = new ArrayList<>();
        Exception error = null;
        for (String filtro : new String[]{"name=" + enc(q.trim()), "tag=" + enc(q.trim().toLowerCase())}) {
            try {
                for (Resultado x : radios(filtro)) {
                    boolean ya = false;
                    for (Resultado y : r) if (y.id.equals(x.id)) { ya = true; break; }
                    if (!ya) r.add(x);
                }
            } catch (Exception e) { error = e; }
        }
        if (r.isEmpty() && error != null) throw error;
        return r;
    }

    private static List<Resultado> radios(String filtro) throws Exception {
        String ruta = "/json/stations/search?" + filtro + "&order=clickcount&reverse=true&hidebroken=true&bitrateMin=128&limit=40";
        Exception ultimo = null;
        for (String s : RADIO_SERVIDORES) {
            try {
                JSONArray a = new JSONArray(leer(s + ruta));
                List<Resultado> r = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String url = o.optString("url_resolved", o.optString("url", ""));
                    if (url.isEmpty()) continue;
                    String det = o.optInt("bitrate", 0) + " kbps " + o.optString("codec", "") + " · " + o.optString("country", "");
                    Resultado em = new Resultado(o.optString("name", "Emisora").trim(), det, url, true);
                    String logo = o.optString("favicon", "");
                    if (logo.startsWith("https://")) em.imagen = logo;
                    r.add(em);
                }
                return r;
            } catch (Exception e) {
                ultimo = e;
            }
        }
        throw ultimo != null ? ultimo : new Exception("Sin respuesta del directorio de radios");
    }
}
