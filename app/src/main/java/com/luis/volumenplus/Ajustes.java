package com.luis.volumenplus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Ajustes guardados en el celular: sonido y lista de canciones. */
public class Ajustes {

    public static final String PREFS = "volumen_pro";
    public static final int MAX_POTENCIA = 24;
    public static final int POTENCIA_SEGURA = 12;

    /** Indice 5 = "Bajo + nitido": bajos potentes y voces claras a la vez (se quita el "barro" de 250-500 Hz). */
    public static final String[] MODOS = {"Natural", "Alta fidelidad", "Bajos potentes", "Voces claras", "Maxima potencia", "Bajo + nitido", "Pico Skorpion"};
    /** Orden en que se muestran los botones (el combinado va de segundo). */
    public static final int[] ORDEN_MODOS = {0, 5, 6, 1, 2, 3, 4};
    public static final int MODO_BAJO_NITIDO = 5;
    private static final int[][] MODO_BANDAS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {2, 1, 0, 0, -1, 0, 1, 2, 3, 2},
            {7, 6, 4, 1, 0, 0, 0, 1, 1, 0},
            {-2, -1, 0, 1, 3, 4, 3, 1, 0, -1},
            {5, 4, 2, 0, -1, 1, 3, 3, 2, 1},
            {6, 5, 3, 0, -2, -1, 2, 4, 3, 1},
            // 2.0.22 "Pico Skorpion": golpe en 60-125 Hz, sin barro en 250-500 Hz, voz presente y brillo (como un pico de la costa)
            {4, 3, 2, -1, -2, 0, 1, 2, 1, 1},
    };
    private static final int[] MODO_ESPACIAL = {0, 300, 200, 0, 300, 200, 150};
    private static final int[] MODO_POTENCIA = {6, 8, 10, 8, 18, 12, 14};

    public static final int REPETIR_NO = 0, REPETIR_TODO = 1, REPETIR_UNA = 2;

    /** Equipos donde suena la musica: cada uno tiene su curva para sonar lo mejor posible. */
    public static final String[] EQUIPOS = {"Automático", "Celular", "Audífonos", "Parlante Bluetooth", "Carro", "Equipo de sonido", "Televisor"};
    private static final int[][] CURVA_EQUIPO = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {-6, -3, 2, 2, 0, 0, 1, 2, 1, 0},   // celular: el parlante no da sub-bajos, se refuerza donde si suena
            {3, 3, 1, 0, -1, 0, 0, 1, 1, 1},    // audifonos: bajo profundo, agudos limpios
            {1, 3, 2, -1, -2, 0, 1, 2, 2, 1},   // parlante bluetooth: golpe en 60-125 Hz, sin retumbe
            {3, 4, 2, -2, -2, -1, 0, 2, 3, 2},  // carro: compensa el ruido del motor y la carretera
            {2, 2, 1, 0, -1, 0, 0, 0, 1, 1},    // equipo de sonido / bafles: casi plano, ellos ya tienen fuerza
            {-2, -1, 0, 0, 1, 2, 3, 2, 1, 0},   // televisor: voces claras
    };
    /** Potencia maxima segura por equipo (los bafles grandes no necesitan tanto refuerzo digital). */
    private static final int[] TOPE_EQUIPO = {24, 24, 18, 20, 22, 10, 16};

    public static int[] curvaEquipo(int e) { return CURVA_EQUIPO[Math.max(0, Math.min(CURVA_EQUIPO.length - 1, e))]; }
    public static int topeEquipo(int e) { return TOPE_EQUIPO[Math.max(0, Math.min(TOPE_EQUIPO.length - 1, e))]; }
    public int equipo() { return p.getInt("equipo", 0); }
    public void setEquipo(int e) { p.edit().putInt("equipo", e).apply(); }
    public boolean transiciones() { return p.getBoolean("transiciones", true); }
    public void setTransiciones(boolean v) { p.edit().putBoolean("transiciones", v).apply(); }

    private final SharedPreferences p;

    public Ajustes(Context c) {
        p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // la version anterior de Volumen+ guardaba el refuerzo en otro lugar: se conserva al actualizar
        if (!p.contains("potencia")) {
            SharedPreferences viejo = c.getSharedPreferences("volumen_plus", Context.MODE_PRIVATE);
            if (viejo.contains("gain_db")) p.edit().putInt("potencia", viejo.getInt("gain_db", 8)).apply();
        }
        // Version 3: estrena el modo combinado "Bajo + nitido" una sola vez (despues respeta lo que elijas)
        if (p.getInt("esquema", 0) < 3) {
            setModo(MODO_BAJO_NITIDO);
            p.edit().putInt("esquema", 3).putBoolean("compresor", true).apply();
        }
        // Version 4: lo que ya tenias en la lista pasa a "Mi lista" (la cola de reproduccion queda aparte)
        if (p.getInt("esquema", 0) < 4) {
            p.edit().putString("milista", p.getString("lista", "")).putInt("esquema", 4).apply();
        }
    }

    public boolean activo() { return p.getBoolean("activo", true); }
    public int potencia() { return p.getInt("potencia", 8); }
    public int espacial() { return p.getInt("espacial", 300); }
    public boolean compresor() { return p.getBoolean("compresor", true); }
    public boolean global() { return p.getBoolean("global", true); }
    public int modo() { return p.getInt("modo", MODO_BAJO_NITIDO); }
    /** Bajo extra en dB (0 a 10): sube 60-125 Hz y quita un poco de 250-500 Hz para no perder nitidez. */
    public int bajoExtra() { return p.getInt("bajo", 5); }
    public void setBajoExtra(int v) { p.edit().putInt("bajo", Math.max(0, Math.min(10, v))).apply(); }
    public boolean claridad() { return p.getBoolean("claridad", true); }
    public void setClaridad(boolean v) { p.edit().putBoolean("claridad", v).apply(); }
    public boolean aleatorio() { return p.getBoolean("aleatorio", false); }
    public void setAleatorio(boolean v) { p.edit().putBoolean("aleatorio", v).apply(); }
    public int repetir() { return p.getInt("repetir", REPETIR_TODO); }
    public void setRepetir(int v) { p.edit().putInt("repetir", v).apply(); }
    public boolean flac() { return p.getBoolean("flac", false); }
    public void setFlac(boolean v) { p.edit().putBoolean("flac", v).apply(); }

    public int[] bandas() {
        int[] b = MODO_BANDAS[MODO_BAJO_NITIDO].clone();
        String s = p.getString("bandas", null);
        if (s == null) return b;
        String[] partes = s.split(",");
        for (int i = 0; i < b.length && i < partes.length; i++) {
            try { b[i] = Integer.parseInt(partes[i].trim()); } catch (NumberFormatException ignored) { }
        }
        return b;
    }

    public void setActivo(boolean v) { p.edit().putBoolean("activo", v).apply(); }
    public void setPotencia(int v) { p.edit().putInt("potencia", Math.max(0, Math.min(MAX_POTENCIA, v))).apply(); }
    public void setEspacial(int v) { p.edit().putInt("espacial", Math.max(0, Math.min(1000, v))).apply(); }
    public void setCompresor(boolean v) { p.edit().putBoolean("compresor", v).apply(); }
    public void setGlobal(boolean v) { p.edit().putBoolean("global", v).apply(); }

    public void setBanda(int i, int db) {
        int[] b = bandas();
        b[i] = Math.max(-12, Math.min(12, db));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < b.length; k++) { if (k > 0) sb.append(','); sb.append(b[k]); }
        p.edit().putString("bandas", sb.toString()).putInt("modo", -1).apply();
    }

    public void setModo(int m) {
        int[] b = MODO_BANDAS[m];
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < b.length; k++) { if (k > 0) sb.append(','); sb.append(b[k]); }
        p.edit().putString("bandas", sb.toString()).putInt("modo", m)
                .putInt("espacial", MODO_ESPACIAL[m]).putInt("potencia", MODO_POTENCIA[m]).apply();
    }

    // ---------- lista de canciones: una por linea "uri<TAB>nombre" ----------
    public static class Cancion {
        public final String uri;
        public final String nombre;
        public Cancion(String uri, String nombre) { this.uri = uri; this.nombre = nombre; }
    }

    public List<Cancion> lista() {
        return leerLista(p.getString("lista", ""));
    }

    private static List<Cancion> leerLista(String s) {
        List<Cancion> l = new ArrayList<>();
        if (s == null || s.isEmpty()) return l;
        for (String linea : s.split("\n")) {
            int t = linea.indexOf('\t');
            if (t > 0) l.add(new Cancion(linea.substring(0, t), linea.substring(t + 1)));
        }
        return l;
    }

    public void guardarLista(List<Cancion> l) {
        p.edit().putString("lista", texto(l)).apply();
    }

    private static String texto(List<Cancion> l) {
        StringBuilder sb = new StringBuilder();
        for (Cancion c : l) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.uri).append('\t').append(c.nombre.replace('\n', ' ').replace('\t', ' '));
        }
        return sb.toString();
    }

    // ---------- Mi lista (lo que el usuario agrega con +) ----------
    public List<Cancion> miLista() { return leerLista(p.getString("milista", "")); }
    public void guardarMiLista(List<Cancion> l) { p.edit().putString("milista", texto(l)).apply(); }

    /** Agrega a Mi lista sin repetir. Devuelve false si ya estaba. */
    public boolean agregarAMiLista(Cancion c) {
        List<Cancion> l = miLista();
        for (Cancion x : l) if (x.uri.equals(c.uri)) return false;
        l.add(c);
        guardarMiLista(l);
        return true;
    }

    // ---------- preferencias que la app aprende ----------
    /** tipo: "g" genero, "a" artista, "q" busqueda. */
    public void sumarGusto(String tipo, String valor, int puntos) {
        if (valor == null) return;
        valor = valor.trim();
        if (valor.isEmpty() || valor.length() > 60) return;
        try {
            JSONObject o = new JSONObject(p.getString("gustos", "{}"));
            String k = tipo + ":" + valor;
            o.put(k, o.optInt(k, 0) + puntos);
            p.edit().putString("gustos", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Los valores con mas puntos de un tipo. */
    public List<String> gustos(String tipo, int max) {
        List<String[]> l = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(p.getString("gustos", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith(tipo + ":")) l.add(new String[]{k.substring(tipo.length() + 1), String.valueOf(o.optInt(k, 0))});
            }
        } catch (Exception ignored) { }
        Collections.sort(l, (a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        List<String> r = new ArrayList<>();
        for (int i = 0; i < l.size() && r.size() < max; i++) r.add(l.get(i)[0]);
        return r;
    }

    public boolean tieneGustos() {
        return !p.getString("gustos", "{}").equals("{}");
    }

    // ---------- listas guardadas con nombre ----------
    private JSONObject listas() {
        try { return new JSONObject(p.getString("listas", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public List<String> nombresListas() {
        List<String> r = new ArrayList<>();
        JSONObject o = listas();
        Iterator<String> it = o.keys();
        while (it.hasNext()) r.add(it.next());
        Collections.sort(r, String.CASE_INSENSITIVE_ORDER);
        return r;
    }

    public int cantidadEn(String nombre) {
        return leerLista(listas().optString(nombre, "")).size();
    }

    /** Guarda la lista actual con un nombre (si ya existe, la reemplaza). */
    public void guardarComo(String nombre) {
        JSONObject o = listas();
        try { o.put(nombre, texto(lista())); } catch (Exception ignored) { }
        p.edit().putString("listas", o.toString()).apply();
    }

    /** Pone una lista guardada como lista de reproduccion. */
    public boolean cargarLista(String nombre) {
        List<Cancion> l = leerLista(listas().optString(nombre, ""));
        if (l.isEmpty()) return false;
        guardarLista(l);
        setActual(0);
        return true;
    }

    public void borrarLista(String nombre) {
        JSONObject o = listas();
        o.remove(nombre);
        p.edit().putString("listas", o.toString()).apply();
    }

    public int actual() { return p.getInt("actual", 0); }
    public void setActual(int i) { p.edit().putInt("actual", i).apply(); }
}
