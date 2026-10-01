import sys, re, json, difflib, os, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from world import *

TS = re.compile(r"\d{4}-\d\d-\d\dT[\d:.]+Z")
def normdb(d):
    if d is None: return None
    out = {}
    for t, v in d.items():
        if t in ("settings", "sqlite_sequence"): continue
        cols = v["cols"]; rows = []
        for r in v["rows"]:
            r = list(r)
            for i, c in enumerate(cols):
                if c == "inode": r[i] = "<ino>"
                elif isinstance(r[i], str): r[i] = TS.sub("<ts>", r[i]).replace("/w_cs/", "/w_X/").replace("/w_java/", "/w_X/")
            rows.append(r)
        if t == "files":
            rows = sorted([x[1:] for x in rows], key=lambda x: x[1]); cols = cols[1:]
        out[t] = {"cols": cols, "rows": rows}
    return out

worlds = {i: World(i, f"{WORK}/w_{i}") for i in IMPLS}
fails = 0
def snap(w):
    return {"cli": w.take_log(), "systemctl": w.take_log("FAKE_SYSTEMCTL_LOG"), "db": normdb(w.db()), "remote": w.remote_tree()}

def step(title, action, *args, **kw):
    """action: callable(world)->result or args for w.run"""
    global fails
    res = {}
    for i, w in worlds.items():
        res[i] = action(w) if callable(action) else w.run(action, *args, **kw)
        res[i] = (res[i], snap(w))
    a, b = res["cs"], res["java"]
    def lines(x):
        (rc, out, err), sn = x
        # Folder order follows the file system's enumeration order, which differs between the two runs: compare sorted.
        L = [f"exit {rc}"] + ["out| " + l for l in sorted(out.splitlines())] + ["err| " + l for l in sorted(err.splitlines())]
        L += ["cli| " + l for l in sorted(sn["cli"].splitlines())] + ["sysctl| " + l for l in sn["systemctl"].splitlines()]
        L += ["remote| " + r for r in sn["remote"]]
        db = sn["db"]
        L += ["db| " + (t + " " + json.dumps(v["rows"], ensure_ascii=False)) for t, v in (db or {}).items()] if db else ["db| none"]
        return [l.replace("w_cs", "w_X").replace("w_java", "w_X") for l in L]
    ta, tb = lines(a), lines(b)
    if ta == tb:
        print(f"SAME   {title}")
    else:
        fails += 1
        print(f"DIFF   {title}")
        for l in difflib.unified_diff(ta, tb, "C#", "Java", lineterm="", n=1): print("    " + l)
    return res

def both(fn):
    return lambda w: fn(w)

def mkfile(w, rel, content="x", mtime=1700000000):
    p = os.path.join(w.src, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(content); os.utime(p, (mtime, mtime))

def seed_check(w):
    import sqlite3
    c = sqlite3.connect(os.path.join(w.home, ".local/share/ProtonBackup/protonbackup.db"))
    c.execute("insert or replace into settings(key,value) values('cli_last_check_utc', ?)", (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),)); c.commit(); c.close()

exec(open(sys.argv[1]).read())
print(f"\n{fails} step(s) differ")
