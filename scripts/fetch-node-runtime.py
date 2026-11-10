#!/usr/bin/env python3
"""
Menyiapkan runtime Node.js + npm untuk Android (arm64, bionic) sebagai aset APK.

Sumber: repositori paket Termux (https://packages.termux.dev), yang menyediakan build Node.js
untuk Android/bionic. Skrip ini:
  1. mengunduh indeks `Packages` (aarch64),
  2. me-resolve dependency paket (nodejs-lts -> libc++, openssl, icu, ... ),
  3. memverifikasi SHA256 setiap .deb,
  4. mengekstrak bin/ dan lib/ ke satu direktori (prefix runtime),
  5. men-dereference symlink dan menulis `node-runtime.zip`.

Di sisi aplikasi (NodeRuntime.kt) isi ZIP diekstrak ke filesDir/runtime/usr dan dijalankan dengan
LD_LIBRARY_PATH=<usr>/lib. Aplikasi memverifikasi dengan `node --version` sebelum dianggap siap.

Pemakaian:
  python3 scripts/fetch-node-runtime.py --out runtime-bundle/assets/node-runtime.zip
  python3 scripts/fetch-node-runtime.py --package nodejs   # versi Node terbaru (bukan LTS)
"""
import argparse
import gzip
import hashlib
import io
import lzma
import os
import shutil
import struct
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

REPO = "https://packages.termux.dev/apt/termux-main"
INDEX = REPO + "/dists/stable/main/binary-aarch64/Packages"
PREFIX = "data/data/com.termux/files/usr/"

# Paket Termux yang tidak diperlukan (alat shell/pengelola paket, bukan dependensi runtime Node).
SKIP = {
    "termux-tools", "termux-exec", "termux-keyring", "termux-licenses", "termux-core", "termux-am",
    "termux-am-socket", "apt", "dpkg", "bash", "dash", "coreutils", "busybox", "util-linux",
    "ca-certificates", "resolv-conf", "gdbm", "libandroid-glob", "libandroid-posix-semaphore",
}
# Direktori/berkas dalam prefix yang tidak dibawa ke APK (menghemat puluhan MB).
SKIP_PARTS = ("/share/man/", "/share/doc/", "/include/", "/lib/pkgconfig/", "/lib/cmake/",
              "/node_modules/npm/docs/", "/node_modules/npm/man/", "/node_modules/corepack/")
KEEP_TOP = ("bin/", "lib/", "etc/")


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def fetch(url, retries=3):
    last = None
    for i in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=120) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            log(f"  retry {i + 1}/{retries} {url}: {e}")
    raise SystemExit(f"Gagal mengunduh {url}: {last}")


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
    names = []
    for part in s.split(","):
        first = part.split("|")[0].strip()
        name = first.split("(")[0].strip()
        if name:
            names.append(name)
    return names


def resolve(roots, index):
    order, seen, stack = [], set(), list(roots)
    while stack:
        n = stack.pop(0)
        if n in seen or n in SKIP:
            continue
        if n not in index:
            log(f"  (dependency tidak ada di indeks, dilewati: {n})")
            continue
        seen.add(n)
        order.append(n)
        stack.extend(parse_depends(index[n].get("Depends", "")))
        stack.extend(parse_depends(index[n].get("Pre-Depends", "")))
    return order


def read_ar(data):
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("bukan arsip ar/deb")
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
            raise SystemExit(f"Format {name} belum didukung (butuh xz/gz)")
    raise SystemExit("data.tar.* tidak ditemukan di .deb")


def extract_deb(blob, stage):
    tf = open_data_tar(read_ar(blob))
    n = 0
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
        # Perlindungan path traversal di dalam arsip
        if not os.path.realpath(dest).startswith(os.path.realpath(stage) + os.sep):
            raise SystemExit(f"Entri berbahaya: {m.name}")
        if m.isdir():
            os.makedirs(dest, exist_ok=True)
        elif m.issym():
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            if os.path.lexists(dest):
                os.remove(dest)
            os.symlink(m.linkname, dest)
            n += 1
        elif m.islnk():
            src = os.path.join(stage, m.linkname[len(PREFIX):] if m.linkname.startswith(PREFIX) else m.linkname)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            if os.path.exists(src):
                shutil.copy2(src, dest)
                n += 1
        elif m.isfile():
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(tf.extractfile(m).read())
            os.chmod(dest, m.mode | 0o200)
            n += 1
    return n


def check_elf_aarch64(path):
    with open(path, "rb") as f:
        h = f.read(20)
    if h[:4] != b"\x7fELF":
        raise SystemExit(f"{path} bukan ELF")
    machine = struct.unpack("<H", h[18:20])[0]
    if machine != 183:  # EM_AARCH64
        raise SystemExit(f"{path} bukan arsitektur AArch64 (e_machine={machine})")


def write_zip(stage, out):
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    stage_real = os.path.realpath(stage)
    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
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
                    z.writestr(info, f.read())
                count += 1
    return count


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="runtime-bundle/assets/node-runtime.zip")
    ap.add_argument("--package", default="nodejs-lts", help="nodejs-lts (default) atau nodejs")
    args = ap.parse_args()

    log(f"Mengunduh indeks: {INDEX}")
    index = parse_packages(fetch(INDEX).decode("utf-8", "replace"))
    if args.package not in index:
        raise SystemExit(f"Paket {args.package} tidak ada di indeks Termux")
    pkgs = resolve([args.package], index)
    log("Paket yang akan dipasang: " + ", ".join(pkgs))

    stage = tempfile.mkdtemp(prefix="node-runtime-")
    try:
        for name in pkgs:
            meta = index[name]
            url = f"{REPO}/{meta['Filename']}"
            log(f"- {name} {meta.get('Version', '')}")
            blob = fetch(url)
            want = meta.get("SHA256")
            if want and hashlib.sha256(blob).hexdigest() != want:
                raise SystemExit(f"SHA256 tidak cocok untuk {name}")
            extract_deb(blob, stage)

        node = os.path.join(stage, "bin", "node")
        npm = os.path.join(stage, "lib", "node_modules", "npm", "bin", "npm-cli.js")
        if not os.path.isfile(node):
            raise SystemExit("bin/node tidak ditemukan setelah ekstraksi")
        if not os.path.isfile(npm):
            raise SystemExit("npm-cli.js tidak ditemukan; gunakan paket yang menyertakan npm")
        check_elf_aarch64(os.path.realpath(node))
        n = write_zip(stage, args.out)
        size = os.path.getsize(args.out) / 1048576
        log(f"OK: {args.out} ({n} file, {size:.1f} MB)")
    finally:
        shutil.rmtree(stage, ignore_errors=True)


if __name__ == "__main__":
    main()
