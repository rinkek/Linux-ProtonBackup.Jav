import os, time
for w in worlds.values():
    w.run("list-sources"); seed_check(w)
    for rel, c in [("a/x.txt","hello"),("a/b/deep.txt","deep"),("top.txt","top"),("café/日本語.txt","utf8"),("empty.txt",""),("with space/a b.txt","sp"),("dup/same.txt","1"),("other/same.txt","22"),("a_b/f.txt","u"),("100%/p.txt","pc")]:
        mkfile(w, rel, c)
    os.symlink(w.src + "/top.txt", w.src + "/link.txt"); os.symlink(w.src + "/a", w.src + "/linkdir")
    mkfile(w, "fresh.txt", "just now", mtime=time.time() + 3600)   # inside the settle window
    w.run("add-source", w.src, "/my-files/Backup/Deep/Er")
step("run-once, first", "--run-once")
step("run-once, again (nothing to do)", "--run-once")
def modify(w):
    mkfile(w, "a/x.txt", "hello changed", mtime=1700000100); os.remove(w.src + "/top.txt"); mkfile(w, "new/n.txt", "n")
    return w.run("--run-once")
step("changed, removed, new", modify)
step("run-once --force", "--run-once", "--force")
step("run-once --source 1", "--run-once", "--source", "1")
step("run-once --source 7 (no such)", "--run-once", "--source", "7")
def skipfail(w):
    open(w.root + "/state/skip", "w").write("zzz.txt\n"); mkfile(w, "zzz.txt", "z"); mkfile(w, "ok2.txt", "o")
    return w.run("--run-once")
step("a file the CLI silently skips", skipfail)
step("and again (retry)", "--run-once")
def badsize(w):
    os.remove(w.root + "/state/skip"); open(w.root + "/state/badsize", "w").close(); mkfile(w, "sz.txt", "size"); return w.run("--run-once")
step("remote claims another size", badsize)
def loggedout(w):
    os.remove(w.root + "/state/badsize"); open(w.root + "/state/loggedout", "w").close(); mkfile(w, "lo.txt", "lo"); return w.run("--run-once", timeout=200)
step("session expired", loggedout)
def nocli(w):
    os.remove(w.root + "/state/loggedout"); b = w.home + "/.local/share/ProtonBackup/bin/proton-drive"; os.rename(b, b + ".off")
    r = w.run("--run-once"); w.pending = b; return r
step("no CLI installed: run-once", nocli)
def nocli2(w):
    r = w.run("status"); os.rename(w.pending + ".off", w.pending); return r
step("no CLI installed: status", nocli2)
step("status after all that", "status")
step("run-once final", "--run-once")
def gone(w):
    import shutil; shutil.rmtree(w.src + "/a"); return w.run("--run-once")
step("a whole folder removed", gone)
def missing_source(w):
    import shutil; shutil.move(w.src, w.src + ".moved"); r = w.run("--run-once"); shutil.move(w.src + ".moved", w.src); return r
step("source folder missing", missing_source)
