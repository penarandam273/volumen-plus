package com.luis.volumenplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Caratulas (version 2.0.22).
 * Usa la portada real cuando existe (Audius, Deezer, logo de la emisora); si no hay, dibuja un diseño propio:
 * degradado segun el genero, disco, ecualizador, iniciales del artista y el nombre de la cancion.
 * Todo se carga en segundo plano (nunca en el hilo de la pantalla) y queda guardado para no gastar datos.
 */
public final class Caratula {
    private Caratula() { }

    private static final LruCache<String, Bitmap> MEMORIA = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final java.util.Set<String> SIN_PORTADA = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final ExecutorService HILOS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "VolumenPlus-caratulas");
        t.setPriority(Thread.MIN_PRIORITY); // nunca le quita fuerza a la musica
        return t;
    });
    private static final Handler PANTALLA = new Handler(Looper.getMainLooper());
    private static final String PREFS = "volumen_caratulas";

    /** "📻 Emisora" / "Cancion · Artista" -> {titulo, artista, radio("1"/"0")}. */
    public static String[] partes(String nombre) {
        String n = nombre == null ? "" : nombre.trim();
        boolean radio = n.startsWith("📻");
        n = n.replaceFirst("^(📻|▶|♪)\\s*", "");
        String titulo = n, artista = "";
        int k = n.lastIndexOf(" · ");
        if (k > 0) { titulo = n.substring(0, k).trim(); artista = n.substring(k + 3).trim(); }
        return new String[]{titulo, artista, radio ? "1" : "0"};
    }

    /** Recuerda la portada real de una cancion de la cola (por su direccion). */
    public static void recordar(Context c, String uri, String imagen) {
        if (uri == null || imagen == null || imagen.isEmpty()) return;
        try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(hash(uri), imagen).apply(); } catch (Throwable ignored) { }
    }

    public static String imagenDe(Context c, String uri) {
        if (uri == null) return null;
        try { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(hash(uri), null); } catch (Throwable t) { return null; }
    }

    /** Pone la caratula en un ImageView: primero el diseño (al instante) y luego la portada real si aparece. */
    public static void poner(ImageView iv, String titulo, String artista, boolean radio, String imagen, int lado) {
        final String clave = clave(titulo, artista, imagen) + "|" + lado;
        iv.setTag(clave);
        Bitmap ya = MEMORIA.get(clave);
        if (ya != null) { iv.setImageBitmap(ya); return; }
        iv.setImageBitmap(diseno(titulo, artista, radio, Math.min(lado, 256)));
        final Context ctx = iv.getContext().getApplicationContext();
        HILOS.execute(() -> {
            Bitmap b = real(ctx, titulo, artista, radio, imagen, lado);
            if (b == null) return;
            MEMORIA.put(clave, b);
            PANTALLA.post(() -> { if (clave.equals(iv.getTag())) iv.setImageBitmap(b); });
        });
    }

    /** Para la notificacion (llamar fuera del hilo de la pantalla): portada real o el diseño. */
    public static Bitmap cargar(Context c, String titulo, String artista, boolean radio, String imagen, int lado) {
        Bitmap b = real(c, titulo, artista, radio, imagen, lado);
        return b != null ? b : diseno(titulo, artista, radio, lado);
    }

    // ---------- portada real ----------
    private static Bitmap real(Context c, String titulo, String artista, boolean radio, String imagen, int lado) {
        String clave = clave(titulo, artista, imagen);
        if (SIN_PORTADA.contains(clave)) return null;
        File dir = new File(c.getCacheDir(), "caratulas");
        File f = new File(dir, hash(clave) + ".jpg");
        try {
            if (f.exists() && f.length() > 0) return escalar(BitmapFactory.decodeFile(f.getAbsolutePath()), lado);
            String url = imagen;
            if ((url == null || url.isEmpty()) && !radio) url = buscarEnDeezer(titulo, artista);
            if (url == null || url.isEmpty()) { SIN_PORTADA.add(clave); return null; }
            byte[] d = bajar(url);
            Bitmap b = d == null ? null : BitmapFactory.decodeByteArray(d, 0, d.length);
            if (b == null || b.getWidth() < 32) { SIN_PORTADA.add(clave); return null; }
            Bitmap g = escalar(b, 500);
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { g.compress(Bitmap.CompressFormat.JPEG, 85, o); }
            return escalar(g, lado);
        } catch (Throwable t) {
            SIN_PORTADA.add(clave);
            return null;
        }
    }

    private static String buscarEnDeezer(String titulo, String artista) {
        try {
            String q = (titulo + " " + artista).trim();
            if (q.length() < 2) return null;
            byte[] d = bajar("https://api.deezer.com/search?q=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20") + "&limit=5");
            if (d == null) return null;
            JSONArray a = new JSONObject(new String(d, "UTF-8")).optJSONArray("data");
            if (a == null) return null;
            String t = normal(titulo);
            String primera = t.isEmpty() ? "" : t.split(" ")[0];
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                // la portada tiene que ser de esa cancion (no de otra que se llame parecido)
                if (!primera.isEmpty() && !normal(o.optString("title", "")).contains(primera)) continue;
                JSONObject al = o.optJSONObject("album");
                if (al != null && !al.optString("cover_big", "").isEmpty()) return al.optString("cover_big");
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static byte[] bajar(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "VolumenPlus/2.0 (Android)");
        try {
            if (c.getResponseCode() >= 400) return null;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                byte[] b = new byte[16 * 1024];
                int n, total = 0;
                while ((n = in.read(b)) > 0) { o.write(b, 0, n); total += n; if (total > 3_000_000) return null; }
                return o.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    // ---------- diseño propio ----------
    private static final int[][] PALETAS = {
            {0xFFF59E0B, 0xFF166534}, // vallenato: oro y verde
            {0xFFD946EF, 0xFF4C1D95}, // reggaeton: fucsia y morado
            {0xFFEF4444, 0xFF7C2D12}, // salsa: rojo y cobre
            {0xFFFACC15, 0xFF15803D}, // champeta / pico: amarillo y verde
            {0xFF38BDF8, 0xFF1E3A8A}, // electronica: cian y azul
            {0xFFFB7185, 0xFF9D174D}, // bachata: rosa
            {0xFF22D3EE, 0xFF0F766E}, // emisora
            {0xFFA78BFA, 0xFF1E1B4B}, // otras
            {0xFF34D399, 0xFF064E3B},
            {0xFFF97316, 0xFF581C87},
    };

    public static Bitmap diseno(String titulo, String artista, boolean radio, int lado) {
        String n = normal(titulo + " " + artista);
        int pal;
        if (radio) pal = 6;
        else if (n.contains("vallenat") || n.contains("silvestre") || n.contains("manjarres") || n.contains("farid") || n.contains("diomedes") || n.contains("celedon")) pal = 0;
        else if (n.contains("reggaeton") || n.contains("perreo") || n.contains("daddy") || n.contains("don omar") || n.contains("bad bunny") || n.contains("feid")) pal = 1;
        else if (n.contains("salsa")) pal = 2;
        else if (n.contains("champeta") || n.contains("pico") || n.contains("terapia")) pal = 3;
        else if (n.contains("remix") || n.contains("house") || n.contains("electro")) pal = 4;
        else if (n.contains("bachata") || n.contains("merengue")) pal = 5;
        else pal = 7 + Math.abs(n.hashCode() % 3);
        int[] c = PALETAS[pal];
        Bitmap b = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, lado, lado, c[0], c[1], Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, lado, lado, p);
        // brillo suave arriba a la izquierda
        p.setShader(new RadialGradient(lado * 0.25f, lado * 0.2f, lado * 0.7f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, lado, lado, p);
        p.setShader(null);
        // disco de vinilo
        float cx = lado * 0.72f, cy = lado * 0.40f, rr = lado * 0.36f;
        p.setColor(0x33000000);
        cv.drawCircle(cx, cy, rr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, lado / 220f));
        p.setColor(0x22FFFFFF);
        for (float r = rr * 0.35f; r < rr; r += rr * 0.09f) cv.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(c[0]);
        cv.drawCircle(cx, cy, rr * 0.22f, p);
        p.setColor(0xCC000000);
        cv.drawCircle(cx, cy, rr * 0.04f, p);
        // ecualizador
        int barras = 14, sem = Math.abs((titulo + artista).hashCode());
        float ancho = lado * 0.6f / barras, base = lado * 0.62f;
        p.setColor(0x99FFFFFF);
        for (int i = 0; i < barras; i++) {
            float h = lado * (0.05f + ((sem >> (i % 16)) & 7) / 7f * 0.16f);
            cv.drawRoundRect(new RectF(lado * 0.07f + i * ancho, base - h, lado * 0.07f + i * ancho + ancho * 0.7f, base), ancho * 0.3f, ancho * 0.3f, p);
        }
        // iniciales del artista (o un icono)
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextSize(lado * 0.2f);
        String ini = radio ? "📻" : iniciales(artista.isEmpty() ? titulo : artista);
        cv.drawText(ini, lado * 0.07f, lado * 0.27f, p);
        // marca
        p.setTextSize(lado * 0.045f);
        p.setColor(0xCCFFFFFF);
        p.setLetterSpacing(0.2f);
        cv.drawText("VOLUMEN+ PRO", lado * 0.07f, lado * 0.07f + lado * 0.03f, p);
        p.setLetterSpacing(0f);
        // banda inferior con el nombre
        p.setColor(0x66000000);
        cv.drawRect(0, lado * 0.7f, lado, lado, p);
        p.setColor(Color.WHITE);
        p.setTextSize(lado * 0.075f);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        cv.drawText(cortar(titulo, p, lado * 0.86f), lado * 0.07f, lado * 0.81f, p);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setTextSize(lado * 0.055f);
        p.setColor(0xDDFFFFFF);
        cv.drawText(cortar(radio ? "En vivo" : (artista.isEmpty() ? "Volumen+ Pro" : artista), p, lado * 0.86f), lado * 0.07f, lado * 0.9f, p);
        return b;
    }

    private static String cortar(String s, Paint p, float max) {
        return TextUtils.ellipsize(s == null ? "" : s, new android.text.TextPaint(p), max, TextUtils.TruncateAt.END).toString();
    }

    private static String iniciales(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : (s == null ? "" : s).trim().split("\\s+")) {
            if (w.isEmpty() || !Character.isLetterOrDigit(w.charAt(0))) continue;
            b.append(Character.toUpperCase(w.charAt(0)));
            if (b.length() == 2) break;
        }
        return b.length() == 0 ? "♪" : b.toString();
    }

    private static Bitmap escalar(Bitmap b, int lado) {
        if (b == null) return null;
        if (b.getWidth() == lado && b.getHeight() == lado) return b;
        int m = Math.min(b.getWidth(), b.getHeight());
        Bitmap c = Bitmap.createBitmap(b, (b.getWidth() - m) / 2, (b.getHeight() - m) / 2, m, m);
        return Bitmap.createScaledBitmap(c, lado, lado, true);
    }

    private static String clave(String titulo, String artista, String imagen) {
        return (imagen != null && !imagen.isEmpty()) ? imagen : normal(titulo) + "|" + normal(artista);
    }

    static String normal(String s) {
        String n = Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("\\((.*?)\\)|\\[(.*?)\\]", " ").replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String hash(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format("%02x", h[i]));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}
