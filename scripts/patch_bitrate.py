"""
Patches HwCameraKit's HwMediaRecorder class (com/huawei/camerakit/impl/c.class):
  1. Optionally replaces the .class with a pre-compiled CBR replacement
  2. Replaces the video encoding bitrate (CONSTANT_Integer in constant pool)
  3. Optionally changes video encoder from H.264 (2) to H.265/HEVC (5)
  4. Replaces log strings to fingerprint the patched JAR (same-length edits)

Usage:
    py patch_bitrate.py <input_jar> <output_jar> <old_bitrate> <new_bitrate> [--h265] [--cbr-class <path>]
    py patch_bitrate.py pull/hwcamerakit.jar libs/hwcamerakit.jar 12000000 100000000
    py patch_bitrate.py pull/hwcamerakit.jar libs/hwcamerakit.jar 12000000 80000000 --h265
    py patch_bitrate.py pull/hwcamerakit.jar libs/hwcamerakit.jar 12000000 100000000 --cbr-class tmp/c.class
"""

import struct
import sys
import zipfile

TARGET_CLASS = "com/huawei/camerakit/impl/c.class"

# Same-length string replacements to fingerprint the patched JAR.
# Each tuple is (old_bytes, new_bytes). Lengths MUST match.
STRING_PATCHES = [
    (b"mediaRecorder prepare done!", b"mediaRecorder prepare d0ne!"),
]

# ---------- Java .class constant pool parser ----------

# Tag values
_CP_UTF8 = 1
_CP_INTEGER = 3
_CP_FLOAT = 4
_CP_LONG = 5
_CP_DOUBLE = 6
_CP_CLASS = 7
_CP_STRING = 8
_CP_FIELDREF = 9
_CP_METHODREF = 10
_CP_IF_METHODREF = 11
_CP_NAME_AND_TYPE = 12
_CP_METHOD_HANDLE = 15
_CP_METHOD_TYPE = 16
_CP_INVOKE_DYNAMIC = 18


def _parse_constant_pool(data):
    """Parse the constant pool from a .class file. Returns list of entries (1-based)."""
    off = 8  # skip magic (4 bytes) + version (4 bytes)
    count = struct.unpack_from(">H", data, off)[0]
    off += 2

    entries = [None]  # index 0 unused
    i = 1
    while i < count:
        tag = data[off]
        off += 1

        if tag == _CP_UTF8:
            length = struct.unpack_from(">H", data, off)[0]
            off += 2
            entries.append(("utf8", data[off : off + length]))
            off += length
        elif tag in (_CP_INTEGER, _CP_FLOAT):
            entries.append(("num4",))
            off += 4
        elif tag in (_CP_LONG, _CP_DOUBLE):
            entries.append(("num8",))
            off += 8
            entries.append(None)  # occupies two slots
            i += 1
        elif tag in (_CP_CLASS, _CP_STRING, _CP_METHOD_TYPE):
            entries.append(("ref1", struct.unpack_from(">H", data, off)[0]))
            off += 2
        elif tag in (_CP_FIELDREF, _CP_METHODREF, _CP_IF_METHODREF):
            cls_idx = struct.unpack_from(">H", data, off)[0]
            nat_idx = struct.unpack_from(">H", data, off + 2)[0]
            entries.append(("memberref", tag, cls_idx, nat_idx))
            off += 4
        elif tag == _CP_NAME_AND_TYPE:
            name_idx = struct.unpack_from(">H", data, off)[0]
            desc_idx = struct.unpack_from(">H", data, off + 2)[0]
            entries.append(("nat", name_idx, desc_idx))
            off += 4
        elif tag == _CP_METHOD_HANDLE:
            entries.append(("mh",))
            off += 3
        elif tag == _CP_INVOKE_DYNAMIC:
            entries.append(("indyn",))
            off += 4
        else:
            raise ValueError(f"Unknown CP tag {tag} at offset {off - 1}")
        i += 1

    return entries


def _find_methodref_index(entries, method_name_bytes):
    """Find the CP index of a MethodRef whose name matches the given bytes."""
    # Find Utf8 entries matching the method name
    name_idxs = {
        i
        for i, e in enumerate(entries)
        if e and e[0] == "utf8" and e[1] == method_name_bytes
    }
    if not name_idxs:
        return None

    # Find NameAndType entries referencing those names
    nat_idxs = {
        i
        for i, e in enumerate(entries)
        if e and e[0] == "nat" and e[1] in name_idxs
    }

    # Find MethodRef entries referencing those NameAndType entries
    for i, e in enumerate(entries):
        if e and e[0] == "memberref" and e[1] == _CP_METHODREF and e[3] in nat_idxs:
            return i
    return None


def _patch_iconst_before_invokevirtual(data, methodref_idx, old_val, new_val):
    """
    In the bytecode, find iconst_<old_val> immediately before
    invokevirtual #methodref_idx and replace with iconst_<new_val>.

    iconst_0..iconst_5 = opcodes 0x03..0x08
    invokevirtual      = opcode 0xB6 + 2-byte big-endian CP index
    """
    assert 0 <= old_val <= 5 and 0 <= new_val <= 5, "iconst only covers 0..5"
    old_op = 0x03 + old_val
    new_op = 0x03 + new_val
    invoke = b"\xb6" + struct.pack(">H", methodref_idx)

    data = bytearray(data)
    pos = 0
    while pos < len(data) - 3:
        if data[pos] == old_op and data[pos + 1 : pos + 4] == invoke:
            data[pos] = new_op
            return bytes(data), True
        pos += 1
    return bytes(data), False


# ---------- Main ----------


def main():
    # Parse args: positional args and flags (--h265, --cbr-class <path>)
    args = []
    flags = set()
    cbr_class_path = None
    argv = sys.argv[1:]
    i = 0
    while i < len(argv):
        a = argv[i]
        if a.lower() == "--cbr-class" and i + 1 < len(argv):
            cbr_class_path = argv[i + 1]
            i += 2
            continue
        elif a.startswith("--"):
            flags.add(a.lower())
        else:
            args.append(a)
        i += 1

    if len(args) != 4:
        print(f"Usage: {sys.argv[0]} <input_jar> <output_jar> <old_bitrate> <new_bitrate> [--h265] [--cbr-class <path>]")
        print(f"  --h265             Change encoder from H.264 to H.265/HEVC")
        print(f"  --cbr-class <path> Replace target .class with compiled CBR version")
        sys.exit(1)

    input_jar = args[0]
    output_jar = args[1]
    old_bitrate = int(args[2])
    new_bitrate = int(args[3])
    use_h265 = "--h265" in flags

    old_bytes = struct.pack(">i", old_bitrate)
    new_bytes = struct.pack(">i", new_bitrate)

    # Read all entries from the input JAR
    with zipfile.ZipFile(input_jar, "r") as zin:
        entries = {name: zin.read(name) for name in zin.namelist()}

    if TARGET_CLASS not in entries:
        print(f"ERROR: {TARGET_CLASS} not found in {input_jar}")
        sys.exit(1)

    data = bytearray(entries[TARGET_CLASS])

    # ── 0. Optionally replace entire .class with CBR version ──
    if cbr_class_path:
        with open(cbr_class_path, "rb") as f:
            data = bytearray(f.read())
        print(f"Replaced {TARGET_CLASS} with CBR class: {cbr_class_path}")

    # ── 1. Patch bitrate (CONSTANT_Integer in constant pool) ──
    marker = b"\x03" + old_bytes  # CP tag 0x03 = CONSTANT_Integer
    pos = data.find(marker)

    if pos < 0:
        print(f"ERROR: bitrate {old_bitrate} not found in {TARGET_CLASS}")
        sys.exit(1)

    data[pos + 1 : pos + 5] = new_bytes
    print(f"Patched bitrate: {old_bitrate} -> {new_bitrate}")

    # ── 2. Optionally patch encoder: H.264 (2) -> H.265/HEVC (5) ──
    if use_h265:
        cp = _parse_constant_pool(data)
        mref_idx = _find_methodref_index(cp, b"setVideoEncoder")
        if mref_idx is None:
            print("WARNING: setVideoEncoder not found in constant pool, skipping encoder patch")
        else:
            patched_bytes, ok = _patch_iconst_before_invokevirtual(data, mref_idx, 2, 5)
            data = bytearray(patched_bytes)
            if ok:
                print("Patched encoder: H.264 (2) -> H.265/HEVC (5)")
            else:
                print("WARNING: could not find iconst_2 + invokevirtual setVideoEncoder pattern")
    else:
        print("Encoder: H.264 (stock, no patch)")

    # ── 3. Apply fingerprint string patches ──
    for old_str, new_str in STRING_PATCHES:
        assert len(old_str) == len(new_str), f"Length mismatch: {old_str!r} vs {new_str!r}"
        spos = data.find(old_str)
        if spos < 0:
            print(f"WARNING: string {old_str!r} not found, skipping")
            continue
        data[spos : spos + len(old_str)] = new_str
        print(f"Patched string: {old_str.decode()!r} -> {new_str.decode()!r}")

    entries[TARGET_CLASS] = bytes(data)

    # Write patched JAR
    with zipfile.ZipFile(output_jar, "w", zipfile.ZIP_DEFLATED) as zout:
        for name, content in entries.items():
            zout.writestr(name, content)

    print(f"Saved: {output_jar}")


if __name__ == "__main__":
    main()
