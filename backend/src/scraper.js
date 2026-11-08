// Sincronización con la web de la RFFM.
//
// IMPORTANTE: la RFFM no publica una API abierta. Antes de activar esto:
//  1. Revisa sus condiciones de uso y, mejor aún, pide permiso por escrito (o acceso a un feed oficial).
//  2. Inspecciona en el navegador (pestaña Red) qué peticiones hace su web al mostrar
//     calendario, resultados y clasificación; muchas webs cargan JSON internamente,
//     que es más estable que leer el HTML.
//  3. Rellena las funciones fetchX de abajo con esas URLs y su formato.
//
// Este archivo deja preparada la parte de guardar en D1; la lectura de la RFFM está por completar.

const COMPETITIONS = [
  // Rellena con los códigos reales de los grupos que quieras seguir, p. ej.:
  // { id: "XXXX", season: "2026-27", category: "Cadete", name: "Cadete Primera · Grupo 5" },
];

export async function syncFromRFFM(env) {
  for (const comp of COMPETITIONS) {
    try {
      const data = await fetchCompetition(comp);
      await saveCompetition(env, comp, data);
    } catch (e) {
      console.error("Error sincronizando", comp.id, e);
    }
    await new Promise((r) => setTimeout(r, 1500)); // no saturar la web de la federación
  }
}

// TODO: implementar con las URLs reales de la RFFM.
// Debe devolver: { teams:[{id,name,club:{id,name,town},field:{id,name,address,town}}],
//                  matches:[{id,round,kickoff,home_id,away_id,field_id,home_goals,away_goals,status,goals:[{team_id,player_name,minute}]}] }
async function fetchCompetition(comp) {
  throw new Error(`fetchCompetition sin implementar para ${comp.id}`);
}

async function saveCompetition(env, comp, data) {
  const db = env.DB;
  const now = new Date().toISOString();
  const stmts = [
    db.prepare(`INSERT OR REPLACE INTO competitions (id, season, category, name) VALUES (?,?,?,?)`)
      .bind(comp.id, comp.season, comp.category, comp.name),
  ];
  for (const t of data.teams) {
    stmts.push(db.prepare(`INSERT OR REPLACE INTO clubs (id,name,town) VALUES (?,?,?)`).bind(t.club.id, t.club.name, t.club.town));
    if (t.field) stmts.push(db.prepare(`INSERT OR REPLACE INTO fields (id,name,address,town) VALUES (?,?,?,?)`)
      .bind(t.field.id, t.field.name, t.field.address, t.field.town));
    stmts.push(db.prepare(`INSERT OR REPLACE INTO teams (id,club_id,competition_id,name,home_field_id) VALUES (?,?,?,?,?)`)
      .bind(t.id, t.club.id, comp.id, t.name, t.field?.id ?? null));
  }
  for (const m of data.matches) {
    stmts.push(db.prepare(
      `INSERT OR REPLACE INTO matches (id,competition_id,round,kickoff,home_id,away_id,field_id,home_goals,away_goals,status,updated_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,?)`).bind(m.id, comp.id, m.round, m.kickoff, m.home_id, m.away_id, m.field_id ?? null,
        m.home_goals ?? null, m.away_goals ?? null, m.status, now));
    stmts.push(db.prepare(`DELETE FROM goals WHERE match_id=?`).bind(m.id));
    for (const g of m.goals || []) {
      stmts.push(db.prepare(
        `INSERT INTO goals (match_id, player_id, team_id, minute)
         VALUES (?, (SELECT id FROM players WHERE team_id=? AND full_name=? LIMIT 1), ?, ?)`)
        .bind(m.id, g.team_id, g.player_name, g.team_id, g.minute ?? null));
    }
  }
  // D1 permite lotes; se trocean para no superar límites
  for (let i = 0; i < stmts.length; i += 80) await db.batch(stmts.slice(i, i + 80));
}
