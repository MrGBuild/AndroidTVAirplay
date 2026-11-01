#!/usr/bin/env python3
"""Validate the shipped ALAC decoder with exact samples and an actual compressed frame."""
import ctypes as C
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

lib=C.CDLL(sys.argv[1])
lib.tv_alac_create.argtypes=[C.c_int,C.c_int];lib.tv_alac_create.restype=C.c_void_p
lib.tv_alac_decode.argtypes=[C.c_void_p,C.c_void_p,C.c_int,C.c_void_p,C.c_int];lib.tv_alac_decode.restype=C.c_int
lib.tv_alac_close.argtypes=[C.c_void_p]

def decode(frame,frames):
    handle=lib.tv_alac_create(44100,frames);assert handle
    try:
        source=C.create_string_buffer(frame);output=C.create_string_buffer(frames*4)
        count=lib.tv_alac_decode(handle,source,len(frame),output,len(output))
        assert count>0, f"decoder rejected a valid ALAC frame: {count}"
        return output.raw[:count]
    finally:lib.tv_alac_close(handle)

bits="001"+"0"*16+"0"+"00"+"1"+(f"{1234:016b}{(-1234)&65535:016b}")*352+"111"
bits+="0"*((-len(bits))%8)
frame=int(bits,2).to_bytes(len(bits)//8,"big")
assert decode(frame,352)==struct.pack("<hh",1234,-1234)*352
assert not lib.tv_alac_create(8000,352)
assert not lib.tv_alac_create(44100,100000)
with tempfile.TemporaryDirectory() as directory:
    file=Path(directory)/"fixture.caf"
    subprocess.run(["ffmpeg","-v","error","-f","lavfi","-i","sine=frequency=440:sample_rate=44100:duration=0.03","-ac","2","-sample_fmt","s16p","-c:a","alac",str(file)],check=True)
    metadata=json.loads(subprocess.check_output(["ffprobe","-v","error","-show_packets","-of","json",str(file)]))
    assert len(metadata['packets'])==1
    packet=metadata['packets'][0]
    with file.open('rb') as source:source.seek(int(packet['pos']));frame=source.read(int(packet['size']))
    expected=subprocess.check_output(["ffmpeg","-v","error","-i",str(file),"-f","s16le","-acodec","pcm_s16le","-"])
    assert decode(frame,4096)==expected,"compressed ALAC samples differ from reference"
print("PASS: shipped ALAC decoder matches exact stereo samples and reference compressed-audio decode")
