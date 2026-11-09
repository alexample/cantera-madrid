# Cantera Madrid

App Android de fútbol base (infantil, cadete y juvenil) de la Comunidad de Madrid. Cada equipo que sigues tiene su ficha con un chat, y hay un chat principal para buscar jugadores y equipos.

> Datos reales de la **Real Federación de Fútbol de Madrid** (rffm.es): fútbol 11 Infantil, Cadete y Juvenil, masculino y femenino. App no oficial.

## Instalar en el móvil

1. Abre **Releases** en este repositorio desde el móvil y descarga `CanteraMadrid.apk`.
2. Ábrela. Android pedirá permiso para instalar apps de esa fuente: acéptalo.
3. Las versiones nuevas se instalan encima de la anterior.

## Cómo funciona

- `app/src/main/assets/index.html` contiene la app entera, que se muestra dentro de un WebView.
- Las preguntas típicas (resultado, próximo partido, clasificación, goleadores…) se responden al momento en el propio móvil.
- Las demás preguntas se envían a la IA: Llama en Cloudflare Workers AI, a través del worker `dondeloechan-ia`, en la ruta `/cantera-ia`. Junto con la pregunta se mandan los datos de la ficha.
- La búsqueda de jugadores solo está disponible con una cuenta verificada, porque son menores.

## Compilar

Cada cambio en `app/` hace que GitHub Actions compile una APK firmada y la publique en Releases. También se puede lanzar a mano desde la pestaña Actions.

## Datos

- `robot/rffm.py` (workflow «Robot de datos RFFM») descarga calendario, resultados, clasificaciones, goleadores y campos con coordenadas. Se ejecuta cada hora los fines de semana y tres veces al día entre semana.
- Solo usa rutas permitidas por el robots.txt de la RFFM (`/api/*`, `/competicion/*`, `/campo/*`).
- Los datos se guardan en `data/`: `indice.json`, `g/<grupo>.json`, `goleadores.json` y `estado.json`. La app los descarga de GitHub al abrirse y los guarda para usarlos sin conexión.

## Backend (siguiente fase)

En `backend/` están el Worker, el esquema de D1 y el sincronizador de datos de la RFFM, que está por completar.
