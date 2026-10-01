#!/usr/bin/env python3
import os, sys, json, shutil
a = sys.argv[1:]
R = os.environ["FAKE_REMOTE"]; LOG = os.environ["FAKE_LOG"]; ST = os.environ.get("FAKE_STATE", "")
with open(LOG, "a") as f: f.write("\t".join(a) + "\n")
def flag(n): return ST and os.path.exists(os.path.join(ST, n))
def real(p): return os.path.join(R, p.lstrip("/"))
if flag("loggedout"):
    print("You need to login first"); sys.exit(1)
if a[:1] == ["version"]: print("proton-drive 9.9.9-fake"); sys.exit(0)
if a[:1] == ["auth"]: print("logged out"); sys.exit(0)
if a[:2] == ["filesystem", "list"]:
    d = real(a[2])
    if not os.path.isdir(d): print("Error: node not found"); sys.exit(1)
    out = []
    for n in sorted(os.listdir(d)):
        p = os.path.join(d, n)
        e = {"uid": "u~" + n, "type": "folder" if os.path.isdir(p) else "file", "name": {"ok": True, "value": n}}
        if os.path.isfile(p): e["activeRevision"] = {"claimedSize": os.path.getsize(p) + (1 if flag("badsize") else 0)}
        out.append(e)
    print(json.dumps(out)); sys.exit(0)
if a[:2] == ["filesystem", "info"]:
    if os.path.exists(real(a[2])): print("{}"); sys.exit(0)
    print("Error: node not found"); sys.exit(1)
if a[:2] == ["filesystem", "create-folder"]:
    p = os.path.join(real(a[2]), a[3])
    if os.path.exists(p): print("Error: already exists"); sys.exit(1)
    if not os.path.isdir(real(a[2])): print("Error: node not found"); sys.exit(1)
    os.mkdir(p); sys.exit(0)
if a[:2] == ["filesystem", "upload"]:
    rest = a[2:]; i = 0
    while i < len(rest) and rest[i].startswith("--"): i += 2 if rest[i] != "--skip-thumbnails" else 1
    files, dest = rest[i:-1], rest[-1]
    if not os.path.isdir(real(dest)): print("Error: node not found"); sys.exit(1)
    if flag("sleep"): __import__("time").sleep(float(open(os.path.join(ST, "sleep")).read()))
    skip = set(open(os.path.join(ST, "skip")).read().split("\n")) if flag("skip") else set()
    for f in files:
        if os.path.basename(f) in skip: continue
        t = os.path.join(real(dest), os.path.basename(f))
        if os.path.exists(t) and not flag("overwrite"): continue
        shutil.copyfile(f, t)
    sys.exit(0)
print("unknown command", a); sys.exit(1)
