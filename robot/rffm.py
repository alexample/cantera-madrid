#!/usr/bin/env python3
"""
Robot de datos de Cantera Madrid.

Descarga de la web pública de la Real Federación de Fútbol de Madrid (www.rffm.es)
los grupos de fútbol 11 y 7 Alevín, Infantil, Cadete, Juvenil y Senior de la temporada en curso:
calendario y resultados, clasificación, goleadores y campos (con coordenadas).

Solo usa rutas que robots.txt permite: /api/*, /competicion/* y /campo/*.
No toca /fichaequipo/, /fichajugador/ ni /acta-partido/, que están vetadas.

Salida (en data/):
  indice.json       equipos, grupos y campos (lo que la app necesita para buscar)
  g/<grupo>.json    calendario, clasificación y goleadores de cada grupo
  goleadores.json   jugadores con goles (solo para cuentas verificadas en la app)
  estado.json       cuándo se actualizó y cuántas cosas se han leído

Uso: python robot/rffm.py [--solo-categoria cadete] [--max-grupos N]
Solo librería estándar de Python.
"""
import argparse
import concurrent.futures as cf
import datetime as dt
import gzip
import json
import os
import re
import sys
import threading
import time
import unicodedata
import urllib.parse
import urllib.request
from collections import Counter, defaultdict

BASE = "https://www.rffm.es"
UA = "Mozilla/5.0 (compatible; CanteraMadridBot/1.0; +https://github.com/alexample/cantera-madrid)"
DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
# El orden importa: primero las de base (una liga «femenino cadete» es cadete), al final las senior.
CATEGORIAS = [("JUVENIL", "juv", "Juvenil"), ("CADETE", "cad", "Cadete"), ("INFANTIL", "inf", "Infantil"),
              ("ALEV", "ale", "Alevín"),
              ("AFICIONADO", "sen", "Senior"), ("TERCERA FEDERACION", "sen", "Senior"), ("SENIOR", "sen", "Senior"),
              ("FEMENINO", "sen", "Senior")]
EXCLUIR = ("SALA", "PLAYA", "UNIVERSITARI", "VETERAN", "BENJAMIN", "ANDANDO", "INCLUSIV", "DEBUTANTE")

_lock = threading.Lock()
_ultimo = [0.0]
PAUSA = 0.35  # segundos mínimos entre peticiones (todas las hebras juntas)
stats = Counter()
errores = []


def norm(s):
    s = unicodedata.normalize("NFD", str(s or "")).encode("ascii", "ignore").decode()
    return re.sub(r"\s+", " ", s.upper().replace("-", " ")).strip()


def pedir(ruta, params=None, intentos=4):
    url = BASE + ruta + ("?" + urllib.parse.urlencode(params) if params else "")
    for i in range(intentos):
        with _lock:
            espera = _ultimo[0] + PAUSA - time.time()
            if espera > 0:
                time.sleep(espera)
            _ultimo[0] = time.time()
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": UA, "Accept": "text/html,application/json;q=0.9,*/*;q=0.8",
                "Accept-Language": "es-ES,es;q=0.9", "Accept-Encoding": "gzip"})
            with urllib.request.urlopen(req, timeout=30) as r:
                cuerpo = r.read()
                if r.headers.get("Content-Encoding") == "gzip":
                    cuerpo = gzip.decompress(cuerpo)
                stats["peticiones"] += 1
                return cuerpo.decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429:
                stats["errores_http"] += 1
                raise
        except Exception:
            pass
        time.sleep(2 * (i + 1))
    stats["fallos"] += 1
    raise RuntimeError("Sin respuesta: " + url)


def api(ruta, params=None):
    return json.loads(pedir(ruta, params))


def page_props(ruta, params=None):
    html = pedir(ruta, params)
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
    if not m:
        raise RuntimeError("Página sin __NEXT_DATA__: " + ruta)
    return json.loads(m.group(1)).get("props", {}).get("pageProps") or {}


def entero(x):
    try:
        return int(str(x).strip())
    except (TypeError, ValueError):
        return None


def limpiar_nombre(n):
    return re.sub(r"\s+", " ", str(n or "").replace("'", "").replace('"', "")).strip()


def club_de(nombre):
    # "A.D. ALCORCON 'B'" -> "A.D. ALCORCON"
    n = re.sub(r"""\s*['"]\s*[A-Z0-9]{1,3}\s*['"]\s*$""", "", str(nombre or "")).strip()
    return re.sub(r"\s+[A-Z]{1,2}$", "", n).strip()


def fecha_iso(f):
    m = re.match(r"(\d{1,2})[-/](\d{1,2})[-/](\d{4})", str(f or "").strip())
    return f"{m.group(3)}-{int(m.group(2)):02d}-{int(m.group(1)):02d}" if m else ""


def jornada_num(j):
    m = re.match(r"\s*(\d+)", str(j or ""))
    return int(m.group(1)) if m else None


# ---------------------------------------------------------------- transformación
def deducir_resultados(partidos, clas):
    """La RFFM actualiza la clasificación en cuanto se mete el resultado, pero el calendario no enseña el marcador
    hasta que se cierra el acta (a veces días después). Si a un equipo le falta exactamente un partido en el
    calendario, la diferencia de goles a favor/en contra entre la clasificación y lo ya publicado es el marcador
    de ese partido. Solo se usa si cuadra para los dos equipos (goles y G/E/P). Se marca como provisional (p[12]=1)."""
    hoy = dt.datetime.now(dt.timezone(dt.timedelta(hours=1))).date().isoformat()
    suma = {}
    for p in partidos:
        if p[7] is None or p[8] is None:
            continue
        for tid, f, c in ((p[3], p[7], p[8]), (p[5], p[8], p[7])):
            s_ = suma.setdefault(tid, [0, 0, 0, 0, 0, 0])  # pj, gf, gc, g, e, p
            s_[0] += 1; s_[1] += f; s_[2] += c
            s_[3 if f > c else 4 if f == c else 5] += 1
    fila = {r[1]: r for r in clas if r[1]}
    pendientes = {}
    for p in partidos:
        if (p[7] is None or p[8] is None) and p[1] and p[1] <= hoy and p[3] and p[5]:
            for tid in (p[3], p[5]):
                pendientes.setdefault(tid, []).append(p)
    def delta(tid):
        r = fila.get(tid)
        if not r or None in (r[3], r[7], r[8], r[4], r[5], r[6]):
            return None
        s_ = suma.get(tid, [0] * 6)
        return [r[3] - s_[0], r[7] - s_[1], r[8] - s_[2], r[4] - s_[3], r[5] - s_[4], r[6] - s_[5]]
    for p in partidos:
        if p[7] is not None or not p[1] or p[1] > hoy or not p[3] or not p[5]:
            continue
        dl, dv = delta(p[3]), delta(p[5])
        if not dl or not dv or dl[0] != 1 or dv[0] != 1:
            continue
        if len(pendientes.get(p[3], [])) != 1 or len(pendientes.get(p[5], [])) != 1:
            continue
        gl, gv = dl[1], dv[1]
        if gl < 0 or gv < 0 or dl[2] != gv or dv[2] != gl:
            continue
        res_l = [1, 0, 0] if gl > gv else [0, 1, 0] if gl == gv else [0, 0, 1]
        res_v = [res_l[2], res_l[1], res_l[0]]
        if dl[3:] != res_l or dv[3:] != res_v:
            continue
        p[7], p[8] = gl, gv
        while len(p) < 13:
            p.append("")
        p[12] = 1


def transformar_grupo(meta, calendar, standings, scorers):
    """pageProps (calendar, standings, scorers) -> archivo compacto del grupo."""
    partidos = []
    escudos = {}
    for ronda in (calendar or {}).get("rounds", []) or []:
        j = jornada_num(ronda.get("jornada"))
        for p in ronda.get("equipos", []) or []:
            for lado in ("local", "visitante"):
                e = (p.get("escudo_equipo_" + lado) or "").strip()
                tid = str(p.get("codigo_equipo_" + lado) or "")
                if e and tid:
                    escudos[tid] = e
            gl, gv = entero(p.get("goles_casa")), entero(p.get("goles_visitante"))
            partidos.append([
                j, fecha_iso(p.get("fecha")), (p.get("hora") or "").strip(),
                str(p.get("codigo_equipo_local") or ""), limpiar_nombre(p.get("equipo_local")),
                str(p.get("codigo_equipo_visitante") or ""), limpiar_nombre(p.get("equipo_visitante")),
                gl, gv, str(p.get("codigo_campo") or ""), (p.get("campo") or "").strip(),
                str(p.get("codacta") or ""),  # código del acta (para enlazar a la web de la RFFM)
            ])
    clas = []
    # Zonas de la clasificación (ascenso, promoción, descenso…) tal como las colorea la RFFM
    zonas = [[str(z.get("nombre_promocion") or "").strip().capitalize(), str(z.get("color_promocion") or "").upper()]
             for z in sorted((standings or {}).get("promociones") or [], key=lambda z: entero(z.get("orden")) or 0)]
    color_zona = {c: i for i, (_, c) in enumerate(zonas)}
    for e in (standings or {}).get("clasificacion", []) or []:
        clas.append([entero(e.get("posicion")), str(e.get("codequipo") or ""), limpiar_nombre(e.get("nombre")),
                     entero(e.get("jugados")), entero(e.get("ganados")), entero(e.get("empatados")),
                     entero(e.get("perdidos")), entero(e.get("goles_a_favor")), entero(e.get("goles_en_contra")),
                     entero(e.get("puntos")), color_zona.get(str(e.get("color") or "").upper(), -1),
                     entero(e.get("puntos_sancion")) or 0])
    clas.sort(key=lambda r: (r[0] is None, r[0] or 0))
    deducir_resultados(partidos, clas)
    gol = []
    for e in (scorers or {}).get("goles", []) or []:
        g = entero(e.get("goles"))
        if g:
            gol.append([limpiar_nombre(e.get("jugador")).title(), str(e.get("codigo_equipo") or ""), g])
    gol.sort(key=lambda r: -r[2])
    return {**meta, "clas": clas, "zonas": zonas, "part": partidos, "gol": gol, "esc": escudos}


# ---------------------------------------------------------------- recorrido
def temporada_actual(temporadas):
    hoy = dt.date.today()
    ini = hoy.year if hoy.month >= 7 else hoy.year - 1
    etiqueta = f"{ini}-{ini + 1}"
    for t in temporadas:
        if t.get("nombre") == etiqueta:
            return str(t["cod_temporada"]), etiqueta
    t = max(temporadas, key=lambda t: entero(t.get("cod_temporada")) or 0)
    return str(t["cod_temporada"]), t.get("nombre")


def categoria(c):
    texto = norm(c.get("NombreCategoria")) + " | " + norm(c.get("nombre"))
    if any(x in texto for x in EXCLUIR):
        return None
    for token, cid, nombre in CATEGORIAS:
        if token in texto:
            return cid, nombre
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--solo-categoria")
    ap.add_argument("--max-grupos", type=int, default=0)
    ap.add_argument("--hebras", type=int, default=3)
    ap.add_argument("--rapido", action="store_true", help="solo grupos con resultados pendientes de hoy o ayer")
    a = ap.parse_args()
    if a.rapido:
        return rapido(a)

    os.makedirs(os.path.join(DATA, "g"), exist_ok=True)
    t0 = time.time()
    temporada, etiqueta = temporada_actual(api("/api/seasons"))
    tipos = api("/api/game-types")
    # Fútbol 11 y fútbol 7 (hay ligas femeninas Infantil/Cadete/Juvenil de F7)
    f11 = [t for t in tipos if re.search(r"\b(11|7)\b", str(t.get("nombre")).replace("-", " "))] or tipos[:1]
    print("Temporada", etiqueta, temporada, "· tipos", [t.get("nombre") for t in f11], flush=True)

    grupos = []
    todas = []
    for t in tipos:
        tj = str(t["codigo_tipo_juego"])
        lista = api("/api/competitions", {"temporada": temporada, "tipojuego": tj}) or []
        es_f11 = t in f11
        for c in lista:
            cat = categoria(c) if es_f11 else None
            todas.append({"tipo": t.get("nombre"), "codigo": str(c.get("codigo")), "nombre": c.get("nombre"),
                          "categoria": c.get("NombreCategoria"), "incluida": bool(cat)})
        if not es_f11:
            continue
        for c in lista:
            cat = categoria(c)
            if not cat or (a.solo_categoria and cat[0] != a.solo_categoria[:3]):
                continue
            fem = "FEMENIN" in norm(c.get("NombreCategoria")) + norm(c.get("nombre"))
            for g in api("/api/groups", {"competicion": c["codigo"]}) or []:
                grupos.append({
                    "id": str(g["codigo"]), "comp": str(c["codigo"]), "tj": tj, "temp": temporada,
                    "cat": cat[0], "competicion": (c.get("nombre") or "").strip(),
                    "grupo": (g.get("nombre") or "").strip(), "fem": fem,
                    "_gol": str(g.get("clasificacion_goleadores")) == "1",
                })
    if a.max_grupos:
        grupos = grupos[: a.max_grupos]
    json.dump(todas, open(os.path.join(DATA, "competiciones.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(len(grupos), "grupos", flush=True)

    def leer(g):
        q = {"temporada": g["temp"], "competicion": g["comp"], "grupo": g["id"], "tipojuego": g["tj"]}
        cal = page_props("/competicion/calendario", {**q, "jornada": 1}).get("calendar")
        try:
            clas = page_props("/competicion/clasificaciones", q).get("standings")
        except Exception as e:
            clas = None
            errores.append(f"clasificación {g['id']}: {e}")
        gol = None
        if g["_gol"]:
            try:
                gol = page_props("/competicion/goleadores", q).get("scorers")
            except Exception as e:
                errores.append(f"goleadores {g['id']}: {e}")
        meta = {k: g[k] for k in ("id", "comp", "tj", "temp", "cat", "competicion", "grupo", "fem")}
        return transformar_grupo(meta, cal, clas, gol)

    hechos = []
    with cf.ThreadPoolExecutor(a.hebras) as ex:
        futuros = {ex.submit(leer, g): g for g in grupos}
        for i, f in enumerate(cf.as_completed(futuros), 1):
            g = futuros[f]
            try:
                hechos.append(f.result())
            except Exception as e:
                errores.append(f"grupo {g['id']} ({g['competicion']} {g['grupo']}): {e}")
                previo = os.path.join(DATA, "g", g["id"] + ".json")
                if os.path.exists(previo):  # se mantiene la última versión buena
                    viejo = json.load(open(previo))
                    viejo["_viejo"] = True
                    hechos.append(viejo)
            if i % 25 == 0:
                print(f"  {i}/{len(grupos)} grupos", flush=True)

    # Campos: se guardan en caché entre ejecuciones (cambian poco)
    ruta_campos = os.path.join(DATA, "campos.json")
    campos = json.load(open(ruta_campos)) if os.path.exists(ruta_campos) else {}
    usados = {p[9] for g in hechos for p in g["part"] if p[9]}
    nuevos = [c for c in usados if c not in campos]
    print(len(usados), "campos,", len(nuevos), "nuevos", flush=True)

    def leer_campo(cid):
        f = page_props(f"/campo/{cid}").get("field") or {}
        lat, lon = f.get("latitud"), f.get("longitud")
        try:
            lat, lon = round(float(lat), 6), round(float(lon), 6)
        except (TypeError, ValueError):
            lat = lon = None
        return cid, [(f.get("nombre_campo") or "").strip(), (f.get("direccion") or "").strip(),
                     (f.get("localidad") or "").strip().title(), lat, lon]

    with cf.ThreadPoolExecutor(a.hebras) as ex:
        for f in cf.as_completed([ex.submit(leer_campo, c) for c in nuevos]):
            try:
                cid, v = f.result()
                campos[cid] = v
            except Exception as e:
                errores.append(f"campo: {e}")

    # Índice de equipos
    equipos = {}
    casa = defaultdict(Counter)
    for g in hechos:
        for p in g["part"]:
            if p[3] and p[9]:
                casa[p[3]][p[9]] += 1
        nombres = {r[1]: r[2] for r in g["clas"]}
        for p in g["part"]:
            nombres.setdefault(p[3], p[4]); nombres.setdefault(p[5], p[6])
        for tid, nom in nombres.items():
            if not tid or not nom or "NO ASIGNADO" in nom.upper() or nom.upper().startswith("DESCANSA"):
                continue
            e = equipos.setdefault(tid, {"id": tid, "n": nom, "c": club_de(nom), "g": []})
            if g["id"] not in e["g"]:
                e["g"].append(g["id"])
    escudos = {}
    for g in hechos:
        escudos.update(g.get("esc") or {})
    for tid, e in equipos.items():
        if casa[tid]:
            e["campo"] = casa[tid].most_common(1)[0][0]

    ahora = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    for g in hechos:
        if g.pop("_viejo", False):
            continue
        g["act"] = ahora
        with open(os.path.join(DATA, "g", g["id"] + ".json"), "w", encoding="utf-8") as fh:
            json.dump(g, fh, ensure_ascii=False, separators=(",", ":"))

    indice = {
        "temporada": etiqueta, "act": ahora,
        "grupos": {g["id"]: [g["cat"], g["competicion"], g["grupo"], g["fem"], g["comp"], g["tj"], g["temp"]] for g in hechos},
        "equipos": [[e["id"], e["n"], e["c"], e["g"], e.get("campo", ""), escudos.get(e["id"], "")] for e in equipos.values()],
        "campos": {k: v for k, v in campos.items() if k in usados},
    }
    json.dump(indice, open(os.path.join(DATA, "indice.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    json.dump(campos, open(ruta_campos, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    goleadores = [[n, t, c, g["id"]] for g in hechos for (n, t, c) in g["gol"]]
    json.dump(goleadores, open(os.path.join(DATA, "goleadores.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    estado = {"act": ahora, "temporada": etiqueta, "grupos": len(hechos), "grupos_previstos": len(grupos),
              "equipos": len(equipos), "campos": len(indice["campos"]), "segundos": round(time.time() - t0),
              **stats, "errores": errores[:50]}
    json.dump(estado, open(os.path.join(DATA, "estado.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps({k: v for k, v in estado.items() if k != "errores"}, ensure_ascii=False))
    print("Errores:", len(errores))
    for e in errores[:20]:
        print(" -", e)
    if not hechos:
        sys.exit(1)


def agenda():
    """data/agenda.json: partidos de ayer a +7 días agrupados por campo, para «¿Qué se juega aquí?» en la app.
    {campoId: [[fechaISO, hora, grupoId, localId, local, visitId, visitante, gl, gv], ...]}"""
    import glob
    hoy = dt.datetime.now(dt.timezone(dt.timedelta(hours=1))).date()
    desde, hasta = (hoy - dt.timedelta(days=1)).isoformat(), (hoy + dt.timedelta(days=7)).isoformat()
    por_campo = {}
    for f in glob.glob(os.path.join(DATA, "g", "*.json")):
        try:
            g = json.load(open(f, encoding="utf-8"))
        except Exception:
            continue
        for p in g.get("part", []):
            if len(p) < 11 or not p[9] or not p[1] or not (desde <= p[1] <= hasta):
                continue
            por_campo.setdefault(str(p[9]), []).append([p[1], p[2] or "", g["id"], p[3], p[4], p[5], p[6], p[7], p[8]])
    for v in por_campo.values():
        v.sort(key=lambda x: (x[0], x[1] or "99"))
    json.dump({"act": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), "campos": por_campo},
              open(os.path.join(DATA, "agenda.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print("agenda:", len(por_campo), "campos,", sum(len(v) for v in por_campo.values()), "partidos")


def rapido(a):
    """Actualiza solo los grupos con partidos hoy o ayer. Reutiliza el índice existente."""
    t0 = time.time()
    ruta_ix = os.path.join(DATA, "indice.json")
    ix = json.load(open(ruta_ix, encoding="utf-8"))
    ahora_m = dt.datetime.now(dt.timezone(dt.timedelta(hours=1))).replace(tzinfo=None)  # hora de Madrid (invierno)
    hoy = ahora_m.date()

    def pendiente(p):
        # Partido sin resultado que ya debería haber terminado (empezó hace más de 75 min, hoy o ayer)
        provisional = len(p) > 12 and p[12] == 1  # deducido de la clasificación: se sigue mirando hasta que salga el oficial
        if (p[7] is not None and not provisional) or not p[1]:
            return False
        try:
            ini = dt.datetime.fromisoformat(p[1] + "T" + (p[2] or "00:00"))
        except ValueError:
            return False
        return ini.date() >= hoy - dt.timedelta(days=1) and ini + dt.timedelta(minutes=75) <= ahora_m

    elegidos = []
    for gid, g in ix["grupos"].items():
        ruta = os.path.join(DATA, "g", gid + ".json")
        if not os.path.exists(ruta):
            continue
        d = json.load(open(ruta, encoding="utf-8"))
        if any(pendiente(p) for p in d["part"]):
            elegidos.append(d)
    print(len(elegidos), "grupos con resultados pendientes", flush=True)
    ahora = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")

    def leer(d):
        q = {"temporada": d["temp"], "competicion": d["comp"], "grupo": d["id"], "tipojuego": d["tj"]}
        cal = page_props("/competicion/calendario", {**q, "jornada": 1}).get("calendar")
        clas = page_props("/competicion/clasificaciones", q).get("standings")
        gol = None
        if d.get("gol"):
            try:
                gol = page_props("/competicion/goleadores", q).get("scorers")
            except Exception as e:
                errores.append(f"goleadores {d['id']}: {e}")
        meta = {k: d[k] for k in ("id", "comp", "tj", "temp", "cat", "competicion", "grupo", "fem")}
        nuevo = transformar_grupo(meta, cal, clas, gol)
        if not nuevo["part"]:
            raise RuntimeError("calendario vacío")
        return nuevo

    cambios = 0
    with cf.ThreadPoolExecutor(a.hebras) as ex:
        futuros = {ex.submit(leer, d): d for d in elegidos}
        for f in cf.as_completed(futuros):
            d = futuros[f]
            try:
                n = f.result()
            except Exception as e:
                errores.append(f"grupo {d['id']}: {e}")
                continue
            if not n.get("esc"):
                n["esc"] = d.get("esc", {})
            if (n["part"], n["clas"], n["gol"], n.get("zonas")) != (d["part"], d["clas"], d["gol"], d.get("zonas")):
                n["act"] = ahora
                json.dump(n, open(os.path.join(DATA, "g", d["id"] + ".json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
                cambios += 1
    if cambios:
        ix["act"] = ahora
        json.dump(ix, open(ruta_ix, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(json.dumps({"grupos": len(elegidos), "cambios": cambios, "segundos": round(time.time() - t0), **stats}))
    for e in errores[:20]:
        print(" -", e)


if __name__ == "__main__":
    try:
        main()
        agenda()
    except Exception as e:  # deja constancia del fallo en data/estado.json
        os.makedirs(DATA, exist_ok=True)
        json.dump({"act": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), "fallo": repr(e)[:500],
                   **stats, "errores": errores[:50]}, open(os.path.join(DATA, "estado.json"), "w"), ensure_ascii=False, indent=1)
        raise
