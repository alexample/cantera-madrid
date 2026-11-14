"""Diagnóstico: busca en qué página publica la RFFM los resultados del día."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import rffm
gid = sys.argv[1] if len(sys.argv) > 1 else "26737740"
g = json.load(open(os.path.join(rffm.DATA, "g", gid + ".json")))
q = {"temporada": g["temp"], "competicion": g["comp"], "grupo": gid, "tipojuego": g["tj"], "jornada": 8}
for ruta in ("/competicion/resultados", "/competicion/resultados-jornada", "/competicion/jornada", "/competicion/partidos"):
    try:
        pp = rffm.page_props(ruta, q)
        print("==", ruta, "claves:", list(pp.keys()))
        for k, v in pp.items():
            if k in ("global", "categories", "notices", "sidebar", "seasons", "gameTypes"): continue
            print("   ", k, json.dumps(v, ensure_ascii=False)[:1500])
    except Exception as e:
        print("==", ruta, "ERROR", str(e)[:200])
txt = rffm.pedir("/robots.txt", None)
print("robots:", txt[:800])
