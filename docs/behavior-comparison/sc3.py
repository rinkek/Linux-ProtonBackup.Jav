import subprocess, signal, time
for w in worlds.values():
    w.run("list-sources"); seed_check(w)
    for rel, c in [("a/1.txt","one"),("b/2.txt","two"),("c/3.txt","three")]: mkfile(w, rel, c)
    w.run("add-source", w.src, "/my-files/B")
def slow(w, secs): open(w.root + "/state/sleep", "w").write(str(secs))
def fast(w):
    p = w.root + "/state/sleep"
    if os.path.exists(p): os.remove(p)
def concurrent(w):
    slow(w, 4)
    p1 = subprocess.Popen(IMPLS[w.impl] + ["--run-once"], env=w.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    time.sleep(2.5)
    r2 = w.run("--run-once")
    o1, e1 = p1.communicate(timeout=60)
    fast(w)
    return (r2[0], r2[1] + "|FIRST:" + w.norm(o1), r2[2] + w.norm(e1))
step("second run while the first is busy", concurrent)
def sigterm(w):
    w.run("force-all"); w.take_log()
    slow(w, 3)
    p = subprocess.Popen(IMPLS[w.impl] + ["--run-once"], env=w.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    time.sleep(2.0)
    p.send_signal(signal.SIGTERM)
    o, e = p.communicate(timeout=60); fast(w)
    return (p.returncode, w.norm(o), w.norm(e))
step("SIGTERM during an upload", sigterm)
step("run after the cancelled one", "--run-once")
step("status", "status")
step("--cleanup, answered n", lambda w: w.run("--cleanup", input="n\n"))
step("--cleanup, no input at all", lambda w: w.run("--cleanup", input=""))
def cleaned(w):
    r = w.run("--cleanup", "--yes")
    left = []
    for d, ds, fs in os.walk(w.home):
        for n in ds + fs: left.append(os.path.relpath(os.path.join(d, n), w.home))
    return (r[0], r[1], r[2] + "|LEFT:" + ",".join(sorted(left)))
step("--cleanup --yes", cleaned)
