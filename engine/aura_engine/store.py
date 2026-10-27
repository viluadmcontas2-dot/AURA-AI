"""SQLite event log and durable jobs. All state transitions are transactional."""
import json
import sqlite3
import threading
import time
import uuid


def packed(x):
    return json.dumps(x, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


class Store:
    def __init__(self, filename):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(filename), check_same_thread=False, timeout=15)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS jobs(
                id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL,
                request TEXT NOT NULL, risk TEXT NOT NULL,
                status TEXT NOT NULL, result TEXT, error TEXT, created REAL NOT NULL, updated REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS events(
                id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL,
                payload TEXT NOT NULL, created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS memory(
                workspace TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
                updated REAL NOT NULL, PRIMARY KEY(workspace,key));
            CREATE TABLE IF NOT EXISTS schedules(
                id TEXT PRIMARY KEY, seconds INTEGER NOT NULL, request TEXT NOT NULL,
                next_run REAL NOT NULL, enabled INTEGER NOT NULL);
        """)
        with self.lock, self.db:
            columns = {r["name"] for r in self.db.execute("PRAGMA table_info(jobs)")}
            if "note" not in columns:
                self.db.execute("ALTER TABLE jobs ADD COLUMN note TEXT")
            changed = self.db.execute(
                "UPDATE jobs SET status='interrupted',updated=? WHERE status='running'",
                (time.time(),)).rowcount
            if changed:
                self._emit("engine.recovered", {"interrupted": changed})

    def close(self):
        with self.lock:
            try:
                self.db.close()
            except sqlite3.ProgrammingError:
                pass

    def _emit(self, kind, payload):
        self.db.execute("INSERT INTO events(type,payload,created) VALUES(?,?,?)",
                        (kind, packed(payload), time.time()))

    def emit(self, kind, payload):
        with self.lock, self.db:
            self._emit(kind, payload)

    def scrub_auth_prompts(self, provider):
        """Depois do login, remove codigo de uso unico e parametros de sessao do link."""
        from urllib.parse import urlsplit, urlunsplit
        with self.lock, self.db:
            rows = self.db.execute("SELECT id,payload FROM events WHERE type='auth.prompt'").fetchall()
            for row in rows:
                payload = json.loads(row["payload"])
                if payload.get("provider") != provider:
                    continue
                payload.pop("code", None)
                if isinstance(payload.get("url"), str):
                    parts = urlsplit(payload["url"])
                    payload["url"] = urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
                self.db.execute("UPDATE events SET payload=? WHERE id=?", (packed(payload), row["id"]))

    def events(self, since=0, limit=100):
        with self.lock:
            rows = self.db.execute(
                "SELECT * FROM events WHERE id>? ORDER BY id ASC LIMIT ?",
                (int(since), max(1, min(int(limit), 1000)))).fetchall()
            return [{"id": r["id"], "type": r["type"],
                     "payload": json.loads(r["payload"]), "created_at": r["created"]} for r in rows]

    def _job(self, row):
        if row is None:
            return None
        return {
            "id": row["id"], "request_id": row["request_id"],
            "request": json.loads(row["request"]), "risk": row["risk"],
            "status": row["status"],
            "result": json.loads(row["result"]) if row["result"] else None,
            "error": json.loads(row["error"]) if row["error"] else None,
            "note": row["note"],
            "created_at": row["created"], "updated_at": row["updated"]}

    def job(self, job_id):
        with self.lock:
            return self._job(self.db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def jobs(self, limit=50):
        with self.lock:
            rows = self.db.execute("SELECT * FROM jobs ORDER BY created DESC LIMIT ?",
                                   (max(1, min(int(limit), 200)),)).fetchall()
            return [self._job(row) for row in rows]

    def submit(self, request, risk, request_id=None, status=None, note=None, created=None, events=()):
        rid = request_id or str(uuid.uuid4())
        body = packed(request)
        with self.lock, self.db:
            prior = self.db.execute("SELECT * FROM jobs WHERE request_id=?", (rid,)).fetchone()
            if prior:
                if prior["request"] != body:
                    raise ValueError("idempotency key reused with different request")
                return self._job(prior)
            job_id = str(uuid.uuid4())
            status = status or ("queued" if risk == "read" else "awaiting_approval")
            now = time.time()
            self.db.execute("""INSERT INTO jobs
                (id,request_id,request,risk,status,note,created,updated)
                VALUES(?,?,?,?,?,?,?,?)""", (job_id, rid, body, risk, status, note, now, now))
            payload = {"id": job_id, "status": status, "risk": risk}
            payload.update(created or {})
            self._emit("job.created", payload)
            for kind, extra in events:
                self._emit(kind, dict(extra, job_id=job_id))
            return self.job(job_id)

    def approve(self, job_id):
        with self.lock, self.db:
            changed = self.db.execute("""UPDATE jobs SET status='queued',updated=?
                WHERE id=? AND status='awaiting_approval'""", (time.time(), job_id)).rowcount
            if not changed:
                raise ValueError("job not pending approval")
            self._emit("job.approved", {"id": job_id})
            return self.job(job_id)

    def claim(self):
        with self.lock, self.db:
            row = self.db.execute(
                "SELECT id FROM jobs WHERE status='queued' ORDER BY created LIMIT 1").fetchone()
            if not row:
                return None
            updated = self.db.execute(
                "UPDATE jobs SET status='running',updated=? WHERE id=? AND status='queued'",
                (time.time(), row["id"])).rowcount
            if not updated:
                return None
            self._emit("job.running", {"id": row["id"]})
            return self.job(row["id"])

    def finish(self, job_id, status, result=None, error=None):
        if status not in ("completed", "failed", "cancelled"):
            raise ValueError("bad terminal state")
        with self.lock, self.db:
            changed = self.db.execute("""UPDATE jobs SET status=?,result=?,error=?,updated=?
                WHERE id=? AND status='running'""",
                (status, packed(result) if result is not None else None,
                 packed(error) if error is not None else None,
                 time.time(), job_id)).rowcount
            if changed:
                self._emit("job." + status, {"id": job_id})
            return self.job(job_id)

    def cancel(self, job_id):
        with self.lock, self.db:
            changed = self.db.execute("""UPDATE jobs SET status='cancelled',updated=?
                WHERE id=? AND status IN ('running','queued','awaiting_approval')""",
                (time.time(), job_id)).rowcount
            if changed:
                self._emit("job.cancelled", {"id": job_id})
            return self.job(job_id)

    def remember(self, workspace, key, value):
        with self.lock, self.db:
            self.db.execute("""INSERT INTO memory(workspace,key,value,updated) VALUES(?,?,?,?)
                ON CONFLICT(workspace,key) DO UPDATE SET value=excluded.value,
                updated=excluded.updated""", (workspace, key, value, time.time()))

    def recall(self, workspace, query=""):
        with self.lock:
            rows = self.db.execute("""SELECT key,value,updated FROM memory
                WHERE workspace=? AND (key LIKE ? OR value LIKE ?)
                ORDER BY updated DESC LIMIT 50""",
                (workspace, "%" + query + "%", "%" + query + "%")).fetchall()
            return [dict(r) for r in rows]

    def usage(self):
        """Summarise reported tokens only; never fabricate monetary costs."""
        totals = {"input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0,
                  "reported_jobs": 0, "unreported_jobs": 0}
        kinds = {"claude_cli", "gemini_cli", "agy_cli", "claude_api", "gemini_api"}
        with self.lock:
            rows = self.db.execute("SELECT request,result FROM jobs WHERE status='completed'").fetchall()
        for row in rows:
            request = json.loads(row["request"])
            if request.get("kind") not in kinds:
                continue
            result = json.loads(row["result"]) if row["result"] else {}
            envelope = result.get("response", {})
            if not isinstance(envelope, dict):
                envelope = {}
            metrics = result.get("usage") or envelope.get("usage") or envelope.get("stats")
            if not isinstance(metrics, dict):
                totals["unreported_jobs"] += 1
                continue
            mapping = {
                "input_tokens": ("input_tokens", "promptTokenCount", "prompt_token_count"),
                "output_tokens": ("output_tokens", "candidatesTokenCount", "candidates_token_count"),
                "cache_read_tokens": ("cache_read_tokens", "cachedContentTokenCount"),
            }
            for metric, options in mapping.items():
                for name in options:
                    value = metrics.get(name)
                    if type(value) is int and value >= 0:
                        totals[metric] += value
                        break
            totals["reported_jobs"] += 1
        return totals

    SPEND_PROVIDERS = {"claude_cli": "claude", "claude_api": "claude", "gemini_cli": "gemini",
                       "gemini_api": "gemini", "agy_cli": "agy"}
    NOT_REPORTED = "não informado"

    @classmethod
    def reported_cost(cls, result):
        """Custo em dolares que o provedor informou no resultado, ou None."""
        result = result if isinstance(result, dict) else {}
        envelope = result.get("response") if isinstance(result.get("response"), dict) else {}
        for source in (envelope, result):
            for name in ("total_cost_usd", "cost_usd"):
                value = cls._money(source.get(name))
                if value is not None:
                    return value
        return None

    @staticmethod
    def _money(value):
        return value if type(value) in (int, float) and value >= 0 and value == value else None

    def spend(self, now=None):
        """Gasto de hoje e do mes por provedor e modelo. So valores que o provedor informou."""
        now = time.time() if now is None else now
        local = time.localtime(now)
        windows = {"today": (local.tm_year, local.tm_mon, local.tm_mday),
                   "month": (local.tm_year, local.tm_mon, None)}
        report = {name: {"date": "%04d-%02d" % key[:2] + ("-%02d" % key[2] if key[2] else ""),
                         "providers": {}, "total_usd": self.NOT_REPORTED, "unreported_jobs": 0}
                  for name, key in windows.items()}
        with self.lock:
            rows = self.db.execute(
                "SELECT request,result,updated FROM jobs WHERE status='completed'").fetchall()
        for row in rows:
            request = json.loads(row["request"])
            provider = self.SPEND_PROVIDERS.get(request.get("kind"))
            if provider is None:
                continue
            result = json.loads(row["result"]) if row["result"] else {}
            envelope = result.get("response") if isinstance(result.get("response"), dict) else {}
            cost = self.reported_cost(result)
            model = request.get("model") or result.get("model") or envelope.get("model")
            usage = envelope.get("modelUsage")
            if not model and isinstance(usage, dict) and usage:
                model = next(iter(usage))
            model = model if isinstance(model, str) and model else self.NOT_REPORTED
            when = time.localtime(row["updated"])
            for name, key in windows.items():
                if (when.tm_year, when.tm_mon) != key[:2] or (key[2] and when.tm_mday != key[2]):
                    continue
                win = report[name]
                prov = win["providers"].setdefault(provider, {
                    "jobs": 0, "cost_usd": self.NOT_REPORTED, "unreported_jobs": 0, "models": {}})
                mod = prov["models"].setdefault(model, {
                    "jobs": 0, "cost_usd": self.NOT_REPORTED, "unreported_jobs": 0})
                for bucket in (prov, mod):
                    bucket["jobs"] += 1
                    if cost is None:
                        bucket["unreported_jobs"] += 1
                    else:
                        bucket["cost_usd"] = (0 if bucket["cost_usd"] == self.NOT_REPORTED
                                              else bucket["cost_usd"]) + cost
                if cost is None:
                    win["unreported_jobs"] += 1
                else:
                    win["total_usd"] = (0 if win["total_usd"] == self.NOT_REPORTED
                                        else win["total_usd"]) + cost
        return report

    def add_schedule(self, data):
        seconds = data.get("every_seconds")
        if type(seconds) is not int or not 30 <= seconds <= 86400 * 365:
            raise ValueError("every_seconds must be >=30 and <=1 year")
        request = data.get("request")
        if not isinstance(request, dict):
            raise ValueError("schedule requires request")
        sid = str(uuid.uuid4())
        with self.lock, self.db:
            self.db.execute("INSERT INTO schedules VALUES(?,?,?,?,?)",
                            (sid, seconds, packed(request), time.time() + seconds, 1))
        return {"id": sid, "every_seconds": seconds, "request": request, "enabled": True}

    def schedules(self):
        with self.lock:
            rows = self.db.execute("SELECT * FROM schedules ORDER BY next_run").fetchall()
            return [{"id": r["id"], "every_seconds": r["seconds"],
                     "request": json.loads(r["request"]),
                     "next_run": r["next_run"], "enabled": bool(r["enabled"])} for r in rows]

    def due_schedules(self):
        with self.lock, self.db:
            now = time.time()
            rows = self.db.execute(
                "SELECT * FROM schedules WHERE enabled=1 AND next_run<=?", (now,)).fetchall()
            for row in rows:
                self.db.execute("UPDATE schedules SET next_run=? WHERE id=?",
                                (now + row["seconds"], row["id"]))
            return [(r["id"], json.loads(r["request"])) for r in rows]
