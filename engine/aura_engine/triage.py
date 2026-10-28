"""Recepcao (regras da skill clareza), triagem de entradas e fila com 'salvo para retomar'.

Nada aqui executa pedido: interpretar e classificar so geram eventos e anotam a fila em queue.json.
O job nasce em promote(), pelo mesmo Engine.submit: politica, teto de gasto e aprovacao valem igual.
"""
import json
import re
import threading
import uuid

from . import adapters, rea_chain
from .config import atomic_write_json

WAKE = re.compile(r"^\s*(?:(?:ei|oi|ok)\s+)?aura\b[\s,:;!-]*", re.I)
HESITATION = re.compile(r"\b(?:ah|ahn|ãh|ãhn|hã|hum|hmm|uh|uhm|eh|éh|né)\b[,.…]*\s*", re.I)
REPEATED = re.compile(r"\b(\w+)(?:\s+\1\b)+", re.I)
DANGLING = {"a", "o", "e", "de", "da", "do", "na", "no", "em", "com", "que", "para", "pra",
            "um", "uma", "meu", "minha"}
STOP = re.compile(r"^(?:por favor[, ]*)?(?:para|pare|parar|cancela|cancele|cancelar|interrompe|"
                  r"interrompa|esquece|chega)(?:\s+(?:tudo|isso|agora|a tarefa|essa tarefa))*"
                  r"(?:[, ]+por favor)?[.!]*$", re.I)
QUESTION = re.compile(r"^(?:como|quando|qual|quais|quanto|quanta|quantos|quantas|quem|onde|"
                      r"por que|porque|o que|que horas)\b", re.I)
STATUS = re.compile(r"andamento|status|como est|quanto falta|terminou|acabou|em que passo", re.I)
ADJUST = re.compile(r"^(?:muda|mude|troca|troque|altera|altere|ajusta|ajuste|em vez|ao invés|"
                    r"na verdade|prefiro|deixa|deixe|usa|use|coloca|coloque|tira|tire|só)\b", re.I)


def clean_speech(text):
    """(texto limpo, ambiguidades). Tira 'AURA,', hesitacoes e palavras repetidas sem mudar o sentido."""
    clean = REPEATED.sub(r"\1", HESITATION.sub("", WAKE.sub("", text)))
    clean = re.sub(r"\s+([?!.,])", r"\1", re.sub(r"\s+", " ", clean)).strip(" ,;")
    if not clean:
        raise ValueError("empty after cleaning")
    words = re.sub(r"\W+$", "", clean).split()
    ambiguities = ["A frase parece cortada no fim."] if words and words[-1].lower() in DANGLING else []
    clean = clean[0].upper() + clean[1:]
    return (clean if clean[-1] in ".?!…" else clean + "."), ambiguities


def classify(text, running):
    """(tipo, confianca). A confianca e a da regra, nao uma probabilidade medida."""
    if STOP.match(text):
        return "stop", 0.95
    if "?" in text or QUESTION.match(text):
        return "question", 0.9 if "?" in text else 0.75
    if running and ADJUST.match(text):
        return "adjust", 0.7
    return "new_task", 0.7


def _title(request):
    return str(request.get("title") or request.get("prompt")
               or "%s %s" % (request.get("kind"), request.get("action", "")))[:60].strip()


class Triage:
    def __init__(self, engine):
        self.engine = engine
        self.file = engine.home / "queue.json"
        self.lock = threading.RLock()

    def _load(self):
        try:
            data = json.loads(self.file.read_text("utf-8"))
        except (OSError, ValueError):
            data = {}
        return {name: data.get(name, []) for name in ("pending", "queued", "saved")}

    def _save(self, queue):
        atomic_write_json(self.file, queue)

    def running(self):
        jobs = [j for j in self.engine.store.jobs(200) if j["status"] == "running"]
        return jobs[-1] if jobs else None  # jobs() vem do mais novo para o mais velho

    def queue(self):
        q, job = self._load(), self.running()
        return {"running": {"jobId": job["id"], "title": _title(job["request"])} if job else None,
                "queued": [{"taskId": i["taskId"], "text": i["text"]} for i in q["queued"]],
                "saved": [{k: i[k] for k in ("taskId", "jobId", "title", "checkpointId", "resumable")}
                          for i in q["saved"]]}

    def _announce(self):
        self.engine.store.emit("queue.updated", self.queue())

    def receive(self, body):
        text, source = body.get("text"), body.get("source", "text")
        if not isinstance(text, str) or not text.strip() or len(text) > 4000:
            raise ValueError("text is required (up to 4000 characters)")
        if source not in ("voice", "text"):
            raise ValueError("source must be voice or text")
        text = text.strip()
        try:
            clean, ambiguities = clean_speech(text)
        except Exception:
            clean, ambiguities = text, []  # a recepcao falhou: segue com o texto original
        job = self.running()
        kind, confidence = classify(clean, job is not None)
        task_id, emit = uuid.uuid4().hex[:12], self.engine.store.emit
        emit("input.interpreted", {"taskId": task_id, "raw": adapters.redact(text),
                                   "clean": adapters.redact(clean), "intent": adapters.redact(clean[:60]),
                                   "ambiguities": ambiguities, "skill": "clareza"})
        emit("input.received", {"taskId": task_id, "kind": kind, "confidence": confidence,
                                "source": source})
        out = {"id": task_id, "kind": kind, "confidence": confidence, "clean": clean,
               "ambiguities": ambiguities}
        with self.lock:
            q = self._load()
            if kind == "question":
                out.update(self._answer(clean, job))
            elif kind == "stop":
                out["applied"] = self._stop(q, job)
                self._save(q)
                if out["applied"]:
                    self._announce()
            else:
                q["pending"].append({"taskId": task_id, "kind": kind, "text": clean})
                self._save(q)
                out["recommended"] = "merge" if kind == "adjust" else "later"
        return out

    def _answer(self, text, job):
        if STATUS.search(text):
            return {"answer": "Rodando: %s." % _title(job["request"]) if job else "Nada rodando agora.",
                    "request": None}
        return {"answer": None, "request": self.engine.route(text)["request"]}  # proposta, nada executa

    def _stop(self, q, job, keep=True):
        """Cancela o job em curso; com keep, ele fica salvo para retomar. False se nada rodava."""
        if job is None:
            return False
        item = {"taskId": uuid.uuid4().hex[:12], "jobId": job["id"], "title": _title(job["request"]),
                "request": job["request"], "resumable": True,
                "checkpointId": (rea_chain.last_done(self.engine.home)
                                 if job["request"].get("kind") == "rea" else None)}
        if keep:
            q["saved"].insert(0, item)
        self.engine.cancel(job["id"])
        self.engine.store.emit("task.interrupted", {
            "taskId": item["taskId"], "jobId": job["id"],
            "checkpointId": item["checkpointId"], "resumable": True})
        return True

    def apply(self, task_id, body):
        """Aplica a escolha da triagem: later (fila), merge (juntar no atual) ou now (parar e fazer agora)."""
        with self.lock:
            q = self._load()
            item = next((i for i in q["pending"] if i["taskId"] == task_id), None)
            if item is None:
                raise KeyError("input not found")
            mode = body.get("mode") or ("merge" if item["kind"] == "adjust" else "later")
            if mode not in ("later", "merge", "now"):
                raise ValueError("mode must be later, merge or now")
            if mode == "now" and body.get("confirm") is not True:
                raise ValueError("confirm is required to stop the current task")
            job = self.running()
            if mode == "merge" and job is None:
                mode = "later"  # nada rodando: nao ha o que juntar
            if mode == "merge":
                old = job["request"]
                request = dict(old, adjustments=old.get("adjustments", []) + [item["text"]])
                if isinstance(old.get("prompt"), str):
                    request["prompt"] = old["prompt"] + "\n\nAjuste pedido pelo dono: " + item["text"]
                self._stop(q, job, keep=False)  # o ajuste substitui o atual
            else:
                request = self.engine.route(item["text"])["request"]
                if mode == "now":
                    self._stop(q, job)  # o atual fica salvo e pode ser retomado
            q["pending"].remove(item)
            entry = {"taskId": task_id, "text": item["text"], "request": request}
            q["queued"].insert(len(q["queued"]) if mode == "later" else 0, entry)
            self._save(q)
            self.promote()
            self._announce()
            return dict(self.queue(), applied=mode)

    def promote(self):
        """Fila em serie: so quando nada roda nem espera worker o primeiro da fila vira job."""
        with self.lock:
            q = self._load()
            if not q["queued"] or any(j["status"] in ("running", "queued")
                                      for j in self.engine.store.jobs(200)):
                return False
            self.engine.submit(q["queued"][0]["request"])
            q["queued"].pop(0)
            self._save(q)
            self._announce()
            return True

    def resume(self, task_id):
        with self.lock:
            q = self._load()
            item = next((i for i in q["saved"] if i["taskId"] == task_id), None)
            if item is None:
                raise KeyError("saved task not found")
            q["saved"].remove(item)
            q["queued"].insert(0, {"taskId": task_id, "text": item["title"], "request": item["request"]})
            self._save(q)
            self.promote()
            self._announce()
            return self.queue()


def http(engine, method, path, body):
    """Rotas de entrada e fila: devolve (codigo, dados) ou None quando a rota nao e daqui."""
    parts = path.split("/")
    if method == "GET" and path == "/v1/queue":
        return 200, engine.triage.queue()
    if method == "POST" and path == "/v1/inputs":
        return 200, engine.triage.receive(body())
    if method == "POST" and len(parts) == 5 and parts[:3] == ["", "v1", "inputs"] and parts[4] == "apply":
        return 200, engine.triage.apply(parts[3], body())
    if method == "POST" and len(parts) == 5 and parts[:3] == ["", "v1", "queue"] and parts[4] == "resume":
        return 200, engine.triage.resume(parts[3])
    return None
