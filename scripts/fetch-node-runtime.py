#!/usr/bin/env python3
"""
Menyiapkan runtime Node.js + npm untuk Android (arm64, bionic) sebagai aset APK.

Sumber utama: repositori paket Termux (https://packages.termux.dev).
  * Termux memisahkan `npm` dari `nodejs`/`nodejs-lts`, jadi KEDUANYA dipasang.
  * Bila paket npm Termux tidak lengkap, npm (JavaScript murni) diambil dari registry npm
    dan diverifikasi integritasnya (sha512).

Validasi STATIS sebelum ZIP dianggap berhasil (runner CI x86_64 tidak bisa menjalankan binary arm64
bionic, jadi `node --version` yang sesungguhnya dijalankan di HP oleh NodeRuntime.check()):
  1. bin/node adalah ELF AArch64.
  2. Versi node dari metadata paket HARUS muncul sebagai string di dalam binary.
  3. Versi npm dibaca dari lib/node_modules/npm/package.json (setara `npm --version`).
  4. Struktur npm lengkap (bin/npm-cli.js, lib/cli.js, node_modules/@npmcli/arborist).
  5. Penutupan library native: setiap DT_NEEDED dari node (dan semua .so yang terjangkau) harus ada
     di bundle atau termasuk library sistem Android. Yang hilang => GAGAL.
  6. ZIP dibuka ulang ke direktori sementara dan semua pengecekan di atas diulang pada isinya.
ZIP hanya ditulis bila semuanya lolos; bila gagal, skrip exit 1 dan TIDAK menghasilkan ZIP.

Pemakaian:
  python3 scripts/fetch-node-runtime.py --out runtime-bundle/assets/node-runtime.zip
  python3 scripts/fetch-node-runtime.py --package nodejs        # Node terbaru (bukan LTS)
  python3 scripts/fetch-node-runtime.py --self-test             # uji logika offline
"""
import argparse
import base64
import gzip
import hashlib
import io
import json
import lzma
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

REPO = "https://packages.termux.dev/apt/termux-main"
INDEX = REPO + "/dists/stable/main/binary-aarch64/Packages"
PREFIX = "data/data/com.termux/files/usr/"
NPM_REGISTRY = "https://registry.npmjs.org/npm"

NODE_PACKAGES = {"nodejs", "nodejs-lts", "nodejs-current"}

# Paket Termux yang bukan dependensi runtime Node (alat shell / pengelola paket).
SKIP = {
    "termux-tools", "termux-exec", "termux-keyring", "termux-licenses", "termux-core", "termux-am",
    "termux-am-socket", "apt", "dpkg", "bash", "dash", "coreutils", "busybox", "util-linux",
    "ca-certificates", "resolv-conf", "gdbm", "libandroid-glob", "libandroid-posix-semaphore",
}
SKIP_PARTS = ("/share/man/", "/share/doc/", "/include/", "/lib/pkgconfig/", "/lib/cmake/",
              "/node_modules/npm/docs/", "/node_modules/npm/man/", "/node_modules/corepack/")
KEEP_TOP = ("bin/", "lib/", "etc/")

# Library yang disediakan Android (NDK stable API) -> tidak perlu dibundel.
ANDROID_SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libstdc++.so", "libandroid.so",
    "libjnigraphics.so", "libnativewindow.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
    "libGLESv3.so", "libvulkan.so", "libOpenSLES.so", "libmediandk.so", "libcamera2ndk.so",
    "libaaudio.so", "libbinder_ndk.so", "libsync.so", "libneuralnetworks.so", "libadbconnection.so",
    "ld-android.so", "libnetd_client.so",
}

REQUIRED_NPM_FILES = (
    "lib/node_modules/npm/bin/npm-cli.js",
    "lib/node_modules/npm/lib/cli.js",
    "lib/node_modules/npm/package.json",
    "lib/node_modules/npm/node_modules/@npmcli/arborist/package.json",
)


class RuntimeError_(Exception):
    pass


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def fail(msg):
    raise RuntimeError_(msg)


# ----------------------------------------------------------------------------- jaringan
def fetch(url, retries=3, headers=None):
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=headers or {})
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            log(f"  retry {i + 1}/{retries} {url}: {e}")
    fail(f"Gagal mengunduh {url}: {last}")


# ----------------------------------------------------------------------------- indeks Termux
def parse_packages(text):
    index = {}
    for block in text.split("\n\n"):
        fields, key = {}, None
        for line in block.splitlines():
            if line.startswith((" ", "\t")) and key:
                fields[key] += "\n" + line.strip()
            elif ":" in line:
                key, _, val = line.partition(":")
                fields[key.strip()] = val.strip()
        if "Package" in fields:
            index[fields["Package"]] = fields
    return index


def parse_depends(s):
    """'a (>= 1), b | c' -> [['a'], ['b', 'c']]  (grup alternatif)."""
    groups = []
    for part in s.split(","):
        alts = []
        for alt in part.split("|"):
            name = alt.split("(")[0].strip()
            if name:
                alts.append(name)
        if alts:
            groups.append(alts)
    return groups


def resolve(roots, index, chosen_node):
    """Urutan paket yang dipasang. Alternatif 'nodejs | nodejs-lts' dianggap terpenuhi oleh
    paket Node yang sudah dipilih (agar tidak menarik dua versi Node)."""
    order, seen = [], set()
    queue = list(roots)
    while queue:
        n = queue.pop(0)
        if n in seen:
            continue
        if n not in index:
            log(f"  (tidak ada di indeks, dilewati: {n})")
            continue
        seen.add(n)
        order.append(n)
        for key in ("Depends", "Pre-Depends"):
            for alts in parse_depends(index[n].get(key, "")):
                if any(a in seen or a in SKIP for a in alts):
                    continue
                if any(a in NODE_PACKAGES for a in alts):
                    continue  # dipenuhi oleh chosen_node
                pick = next((a for a in alts if a in index), None)
                if pick:
                    queue.append(pick)
                else:
                    log(f"  (dependency tidak ada di indeks: {' | '.join(alts)})")
    return order


# ----------------------------------------------------------------------------- .deb
def read_ar(data):
    if not data.startswith(b"!<arch>\n"):
        fail("bukan arsip ar/deb")
    pos, files = 8, {}
    while pos + 60 <= len(data):
        hdr = data[pos:pos + 60]
        name = hdr[:16].decode().strip().rstrip("/")
        size = int(hdr[48:58].decode().strip())
        pos += 60
        files[name] = data[pos:pos + size]
        pos += size + (size & 1)
    return files


def open_data_tar(files):
    for name, blob in files.items():
        if name.startswith("data.tar"):
            if name.endswith(".xz"):
                return tarfile.open(fileobj=io.BytesIO(lzma.decompress(blob)))
            if name.endswith(".gz"):
                return tarfile.open(fileobj=io.BytesIO(gzip.decompress(blob)))
            if name.endswith(".tar"):
                return tarfile.open(fileobj=io.BytesIO(blob))
            fail(f"Format {name} belum didukung (butuh xz/gz)")
    fail("data.tar.* tidak ditemukan di .deb")


def extract_deb(blob, stage):
    """Ekstrak bin/, lib/, etc/ dari prefix Termux ke stage. Mengembalikan daftar path relatif."""
    tf = open_data_tar(read_ar(blob))
    stage_real = os.path.realpath(stage)
    paths = []
    for m in tf:
        name = m.name
        while name.startswith("./"):
            name = name[2:]
        if not name.startswith(PREFIX):
            continue
        rel = name[len(PREFIX):]
        if not rel or not rel.startswith(KEEP_TOP):
            continue
        if any(p in "/" + rel for p in SKIP_PARTS):
            continue
        dest = os.path.join(stage, rel)
        if not os.path.realpath(dest).startswith(stage_real + os.sep):
            fail(f"Entri berbahaya: {m.name}")
        if m.isdir():
            os.makedirs(dest, exist_ok=True)
        elif m.issym():
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            if os.path.lexists(dest):
                os.remove(dest)
            target = m.linkname
            if target.startswith("/" + PREFIX):  # tautan absolut Termux -> relatif di dalam stage
                target = os.path.relpath(os.path.join(stage, target[len("/" + PREFIX):]), os.path.dirname(dest))
            os.symlink(target, dest)
            paths.append(rel)
        elif m.islnk():
            lt = m.linkname[len(PREFIX):] if m.linkname.startswith(PREFIX) else m.linkname
            src = os.path.join(stage, lt)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            if os.path.exists(src):
                shutil.copy2(src, dest)
                paths.append(rel)
        elif m.isfile():
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(tf.extractfile(m).read())
            os.chmod(dest, (m.mode | 0o200) & 0o777)
            paths.append(rel)
    return paths


# ----------------------------------------------------------------------------- semver minimal
def _v(s):
    m = re.match(r"^v?(\d+)(?:\.(\d+|x|X|\*))?(?:\.(\d+|x|X|\*))?(?:-.*)?$", s.strip())
    if not m:
        return None
    parts = [m.group(1), m.group(2), m.group(3)]
    return [None if (p is None or p in "xX*") else int(p) for p in parts]


def _cmp(a, b):
    return (a > b) - (a < b)


def _comparators(tok):
    """Satu token -> daftar (op, (maj,min,pat)) yang semuanya harus terpenuhi."""
    tok = tok.strip()
    if tok in ("", "*", "x", "X"):
        return []
    op = ""
    for cand in (">=", "<=", ">", "<", "=", "^", "~"):
        if tok.startswith(cand):
            op, tok = cand, tok[len(cand):].strip()
            break
    v = _v(tok)
    if v is None:
        fail(f"rentang semver tidak didukung: {tok!r}")
    maj, mi, pa = v
    lo = (maj or 0, mi or 0, pa or 0)
    if op in ("", "=", "^", "~"):
        if op in ("", "=") and None not in v:
            return [("=", lo)]
        if op == "^" and maj and None not in v or (op == "^" and maj):
            hi = (maj + 1, 0, 0)
        elif op == "^" and mi:
            hi = (0, mi + 1, 0)
        elif op == "^":
            hi = (0, 0, (pa or 0) + 1) if pa is not None else (0, (mi or 0) + 1, 0)
        elif mi is None:
            hi = (maj + 1, 0, 0)
        else:
            hi = (maj, mi + 1, 0)
        if op == "" or op == "=":  # x-range seperti "24" atau "24.22"
            hi = (maj + 1, 0, 0) if mi is None else (maj, mi + 1, 0)
        return [(">=", lo), ("<", hi)]
    if op == ">":
        if pa is None:
            return [(">=", (maj + 1, 0, 0) if mi is None else (maj, mi + 1, 0))]
        return [(">", lo)]
    return [(op, lo)]


def satisfies(version, rng):
    ver = _v(version)
    if ver is None or "-" in version:
        return False
    ver = tuple(ver)
    for alt in rng.split("||"):
        ok = True
        for tok in re.findall(r"(?:[<>]=?|=|\^|~)?\s*v?[\dxX*][\w.*-]*", alt.strip()) or [""]:
            for op, bound in _comparators(tok):
                c = _cmp(ver, bound)
                if not {"=": c == 0, ">=": c >= 0, ">": c > 0, "<=": c <= 0, "<": c < 0}[op]:
                    ok = False
        if ok:
            return True
    return False


# Versi npm yang dibawa rilis Node resmi untuk tiap mayor (paling kompatibel).
def expected_npm_major(node_version):
    major = int(node_version.split(".")[0]) if node_version else 0
    return {18: 10, 20: 10, 21: 10, 22: 10, 23: 11, 24: 11, 25: 11}.get(major)


# ----------------------------------------------------------------------------- smoke test npm
def smoke_npm(npm_dir, host_node):
    """Jalankan `node npm-cli.js --version` dengan node HOST (npm itu JS murni).
    Mengembalikan (ok|None, detail). None = dilewati karena tidak ada node host."""
    if not host_node:
        return None, "node host tidak tersedia (smoke test dilewati)"
    cli = os.path.join(npm_dir, "bin", "npm-cli.js")
    if not os.path.isfile(cli):
        return False, "npm-cli.js tidak ada"
    home = tempfile.mkdtemp(prefix="npm-smoke-")
    env = {"PATH": os.environ.get("PATH", ""), "HOME": home, "npm_config_cache": os.path.join(home, "c"),
           "npm_config_update_notifier": "false", "NO_COLOR": "1"}
    try:
        r = subprocess.run([host_node, cli, "--version"], capture_output=True, text=True, timeout=90, env=env, cwd=home)
    except Exception as e:  # noqa: BLE001
        return False, f"gagal menjalankan: {e}"
    finally:
        shutil.rmtree(home, ignore_errors=True)
    out = (r.stdout or "").strip().splitlines()
    last = out[-1].strip() if out else ""
    try:
        want = json.load(open(os.path.join(npm_dir, "package.json")))["version"]
    except Exception:  # noqa: BLE001
        want = None
    ok = r.returncode == 0 and re.fullmatch(r"\d+\.\d+\.\d+", last) is not None and (want is None or last == want)
    return ok, f"exit={r.returncode} stdout={last!r} stderr={(r.stderr or '').strip()[:500]!r}"


# ----------------------------------------------------------------------------- npm dari registry (fallback)
def install_npm_version(stage, version):
    log(f"Mengambil npm {version} dari registry npm…")
    meta = json.loads(fetch(f"{NPM_REGISTRY}/{version}").decode())
    tarball, integ = meta["dist"]["tarball"], meta["dist"].get("integrity", "")
    blob = fetch(tarball)
    if integ.startswith("sha512-"):
        want = base64.b64decode(integ[7:])
        if hashlib.sha512(blob).digest() != want:
            fail("Integritas sha512 npm tidak cocok")
    else:
        sha1 = meta["dist"].get("shasum")
        if not sha1 or hashlib.sha1(blob).hexdigest() != sha1:  # noqa: S324
            fail("Checksum npm tidak cocok / tidak tersedia")
    dest_root = os.path.join(stage, "lib", "node_modules", "npm")
    shutil.rmtree(dest_root, ignore_errors=True)
    os.makedirs(dest_root)
    real = os.path.realpath(dest_root)
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tf:
        for m in tf:
            if not m.name.startswith("package/") or not (m.isfile() or m.isdir()):
                continue
            rel = m.name[len("package/"):]
            if not rel:
                continue
            dest = os.path.join(dest_root, rel)
            if not os.path.realpath(dest).startswith(real + os.sep):
                fail(f"Entri berbahaya di tarball npm: {m.name}")
            if m.isdir():
                os.makedirs(dest, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as f:
                    f.write(tf.extractfile(m).read())
    log(f"  npm {meta['version']} dari registry")
    return meta["version"]


def registry_candidates(node_version, limit=4):
    """Versi npm yang paling mungkin cocok: tag latest-<mayor-npm-bawaan-node>, lalu versi lain yang
    engines-nya memenuhi node ini (mayor sama dengan npm bawaan bila diketahui)."""
    exp = expected_npm_major(node_version)
    pack = json.loads(fetch(NPM_REGISTRY, headers={"Accept": "application/vnd.npm.install-v1+json"}).decode())
    out = []
    tag = pack.get("dist-tags", {}).get(f"latest-{exp}") if exp else None
    if tag:
        out.append(tag)
    vers = []
    for v, meta in pack.get("versions", {}).items():
        if "-" in v or _v(v) is None:
            continue
        if exp and int(v.split(".")[0]) != exp:
            continue
        eng = (meta.get("engines") or {}).get("node")
        if eng and not satisfies(node_version, eng):
            continue
        vers.append(v)
    vers.sort(key=lambda v: tuple(int(x) for x in v.split(".")), reverse=True)
    for v in vers:
        if v not in out:
            out.append(v)
    return out[:limit]


def ensure_working_npm(stage, node_version, host_node):
    """Pastikan npm di stage lolos `npm --version` (node host). Bila tidak, coba versi dari registry."""
    npm_dir = os.path.join(stage, "lib", "node_modules", "npm")
    structurally_ok = all(os.path.isfile(os.path.join(stage, r)) for r in REQUIRED_NPM_FILES)
    if structurally_ok:
        ok, detail = smoke_npm(npm_dir, host_node)
        log(f"  smoke test npm bawaan Termux: ok={ok} {detail}")
        if ok is not False:
            return "termux"
        log("  npm Termux gagal smoke test -> cari versi yang kompatibel di registry")
    else:
        log("  struktur npm Termux tidak lengkap -> ambil dari registry")
    tried = []
    for ver in registry_candidates(node_version):
        install_npm_version(stage, ver)
        if not all(os.path.isfile(os.path.join(stage, r)) for r in REQUIRED_NPM_FILES):
            tried.append(f"{ver}: struktur tidak lengkap")
            continue
        ok, detail = smoke_npm(npm_dir, host_node)
        log(f"  smoke test npm {ver}: ok={ok} {detail}")
        if ok is not False:
            return f"registry {ver}"
        tried.append(f"{ver}: {detail}")
    fail("Tidak ada versi npm yang lolos smoke test: " + " | ".join(tried))


# ----------------------------------------------------------------------------- ELF
def elf_info(path):
    """Parser ELF64 little-endian minimal: machine, DT_NEEDED, DT_SONAME, RPATH/RUNPATH."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF":
        fail(f"{path} bukan ELF")
    if data[4] != 2 or data[5] != 1:
        fail(f"{path}: hanya ELF64 little-endian yang didukung")
    machine = struct.unpack_from("<H", data, 18)[0]
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    loads, dyn = [], None
    for i in range(phnum):
        o = phoff + i * phentsize
        p_type, _flags, p_off, p_vaddr, _pa, p_filesz, _ms, _al = struct.unpack_from("<IIQQQQQQ", data, o)
        if p_type == 1:
            loads.append((p_vaddr, p_off, p_filesz))
        elif p_type == 2:
            dyn = (p_off, p_filesz)
    info = {"machine": machine, "needed": [], "soname": None, "rpath": [], "runpath": []}
    if not dyn:
        return info

    def v2o(v):
        for va, off, sz in loads:
            if va <= v < va + sz:
                return off + (v - va)
        return None

    entries, strtab = [], None
    for o in range(dyn[0], dyn[0] + dyn[1], 16):
        tag, val = struct.unpack_from("<qQ", data, o)
        if tag == 0:
            break
        entries.append((tag, val))
        if tag == 5:
            strtab = v2o(val)
    if strtab is None:
        return info

    def s(off):
        end = data.index(b"\0", strtab + off)
        return data[strtab + off:end].decode(errors="replace")

    for tag, val in entries:
        if tag == 1:
            info["needed"].append(s(val))
        elif tag == 14:
            info["soname"] = s(val)
        elif tag == 15:
            info["rpath"] = s(val).split(":")
        elif tag == 29:
            info["runpath"] = s(val).split(":")
    return info


def native_closure(root):
    """Periksa penutupan library native dari bin/node di pohon `root`.
    Mengembalikan (daftar_hilang, daftar_dipakai_sistem, so_terjangkau)."""
    libdir = os.path.join(root, "lib")
    have = {}
    if os.path.isdir(libdir):
        for fn in os.listdir(libdir):
            p = os.path.join(libdir, fn)
            if os.path.isfile(p) and ".so" in fn:
                have[fn] = p
    node = os.path.join(root, "bin", "node")
    missing, system, reached = [], set(), set()
    queue = [("node", node)]
    while queue:
        who, path = queue.pop()
        for need in elf_info(path)["needed"]:
            if need in reached:
                continue
            if need in have:
                reached.add(need)
                queue.append((need, have[need]))
            elif need in ANDROID_SYSTEM_LIBS:
                system.add(need)
            else:
                missing.append(f"{need} (dibutuhkan oleh {who})")
    return missing, sorted(system), sorted(reached)


# ----------------------------------------------------------------------------- validasi
def read_npm_version(root):
    with open(os.path.join(root, "lib/node_modules/npm/package.json")) as f:
        return json.load(f)["version"]


def validate_tree(root, node_version, label):
    """Validasi statis pohon runtime. Melempar RuntimeError_ bila ada yang tidak lolos."""
    problems, notes = [], []
    node = os.path.join(root, "bin", "node")
    if not os.path.isfile(node):
        fail(f"[{label}] bin/node tidak ada")
    info = elf_info(node)
    if info["machine"] != 183:
        problems.append(f"bin/node bukan AArch64 (e_machine={info['machine']})")
    notes.append(f"node ELF: AArch64, DT_NEEDED={info['needed']}")
    if info["runpath"] or info["rpath"]:
        notes.append(f"node RUNPATH/RPATH={info['runpath'] or info['rpath']} (aplikasi memakai LD_LIBRARY_PATH=<runtime>/lib)")

    if node_version:
        with open(node, "rb") as f:
            blob = f.read()
        if ("v" + node_version).encode() in blob or node_version.encode() in blob:
            notes.append(f"versi node {node_version}: string versi ditemukan di dalam binary")
        else:
            notes.append(f"PERINGATAN: string versi {node_version} tidak ditemukan di binary node")

    for rel in REQUIRED_NPM_FILES:
        if not os.path.isfile(os.path.join(root, rel)):
            problems.append(f"file npm wajib tidak ada: {rel}")
    npm_ver = None
    if not problems:
        try:
            npm_ver = read_npm_version(root)
            notes.append(f"npm {npm_ver} (dari package.json)")
        except Exception as e:  # noqa: BLE001
            problems.append(f"package.json npm tidak terbaca: {e}")

    missing, system, reached = native_closure(root)
    notes.append(f"library native terjangkau dari node: {reached}")
    notes.append(f"library sistem Android yang dipakai: {system}")
    for m in missing:
        problems.append(f"library native HILANG: {m}")
    if problems:
        for n in notes:
            log(f"  [{label}] {n}")
        fail(f"[{label}] validasi gagal:\n  - " + "\n  - ".join(problems))
    return npm_ver, notes


def write_zip(stage, out):
    stage_real = os.path.realpath(stage)
    tmp = out + ".tmp"
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    count, total = 0, 0
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for root, _dirs, files in os.walk(stage):
            for fn in sorted(files):
                p = os.path.join(root, fn)
                real = os.path.realpath(p)  # dereference symlink
                if not os.path.isfile(real) or not real.startswith(stage_real + os.sep):
                    continue
                arc = os.path.relpath(p, stage).replace(os.sep, "/")
                info = zipfile.ZipInfo(arc)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o755 if arc.startswith("bin/") else 0o644) << 16
                with open(real, "rb") as f:
                    data = f.read()
                z.writestr(info, data)
                count += 1
                total += len(data)
    return tmp, count, total


def verify_zip(tmp_zip, node_version):
    """Buka ulang ZIP ke direktori sementara dan validasi isinya."""
    chk = tempfile.mkdtemp(prefix="verify-zip-")
    try:
        with zipfile.ZipFile(tmp_zip) as z:
            bad = z.testzip()
            if bad:
                fail(f"ZIP rusak: {bad}")
            z.extractall(chk)
        return validate_tree(chk, node_version, "isi ZIP")
    finally:
        shutil.rmtree(chk, ignore_errors=True)


def summarize(stage):
    tops = {}
    for root, _d, files in os.walk(stage):
        for fn in files:
            p = os.path.join(root, fn)
            if os.path.isfile(p):
                top = os.path.relpath(p, stage).split(os.sep)
                key = "/".join(top[:2]) if top[0] == "lib" and len(top) > 2 else top[0]
                tops[key] = tops.get(key, 0) + os.path.getsize(p)
    for k, v in sorted(tops.items(), key=lambda kv: -kv[1])[:12]:
        log(f"    {v / 1048576:8.1f} MB  {k}")


# ----------------------------------------------------------------------------- main
def build(args):
    log(f"Mengunduh indeks: {INDEX}")
    index = parse_packages(fetch(INDEX).decode("utf-8", "replace"))
    if args.package not in index:
        fail(f"Paket {args.package} tidak ada di indeks Termux")
    roots = [args.package]
    if "npm" in index:
        roots.append("npm")
    else:
        log("Paket 'npm' tidak ada di indeks Termux -> npm akan diambil dari registry npm")
    pkgs = resolve(roots, index, args.package)
    log("Paket yang akan dipasang: " + ", ".join(pkgs))
    node_version = re.sub(r"^\d+:", "", index[args.package].get("Version", "")).split("-")[0]

    stage = tempfile.mkdtemp(prefix="node-runtime-")
    try:
        for name in pkgs:
            meta = index[name]
            log(f"- {name} {meta.get('Version', '')}")
            blob = fetch(f"{REPO}/{meta['Filename']}")
            want = meta.get("SHA256")
            if want and hashlib.sha256(blob).hexdigest() != want:
                fail(f"SHA256 tidak cocok untuk {name}")
            paths = extract_deb(blob, stage)
            hits = [p for p in paths if "npm-cli.js" in p or p in ("bin/node", "bin/npm")]
            log(f"    {len(paths)} berkas; penting: {hits[:4] or '-'}")

        # npm: pakai Termux bila lengkap DAN lolos `npm --version`; jika tidak, ambil versi kompatibel.
        host_node = shutil.which("node")
        log(f"node host untuk smoke test: {host_node or '-'}"
            + (f" ({subprocess.run([host_node, '--version'], capture_output=True, text=True).stdout.strip()})" if host_node else ""))
        npm_source = ensure_working_npm(stage, node_version, host_node)
        log(f"Sumber npm: {npm_source}")

        log("Ukuran terbesar di runtime:")
        summarize(stage)
        validate_tree(stage, node_version, "stage")

        tmp, count, total = write_zip(stage, args.out)
        try:
            npm_ver, notes = verify_zip(tmp, node_version)
        except Exception:
            os.remove(tmp)
            raise
        os.replace(tmp, args.out)
        size = os.path.getsize(args.out) / 1048576
        summary = [
            f"node {node_version} (Termux {args.package}), npm {npm_ver}",
            f"{count} berkas, {total / 1048576:.0f} MB tidak terkompresi, ZIP {size:.1f} MB",
        ] + notes
        for line in summary:
            log("  OK " + line)
        sm = os.environ.get("GITHUB_STEP_SUMMARY")
        if sm:
            with open(sm, "a") as f:
                f.write("### Runtime Node.js (validasi statis)\n" + "\n".join(f"- {s}" for s in summary) +
                        "\n\n> Eksekusi `node --version` sesungguhnya dilakukan di perangkat (Settings > Runtime).\n")
        log(f"SELESAI: {args.out}")
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="runtime-bundle/assets/node-runtime.zip")
    ap.add_argument("--package", default="nodejs-lts", help="nodejs-lts (default) atau nodejs")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        import runpy
        here = os.path.dirname(os.path.abspath(__file__))
        runpy.run_path(os.path.join(here, "test_fetch_node_runtime.py"), run_name="__main__")
        return
    try:
        build(args)
    except RuntimeError_ as e:
        log(f"GAGAL: {e}")
        if os.path.exists(args.out):
            os.remove(args.out)
        sys.exit(1)


if __name__ == "__main__":
    main()
