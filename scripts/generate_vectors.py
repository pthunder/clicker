"""Generate test vectors for Clicker's Kotlin OPACK and SRP using pyatv and srptools."""
import binascii
import hashlib

from pyatv.support import opack
from srptools import SRPClientSession, SRPContext, SRPServerSession, constants

msg = {
    "_i": "_systemInfo",
    "_t": 2,
    "_c": {
        "_bf": 0, "_cf": 512, "_clFl": 128, "_i": "a1b2c3d4e5f6",
        "_idsID": b"0123456789abcdef0123456789abcdef0123",
        "_sv": "170.18", "_sid": 4000000000, "f": 1000.0, "t": True, "n": None,
        "long": "x" * 40, "list": [1, "two", b"\x03"],
    },
    "_x": 54321,
}
print("OPACK", opack.pack(msg).hex())

pin = "1234"
salt = bytes.fromhex("00a1b2c3d4e5f60718293a4b5c6d7e8f")  # leading zero on purpose
client_private = "11" * 32
server_private = "22" * 32

ctx = SRPContext("Pair-Setup", pin, prime=constants.PRIME_3072, generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
verifier = ctx.get_common_password_verifier(ctx.get_common_password_hash(salt))
server_public = ctx.get_server_public(password_verifier=verifier, server_private=int(server_private, 16))

client = SRPClientSession(ctx, private=client_private)
client.process(format(server_public, "x"), binascii.hexlify(salt).decode())

# Server side check: the server's premaster secret must give the same key
server_ctx = SRPContext("Pair-Setup", pin, prime=constants.PRIME_3072, generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
u = server_ctx.get_common_secret(server_public, int(client.public, 16))
s_server = server_ctx.get_server_premaster_secret(password_verifier=verifier, server_private=int(server_private, 16), client_public=int(client.public, 16), common_secret=u)
print("KEYS_AGREE", server_ctx.get_common_session_key(s_server).hex() == client.key)

class _S:
    public = format(server_public, "x")
server = _S()
print("SALT", salt.hex())
print("APRIV", client_private)
print("B", server.public)
print("A", client.public)
print("K", client.key)
print("M1", client.key_proof)
print("M2", client.key_proof_hash)
