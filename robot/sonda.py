"""Diagnóstico: compara los resultados de una jornada en las distintas páginas de la RFFM."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import rffm
gid = sys.argv[1] if len(sys.argv) > 1 else "26737740"
g = json.load(open(os.path.join(rffm.DATA, "g", gid + ".json")))
q = {"temporada": g["temp"], "competicion": g["comp"], "grupo": gid, "tipojuego": g["tj"]}
hoy = "2026-11-14"
jor = max(p[0] for p in g["part"] if p[1] == hoy)
for etiqueta, extra in (("calendario jornada=1", {"jornada": 1}), (f"calendario jornada={jor}", {"jornada": jor}), ("calendario sin jornada", {})):
    pp = rffm.page_props("/competicion/calendario", {**q, **extra})
    print("==", etiqueta, "claves:", list(pp.keys()))
    cal = pp.get("calendar") or {}
    print("   claves calendar:", list(cal.keys()) if isinstance(cal, dict) else type(cal))
    for r in (cal.get("rounds") or []):
        if str(r.get("jornada")) in (str(jor), f"Jornada {jor}") or rffm.jornada_num(r.get("jornada")) == jor:
            for p in (r.get("equipos") or [])[:3]:
                print("   ", {k: v for k, v in p.items() if "escudo" not in k})
for k, v in pp.items():
    if k != "calendar": print("otra clave", k, str(v)[:300])
st = rffm.page_props("/competicion/clasificaciones", q)
print("== clasificaciones claves:", list(st.keys()), "| standings:", list((st.get("standings") or {}).keys()))
for k, v in (st.get("standings") or {}).items():
    if k != "clasificacion": print("   ", k, str(v)[:600])
