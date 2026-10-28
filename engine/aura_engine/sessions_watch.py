"""Vigia das sessoes abertas no app: avisa `session.updated {session_id, version}` quando o historico muda.

O app declara a sessao que esta olhando com POST /v1/sessions/{id}/watch e renova antes de TTL segundos; sem
renovacao ela sai da vigia sozinha. O motor confere so as sessoes vigiadas, a cada ~2 s (AURA_SESSION_WATCH_SECS).
A primeira leitura de cada sessao e a linha de base: so mudancas depois dela geram evento.
"""
import os
import threading
import time

from . import sessions

TTL = 60.0
INTERVAL = 2.0


def interval():
    try:
        return max(0.05, min(float(os.environ.get("AURA_SESSION_WATCH_SECS", INTERVAL)), 30.0))
    except ValueError:
        return INTERVAL


class Watcher:
    def __init__(self, engine):
        self.engine = engine
        self.lock = threading.Lock()
        self.until = {}      # session_id -> instante em que a vigia expira
        self.seen = {}       # session_id -> ultima versao observada
        self.thread = None

    def watch(self, session_id):
        with self.lock:
            self.until[session_id] = time.monotonic() + TTL
            if session_id not in self.seen:
                self.seen[session_id] = sessions.version(self.engine, session_id)
            if self.thread is None or not self.thread.is_alive():
                self.thread = threading.Thread(target=self._loop, daemon=True, name="aura-session-watch")
                self.thread.start()
        return int(TTL)

    def _loop(self):
        while not self.engine.stop_event.wait(interval()):
            now = time.monotonic()
            with self.lock:
                for sid in [s for s, t in self.until.items() if t < now]:
                    self.until.pop(sid, None)
                    self.seen.pop(sid, None)
                active = list(self.until)
                if not active:
                    self.thread = None
                    return
            for sid in active:
                try:
                    current = sessions.version(self.engine, sid)
                except Exception:
                    continue
                with self.lock:
                    if sid not in self.until:
                        continue
                    changed = self.seen.get(sid) != current
                    self.seen[sid] = current
                if changed and current is not None:
                    self.engine.store.emit("session.updated", {"session_id": sid, "version": current})


def watcher(engine):
    with _guard:
        found = getattr(engine, "_session_watcher", None)
        if found is None:
            found = engine._session_watcher = Watcher(engine)
        return found


_guard = threading.Lock()
