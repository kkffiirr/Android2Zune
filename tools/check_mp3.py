"""Minimal MP3 inspector used to verify converter output: python tools/check_mp3.py file.mp3
Walks MPEG audio frames (rate, channels, duration, VBR header) and lists ID3v2 frames."""
import struct
import sys

BITRATES = {  # (version, layer 3) -> kbps table; version bit: 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
    3: [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0],
    2: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0],
    0: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0],
}
RATES = {3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000]}


def syncsafe(b):
    return (b[0] << 21) | (b[1] << 14) | (b[2] << 7) | b[3]


def id3(data):
    out = {}
    if data[:3] != b"ID3":
        return out, 0
    ver = data[3]
    size = syncsafe(data[6:10])
    pos = 10
    while pos + 10 <= 10 + size:
        fid = data[pos:pos + 4].decode("latin1")
        if not fid.strip("\x00"):
            break
        n = struct.unpack(">I", data[pos + 4:pos + 8])[0] if ver == 3 else syncsafe(data[pos + 4:pos + 8])
        body = data[pos + 10:pos + 10 + n]
        if fid.startswith("T"):
            enc = body[0]
            txt = body[1:].decode({0: "latin1", 1: "utf-16", 2: "utf-16-be", 3: "utf-8"}[enc], "replace").strip("\x00")
            out[fid] = txt
        elif fid == "APIC":
            out[fid] = f"{n} bytes, image starts {body[body.find(b'\xff\xd8'):][:2].hex() if b'\xff\xd8' in body else 'n/a'}"
        pos += 10 + n
    return out, 10 + size, ver


def frames(data, start):
    pos, count, rate, ch, vbr, first = start, 0, 0, 0, None, None
    total_samples = 0
    while pos + 4 <= len(data):
        h = struct.unpack(">I", data[pos:pos + 4])[0]
        if (h >> 21) != 0x7FF:
            pos += 1
            continue
        ver, layer = (h >> 19) & 3, (h >> 17) & 3
        if ver == 1 or layer != 1:
            pos += 1
            continue
        br = BITRATES[ver][(h >> 12) & 15] * 1000
        sr = RATES[ver][(h >> 10) & 3]
        pad = (h >> 9) & 1
        if br == 0:
            pos += 1
            continue
        spf = 1152 if ver == 3 else 576
        length = (144 if ver == 3 else 72) * br // sr + pad
        mode = (h >> 6) & 3
        if first is None:
            first = (ver, br, sr, mode)
            side = 32 if (ver == 3 and mode != 3) else 17 if ver == 3 else 17 if mode != 3 else 9
            tag = data[pos + 4 + side: pos + 4 + side + 4]
            if tag in (b"Xing", b"Info"):
                vbr = tag.decode()
                pos += length
                continue                       # the VBR header frame holds no audio
        rate, ch = sr, 1 if mode == 3 else 2
        count += 1
        total_samples += spf
        pos += length
    return count, rate, ch, vbr, total_samples, first


if __name__ == "__main__":
    d = open(sys.argv[1], "rb").read()
    tags, start, *_ = id3(d)
    count, rate, ch, vbr, samples, first = frames(d, start)
    print(f"{sys.argv[1]}: {len(d)} bytes")
    print(f"  audio frames={count} rate={rate} channels={ch} duration={samples / rate:.2f}s vbr_header={vbr}")
    print(f"  avg bitrate={len(d) * 8 / (samples / rate) / 1000:.0f} kbps")
    for k, v in tags.items():
        print(f"  ID3 {k}: {v}")
