"""T20 (a)+(b): recepcao (skill clareza), triagem de entradas e fila com 'salvo para retomar'."""
import unittest
import unittest.mock

from aura_engine import adapters
from test_lote_c import Base


class Triage(Base):
    def say(self, text, source="voice"):
        code, body = self.call("POST", "/v1/inputs", {"text": text, "source": source})
        self.assertEqual(code, 200, body)
        return body

    def running(self, request=None):
        """Um job 'rodando' sem worker: o proprio teste faz o claim."""
        job = self.engine.submit(request or {"kind": "files", "action": "list", "path": "."})
        if job["status"] == "awaiting_approval":
            self.engine.approve(job["id"])
        return self.engine.store.claim()

    def apply(self, body, **options):
        return self.call("POST", "/v1/inputs/%s/apply" % body["id"], options)

    def test_skill_clareza_is_bundled(self):
        skill = next((s for s in adapters.scan_skills(self.home) if s["id"] == "clareza"), None)
        self.assertIsNotNone(skill)
        self.assertEqual(skill["origin"], "bundled")

    def test_reception_cleans_the_speech_and_marks_a_cut_sentence(self):
        raw = "AURA, ãh, pega o o aplicativo que eu comecei e arruma a tela"
        body = self.say(raw)
        self.assertEqual(body["clean"], "Pega o aplicativo que eu comecei e arruma a tela.")
        self.assertEqual(body["ambiguities"], [])
        self.assertEqual(len(self.say("arruma aquela tela e deixa funcionando na minha")["ambiguities"]), 1)
        interpreted, received = self.events("input.interpreted"), self.events("input.received")
        payload = interpreted[0]["payload"]
        self.assertEqual((payload["raw"], payload["skill"]), (raw, "clareza"))
        for key in ("clean", "intent", "ambiguities"):
            self.assertIn(key, payload)
        self.assertLess(interpreted[0]["id"], received[0]["id"])  # interpreta ANTES de classificar

    def test_reception_failure_keeps_the_original_text_and_runs_nothing(self):
        with unittest.mock.patch("aura_engine.triage.clean_speech", side_effect=RuntimeError("boom")):
            body = self.say("apaga todos os arquivos da pasta")
        self.assertEqual(body["clean"], "apaga todos os arquivos da pasta")
        self.assertEqual(self.engine.store.jobs(), [])
        self.assertEqual(len(self.events("input.received")), 1)

    def test_each_input_is_classified(self):
        idle = [("Como está o andamento?", "question"), ("quanto falta", "question"),
                ("AURA, para tudo", "stop"), ("pare", "stop"),
                ("Instala também na TV da sala", "new_task"),
                ("para de usar o tema escuro", "new_task"),  # "para de" e pedido, nao e parar
                ("troca o botão para azul", "new_task")]  # sem job rodando nao ha o que ajustar
        for text, kind in idle:
            with self.subTest(text=text):
                body = self.say(text, "text")
                self.assertEqual(body["kind"], kind)
                self.assertTrue(0 < body["confidence"] <= 1)
        last = self.events("input.received")[-1]["payload"]
        self.assertTrue({"taskId", "kind", "confidence", "source"} <= set(last))
        self.assertEqual((last["kind"], last["source"]), ("new_task", "text"))
        self.running()
        self.assertEqual(self.say("troca o botão para azul")["kind"], "adjust")

    def test_question_is_answered_now_without_touching_the_queue(self):
        self.running({"kind": "claude_cli", "prompt": "Corrigir tela de login"})
        status = self.say("Como está o andamento?")
        self.assertIn("Corrigir tela de login", status["answer"])
        other = self.say("Qual a capital da França?")
        self.assertIsNone(other["answer"])
        self.assertEqual(other["request"]["prompt"], "Qual a capital da França?")  # proposta, nada executado
        self.assertEqual(self.call("GET", "/v1/queue")[1]["queued"], [])

    def test_new_task_waits_for_the_current_one_then_starts(self):
        first = self.engine.submit({"kind": "files", "action": "list", "path": "."})
        code, applied = self.apply(self.say("Instala também na TV da sala", "text"))
        self.assertEqual(code, 200, applied)
        self.assertEqual([j["id"] for j in self.engine.store.jobs()], [first["id"]])  # ainda nao nasceu
        queue = self.call("GET", "/v1/queue")[1]
        self.assertEqual([i["text"] for i in queue["queued"]], ["Instala também na TV da sala."])
        self.engine.run_once()  # termina o atual; o motor promove o proximo
        self.assertEqual(len(self.engine.store.jobs()), 2)
        self.assertEqual(self.call("GET", "/v1/queue")[1]["queued"], [])
        self.assertTrue(self.events("queue.updated"))

    def test_stop_saves_the_running_job_and_resume_runs_it_again(self):
        job = self.running()
        body = self.say("para")
        self.assertEqual((body["kind"], body["applied"]), ("stop", True))
        self.assertEqual(self.engine.store.job(job["id"])["status"], "cancelled")
        saved = self.call("GET", "/v1/queue")[1]["saved"]
        self.assertEqual([(s["jobId"], s["resumable"]) for s in saved], [(job["id"], True)])
        event = self.events("task.interrupted")[-1]["payload"]
        self.assertEqual((event["taskId"], event["resumable"]), (saved[0]["taskId"], True))
        self.assertIn("checkpointId", event)
        code, queue = self.call("POST", "/v1/queue/%s/resume" % saved[0]["taskId"], {})
        self.assertEqual(code, 200, queue)
        self.assertEqual(queue["saved"], [])
        again = next(j for j in self.engine.store.jobs() if j["id"] != job["id"])
        self.assertEqual(again["request"], job["request"])

    def test_adjust_restarts_the_running_job_with_the_adjustment(self):
        job = self.running({"kind": "claude_cli", "prompt": "Corrigir tela de login"})
        body = self.say("troca o botão para azul")
        self.assertEqual((body["kind"], body["recommended"]), ("adjust", "merge"))
        code, applied = self.apply(body)
        self.assertEqual(code, 200, applied)
        self.assertEqual(self.engine.store.job(job["id"])["status"], "cancelled")
        new = next(j for j in self.engine.store.jobs() if j["id"] != job["id"])
        self.assertEqual(new["request"]["adjustments"], ["Troca o botão para azul."])
        self.assertIn("Corrigir tela de login", new["request"]["prompt"])
        self.assertIn("Troca o botão para azul.", new["request"]["prompt"])
        self.assertEqual(applied["saved"], [])  # o ajuste substitui o atual: nada fica salvo

    def test_do_it_now_needs_confirmation_and_saves_the_current(self):
        job = self.running()
        body = self.say("Instala também na TV da sala")
        code, refused = self.apply(body, mode="now")
        self.assertEqual(code, 400, refused)
        self.assertEqual(self.engine.store.job(job["id"])["status"], "running")
        code, done = self.apply(body, mode="now", confirm=True)
        self.assertEqual(code, 200, done)
        self.assertEqual(self.engine.store.job(job["id"])["status"], "cancelled")
        self.assertEqual([s["jobId"] for s in done["saved"]], [job["id"]])
        self.assertEqual(len(self.engine.store.jobs()), 2)  # o pedido novo comecou


if __name__ == "__main__":
    unittest.main()
