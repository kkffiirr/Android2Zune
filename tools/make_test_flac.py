"""Writes small valid FLAC files (verbatim subframes) with Vorbis-comment tags and a cover picture.

Used to test the on-phone FLAC -> MP3 conversion without anyone's music:  python tools/make_test_flac.py OUTDIR
Creates test_44k.flac (44.1 kHz stereo) and test_96k.flac (96 kHz stereo, exercises LAME's resampling).
"""
import io
import math
import os
import struct
import sys

from PIL import Image

BLOCK = 4096


def crc8(data):
    c = 0
    for b in data:
        c ^= b
        for _ in range(8):
            c = ((c << 1) ^ 0x07) & 0xFF if c & 0x80 else (c << 1) & 0xFF
    return c


def crc16(data):
    c = 0
    for b in data:
        c ^= b << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x8005) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
    return c


RATE_CODE = {88200: 1, 176400: 2, 192000: 3, 8000: 4, 16000: 5, 22050: 6, 24000: 7, 32000: 8, 44100: 9, 48000: 10, 96000: 11}


def meta_block(kind, payload, last=False):
    return bytes([(0x80 if last else 0) | kind]) + len(payload).to_bytes(3, "big") + payload


def cover_jpeg():
    img = Image.new("RGB", (64, 64), (236, 0, 140))
    for x in range(64):
        for y in range(64):
            if x > y:
                img.putpixel((x, y), (247, 97, 31))
    buf = io.BytesIO()
    img.save(buf, "JPEG")
    return buf.getvalue()


def picture_block(jpeg):
    mime = b"image/jpeg"
    return (struct.pack(">II", 3, len(mime)) + mime + struct.pack(">I", 0) +
            struct.pack(">IIII", 64, 64, 24, 0) + struct.pack(">I", len(jpeg)) + jpeg)


def comment_block(tags):
    vendor = b"make_test_flac"
    out = struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", len(tags))
    for k, v in tags:
        s = f"{k}={v}".encode("utf-8")
        out += struct.pack("<I", len(s)) + s
    return out


def write_flac(path, rate, frames, tags):
    total = frames * BLOCK
    streaminfo = struct.pack(">HH", BLOCK, BLOCK) + (0).to_bytes(3, "big") + (0).to_bytes(3, "big")
    streaminfo += ((rate << 44) | (1 << 41) | (15 << 36) | total).to_bytes(8, "big") + bytes(16)
    data = b"fLaC" + meta_block(0, streaminfo) + meta_block(6, picture_block(cover_jpeg())) + meta_block(4, comment_block(tags), last=True)
    for n in range(frames):
        hdr = bytes([0xFF, 0xF8, (12 << 4) | RATE_CODE[rate], (1 << 4) | (4 << 1)]) + bytes([n])   # n < 128: one UTF-8 byte
        hdr += bytes([crc8(hdr)])
        body = b""
        for ch in range(2):
            freq, amp = (440.0, 0.4) if ch == 0 else (660.0, 0.4)
            samples = [int(amp * 32767 * math.sin(2 * math.pi * freq * (n * BLOCK + i) / rate)) for i in range(BLOCK)]
            body += b"\x02" + struct.pack(">%dh" % BLOCK, *samples)              # verbatim subframe
        frame = hdr + body
        data += frame + struct.pack(">H", crc16(frame))
    with open(path, "wb") as f:
        f.write(data)
    print(path, len(data), "bytes,", total / rate, "s")


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    tags = [("TITLE", "Test Tone Zażółć"), ("ARTIST", "Zune Android Test"), ("ALBUM", "Converter Checks"),
            ("TRACKNUMBER", "3/12"), ("DATE", "2026"), ("GENRE", "Test")]
    write_flac(os.path.join(out, "test_44k.flac"), 44100, 33, tags)
    write_flac(os.path.join(out, "test_96k.flac"), 96000, 70, tags)
