import os, subprocess, shutil, sqlite3, json, re, time, sys

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.environ.get("COMPARE_WORK", "/tmp/protonbackup-compare")
IMPLS = {
    "cs": [WORK + "/csbin/protonbackup"],
    "java": [WORK + "/java/usr/lib/protonbackup/bin/protonbackup"],
}

class World:
    def __init__(self, impl, root):
        self.impl, self.root = impl, root
        shutil.rmtree(root, ignore_errors=True)
        for d in ["home", "remote/my-files", "state", "fakebin", "src"]:
            os.makedirs(os.path.join(root, d))
        self.home = os.path.join(root, "home"); self.src = os.path.join(root, "src")
        bindir = os.path.join(self.home, ".local/share/ProtonBackup/bin"); os.makedirs(bindir)
        os.symlink(HERE + "/fakecli.py", os.path.join(bindir, "proton-drive"))
        shutil.copy(HERE + "/fakesystemctl", os.path.join(root, "fakebin/systemctl"))
        self.env = dict(os.environ, HOME=self.home, LC_ALL="C.UTF-8", DOTNET_ROOT=os.path.expanduser("~/.dotnet"),
                        FAKE_REMOTE=os.path.join(root, "remote"), FAKE_LOG=os.path.join(root, "cli.log"),
                        FAKE_STATE=os.path.join(root, "state"), FAKE_SYSTEMCTL_LOG=os.path.join(root, "systemctl.log"),
                        PATH=os.path.join(root, "fakebin") + ":" + os.environ["PATH"])
        for v in ["LD_LIBRARY_PATH", "JAVA_TOOL_OPTIONS"]:
            self.env.pop(v, None)
        # .NET resolved ApplicationData to the working directory when ~/.config did not exist yet; spell the XDG folders out for both.
        self.env.update(XDG_DATA_HOME=self.home + "/.local/share", XDG_CONFIG_HOME=self.home + "/.config", XDG_CACHE_HOME=self.home + "/.cache")
        os.makedirs(self.home + "/.config")
        open(self.env["FAKE_LOG"], "w").close(); open(self.env["FAKE_SYSTEMCTL_LOG"], "w").close()

    def norm(self, text):
        return text.replace(self.root, "<W>")

    def run(self, *args, input=None, timeout=120):
        p = subprocess.run(IMPLS[self.impl] + list(args), env=self.env, capture_output=True, text=True, input=input, timeout=timeout)
        return p.returncode, self.norm(p.stdout), self.norm(p.stderr)

    def take_log(self, name="FAKE_LOG"):
        path = self.env[name]; t = self.norm(open(path).read()); open(path, "w").close(); return t

    def db(self):
        path = os.path.join(self.home, ".local/share/ProtonBackup/protonbackup.db")
        if not os.path.exists(path): return None
        c = sqlite3.connect(path); out = {}
        for (t,) in c.execute("select name from sqlite_master where type='table' order by name"):
            cols = [r[1] for r in c.execute(f"pragma table_info({t})")]
            out[t] = {"cols": cols, "rows": [list(r) for r in c.execute(f"select * from {t}")]}
        return out

    def remote_tree(self):
        r = os.path.join(self.root, "remote"); out = []
        for d, ds, fs in os.walk(r):
            for n in sorted(ds + fs): out.append(os.path.relpath(os.path.join(d, n), r) + ("/" if os.path.isdir(os.path.join(d, n)) else ""))
        return sorted(out)
