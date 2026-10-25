#!/usr/bin/env python3
"""Exercise native RAOP over RTSP + encrypted UDP ALAC; no Apple device required.

Usage: python3 tests/native_audio_smoke.py /path/to/libshairplay.so
Requires Python 3, OpenSSL and a host build of app/src/main/cpp.
This does not exercise Android JNI, AudioTrack, discovery or Apple interoperability.
"""
import base64
import ctypes as C
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import threading


def main():
    root = Path(__file__).resolve().parents[1]
    library = C.CDLL(sys.argv[1])
    init_t = C.CFUNCTYPE(C.c_void_p, C.c_void_p, C.c_int, C.c_int, C.c_int)
    process_t = C.CFUNCTYPE(None, C.c_void_p, C.c_void_p, C.c_void_p, C.c_int)
    destroy_t = C.CFUNCTYPE(None, C.c_void_p, C.c_void_p)
    volume_t = C.CFUNCTYPE(None, C.c_void_p, C.c_void_p, C.c_float)
    received = threading.Event()
    destroyed = threading.Event()
    volume_changed = threading.Event()
    frames, formats, volumes = [], [], []

    @init_t
    def init(_, bits, channels, rate):
        formats.append((bits, channels, rate))
        return 1

    @process_t
    def process(_, session, buffer, size):
        frames.append(C.string_at(buffer, size))
        received.set()

    @destroy_t
    def destroy(_, session):
        destroyed.set()

    @volume_t
    def volume(_, session, value):
        volumes.append(value)
        volume_changed.set()

    class Callbacks(C.Structure):
        _fields_ = [("cls", C.c_void_p), ("init", init_t), ("process", process_t),
                    ("destroy", destroy_t), ("flush", C.c_void_p), ("volume", volume_t),
                    ("metadata", C.c_void_p), ("art", C.c_void_p),
                    ("remote", C.c_void_p), ("progress", C.c_void_p)]

    cb = Callbacks(None, init, process, destroy, None, volume, None, None, None, None)
    library.raop_init.argtypes = [C.c_int, C.POINTER(Callbacks), C.c_char_p, C.POINTER(C.c_int)]
    library.raop_init.restype = C.c_void_p
    library.raop_start.argtypes = [C.c_void_p, C.POINTER(C.c_ushort), C.c_char_p, C.c_int, C.c_char_p]
    library.raop_set_log_level.argtypes = [C.c_void_p, C.c_int]
    library.raop_destroy.argtypes = [C.c_void_p]
    key_file = root / "app/src/main/assets/airport.key"
    error, port = C.c_int(), C.c_ushort(0)
    raop = library.raop_init(1, C.byref(cb), key_file.read_bytes(), C.byref(error))
    assert raop, "native receiver initialization failed"
    library.raop_set_log_level(raop, 0)
    try:
        assert library.raop_start(raop, C.byref(port), bytes.fromhex("021122334455"), 6, None) >= 0
        with socket.create_connection(("127.0.0.1", port.value), timeout=3) as connection:
            stream = connection.makefile("rb")
            seq = 0

            def rtsp(method, body=b"", extra=""):
                nonlocal seq
                seq += 1
                connection.sendall((f"{method} rtsp://127.0.0.1/stream RTSP/1.0\r\nCSeq: {seq}\r\n"
                                    f"Content-Length: {len(body)}\r\n{extra}\r\n").encode() + body)
                status = stream.readline().decode()
                assert "200 OK" in status, status
                headers = {}
                while True:
                    line = stream.readline()
                    assert line, "unexpected disconnect"
                    if line == b"\r\n":
                        break
                    k, v = line.decode().split(":", 1)
                    headers[k.lower()] = v.strip()
                length = int(headers.get("content-length", "0"))
                if length:
                    assert len(stream.read(length)) == length
                return headers

            challenge = base64.b64encode(bytes(range(16))).decode()
            response = rtsp("OPTIONS", extra=f"Apple-Challenge: {challenge}\r\n")
            assert "ANNOUNCE" in response.get("public", "")
            assert response.get("apple-response"), "RSA challenge response missing"
            aes_key, iv = bytes(range(16)), bytes(range(16, 32))
            encrypted_key = subprocess.run(["openssl", "pkeyutl", "-encrypt", "-inkey", str(key_file),
                                            "-pkeyopt", "rsa_padding_mode:oaep"], input=aes_key,
                                           check=True, capture_output=True).stdout
            sdp = ("v=0\r\no=iTunes 0 0 IN IP4 127.0.0.1\r\ns=iTunes\r\nc=IN IP4 127.0.0.1\r\n"
                   "t=0 0\r\nm=audio 0 RTP/AVP 96\r\na=rtpmap:96 AppleLossless\r\n"
                   "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n"
                   f"a=rsaaeskey:{base64.b64encode(encrypted_key).decode()}\r\n"
                   f"a=aesiv:{base64.b64encode(iv).decode()}\r\n").encode()
            rtsp("ANNOUNCE", sdp, "Content-Type: application/sdp\r\n")
            setup = rtsp("SETUP", extra="Transport: RTP/AVP/UDP;unicast;mode=record;control_port=0;timing_port=0\r\n")
            audio_port = int(re.search(r"server_port=(\d+)", setup["transport"]).group(1))
            rtsp("RECORD", extra="Session: DEADBEEF\r\n")
            # Valid ALAC stereo frame using its uncompressed-sample mode, 352 samples.
            bits = "001" + "0" * 16 + "0" + "00" + "1"
            bits += (f"{1234:016b}{(-1234) & 65535:016b}") * 352
            bits += "0" * ((-len(bits)) % 8)
            frame = int(bits, 2).to_bytes(len(bits) // 8, "big")
            encrypted_length = len(frame) // 16 * 16
            encrypted = subprocess.run(["openssl", "enc", "-aes-128-cbc", "-nopad", "-K", aes_key.hex(), "-iv", iv.hex()],
                                       input=frame[:encrypted_length], check=True, capture_output=True).stdout
            packet = struct.pack("!BBHII", 0x80, 0x60, 1, 0, 1) + encrypted + frame[encrypted_length:]
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                udp.sendto(packet, ("127.0.0.1", audio_port))
            assert received.wait(3), "no decoded PCM callback"
            assert formats == [(16, 2, 44100)], formats
            assert frames[0] == struct.pack("<hh", 1234, -1234) * 352, "decoded samples differ"
            rtsp("SET_PARAMETER", b"volume: -12.0\r\n", "Content-Type: text/parameters\r\n")
            assert volume_changed.wait(3) and volumes[-1] == -12, "volume callback missing"
            rtsp("TEARDOWN", extra="Session: DEADBEEF\r\n")
            assert destroyed.wait(3), "audio teardown callback missing"
        print("PASS: RTSP, RSA challenge, encrypted ALAC -> 352 exact stereo PCM samples, volume, teardown")
    finally:
        library.raop_destroy(raop)


if __name__ == "__main__":
    main()
