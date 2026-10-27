"""One-shot credential handoff for Android's explicitly user-authorised Termux RUN_COMMAND.

Never run --emit-token in recorded terminals or logs. Android must hold the
result privately and store it with Android Keystore. No API exposes the token.
"""
import argparse
import json
import pathlib
from .security import load_or_create_token


def main():
    parser = argparse.ArgumentParser(description="Pair approved Android client with AURA Termux engine")
    parser.add_argument("--emit-token", action="store_true",
                        help="Sensitive: only with authorised Android RUN_COMMAND result receiver")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port out of range")
    home = pathlib.Path.home() / ".aura" / "engine"
    if not args.emit_token:
        print(json.dumps({"service": "aura-engine", "pairing": "ready",
                          "message": "No token displayed. Use the authorised app pairing flow."}))
        return
    token = load_or_create_token(home)
    print(json.dumps({"service": "aura-engine", "host": "127.0.0.1",
                      "port": args.port, "token": token}))


if __name__ == "__main__":
    main()
