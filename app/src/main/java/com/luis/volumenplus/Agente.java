package com.luis.volumenplus;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Cerebro del Agente DJ: entiende lo que le dices (por voz o escrito) y decide que hacer.
 * Funciona sin internet para entender; solo usa internet para buscar musica.
 */
public final class Agente {

    /** Lo que el agente puede hacer en la app. */
    public interface Manos {
        void tocar(String que);            // buscar y poner una cancion o artista
        void fiesta(int estilo);           // armar lista de fiesta
        void nueva(String genero);         // musica nueva / tendencias
        void exitos();                     // top de hoy
        void paraTi();
        void emisora(String que);
        void control(String accion);       // pausa, sigue, siguiente, anterior, aleatorio, repetir
        void volumen(int pasos);           // + sube, - baja (volumen del telefono)
        void bajo(int pasos);
        void potencia(int pasos);
        void modo(int modo);
        void equipo(int equipo);
        void efecto(int efecto);
        void saludo(String texto);
        void calidad(boolean maxima);
        void queSuena();
        void revisar();                    // el guardian revisa y repara la app
        void responder(String texto);      // lo dice en voz alta y lo muestra
    }

    private final Manos m;

    public Agente(Manos manos) { this.m = manos; }

    static String normal(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return " " + n.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim() + " ";
    }

    private static boolean tiene(String t, String... palabras) {
        for (String p : palabras) if (t.contains(" " + p + " ") || (p.endsWith("*") && t.contains(" " + p.substring(0, p.length() - 1)))) return true;
        return false;
    }

    /** "familia mendoza" -> "Familia Mendoza" (articulos en minuscula). */
    private static String nombres(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.trim().split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            boolean art = w.matches("la|el|los|las|de|del|y|para|mi|mis|todos|todas|que");
            b.append(art ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    /** Quita las palabras de orden y deja "lo que se busca". */
    private static String resto(String t, String... quitar) {
        String r = t;
        for (String q : quitar) r = r.replace(" " + q + " ", " ");
        return r.replaceAll("\\s+", " ").trim();
    }

    private static int estiloFiesta(String t) {
        if (tiene(t, "reggaeton", "regueton", "reguetón", "perreo", "urbano", "urbana")) return 1;
        if (tiene(t, "salsa", "salsita")) return 2;
        if (tiene(t, "vallenato", "vallenatos", "vallenata", "parranda")) return 3;
        if (tiene(t, "champeta", "champetas", "terapia")) return 4;
        if (tiene(t, "electronica", "electro", "house", "edm", "techno", "tecno")) return 5;
        if (tiene(t, "bachata", "merengue", "bachatas", "merengues")) return 6;
        if (tiene(t, "hora loca", "guaracha", "aleteo", "zapateo") || t.contains("hora loca")) return 7;
        return 0; // crossover
    }

    private static int equipoDe(String t) {
        if (tiene(t, "carro", "auto", "carro*", "coche", "camioneta", "moto")) return 4;
        if (tiene(t, "audifonos", "audifono", "auriculares", "cascos", "diadema")) return 2;
        if (tiene(t, "parlante", "bafle", "bocina", "jbl", "bluetooth")) return 3;
        if (tiene(t, "equipo", "minicomponente", "bafles", "sonido grande", "picó", "pico", "torre")) return 5;
        if (tiene(t, "tele", "televisor", "tv", "television")) return 6;
        if (tiene(t, "celular", "telefono", "movil")) return 1;
        if (tiene(t, "automatico", "solo")) return 0;
        return -1;
    }

    /** Entiende una frase y la ejecuta. */
    public void escuchar(String frase) {
        if (frase == null || frase.trim().isEmpty()) return;
        String t = normal(frase);

        // ---- guardian ----
        if (tiene(t, "revisa la app", "diagnostico", "diagnostica", "repara", "reparate", "arreglate", "chequeo", "salud")
                || t.contains(" revisa la app ") || t.contains(" estado de la app ") || t.contains(" revisate ")) { m.revisar(); return; }
        // ---- saludos de locutor ----
        if (tiene(t, "saluda", "saludo", "saludos", "saludar", "dedica", "dedicatoria", "felicita", "felicitaciones", "cumpleanos")) {
            String a = nombres(resto(t, "dj", "manda", "mandale", "envia", "un", "una", "saludo", "saludos", "saluda", "saludar", "a", "dedica",
                    "dedicatoria", "por", "favor", "porfa", "dile", "di", "feliz", "cumpleanos"));
            String txt;
            if (tiene(t, "cumpleanos")) txt = "¡Atención, atención! Un feliz cumpleaños para " + a.replaceFirst("^[Pp]ara ", "").trim()
                    + ". ¡Que la pase súper bien, que esta fiesta es en su honor!";
            else txt = "¡Un saludo muy especial " + (a.toLowerCase().startsWith("para ") ? "para " + a.substring(5) : "para " + a) + "! ¡Que siga la fiesta!";
            m.saludo(txt);
            return;
        }
        // ---- efectos ----
        int ef = Efectos.buscar(t);
        if (ef >= 0 && (tiene(t, "efecto", "suelta", "sueltala", "lanza", "tira", "echa", "hazme", "haz", "toca la", "suena la") || t.contains(" la corneta ") || t.trim().split(" ").length <= 2)) {
            m.efecto(ef);
            return;
        }
        // ---- equipo / ecualizacion ----
        if (tiene(t, "ecualiza", "ecualizar", "ecualizacion", "ajusta", "configura", "conectado", "conecte", "estoy", "suena en", "sonar en")
                || t.contains(" para el ") || t.contains(" para la ") || t.contains(" en el ")) {
            int e = equipoDe(t);
            if (e >= 0 && !tiene(t, "fiesta", "lista")) { m.equipo(e); return; }
        }
        // ---- fiesta ----
        if (tiene(t, "fiesta", "rumba", "parranda", "farra", "bailar", "baile", "bailable", "hora loca", "playlist", "lista") || t.contains("hora loca")) {
            if (tiene(t, "nueva", "nuevo", "estrenos") && !tiene(t, "fiesta", "rumba")) { m.nueva(null); return; }
            m.fiesta(estiloFiesta(t));
            return;
        }
        // ---- novedades (antes que "de nuevo" = repetir) ----
        if (t.contains(" hay de nuevo ") || t.contains(" que hay nuevo ") || tiene(t, "novedades", "estrenos")) { m.nueva("Latin"); return; }
        // ---- controles ----
        if (tiene(t, "pausa", "pausar", "parala", "detente", "deten", "stop", "silencio", "calla") || t.trim().equals("para") || t.contains(" para la musica ")) { m.control("pausa"); return; }
        if (tiene(t, "siguiente", "salta", "saltar", "otra", "cambia", "cambiala", "proxima", "next")) { m.control("siguiente"); return; }
        if (tiene(t, "anterior", "atras", "devuelve", "regresa", "la de antes")) { m.control("anterior"); return; }
        if (tiene(t, "aleatorio", "aleatoria", "revuelve", "mezcla", "shuffle", "al azar")) { m.control("aleatorio"); return; }
        if (tiene(t, "repite", "repetir", "otra vez", "de nuevo")) { m.control("repetir"); return; }
        if (tiene(t, "sigue", "continua", "reanuda", "play", "dale play")) { m.control("sigue"); return; }

        // ---- sonido ----
        boolean subir = tiene(t, "sube", "subele", "mas", "aumenta", "dale mas", "duro", "fuerte", "potente");
        boolean bajar = tiene(t, "baja", "bajale", "menos", "quita", "reduce", "suave", "pasito");
        if (tiene(t, "bajo", "bajos", "graves", "bass", "retumbe", "golpe")) { m.bajo(bajar && !subir ? -2 : 2); return; }
        if (tiene(t, "potencia", "power")) { m.potencia(bajar && !subir ? -3 : 3); return; }
        if (tiene(t, "volumen", "volumen*") || (tiene(t, "duro", "fuerte", "pasito", "suave") && !tiene(t, "pon", "busca"))
                || ((subir || bajar) && t.trim().split(" ").length <= 2)) {
            m.volumen(bajar && !subir ? -2 : 2); return;
        }
        if (tiene(t, "nitido", "nitidez", "claro", "claridad", "voces", "voz")) { m.modo(Ajustes.MODO_BAJO_NITIDO); return; }
        if (tiene(t, "maxima potencia", "a todo") || t.contains("a todo")) { m.modo(4); return; }
        if (tiene(t, "natural", "plano", "original")) { m.modo(0); return; }
        if (tiene(t, "calidad", "hifi", "flac", "alta fidelidad")) { m.calidad(!bajar); return; }
        int e = equipoDe(t);
        if (e > 0 && (t.trim().split(" ").length <= 3 || tiene(t, "equipo", "carro", "audifonos", "parlante", "televisor", "tv"))) { m.equipo(e); return; }

        // ---- informacion ----
        if (tiene(t, "que suena", "que esta sonando", "que cancion", "como se llama") || t.contains("que suena") || t.contains("como se llama")) { m.queSuena(); return; }
        if (tiene(t, "tendencia", "tendencias", "nuevo", "nueva", "nuevas", "estrenos", "estreno", "lanzamientos", "novedades", "actual")) {
            m.nueva(t.contains("electro") ? "Electronic" : tiene(t, "rap", "trap", "hip hop") ? "Hip-Hop/Rap" : t.contains("pop") ? "Pop" : "Latin");
            return;
        }
        if (tiene(t, "exitos", "top", "lo mas sonado", "pegadas", "sonando")) { m.exitos(); return; }
        if (tiene(t, "para mi", "recomienda", "recomiendame", "algo que me guste", "gustos", "sorprendeme")) { m.paraTi(); return; }
        if (tiene(t, "emisora", "emisoras", "radio")) {
            m.emisora(resto(t, "pon", "ponme", "busca", "una", "la", "emisora", "emisoras", "radio", "de"));
            return;
        }
        if (tiene(t, "ayuda", "que puedes hacer", "que sabes hacer", "comandos") || t.contains("que puedes")) {
            m.responder("Puedo armar fiestas: di fiesta de salsa, de reggaetón o crossover. Poner canciones: pon Karol G. "
                    + "Ecualizar: ecualiza para el carro. Efectos: corneta, sirena o explosión. Saludos: saluda a la familia Mendoza. "
                    + "Y música nueva: qué hay de nuevo.");
            return;
        }
        // ---- poner musica (lo demas) ----
        String q = resto(t, "pon", "ponme", "ponga", "pone", "reproduce", "toca", "tocame", "busca", "buscame", "quiero", "oir", "escuchar",
                "escuche", "dj", "por", "favor", "porfa", "una", "un", "la", "el", "de", "cancion", "canciones", "musica", "tema", "algo", "me");
        if (q.length() >= 2) m.tocar(q);
        else m.responder("No te entendí. Di por ejemplo: fiesta de salsa, pon Karol G o ecualiza para el carro.");
    }
}
