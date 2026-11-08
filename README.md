# Cantera Madrid

App Android de fútbol base (infantil, cadete y juvenil) de la Comunidad de Madrid. Cada equipo que sigues tiene su ficha con un chat, y hay un chat principal para buscar jugadores y equipos.

> **Prototipo:** los clubes, campos, jugadores y resultados son inventados.

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

## Backend (siguiente fase)

En `backend/` están el Worker, el esquema de D1 y el sincronizador de datos de la RFFM, que está por completar.
