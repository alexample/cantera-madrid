# Cantera Madrid: backend en Cloudflare

Este Worker responde las preguntas de la app con un modelo libre (Llama 3.3 70B) en **Workers AI**. Los datos están guardados en **D1**.

```
App móvil ──► Worker /api/chat ──► Llama 3.3 (Workers AI)
                    │                 │ pide datos con "herramientas"
                    └──── D1 ◄────────┘ (equipos, partidos, campos, jugadores)
Cron cada hora ──► scraper RFFM ──► D1
```

## Despliegue

```bash
npm i -g wrangler
wrangler login
wrangler d1 create cantera-madrid          # copia el database_id en wrangler.toml
wrangler d1 execute cantera-madrid --remote --file=schema.sql
wrangler deploy
```

## API

- `GET /api/teams?q=getafe`: buscar equipos para seguirlos.
- `POST /api/chat` con `{ "message": "...", "teamId": "id-del-equipo" | null, "history": [...] }`
  - Si se envía `teamId`, es el chat de la ficha: solo puede consultar ese equipo.
  - Si no se envía, es el chat principal: busca equipos y clasificaciones, y también jugadores si el usuario está verificado.
  - Respuesta: `{ reply, tools }`. Los datos de `tools` sirven para que la app pinte tarjetas (marcador, mapa, tabla).
- Las cuentas verificadas se identifican con la cabecera `Authorization: Bearer <token>`. En `verified_users` se guarda el hash del token, nunca el token.

## Pendiente

1. **`src/scraper.js`**: falta implementar la lectura de la RFFM. Antes de hacerlo, revisa sus condiciones de uso o pide acceso oficial.
2. **Verificación**: falta el flujo para que el club dé de alta a familias y entrenadores (un código de invitación).
3. **Privacidad (RGPD/LOPDGDD)**: se trata de datos de menores. Hace falta una política de privacidad, un registro de tratamiento y la mínima exposición de nombres. Las respuestas ya recortan los nombres a nombre y primer apellido.
4. **App móvil**: el prototipo web se puede empaquetar con Capacitor para iOS y Android. También se puede reescribir en React Native o Expo y llamar a esta misma API.
