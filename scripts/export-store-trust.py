#!/usr/bin/env python3
"""Run on the store host; export public trust only after proving key possession.

Usage: sudo python3 scripts/export-store-trust.py > trusted-store-keys.json
Run from the deployment directory. Private key material stays in host memory.
"""
import base64
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path


def run(*args, data=None):
    return subprocess.run(args, input=data, stdout=subprocess.PIPE, check=True).stdout


def export():
    sql = "SELECT json_agg(t) FROM (SELECT key_id, public_key_base64 FROM signing_key WHERE owner_type='PLATFORM' AND status='ACTIVE' AND (valid_to IS NULL OR valid_to > CURRENT_TIMESTAMP)) t"
    rows = json.loads(run("docker", "compose", "exec", "-T", "postgres", "psql",
                          "-U", "store", "-d", "store", "-XAt", "-c", sql))
    if not rows or len(rows) != 1:
        raise RuntimeError("Expected exactly one active platform key; review rotation manually")
    row = rows[0]
    key_id = row["key_id"]
    if not re.fullmatch(r"platform-ed25519-[A-Za-z0-9_-]+", key_id):
        raise RuntimeError("Unexpected platform key ID")
    public = base64.b64decode(row["public_key_base64"], validate=True)
    expected_fingerprint = "8b694d02cf8e7597e3ce8a494c9f1dd544ebbda33cb74bea5944fe830920668f"
    if hashlib.sha256(public).hexdigest() != expected_fingerprint:
        raise RuntimeError("Production key changed; review host trust and key rotation before exporting")
    private = base64.b64decode(run("docker", "compose", "exec", "-T", "store",
                                  "cat", f"/var/lib/infinia-store/keys/{key_id}.b64").strip(), validate=True)
    derived = run("openssl", "pkey", "-inform", "DER", "-pubout", "-outform", "DER", data=private)
    if derived != public:
        raise RuntimeError("Database public key does not match persisted private key")
    challenge = b"Infinia Store trust verification"
    with tempfile.TemporaryDirectory(prefix="infinia-trust-") as directory:
        payload = Path(directory) / "challenge"
        payload.write_bytes(challenge)
        signature = run("openssl", "pkeyutl", "-sign", "-rawin", "-keyform", "DER",
                        "-inkey", "/dev/stdin", "-in", str(payload), data=private)
    revoked_sql = "SELECT coalesce(json_agg(key_id), '[]') FROM signing_key WHERE owner_type='PLATFORM' AND status='REVOKED'"
    revoked = json.loads(run("docker", "compose", "exec", "-T", "postgres", "psql",
                             "-U", "store", "-d", "store", "-XAt", "-c", revoked_sql))
    return {"keys": [{"id": key_id, "publicKey": row["public_key_base64"]}],
            "revokedKeys": revoked,
            "_verification": {"publicKeySha256": hashlib.sha256(public).hexdigest(),
                              "challenge": challenge.decode(),
                              "signature": base64.b64encode(signature).decode()}}


if __name__ == "__main__":
    print(json.dumps(export(), indent=2))
