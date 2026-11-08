// Herramientas que el modelo puede usar. Cada una es una consulta SQL a D1.
// El modelo NUNCA inventa datos: solo redacta a partir de lo que devuelven estas funciones.

export const TEAM_TOOLS = [
  {
    name: "ultimo_resultado",
    description: "Último partido jugado por un equipo: rival, marcador, fecha, campo y goleadores.",
    parameters: { type: "object", properties: { team_id: { type: "string" } }, required: ["team_id"] },
  },
  {
    name: "proximo_partido",
    description: "Próximo partido de un equipo: rival, fecha, hora, campo y dirección.",
    parameters: { type: "object", properties: { team_id: { type: "string" } }, required: ["team_id"] },
  },
  {
    name: "clasificacion",
    description: "Clasificación completa del grupo en el que juega el equipo.",
    parameters: { type: "object", properties: { team_id: { type: "string" } }, required: ["team_id"] },
  },
  {
    name: "partidos_equipo",
    description: "Lista de partidos de un equipo. Opcionalmente filtrados por rival (texto) o por jornada.",
    parameters: {
      type: "object",
      properties: {
        team_id: { type: "string" },
        rival: { type: "string", description: "Nombre o parte del nombre del club rival" },
        jornada: { type: "integer" },
      },
      required: ["team_id"],
    },
  },
  {
    name: "goleadores_equipo",
    description: "Máximos goleadores de un equipo en la temporada.",
    parameters: { type: "object", properties: { team_id: { type: "string" } }, required: ["team_id"] },
  },
];

export const MAIN_TOOLS = [
  {
    name: "buscar_equipos",
    description: "Busca equipos por nombre de club, municipio y/o categoría (Infantil, Cadete, Juvenil).",
    parameters: {
      type: "object",
      properties: { texto: { type: "string" }, categoria: { type: "string" } },
      required: ["texto"],
    },
  },
  {
    name: "clasificacion_categoria",
    description: "Clasificaciones de los grupos de una categoría.",
    parameters: { type: "object", properties: { categoria: { type: "string" } }, required: ["categoria"] },
  },
];

export const PLAYER_TOOL = {
  name: "buscar_jugador",
  description: "Busca jugadores por nombre y apellidos. Devuelve nombre, dorsal, posición y equipo. Solo disponible para cuentas verificadas.",
  parameters: { type: "object", properties: { nombre: { type: "string" } }, required: ["nombre"] },
};

const norm = (s) => s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").trim();

export async function runTool(env, name, args, ctx) {
  const db = env.DB;
  // En una ficha, el modelo solo puede consultar SU equipo
  const teamId = ctx.teamId || args.team_id;

  switch (name) {
    case "ultimo_resultado": {
      const m = await db.prepare(
        `SELECT m.*, h.name home, a.name away, f.name field, f.address
           FROM matches m JOIN teams h ON h.id=m.home_id JOIN teams a ON a.id=m.away_id
           LEFT JOIN fields f ON f.id=m.field_id
          WHERE (m.home_id=?1 OR m.away_id=?1) AND m.status='played'
          ORDER BY m.kickoff DESC LIMIT 1`).bind(teamId).first();
      if (!m) return { info: "Sin partidos jugados" };
      const { results: goals } = await db.prepare(
        `SELECT g.minute, g.team_id, p.full_name FROM goals g LEFT JOIN players p ON p.id=g.player_id
          WHERE g.match_id=? ORDER BY g.minute`).bind(m.id).all();
      // Nombres de goleadores: solo nombre + primer apellido
      return { ...m, goleadores: goals.map((g) => ({ minuto: g.minute, equipo: g.team_id, jugador: shortName(g.full_name) })) };
    }
    case "proximo_partido":
      return (await db.prepare(
        `SELECT m.round jornada, m.kickoff, h.name home, a.name away, f.name campo, f.address direccion, f.lat, f.lng
           FROM matches m JOIN teams h ON h.id=m.home_id JOIN teams a ON a.id=m.away_id
           LEFT JOIN fields f ON f.id=m.field_id
          WHERE (m.home_id=?1 OR m.away_id=?1) AND m.status='scheduled' AND m.kickoff >= datetime('now')
          ORDER BY m.kickoff ASC LIMIT 1`).bind(teamId).first()) || { info: "No hay partidos programados" };

    case "clasificacion": {
      const t = await db.prepare(`SELECT competition_id FROM teams WHERE id=?`).bind(teamId).first();
      return t ? standings(db, t.competition_id) : { error: "Equipo no encontrado" };
    }
    case "partidos_equipo": {
      let sql = `SELECT m.round jornada, m.kickoff, h.name home, a.name away, m.home_goals, m.away_goals, m.status
                   FROM matches m JOIN teams h ON h.id=m.home_id JOIN teams a ON a.id=m.away_id
                  WHERE (m.home_id=?1 OR m.away_id=?1)`;
      const b = [teamId];
      if (args.jornada) { sql += ` AND m.round=?${b.length + 1}`; b.push(args.jornada); }
      if (args.rival) { sql += ` AND (h.name LIKE ?${b.length + 1} OR a.name LIKE ?${b.length + 1})`; b.push(`%${args.rival}%`); }
      sql += " ORDER BY m.kickoff";
      return (await db.prepare(sql).bind(...b).all()).results;
    }
    case "goleadores_equipo":
      return (await db.prepare(
        `SELECT p.full_name, p.shirt, COUNT(*) goles FROM goals g JOIN players p ON p.id=g.player_id
          WHERE g.team_id=? GROUP BY p.id ORDER BY goles DESC LIMIT 5`).bind(teamId).all())
        .results.map((r) => ({ jugador: shortName(r.full_name), dorsal: r.shirt, goles: r.goles }));

    case "buscar_equipos": {
      const q = `%${args.texto}%`;
      let sql = `SELECT t.id, t.name, c.town, k.name grupo, k.category FROM teams t
                   JOIN clubs c ON c.id=t.club_id JOIN competitions k ON k.id=t.competition_id
                  WHERE (t.name LIKE ?1 OR c.name LIKE ?1 OR c.town LIKE ?1)`;
      const b = [q];
      if (args.categoria) { sql += " AND k.category LIKE ?2"; b.push(`%${args.categoria}%`); }
      return (await db.prepare(sql + " LIMIT 10").bind(...b).all()).results;
    }
    case "clasificacion_categoria": {
      const { results: comps } = await db.prepare(
        `SELECT id, name FROM competitions WHERE category LIKE ? LIMIT 5`).bind(`%${args.categoria}%`).all();
      const out = [];
      for (const c of comps) out.push({ grupo: c.name, tabla: await standings(db, c.id) });
      return out;
    }
    case "buscar_jugador": {
      if (!ctx.verified) return { error: "Búsqueda de jugadores solo para cuentas verificadas" };
      const words = norm(args.nombre).split(/\s+/).filter((w) => w.length > 1).slice(0, 4);
      if (!words.length) return [];
      const where = words.map((_, i) => `p.name_search LIKE ?${i + 1}`).join(" AND ");
      return (await db.prepare(
        `SELECT p.full_name, p.shirt dorsal, p.position, t.id team_id, t.name equipo
           FROM players p JOIN teams t ON t.id=p.team_id WHERE ${where} LIMIT 10`)
        .bind(...words.map((w) => `%${w}%`)).all()).results;
    }
    default:
      return { error: `Herramienta desconocida: ${name}` };
  }
}

async function standings(db, competitionId) {
  const { results } = await db.prepare(
    `WITH r AS (
       SELECT home_id team, home_goals gf, away_goals gc FROM matches WHERE competition_id=?1 AND status='played'
       UNION ALL
       SELECT away_id, away_goals, home_goals FROM matches WHERE competition_id=?1 AND status='played')
     SELECT t.name equipo, COUNT(r.team) pj,
            SUM(r.gf>r.gc) g, SUM(r.gf=r.gc) e, SUM(r.gf<r.gc) p,
            SUM(r.gf) gf, SUM(r.gc) gc, SUM(r.gf)-SUM(r.gc) dg,
            SUM(CASE WHEN r.gf>r.gc THEN 3 WHEN r.gf=r.gc THEN 1 ELSE 0 END) pts
       FROM teams t LEFT JOIN r ON r.team=t.id
      WHERE t.competition_id=?1
      GROUP BY t.id ORDER BY pts DESC, dg DESC, gf DESC`).bind(competitionId).all();
  return results.map((x, i) => ({ pos: i + 1, ...x }));
}

function shortName(full) {
  if (!full) return null;
  const p = full.split(" ");
  return p.slice(0, 2).join(" ");
}
