#!/usr/bin/env python3
"""Uji offline untuk fetch-node-runtime.py (tanpa jaringan). Jalankan: python3 scripts/fetch-node-runtime.py --self-test"""
import base64
import hashlib
import importlib.util
import io
import json
import lzma
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("fetchnode", os.path.join(HERE, "fetch-node-runtime.py"))
f = importlib.util.module_from_spec(spec)
spec.loader.exec_module(f)

ok = 0


def check(name, cond, extra=""):
    global ok
    print(("PASS " if cond else "FAIL ") + name + (" " + str(extra) if extra else ""))
    if not cond:
        sys.exit(1)
    ok += 1


def make_elf(machine, needed):
    """ELF64 LE minimal dengan PT_LOAD + PT_DYNAMIC + DT_NEEDED."""
    strtab = b"\0"
    offs = []
    for n in needed:
        offs.append(len(strtab))
        strtab += n.encode() + b"\0"
    ehsize, phsz, phnum = 64, 56, 2
    str_off = ehsize + phsz * phnum
    dyn_off = (str_off + len(strtab) + 15) & ~15
    dyn = b"".join(struct.pack("<qQ", 1, o) for o in offs)
    dyn += struct.pack("<qQ", 5, str_off) + struct.pack("<qQ", 10, len(strtab)) + struct.pack("<qQ", 0, 0)
    total = dyn_off + len(dyn)
    hdr = b"\x7fELF" + bytes([2, 1, 1, 0]) + b"\0" * 8
    hdr += struct.pack("<HHIQQQIHHHHHH", 3, machine, 1, 0, ehsize, 0, 0, ehsize, phsz, phnum, 0, 0, 0)
    ph_load = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, total, total, 0x1000)
    ph_dyn = struct.pack("<IIQQQQQQ", 2, 6, dyn_off, dyn_off, dyn_off, len(dyn), len(dyn), 8)
    blob = hdr + ph_load + ph_dyn + strtab
    blob += b"\0" * (dyn_off - len(blob)) + dyn
    return blob


# ---- 1. parse_depends / resolve ---------------------------------------------------------
check("parse_depends alternatif", f.parse_depends("a (>= 1), b | c, d") == [["a"], ["b", "c"], ["d"]])
idx = f.parse_packages("""Package: nodejs-lts
Version: 24.22.2-1
Depends: libc++, openssl (>= 3), termux-exec, libicu
Filename: pool/n.deb

Package: npm
Version: 11.1
Depends: nodejs | nodejs-lts
Filename: pool/npm.deb

Package: nodejs
Version: 25.0.0
Filename: pool/nj.deb

Package: libc++
Depends: termux-tools
Filename: pool/c.deb

Package: openssl
Filename: pool/o.deb

Package: libicu
Depends: libc++
Filename: pool/i.deb
""")
order = f.resolve(["nodejs-lts", "npm"], idx, "nodejs-lts")
check("resolve: npm ikut, tidak menarik nodejs kedua", order == ["nodejs-lts", "npm", "libc++", "openssl", "libicu"], order)

# ---- 2. parser ELF dibanding readelf pada binary asli ------------------------------------
host = os.path.realpath(sys.executable)
if shutil.which("readelf"):
    out = subprocess.run(["readelf", "-d", host], capture_output=True, text=True).stdout
    want = sorted(__import__("re").findall(r"\(NEEDED\)\s+Shared library: \[(.+?)\]", out))
    got = sorted(f.elf_info(host)["needed"])
    check("elf_info == readelf pada python host", want == got and len(got) > 0, got)
else:
    print("SKIP readelf tidak ada")

# ---- 3. jalur validasi lengkap dengan ELF AArch64 sintetis -------------------------------
def build_tree(root, node_needed, with_libs=True, npm_files=True):
    os.makedirs(f"{root}/bin"); os.makedirs(f"{root}/lib")
    with open(f"{root}/bin/node", "wb") as fh:
        fh.write(make_elf(183, node_needed) + b"...v24.22.2...")
    if with_libs:
        with open(f"{root}/lib/libc++_shared.so", "wb") as fh:
            fh.write(make_elf(183, ["libc.so", "libm.so", "libdl.so"]))
        with open(f"{root}/lib/libicu.so.79", "wb") as fh:
            fh.write(make_elf(183, ["libc++_shared.so", "libc.so"]))
    if npm_files:
        for rel in f.REQUIRED_NPM_FILES:
            p = f"{root}/{rel}"
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w") as fh:
                fh.write(json.dumps({"version": "11.6.0"}) if rel.endswith("package.json") else "//")

good = tempfile.mkdtemp()
build_tree(good, ["libc++_shared.so", "libicu.so.79", "libc.so", "libdl.so", "liblog.so"])
npm_ver, notes = f.validate_tree(good, "24.22.2", "uji")
check("validate_tree lolos + npm version dibaca", npm_ver == "11.6.0", npm_ver)
check("versi node ditemukan di binary", any("string versi ditemukan" in n for n in notes))

tmp, count, total = f.write_zip(good, os.path.join(good, "..", "t.zip"))
v2, _ = f.verify_zip(tmp, "24.22.2")
check("verify_zip membuka ulang & memvalidasi isi ZIP", v2 == "11.6.0")
with zipfile.ZipFile(tmp) as z:
    check("bin/node di ZIP bermode 755", z.getinfo("bin/node").external_attr >> 16 == 0o755)
os.remove(tmp)

bad = tempfile.mkdtemp()
build_tree(bad, ["libc++_shared.so", "libssl.so.3", "libc.so"], with_libs=True)
try:
    f.validate_tree(bad, "24.22.2", "uji"); check("library hilang harus gagal", False)
except f.RuntimeError_ as e:
    check("library native hilang (libssl.so.3) terdeteksi", "libssl.so.3" in str(e))

bad2 = tempfile.mkdtemp()
build_tree(bad2, ["libc.so"], npm_files=False)
try:
    f.validate_tree(bad2, "24.22.2", "uji"); check("npm hilang harus gagal", False)
except f.RuntimeError_ as e:
    check("npm-cli.js hilang terdeteksi", "npm-cli.js" in str(e))

bad3 = tempfile.mkdtemp()
build_tree(bad3, ["libc.so"])
open(f"{bad3}/bin/node", "wb").write(make_elf(62, ["libc.so"]))  # x86_64
try:
    f.validate_tree(bad3, "24.22.2", "uji"); check("arsitektur salah harus gagal", False)
except f.RuntimeError_ as e:
    check("ELF bukan AArch64 terdeteksi", "AArch64" in str(e))

# ---- 4. ekstraksi .deb (symlink absolut Termux, filter, traversal) ----------------------
def mkdeb(files, links=()):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as t:
        for name, data in files.items():
            ti = tarfile.TarInfo("./" + f.PREFIX + name); ti.size = len(data); ti.mode = 0o755; t.addfile(ti, io.BytesIO(data))
        for name, target in links:
            ti = tarfile.TarInfo("./" + f.PREFIX + name); ti.type = tarfile.SYMTYPE; ti.linkname = target; t.addfile(ti)
    d = lzma.compress(buf.getvalue())
    def member(n, b):
        h = (n + "/").ljust(16).encode() + b"0".ljust(12) + b"0".ljust(6) + b"0".ljust(6) + b"100644".ljust(8) + str(len(b)).ljust(10).encode() + b"`\n"
        return h + b + (b"\n" if len(b) & 1 else b"")
    return b"!<arch>\n" + member("debian-binary", b"2.0\n") + member("data.tar.xz", d)

stage = tempfile.mkdtemp()
paths = f.extract_deb(mkdeb({"lib/libreal.so.1.2": b"SO", "share/man/x": b"m", "include/h.h": b"h"},
                            links=[("lib/libreal.so.1", "libreal.so.1.2"),
                                   ("lib/libabs.so", "/" + f.PREFIX + "lib/libreal.so.1.2")]), stage)
check("extract_deb: filter share/include", not os.path.exists(stage + "/include") and not os.path.exists(stage + "/share"))
check("extract_deb: symlink relatif & absolut-Termux valid",
      os.path.realpath(stage + "/lib/libabs.so") == os.path.realpath(stage + "/lib/libreal.so.1.2")
      and os.path.realpath(stage + "/lib/libreal.so.1") == os.path.realpath(stage + "/lib/libreal.so.1.2"))
try:
    f.extract_deb(mkdeb({"lib/../../../../evil": b"x"}), stage); check("traversal harus ditolak", False)
except f.RuntimeError_:
    check("extract_deb menolak path traversal", True)

# ---- 5. fallback npm dari registry (fetch di-mock) --------------------------------------
tgz = io.BytesIO()
with tarfile.open(fileobj=tgz, mode="w:gz") as t:
    for rel, data in {"bin/npm-cli.js": b"//", "lib/cli.js": b"//", "package.json": b'{"version":"11.9.9"}',
                      "node_modules/@npmcli/arborist/package.json": b"{}"}.items():
        ti = tarfile.TarInfo("package/" + rel); ti.size = len(data); t.addfile(ti, io.BytesIO(data))
tgz = tgz.getvalue()
meta = {"version": "11.9.9", "dist": {"tarball": "https://x/npm.tgz", "integrity": "sha512-" + base64.b64encode(hashlib.sha512(tgz).digest()).decode()}}
orig = f.fetch
f.fetch = lambda url, retries=3: json.dumps(meta).encode() if url == f.NPM_REGISTRY else tgz
try:
    st2 = tempfile.mkdtemp()
    f.install_npm_from_registry(st2)
    check("fallback npm dari registry: semua berkas wajib ada",
          all(os.path.isfile(os.path.join(st2, r)) for r in f.REQUIRED_NPM_FILES))
    meta["dist"]["integrity"] = "sha512-" + base64.b64encode(hashlib.sha512(b"x").digest()).decode()
    try:
        f.install_npm_from_registry(tempfile.mkdtemp()); check("integritas salah harus gagal", False)
    except f.RuntimeError_:
        check("integritas sha512 npm yang salah ditolak", True)
finally:
    f.fetch = orig

print(f"SEMUA LULUS ({ok} pengecekan)")
