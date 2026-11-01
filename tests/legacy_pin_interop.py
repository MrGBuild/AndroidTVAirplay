#!/usr/bin/env python3
"""Independent legacy AirPlay client against PinInteropHost (synthetic credentials).
Test-only dependencies: srptools and cryptography. Never sends real user credentials.
"""
import hashlib
import plistlib
import re
import socket
import sys
from srptools import SRPContext, SRPClientSession, constants
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat


class AppleContext(SRPContext):
    def get_common_session_key(self, secret):
        return self.hash(secret, bytes(4), as_bytes=True) + self.hash(secret, bytes([0, 0, 0, 1]), as_bytes=True)


def run(port, protocol, srp_password, expected):
    with socket.create_connection(("127.0.0.1", port), timeout=5) as sock:
        stream = sock.makefile("rb")
        seq = 0

        def post(fields, auth=None):
            nonlocal seq
            seq += 1
            body = plistlib.dumps(fields, fmt=plistlib.FMT_BINARY)
            headers = (f"POST /pair-setup-pin {protocol}\r\nCSeq: {seq}\r\n"
                       f"Content-Length: {len(body)}\r\nContent-Type: application/x-apple-binary-plist\r\n")
            if auth:
                headers += f"Authorization: {auth}\r\n"
            sock.sendall((headers + "\r\n").encode() + body)
            code = int(stream.readline().split()[1])
            response_headers = {}
            while (line := stream.readline().strip()):
                name, value = line.decode().split(":", 1)
                response_headers[name.lower()] = value.strip()
            response = stream.read(int(response_headers.get("content-length", 0)))
            return code, response_headers, plistlib.loads(response) if response else None

        start = {"method": "pin", "user": "AABBCCDDEEFF0011"}
        code, headers, _ = post(start)
        assert code == 401
        nonce = re.search(r'nonce="([^"]+)"', headers["www-authenticate"])[1]
        md5 = lambda value: hashlib.md5(value.encode()).hexdigest()
        response = md5(md5("AirPlay:raop:synthetic-test-password") + ":" + nonce + ":" + md5("POST:/pair-setup-pin"))
        auth = f'Digest username="AirPlay", realm="raop", nonce="{nonce}", uri="/pair-setup-pin", response="{response}"'
        code, _, reply = post(start, auth)
        assert code == 200
        context = AppleContext("AABBCCDDEEFF0011", srp_password, prime=constants.PRIME_2048, generator=constants.PRIME_2048_GEN)
        client = SRPClientSession(context)
        client.process(reply["pk"].hex(), reply["salt"].hex())
        text = lambda value: value.decode() if isinstance(value, bytes) else value
        code, _, reply = post({"pk": bytes.fromhex(text(client.public)), "proof": bytes.fromhex(text(client.key_proof))})
        assert code == expected, code
        if expected != 200:
            return
        assert client.verify_proof(reply["proof"].hex())
        key = bytes.fromhex(text(client.key))
        aes_key = hashlib.sha512(b"Pair-Setup-AES-Key" + key).digest()[:16]
        iv = bytearray(hashlib.sha512(b"Pair-Setup-AES-IV" + key).digest()[:16])
        iv[-1] = (iv[-1] + 1) % 256
        encrypted = AESGCM(aes_key).encrypt(bytes(iv), bytes([0x23]) * 32, None)
        code, _, reply = post({"epk": encrypted[:32], "authTag": encrypted[32:]})
        assert code == 200
        iv[-1] = (iv[-1] + 1) % 256
        receiver_key = AESGCM(aes_key).decrypt(bytes(iv), reply["epk"] + reply["authTag"], None)
        expected_key = Ed25519PrivateKey.from_private_bytes(bytes(32)).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        assert receiver_key == expected_key


if __name__ == "__main__":
    for version in ("RTSP/1.0", "HTTP/1.1"):
        run(int(sys.argv[1]), version, "synthetic-test-password", 200)
        run(int(sys.argv[1]), version, "incorrect-password", 403)
    print("Independent Python client: RTSP + HTTP SRP proofs, AES-GCM identity exchange and wrong-password rejection passed")
