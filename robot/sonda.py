"""Diagnóstico: ¿trae la clasificación de la RFFM las zonas de ascenso/descenso?"""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import rffm
for gid in sys.argv[1:]:
    g = json.load(open(os.path.join(rffm.DATA, "g", gid + ".json")))
    q = {"temporada": g["temp"], "competicion": g["comp"], "grupo": gid, "tipojuego": g["tj"]}
    st = rffm.page_props("/competicion/clasificaciones", q)
    std = st.get("standings") or {}
    print("==", gid, "claves standings:", [k for k in std.keys() if k != "clasificacion"])
    for k, v in std.items():
        if k != "clasificacion": print("   ", k, json.dumps(v, ensure_ascii=False)[:400])
    c = std.get("clasificacion") or []
    for r in c[:2] + c[-2:]:
        print("   fila:", json.dumps(r, ensure_ascii=False)[:900])
