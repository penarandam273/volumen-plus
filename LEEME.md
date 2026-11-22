# Volumen+ Pro (penarandam273/volumen-plus)

**Cómo se actualiza:** cada cambio que se sube aquí se compila solo en GitHub Actions, firmado con la llave oficial
(secreto `VOLUMEN_LLAVE`), y queda publicado en **Releases**. En la app, la tarjeta del Agente DJ tiene
**⬆️ Buscar actualización**: descarga la versión nueva, revisa que tenga la firma oficial y la instala **encima**
(no se pierden listas, gustos ni ajustes). La app también revisa sola cada 12 horas.

La llave (`volumenplus.keystore`) **no** se sube al repositorio.

---

# Volumen+ (Redmi 13C) · versión 2

App Android hecha a medida (Java, sin librerías externas). Paquete `com.luis.volumenplus`, firmado con la llave fija: cada versión nueva se instala **encima** de la anterior.

- **Refuerzo en todo el teléfono** (encendido de fábrica): el mismo método de la versión 1 que sí funciona en Xiaomi (LoudnessEnhancer + ecualizador sobre la salida general y sobre las apps que lo permiten), ahora hasta **+24 dB**.
- **Reproductor** de tu música (MP3, FLAC, WAV, AAC, OGG…) con controles en la notificación, pantalla de bloqueo y audífonos.
- **Música en línea gratis y sin anuncios**: Internet Archive (álbumes y grabaciones libres, muchos en **FLAC**) y emisoras de radio por internet de 128 kbps o más (Radio Browser). Sin cuentas ni claves.
- **Sonido Pro** en el reproductor: DynamicsProcessing (ecualizador de 10 bandas → compresor de 3 bandas → limitador) + sonido envolvente. Modos: Natural, Alta fidelidad, Bajos potentes, Voces claras y Máxima potencia.

> ⚠️ Más de +12 dB por mucho tiempo puede dañar el oído con audífonos.

---

## Paso 1 · Compilar el APK (elige UNA opción)

### Opción A — GitHub (no necesitas instalar nada en el PC)
1. Entra a github.com con tu cuenta → **New repository** → nombre `VolumenPlus` → **Create repository**.
2. Toca **uploading an existing file**.
3. Descomprime el ZIP en tu PC y **arrastra todo el contenido de la carpeta `VolumenPlus`** (incluida la carpeta `.github`) a la página → **Commit changes**.
4. Ve a la pestaña **Actions**: se ejecuta solo “Compilar APK” (tarda 3–6 min). Si pide habilitar Actions, acéptalo.
5. Cuando salga el visto verde ✔, ve a **Releases** (lado derecho de la página principal del repositorio) y descarga **VolumenPlus.apk**. Puedes abrir ese enlace directamente desde el celular.

### Opción B — Android Studio en el PC
1. Instala Android Studio (developer.android.com/studio).
2. **File > Open** → elige la carpeta `VolumenPlus` → espera que termine “Gradle sync”.
3. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.
5. (Opcional) Con el celular conectado por USB y la **Depuración USB** activada, el botón ▶ Run lo instala directamente.

---

## Paso 2 · Instalar en el Redmi 13C
1. Pasa el APK al celular (descarga desde Releases, por cable USB, Drive o WhatsApp “Documento”).
2. Ábrelo desde **Administrador de archivos** (o la notificación de descarga).
3. Si sale “Por tu seguridad, no se permite instalar apps desconocidas”: toca **Ajustes** → activa **Permitir de esta fuente** → regresa e **Instalar**.
4. Xiaomi puede mostrar un análisis de seguridad o avisar que la app “no es de la tienda”: es normal porque la hiciste tú. Toca **Instalar de todos modos** / **Continuar**.
5. Por seguridad, después vuelve a desactivar “Permitir de esta fuente”.

## Paso 3 · Que Xiaomi no la cierre sola (importante)
En **Ajustes > Aplicaciones > Administrar aplicaciones > Volumen+**:
- **Ahorro de batería** → **Sin restricciones**.
- **Inicio automático** → activado.
- **Notificaciones** → permitidas.

## Paso 4 · Usarla
1. Sube el volumen normal al máximo (o toca el botón de la app).
2. Activa **Activar refuerzo** y mueve la barra (empieza en +6 dB).
3. Para apagarla: desde la app o con **Apagar** en la notificación.

**Si no notas cambio en alguna app** (algunas, como YouTube, no siempre permiten efectos): apaga y vuelve a encender el refuerzo con la app de video ya abierta. Si aun así no cambia, esa app bloquea los efectos de audio.

---

## Otros trucos de volumen sin app
- **Audífonos / parlante Bluetooth bajito:** Ajustes > Acerca del teléfono > toca 7 veces “Versión del SO” → Ajustes adicionales > **Opciones de desarrollador** > activa **Desactivar volumen absoluto**. Reinicia el Bluetooth.
- Limpia la rejilla del parlante con un cepillo suave seco (el polvo baja mucho el volumen).
- Quita la funda si tapa la salida del parlante.

---

## Cámara del Redmi 13C: cómo sacarle la máxima resolución
La cámara principal es de **50 MP** y graba video máximo en **1080p a 30 fps**. Ese es el límite físico del sensor: **ningún código o app puede subir la resolución por encima de eso**; las apps que “aumentan megapíxeles” solo agrandan la imagen sin agregar detalle. Lo que sí puedes hacer es usar todo lo que el equipo ya trae:

1. **Activar 50 MP:** abre Cámara → en la barra de modos desliza hasta **Más** → toca **50 MP** (en algunas versiones aparece como botón “50M” arriba en modo Foto). Por defecto el teléfono toma fotos más pequeñas para ahorrar espacio.
2. **Calidad de imagen alta:** Cámara → menú ☰ (arriba) → **Ajustes** → **Calidad de imagen** → **Alta**.
3. **Video:** en modo Video, elige **1080p** (es el máximo).
4. Para mejores fotos: limpia el lente, usa **HDR** en automático, buena luz, **modo Noche** en lugares oscuros y evita el zoom digital (mejor acércate).
5. Las fotos de 50 MP salen mejor con buena luz; de noche el modo normal suele verse mejor.

> Existen versiones modificadas de “Google Camera (GCam)”, pero para el procesador Helio G85 de este equipo suelen ser inestables y vienen de sitios no oficiales. No las recomiendo.

---

## Estructura del proyecto
```
VolumenPlus/
├── .github/workflows/compilar-apk.yml   ← compila el APK en GitHub
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/luis/volumenplus/
│       │   ├── MainActivity.java         ← pantalla con interruptor y barra
│       │   └── BoostService.java         ← servicio que aplica el refuerzo
│       └── res/                          ← ícono, nombre y colores
├── build.gradle, settings.gradle, gradle.properties
└── gradlew, gradlew.bat, gradle/          ← Gradle wrapper
```
