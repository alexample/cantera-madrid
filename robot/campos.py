#!/usr/bin/env python3
"""
Verifica la ubicación de los campos y calcula el punto al que debe llevar el GPS.

Para cada campo:
  1. Compara las coordenadas de la RFFM con la dirección postal (geocodificada con OpenStreetMap/Nominatim).
     Si están a más de 1,5 km o faltan, se usa la dirección (y se marca como corregido).
  2. Busca en OpenStreetMap (Overpass) la instalación deportiva real alrededor de ese punto.
  3. Busca las entradas o puertas de esa instalación y elige la principal.
     El punto de navegación es la entrada; si no hay ninguna en el mapa, el centro de la instalación;
     si tampoco hay instalación, el punto de la RFFM.

Escribe en data/campos.json (y en el índice) tres valores más por campo: [.., lat_nav, lon_nav, tipo]
y un informe en data/campos_revision.json.

Uso: python robot/campos.py [--todos]   (sin --todos solo revisa los campos que aún no tienen punto de navegación)
"""
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
UA = "CanteraMadridBot/1.0 (+https://github.com/alexample/cantera-madrid)"
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]
MADRID_VIEWBOX = "-4.6,41.2,-3.0,39.8"


def km(a, b, c, d):
    r = 6371
    t = math.radians
    h = math.sin(t(c - a) / 2) ** 2 + math.cos(t(a)) * math.cos(t(c)) * math.sin(t(d - b) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def get(url, data=None, intentos=3):
    for i in range(intentos):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept-Language": "es"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            err = e
            time.sleep(3 * (i + 1))
    raise err


def limpiar_dir(d):
    d = re.sub(r"\bS/?N\b|\bSIN N[UÚ]MERO\b", "", d or "", flags=re.I)
    d = re.sub(r"\bAVDA?\.?\s", "Avenida ", d, flags=re.I)
    d = re.sub(r"\bC/\s*|\bCL\.?\s", "Calle ", d, flags=re.I)
    d = re.sub(r"\bPZA?\.?\s", "Plaza ", d, flags=re.I)
    d = re.sub(r"\bCTRA\.?\s", "Carretera ", d, flags=re.I)
    d = re.sub(r"\bPº\s*|\bPS\.?\s", "Paseo ", d, flags=re.I)
    return re.sub(r"\s+", " ", d).strip(" ,.-")


def localidad(l):
    # «Escorial (El)» -> «El Escorial»
    m = re.match(r"(.+?)\s*\((El|La|Los|Las)\)$", l or "", flags=re.I)
    return f"{m.group(2)} {m.group(1)}" if m else (l or "")


def geocodificar(direccion, loc):
    q = ", ".join(x for x in [limpiar_dir(direccion), localidad(loc), "Comunidad de Madrid", "España"] if x)
    url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode(
        {"q": q, "format": "jsonv2", "limit": 1, "countrycodes": "es", "viewbox": MADRID_VIEWBOX, "bounded": 1})
    time.sleep(1.1)  # política de uso de Nominatim: 1 petición por segundo
    r = get(url)
    return (float(r[0]["lat"]), float(r[0]["lon"]), r[0].get("display_name", "")) if r else None


_OSM = None
_REJILLA = {}


def _celda(la, lo):
    return (int(la * 100), int(lo * 100))


def cargar_osm():
    """Una sola consulta: instalaciones deportivas y entradas/puertas de toda la Comunidad de Madrid."""
    global _OSM
    if _OSM is not None:
        return
    q = """[out:json][timeout:300][bbox:39.85,-4.6,41.17,-3.05];
(way[leisure~"^(sports_centre|stadium)$"];relation[leisure~"^(sports_centre|stadium)$"];way[leisure=pitch][sport~"soccer"];);
out tags center bb;
(node[entrance];node[barrier~"^(gate|entrance|lift_gate|turnstile)$"];);
out;"""
    data = urllib.parse.urlencode({"data": q}).encode()
    for url in OVERPASS:
        try:
            _OSM = get(url, data=data, intentos=3).get("elements", [])
            break
        except Exception as e:
            print("Overpass falló en", url, e, flush=True)
    _OSM = _OSM or []
    for e in _OSM:
        la = e.get("lat", e.get("center", {}).get("lat"))
        lo = e.get("lon", e.get("center", {}).get("lon"))
        if la is None:
            continue
        _REJILLA.setdefault(_celda(la, lo), []).append(e)
    print(len(_OSM), "elementos de OpenStreetMap", flush=True)


def overpass(lat, lon, radio=400):
    """Elementos (instalaciones y entradas) alrededor del punto, a partir de la descarga única."""
    cargar_osm()
    a, b = _celda(lat, lon)
    out = []
    for da in (-1, 0, 1):
        for db in (-1, 0, 1):
            for e in _REJILLA.get((a + da, b + db), []):
                la = e.get("lat", e.get("center", {}).get("lat"))
                lo = e.get("lon", e.get("center", {}).get("lon"))
                lim = radio + (60 if e["type"] == "node" else 0)
                if km(lat, lon, la, lo) * 1000 <= lim:
                    out.append(e)
    return out


def elegir(lat, lon, elems):
    """Devuelve (instalación, entrada) más plausibles alrededor del punto."""
    inst = [e for e in elems if e["type"] in ("way", "relation") and "center" in e]
    nodos = [e for e in elems if e["type"] == "node"]

    def contiene(e, la, lo, margen=0.0):
        b = e.get("bounds")
        return b and b["minlat"] - margen <= la <= b["maxlat"] + margen and b["minlon"] - margen <= lo <= b["maxlon"] + margen

    def puntuacion(e):
        t = e.get("tags", {})
        tipo = t.get("leisure")
        d = km(lat, lon, e["center"]["lat"], e["center"]["lon"])
        bonus = 0
        if tipo in ("sports_centre", "stadium"):
            bonus -= 0.15
        if "soccer" in t.get("sport", "") or "futbol" in t.get("name", "").lower() or "fútbol" in t.get("name", "").lower():
            bonus -= 0.1
        if contiene(e, lat, lon):
            bonus -= 0.3
        return d + bonus

    f = min(inst, key=puntuacion) if inst else None
    entrada = None
    if f:
        cand = [n for n in nodos if contiene(f, n["lat"], n["lon"], 0.0004)
                and n.get("tags", {}).get("access") not in ("private", "no")]

        def prio(n):
            t = n.get("tags", {})
            p = {"main": 0, "yes": 1, "entrance": 1, "public": 1}.get(t.get("entrance"), 2 if "entrance" in t else 3)
            if t.get("barrier") in ("gate", "entrance"):
                p = min(p, 2)
            return (p, km(f["center"]["lat"], f["center"]["lon"], n["lat"], n["lon"]))

        if cand:
            entrada = min(cand, key=prio)
    return f, entrada


def guardar(campos, rev):
    json.dump(campos, open(os.path.join(DATA, "campos.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    ruta_ix = os.path.join(DATA, "indice.json")
    if os.path.exists(ruta_ix):
        ix = json.load(open(ruta_ix, encoding="utf-8"))
        for k in ix.get("campos", {}):
            if k in campos:
                ix["campos"][k] = campos[k]
        json.dump(ix, open(ruta_ix, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    json.dump(rev, open(os.path.join(DATA, "campos_revision.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)


def main():
    todos = "--todos" in sys.argv
    limite = time.time() + 50 * 60  # se para a los 45 min y guarda; la siguiente pasada sigue donde lo dejó
    ruta = os.path.join(DATA, "campos.json")
    campos = json.load(open(ruta, encoding="utf-8"))
    ruta_rev = os.path.join(DATA, "campos_revision.json")
    rev = json.load(open(ruta_rev, encoding="utf-8")) if os.path.exists(ruta_rev) else {}
    pend = [k for k, v in campos.items() if todos or len(v) < 8]
    print(len(pend), "campos por revisar de", len(campos), flush=True)
    for i, cid in enumerate(pend, 1):
        if time.time() > limite:
            print("Tiempo agotado: se guardan", i - 1, "campos; el resto en la siguiente pasada", flush=True)
            break
        v = campos[cid]
        nombre, direccion, loc, la, lo = v[0], v[1], v[2], v[3], v[4]
        info = {"nombre": nombre, "localidad": loc, "rffm": [la, lo] if la is not None else None}
        if "PENDIENTE" in (nombre or "").upper():
            info["aviso"] = "Campo pendiente de asignar"
            campos[cid] = v[:5] + [None, None, "sin_campo"]
            rev[cid] = info
            continue
        try:
            geo = geocodificar(direccion, loc) if direccion or loc else None
        except Exception:
            geo = None
        base, corregido = (la, lo), False
        if geo:
            info["direccion_gps"] = [round(geo[0], 6), round(geo[1], 6)]
            if la is None:
                base, corregido = (geo[0], geo[1]), True
                info["aviso"] = "La RFFM no da coordenadas: se usa la dirección"
            else:
                d = km(la, lo, geo[0], geo[1])
                info["dist_rffm_direccion_km"] = round(d, 2)
                if d > 1.5:
                    # ¿Hay una instalación deportiva junto a la dirección? Entonces la RFFM está mal.
                    el = overpass(geo[0], geo[1], 350)
                    f, _ = elegir(geo[0], geo[1], el)
                    if f:
                        base, corregido = (geo[0], geo[1]), True
                        info["aviso"] = f"Coordenadas de la RFFM a {d:.1f} km de la dirección: corregido"
                    else:
                        info["aviso"] = f"Coordenadas de la RFFM a {d:.1f} km de la dirección (sin confirmar)"
        if base[0] is None:
            campos[cid] = v[:5] + [None, None, "sin_ubicacion"]
            info["aviso"] = "Sin coordenadas ni dirección localizable"
            rev[cid] = info
            continue
        el = overpass(base[0], base[1])
        f, entrada = elegir(base[0], base[1], el)
        if entrada:
            nav, tipo = (entrada["lat"], entrada["lon"]), "entrada"
            info["entrada"] = entrada.get("tags", {})
        elif f:
            nav, tipo = (f["center"]["lat"], f["center"]["lon"]), "instalacion"
        else:
            nav, tipo = base, "rffm" if not corregido else "direccion"
        if f:
            info["instalacion"] = f.get("tags", {}).get("name") or f.get("tags", {}).get("leisure")
            info["dist_instalacion_km"] = round(km(base[0], base[1], f["center"]["lat"], f["center"]["lon"]), 2)
        nueva = list(v[:5])
        if corregido:
            nueva[3], nueva[4] = round(base[0], 6), round(base[1], 6)
        campos[cid] = nueva + [round(nav[0], 6), round(nav[1], 6), tipo]
        info.update({"navegacion": [round(nav[0], 6), round(nav[1], 6)], "tipo": tipo, "corregido": corregido})
        rev[cid] = info
        if i % 10 == 0:
            print(f"  {i}/{len(pend)}", flush=True)
            guardar(campos, rev)

    guardar(campos, rev)
    from collections import Counter
    c = Counter(r.get("tipo", "otro") for r in rev.values())
    avisos = [f"{k}: {r['nombre']} ({r['localidad']}) — {r['aviso']}" for k, r in rev.items() if r.get("aviso")]
    print("Resultado:", dict(c))
    print(len(avisos), "avisos:")
    for a in avisos:
        print(" -", a)


if __name__ == "__main__":
    main()
