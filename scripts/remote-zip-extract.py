#!/usr/bin/env python3
"""从远程 ZIP（支持 HTTP Range）里只取需要的成员，不下整个包。

用途：Unihertz 官方固件包 3.5GB，但解 profile 只需要 boot.img / vendor_boot.img。
服务器支持 Range 时，先读中央目录，再按成员逐段拉取。

用法：
    ./scripts/remote-zip-extract.py <zip-url> <out-dir> <member> [member...]
    ./scripts/remote-zip-extract.py <zip-url> --list

示例：
    ./scripts/remote-zip-extract.py "$URL" artifacts/fw-tee-2025111915 boot.img vendor_boot.img
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import urllib.parse
import urllib.request
import zlib

EOCD_SIG = b"PK\x05\x06"
CD_SIG = b"PK\x01\x02"
LFH_SIG = b"PK\x03\x04"
ZIP64_EOCD_SIG = b"PK\x06\x06"
ZIP64_LOC_SIG = b"PK\x06\x07"

UA = "titan2-remote-zip/1.0"


def http_get(url: str, start: int | None = None, end: int | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    if start is not None:
        rng = f"bytes={start}-" if end is None else f"bytes={start}-{end}"
        req.add_header("Range", rng)
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read()


def content_length(url: str) -> int:
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return int(resp.headers["Content-Length"])


class Entry:
    __slots__ = ("name", "method", "comp_size", "uncomp_size", "local_off")

    def __init__(self, name, method, comp_size, uncomp_size, local_off):
        self.name = name
        self.method = method
        self.comp_size = comp_size
        self.uncomp_size = uncomp_size
        self.local_off = local_off


def parse_central_directory(cd: bytes) -> list[Entry]:
    entries: list[Entry] = []
    off = 0
    while off + 46 <= len(cd):
        if cd[off : off + 4] != CD_SIG:
            break
        (
            _ver_made,
            _ver_need,
            flags,
            method,
            _mtime,
            _mdate,
            _crc,
            comp_size,
            uncomp_size,
            name_len,
            extra_len,
            comment_len,
            _disk,
            _int_attr,
            _ext_attr,
            local_off,
        ) = struct.unpack("<HHHHHHIIIHHHHHII", cd[off + 4 : off + 46])
        name = cd[off + 46 : off + 46 + name_len].decode("utf-8", "replace")
        extra = cd[off + 46 + name_len : off + 46 + name_len + extra_len]

        # ZIP64 扩展字段
        if comp_size == 0xFFFFFFFF or uncomp_size == 0xFFFFFFFF or local_off == 0xFFFFFFFF:
            eo = 0
            while eo + 4 <= len(extra):
                hid, hsize = struct.unpack("<HH", extra[eo : eo + 4])
                if hid == 0x0001:
                    p = eo + 4
                    if uncomp_size == 0xFFFFFFFF:
                        uncomp_size = struct.unpack("<Q", extra[p : p + 8])[0]
                        p += 8
                    if comp_size == 0xFFFFFFFF:
                        comp_size = struct.unpack("<Q", extra[p : p + 8])[0]
                        p += 8
                    if local_off == 0xFFFFFFFF:
                        local_off = struct.unpack("<Q", extra[p : p + 8])[0]
                        p += 8
                    break
                eo += 4 + hsize
        entries.append(Entry(name, method, comp_size, uncomp_size, local_off))
        off += 46 + name_len + extra_len + comment_len
    return entries


def read_entries(url: str, total: int) -> list[Entry]:
    tail_len = min(total, 1 << 16)
    tail = http_get(url, total - tail_len, total - 1)
    idx = tail.rfind(EOCD_SIG)
    if idx < 0:
        raise SystemExit("不是有效的 ZIP（找不到 EOCD）")
    (_disk, _cd_disk, _n_disk, n_entries, cd_size, cd_off, _clen) = struct.unpack(
        "<HHHHIIH", tail[idx + 4 : idx + 22]
    )

    # ZIP64
    z64 = tail.rfind(ZIP64_EOCD_SIG)
    if z64 >= 0:
        n_entries, cd_size, cd_off = struct.unpack("<QQQ", tail[z64 + 32 : z64 + 56])
    elif tail_len >= total:
        raise SystemExit("需要 ZIP64 支持")

    cd = http_get(url, cd_off, cd_off + cd_size - 1)
    entries = parse_central_directory(cd)
    if len(entries) != n_entries:
        print(f"警告：中央目录解析出 {len(entries)} 项，EOCD 声称 {n_entries} 项", file=sys.stderr)
    return entries


def extract_member(url: str, entry: Entry, out_path: str) -> None:
    lfh = http_get(url, entry.local_off, entry.local_off + 29)
    if lfh[:4] != LFH_SIG:
        raise SystemExit(f"{entry.name}: 局部头签名错误")
    name_len, extra_len = struct.unpack("<HH", lfh[26:30])
    data_off = entry.local_off + 30 + name_len + extra_len
    raw = http_get(url, data_off, data_off + entry.comp_size - 1)

    if entry.method == 0:
        data = raw
    elif entry.method == 8:
        data = zlib.decompress(raw, -zlib.MAX_WBITS)
    else:
        raise SystemExit(f"{entry.name}: 不支持的压缩方法 {entry.method}")

    if len(data) != entry.uncomp_size:
        print(
            f"警告：{entry.name} 解压后 {len(data)} 字节，预期 {entry.uncomp_size}",
            file=sys.stderr,
        )
    with open(out_path, "wb") as fh:
        fh.write(data)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("out_dir", nargs="?")
    ap.add_argument("members", nargs="*")
    ap.add_argument("--list", action="store_true", help="只列目录")
    args = ap.parse_args()

    total = content_length(args.url)
    print(f"远端大小: {total} 字节", file=sys.stderr)
    entries = read_entries(args.url, total)

    if args.list:
        for e in entries:
            print(f"{e.comp_size:>12} {e.uncomp_size:>12} {e.name}")
        return 0

    if not args.out_dir or not args.members:
        ap.error("需要 <out-dir> 和至少一个成员名（或用 --list）")

    os.makedirs(args.out_dir, exist_ok=True)
    wanted = {m: None for m in args.members}
    for e in entries:
        base = e.name.rsplit("/", 1)[-1]
        if e.name in wanted and wanted[e.name] is None:
            wanted[e.name] = e
        elif base in wanted and wanted[base] is None:
            wanted[base] = e

    missing = [m for m, e in wanted.items() if e is None]
    if missing:
        print(f"未找到成员: {', '.join(missing)}", file=sys.stderr)
        print("可选:", file=sys.stderr)
        for e in entries:
            print("  " + e.name, file=sys.stderr)
        return 1

    for name, e in wanted.items():
        out = os.path.join(args.out_dir, name.rsplit("/", 1)[-1])
        print(f"提取 {e.name} -> {out}", file=sys.stderr)
        extract_member(args.url, e, out)
        print(f"  完成: {os.path.getsize(out)} 字节", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
