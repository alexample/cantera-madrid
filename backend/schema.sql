-- Esquema D1 (SQLite) para Cantera Madrid
DROP TABLE IF EXISTS goals;
DROP TABLE IF EXISTS matches;
DROP TABLE IF EXISTS players;
DROP TABLE IF EXISTS teams;
DROP TABLE IF EXISTS competitions;
DROP TABLE IF EXISTS fields;
DROP TABLE IF EXISTS clubs;
DROP TABLE IF EXISTS verified_users;

CREATE TABLE clubs (
  id TEXT PRIMARY KEY,            -- código de club en la RFFM
  name TEXT NOT NULL,
  town TEXT
);

CREATE TABLE fields (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  address TEXT,
  town TEXT,
  lat REAL, lng REAL
);

CREATE TABLE competitions (
  id TEXT PRIMARY KEY,            -- código de competición/grupo en la RFFM
  season TEXT NOT NULL,           -- '2026-27'
  category TEXT NOT NULL,         -- 'Infantil' | 'Cadete' | 'Juvenil'
  name TEXT NOT NULL              -- 'Cadete Primera · Grupo 5'
);

CREATE TABLE teams (
  id TEXT PRIMARY KEY,
  club_id TEXT NOT NULL REFERENCES clubs(id),
  competition_id TEXT NOT NULL REFERENCES competitions(id),
  name TEXT NOT NULL,             -- 'UD Getafe Sur Cadete A'
  home_field_id TEXT REFERENCES fields(id)
);
CREATE INDEX idx_teams_comp ON teams(competition_id);

CREATE TABLE matches (
  id TEXT PRIMARY KEY,
  competition_id TEXT NOT NULL REFERENCES competitions(id),
  round INTEGER NOT NULL,         -- jornada
  kickoff TEXT,                   -- ISO 8601, hora de Madrid
  home_id TEXT NOT NULL REFERENCES teams(id),
  away_id TEXT NOT NULL REFERENCES teams(id),
  field_id TEXT REFERENCES fields(id),
  home_goals INTEGER,             -- NULL si no se ha jugado
  away_goals INTEGER,
  status TEXT DEFAULT 'scheduled', -- scheduled | played | postponed
  updated_at TEXT
);
CREATE INDEX idx_matches_home ON matches(home_id, kickoff);
CREATE INDEX idx_matches_away ON matches(away_id, kickoff);
CREATE INDEX idx_matches_comp ON matches(competition_id, round);

-- Datos de menores: solo se exponen a cuentas verificadas
CREATE TABLE players (
  id TEXT PRIMARY KEY,
  team_id TEXT NOT NULL REFERENCES teams(id),
  full_name TEXT NOT NULL,
  name_search TEXT NOT NULL,      -- minúsculas y sin tildes, para buscar
  shirt INTEGER,
  position TEXT
);
CREATE INDEX idx_players_search ON players(name_search);

CREATE TABLE goals (
  match_id TEXT NOT NULL REFERENCES matches(id),
  player_id TEXT REFERENCES players(id),
  team_id TEXT NOT NULL REFERENCES teams(id),
  minute INTEGER
);

-- Familias / entrenadores verificados por el club (token de sesión ya validado)
CREATE TABLE verified_users (
  token_hash TEXT PRIMARY KEY,
  club_id TEXT REFERENCES clubs(id),
  role TEXT,                      -- 'familia' | 'entrenador' | 'club'
  expires_at TEXT
);
