"""Writes ZstdTest's fixtures with the reference zstd (python-zstandard 0.23.0, libzstd 1.5.6).

Run from this folder: python3 fixtures.py. It writes each .zst and expected.txt, which lists
what each decompresses to by length and SHA-256, so the originals needn't be kept.
"""

import hashlib
import io
import json
import random
import struct

import zstandard


def text(size):
    """Log lines of JSON, as an API answer reads: repetitive, but not one thing over and over."""
    rng = random.Random(1)
    words = ["order", "menu", "basket", "store", "price", "delivery", "token", "item", "user"]
    out = []
    total = 0
    while total < size:
        line = json.dumps(
            {
                "id": rng.randrange(10**9),
                "event": rng.choice(words) + "." + rng.choice(words),
                "tags": rng.sample(words, rng.randrange(1, 5)),
                "amount": round(rng.random() * 100, 2),
                "note": " ".join(rng.choice(words) for _ in range(rng.randrange(0, 12))),
            }
        )
        out.append(line)
        total += len(line) + 1
    return ("\n".join(out) + "\n").encode()


def compress(data, **options):
    return zstandard.ZstdCompressor(**options).compress(data)


def records(count):
    """Three kinds of fixed-size record in turn, each mostly its kind's last one: matches at three
    distances that keep trading places, which is what the repeat offsets are for."""
    rng = random.Random(4)
    last = {kind: rng.randbytes(size) for kind, size in [(0, 37), (1, 53), (2, 71)]}
    out = bytearray()
    for _ in range(count):
        kind = rng.choice([0, 1, 2, 0, 1, 0])
        record = bytearray(last[kind])
        for _ in range(rng.randrange(0, 3)):
            record[rng.randrange(len(record))] = rng.randrange(256)
        last[kind] = bytes(record)
        out += record
    return bytes(out)


source = text(400_000)
noise = random.Random(2).randbytes(20_000)
mixed = b"".join(random.Random(3).randbytes(5_000) * 2 for _ in range(20))
small = b"".join(random.Random(5).randbytes(1_000) * 2 for _ in range(60))

cases = {
    "text-1": (source, compress(source, level=1)),
    "text-3": (source, compress(source, level=3, write_checksum=True)),
    "text-19": (source, compress(source, level=19)),
    "noise": (noise, compress(noise, level=3)),
    "mixed": (mixed, compress(mixed, level=3)),
    "records": (records(6_000), compress(records(6_000), level=19)),
    "records-3": (records(6_000), compress(records(6_000), level=3)),
    "small-literals": (small, compress(small, level=3)),
    "rle": (b"a" * 300_000, compress(b"a" * 300_000, level=3)),
    "empty": (b"", compress(b"", level=3)),
    "short": (b"hello, hello, hello!", compress(b"hello, hello, hello!", level=3)),
    "frames": (
        b"first frame, then second frame",
        compress(b"first frame, ", level=3)
        + struct.pack("<II", 0x184D2A53, 5)
        + b"skip!"
        + compress(b"then second frame", level=3),
    ),
}

writer = zstandard.ZstdCompressor(level=3, write_content_size=False, write_checksum=True)
chunks = writer.compressobj()
cases["streamed"] = (source, chunks.compress(source) + chunks.flush())

with open("expected.txt", "w") as listing:
    for name, (original, compressed) in cases.items():
        reader = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(compressed), read_across_frames=True)
        assert reader.read() == original, name
        with open(name + ".zst", "wb") as out:
            out.write(compressed)
        listing.write(f"{name} {len(original)} {hashlib.sha256(original).hexdigest()}\n")
