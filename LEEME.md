# Olympus Scanlation (olympusxyz.com) para Mihon

Código fuente de la extensión. Ojo: **no está compilado ni probado**, se armó a partir de las
respuestas reales de la API de olympusxyz.com y de la estructura de Keiyoushi.

## Cómo sacar el APK (sin instalar nada en el PC)

1. En GitHub, haz **Fork** de `keiyoushi/extensions-source`.
2. Sube el contenido de este zip a la raíz de TU fork, respetando las carpetas
   (`src/es/olympusxyz/...` y `.github/workflows/...`).
3. En tu fork: pestaña **Actions** → **Build Olympus XYZ** → **Run workflow**.
4. Cuando termine (unos minutos), entra a la ejecución y baja el artefacto **olympusxyz-apk**.
5. Desinstala la extensión vieja de Olympus en Mihon, instala el APK y confía en la extensión
   cuando Mihon te lo pida.

## Si falla

- Error de Java: en el `.yml` cambia `java-version: 21` por `17` (o al revés).
- Error en `response.body`: cambia `it.body.string()` por `it.body!!.string()`.
- Error de plugin `kotlinx-serialization`: borra esa línea de `build.gradle`.
- Cualquier otro: copia las últimas ~30 líneas del log rojo y pásalas.

## Endpoints que usa (verificados)

- `/api/homepage` → popular (rankings) y últimos capítulos (new_chapters)
- `/api/series?page=N&direction=asc|desc&type=comic` → catálogo
- `/api/series/{slug}?type=comic` → detalle
- `/api/series/{slug}/chapters?page=N&direction=desc&type=comic` → capítulos
- `/api/capitulo/{slug}/{número}?type=comic` → imágenes

Pendiente de verificar: el parámetro de búsqueda por texto (`SEARCH_PARAM` en `OlympusXyz.kt`).
