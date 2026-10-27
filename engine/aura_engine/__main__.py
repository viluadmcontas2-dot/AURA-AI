"""Start on Termux with: python -m aura_engine"""
import argparse
import signal

from .core import Engine
from .server import make_server


def main():
    parser = argparse.ArgumentParser(description="AURA local-only Termux engine")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    engine = Engine(auto_worker=True)
    server = make_server(engine, "127.0.0.1", args.port)
    print("AURA engine listening on 127.0.0.1:%s" % server.server_port, flush=True)
    print("Use the authorised Android client to access jobs. Token never printed.", flush=True)
    try:
        server.serve_forever(poll_interval=0.3)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        engine.close()


if __name__ == "__main__":
    main()
