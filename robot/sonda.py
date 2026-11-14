"""Diagnóstico: ¿trae la página de clasificaciones los resultados de la jornada?"""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import rffm
gid = sys.argv[1] if len(sys.argv) > 1 else "26737740"
g = json.load(open(os.path.join(rffm.DATA, "g", gid + ".json")))
q = {"temporada": g["temp"], "competicion": g["comp"], "grupo": gid, "tipojuego": g["tj"]}
for extra in ({}, {"jornada": 8}):
    st = rffm.page_props("/competicion/clasificaciones", {**q, **extra})
    print("== clasificaciones", extra)
    print("   rounds:", json.dumps(st.get("rounds"), ensure_ascii=False)[:1200])
    c = (st.get("standings") or {}).get("clasificacion") or []
    if c: print("   fila 1 completa:", json.dumps(c[0], ensure_ascii=False)[:1500])
    print("   jornada:", (st.get("standings") or {}).get("jornada"))
