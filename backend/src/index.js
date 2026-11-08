import { TEAM_TOOLS, MAIN_TOOLS, PLAYER_TOOL, runTool } from "./tools.js";
import { syncFromRFFM } from "./scraper.js";

const json = (data, status = 200, env) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": env?.ALLOWED_ORIGIN || "*",
      "access-control-allow-headers": "content-type, authorization",
    },
  });

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return json({}, 204, env);

    // Listado de equipos para la pantalla "Seguir un equipo"
    if (url.pathname === "/api/teams" && req.method === "GET") {
      const q = `%${url.searchParams.get("q") || ""}%`;
      const { results } = await env.DB.prepare(
        `SELECT t.id, t.name, c.town, k.name grupo FROM teams t
           JOIN clubs c ON c.id=t.club_id JOIN competitions k ON k.id=t.competition_id
          WHERE t.name LIKE ?1 OR c.town LIKE ?1 ORDER BY t.name LIMIT 50`).bind(q).all();
      return json(results, 200, env);
    }

    // Chat: { message, teamId?, history? }  — teamId vacío = chat principal
    if (url.pathname === "/api/chat" && req.method === "POST") {
      const { message, teamId, history = [] } = await req.json();
      if (!message || message.length > 500) return json({ error: "Mensaje no válido" }, 400, env);
      const verified = await isVerified(req, env);
      const answer = await chat(env, { message, teamId, history: history.slice(-8), verified });
      return json(answer, 200, env);
    }

    return json({ error: "No encontrado" }, 404, env);
  },

  // Cron: refresca resultados, calendario y clasificaciones
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(syncFromRFFM(env));
  },
};

async function chat(env, { message, teamId, history, verified }) {
  let system, tools;
  if (teamId) {
    const team = await env.DB.prepare(
      `SELECT t.id, t.name, k.name grupo FROM teams t JOIN competitions k ON k.id=t.competition_id WHERE t.id=?`)
      .bind(teamId).first();
    if (!team) return { error: "Equipo no encontrado" };
    system = `Eres el asistente de la ficha del equipo "${team.name}" (${team.grupo}), id "${team.id}".
Responde en español, breve y claro, para padres y entrenadores de fútbol base.
Usa SIEMPRE las herramientas para cualquier dato (resultados, fechas, campos, clasificación). Nunca inventes datos.
Si te preguntan por otro equipo, explica que esta ficha es solo de ${team.name}.
Fecha de hoy: ${new Date().toISOString().slice(0, 10)}. Zona horaria: Europe/Madrid.`;
    tools = TEAM_TOOLS;
  } else {
    system = `Eres el buscador de Cantera Madrid (fútbol base infantil, cadete y juvenil de la Comunidad de Madrid).
Responde en español, breve. Usa SIEMPRE las herramientas; nunca inventes datos.
${verified
  ? "El usuario está verificado: puedes buscar jugadores, pero muestra solo nombre, dorsal, posición y equipo."
  : "El usuario NO está verificado: no busques ni des información de jugadores; explica que es necesario verificar la cuenta con su club."}`;
    tools = verified ? [...MAIN_TOOLS, PLAYER_TOOL] : MAIN_TOOLS;
  }

  const messages = [{ role: "system", content: system }, ...history, { role: "user", content: message }];
  const used = [];

  // Bucle de herramientas: el modelo pide datos, los damos, y redacta
  for (let step = 0; step < 4; step++) {
    const res = await env.AI.run(env.MODEL, { messages, tools, max_tokens: 600 });
    const calls = res.tool_calls || [];
    if (!calls.length) return { reply: res.response, tools: used };

    messages.push({ role: "assistant", content: "", tool_calls: calls });
    for (const call of calls) {
      const args = typeof call.arguments === "string" ? JSON.parse(call.arguments) : call.arguments || {};
      const data = await runTool(env, call.name, args, { teamId, verified });
      used.push({ name: call.name, data }); // la app puede pintar tarjetas con estos datos
      messages.push({ role: "tool", name: call.name, content: JSON.stringify(data).slice(0, 6000) });
    }
  }
  return { reply: "No he podido completar la consulta. Inténtalo de nuevo.", tools: used };
}

async function isVerified(req, env) {
  const auth = req.headers.get("authorization") || "";
  const token = auth.replace(/^Bearer\s+/i, "");
  if (!token) return false;
  const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)))]
    .map((b) => b.toString(16).padStart(2, "0")).join("");
  const row = await env.DB.prepare(
    `SELECT 1 FROM verified_users WHERE token_hash=? AND expires_at > datetime('now')`).bind(hash).first();
  return !!row;
}
