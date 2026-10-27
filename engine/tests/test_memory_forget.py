"""Memoria: tipo (kind), origem (source), esquecer uma lembranca e esquecer tudo."""
import contextlib
import json
import pathlib
import re
import sqlite3
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.request

from aura_engine.core import Engine
from aura_engine.server import make_server

# Tabela exatamente como o motor anterior a esta tarefa a criava (sem id, kind, source, created).
OLD_MEMORY_DDL = """CREATE TABLE memory(
    workspace TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
    updated REAL NOT NULL, PRIMARY KEY(workspace,key))"""
LEGACY = [("default", "style", "minimal", 1700000000.5),
          ("default", "idioma", "pt-BR simples", 1700000100.0),
          ("projeto-x", "style", "verbose", 1700000200.25)]
NEW_FIELDS = {"id", "kind", "text", "source", "createdAt"}


class MemoryBase(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.home = pathlib.Path(temp.name) / "home"

    def make_engine(self):
        engine = Engine(home=self.home, auto_worker=False)
        self.addCleanup(engine.close)
        server = make_server(engine, "127.0.0.1", 0)
        self.addCleanup(server.server_close)
        threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True).start()
        self.addCleanup(server.shutdown)
        self.engine = engine
        self.base = "http://127.0.0.1:%d" % server.server_port
        return engine

    def raw_call(self, method, path, body=None, auth=True):
        """auth: True = token do motor, False = sem token, texto = esse token (para testar token errado)."""
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if data is not None else {}
        token = self.engine.token if auth is True else auth
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=8) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as err:
            with err:
                return err.code, err.headers, err.read()

    def call(self, method, path, body=None, auth=True):
        status, _, raw = self.raw_call(method, path, body, auth)
        try:
            return status, (json.loads(raw) if raw else None)
        except ValueError:
            return status, raw.decode("utf-8", "replace")

    def remember(self, key, value, workspace="default", **extra):
        code, body = self.call("POST", "/v1/memory", dict(extra, workspace=workspace, key=key, value=value))
        self.assertEqual((code, body), (200, {"stored": True}))

    def entries(self, workspace="default", q=None):
        path = "/v1/memory?workspace=" + workspace + ("&q=" + q if q else "")
        code, body = self.call("GET", path)
        self.assertEqual(code, 200)
        return body["entries"]

    def by_text(self, workspace="default"):
        return {e["text"]: e for e in self.entries(workspace)}


class MemoryItems(MemoryBase):
    def test_MEM_post_without_kind_gives_preference_and_a_typed_item(self):
        self.make_engine()
        before = time.time()
        self.remember("estilo", "Respostas curtas, em portugues simples")
        (item,) = self.entries()
        self.assertTrue(NEW_FIELDS <= set(item), sorted(item))
        self.assertEqual(item["kind"], "preference")
        self.assertEqual(item["text"], "Respostas curtas, em portugues simples")
        self.assertEqual(item["source"], "user")
        self.assertIsInstance(item["createdAt"], (int, float))
        self.assertTrue(before - 1 <= item["createdAt"] <= time.time() + 1)
        # o app monta a URL com o id: so letras, digitos, '_' e '-' (AuraEngineClient.segment)
        self.assertRegex(item["id"], r"^[a-zA-Z0-9_-]{1,100}$")
        # campos de antes continuam la, com os mesmos valores
        self.assertEqual((item["key"], item["value"]), ("estilo", item["text"]))
        self.assertIn("updated", item)

    def test_MEM_post_accepts_every_kind_and_a_source(self):
        self.make_engine()
        for kind in ("preference", "project", "device", "rule"):
            with self.subTest(kind=kind):
                self.remember("k-" + kind, "texto " + kind, kind=kind, source="repository")
        found = {e["kind"]: e for e in self.entries()}
        self.assertEqual(sorted(found), ["device", "preference", "project", "rule"])
        self.assertTrue(all(e["source"] == "repository" for e in found.values()))
        self.assertEqual(len({e["id"] for e in found.values()}), 4)

    def test_MEM_null_kind_and_source_mean_omitted(self):
        self.make_engine()
        self.remember("k", "v", kind=None, source=None)
        (item,) = self.entries()
        self.assertEqual((item["kind"], item["source"]), ("preference", "user"))

    def test_MEM_post_rejects_bad_kind_and_source_without_echoing_them(self):
        self.make_engine()
        secret = "sk-ant-SEGREDO-NO-KIND"
        bad = [{"kind": secret}, {"kind": "Preference"}, {"kind": ""}, {"kind": 1}, {"kind": True},
               {"kind": ["rule"]}, {"kind": {"a": 1}},
               {"source": 7}, {"source": ""}, {"source": "   "}, {"source": "x" * 61},
               {"source": ["a"]}, {"source": {"a": 1}}, {"source": secret + "x" * 60}]
        for extra in bad:
            with self.subTest(extra=str(extra)[:40]):
                code, resp = self.call("POST", "/v1/memory", dict(extra, key="k", value="v"))
                self.assertEqual(code, 400)
                self.assertEqual(resp["error"]["code"], "invalid_request")
                self.assertNotIn(secret, json.dumps(resp))
        self.assertEqual(self.entries(), [])
        self.assertNotIn(secret, json.dumps(self.engine.store.events(0, 1000)))

    def test_MEM_posting_the_same_key_updates_in_place(self):
        self.make_engine()
        self.remember("estilo", "v1", kind="rule", source="repository")
        (first,) = self.entries()
        self.remember("estilo", "v2")
        (second,) = self.entries()
        self.assertEqual(second["id"], first["id"])
        self.assertEqual(second["createdAt"], first["createdAt"])
        self.assertEqual(second["text"], "v2")
        # kind e source omitidos nao voltam ao padrao: ficam como estavam
        self.assertEqual((second["kind"], second["source"]), ("rule", "repository"))
        self.assertGreaterEqual(second["updated"], first["updated"])
        self.remember("estilo", "v3", kind="device")
        (third,) = self.entries()
        self.assertEqual((third["id"], third["kind"], third["source"]), (first["id"], "device", "repository"))

    def test_MEM_workspace_and_query_filters_still_work(self):
        self.make_engine()
        self.remember("estilo", "curto", workspace="default")
        self.remember("estilo", "longo", workspace="projeto-x")
        self.remember("idioma", "pt-BR", workspace="default")
        default, other = self.entries("default"), self.entries("projeto-x")
        self.assertEqual(sorted(e["text"] for e in default), ["curto", "pt-BR"])
        self.assertEqual([e["text"] for e in other], ["longo"])
        self.assertNotEqual(next(e["id"] for e in default if e["key"] == "estilo"), other[0]["id"])
        self.assertEqual([e["text"] for e in self.entries("default", q="idio")], ["pt-BR"])
        self.assertEqual([e["text"] for e in self.entries("default", q="curt")], ["curto"])

    def test_MEM_get_and_post_still_require_the_token(self):
        self.make_engine()
        self.assertEqual(self.call("GET", "/v1/memory", auth=False)[0], 401)
        # sem corpo de proposito: o motor responde 401 antes de ler o corpo e, no Windows, fechar o
        # socket com corpo pendente pode derrubar a resposta no cliente (WinError 10053)
        self.assertEqual(self.call("POST", "/v1/memory", auth=False)[0], 401)
        self.assertEqual(self.entries(), [])


class ForgetOne(MemoryBase):
    def seed(self):
        self.make_engine()
        for key, text in (("a", "um"), ("b", "dois"), ("c", "tres")):
            self.remember(key, text)
        return self.by_text()

    def test_FORGET_one_is_204_with_no_body_and_removes_only_that_item(self):
        items = self.seed()
        status, headers, raw = self.raw_call("DELETE", "/v1/memory/" + items["dois"]["id"])
        self.assertEqual((status, raw), (204, b""))
        self.assertIsNone(headers.get("Content-Length"))  # 204 nao leva Content-Length (RFC 9110)
        self.assertEqual(headers.get("Cache-Control"), "no-store")
        self.assertEqual(sorted(self.by_text()), ["tres", "um"])

    def test_FORGET_one_unknown_or_already_forgotten_is_404_in_the_error_format(self):
        items = self.seed()
        ghost = "00000000-0000-4000-8000-000000000000"
        code, resp = self.call("DELETE", "/v1/memory/" + ghost)
        self.assertEqual(code, 404)
        self.assertEqual(resp["error"]["code"], "not_found")
        self.assertTrue(resp["error"]["message"])
        self.assertNotIn(ghost, json.dumps(resp))
        self.assertEqual(len(self.entries()), 3)
        gone = items["um"]["id"]
        self.assertEqual(self.call("DELETE", "/v1/memory/" + gone)[0], 204)
        self.assertEqual(self.call("DELETE", "/v1/memory/" + gone)[0], 404)
        self.assertEqual(len(self.entries()), 2)

    def test_FORGET_one_never_matches_by_pattern_or_injection(self):
        self.seed()
        for hostile in ("%", "_", "%25", "..", ".", "%27%20OR%20%271%27%3D%271", "x" * 5000):
            with self.subTest(hostile=hostile[:30]):
                self.assertEqual(self.call("DELETE", "/v1/memory/" + hostile)[0], 404)
                self.assertEqual(len(self.entries()), 3)

    def test_FORGET_trailing_slash_never_wipes_everything(self):
        self.seed()
        # cliente que monta "/v1/memory/" + id com id vazio nao pode apagar tudo
        code, _ = self.call("DELETE", "/v1/memory/")
        self.assertEqual(code, 404)
        self.assertEqual(len(self.entries()), 3)

    def test_FORGET_one_requires_the_token(self):
        items = self.seed()
        path = "/v1/memory/" + items["um"]["id"]
        self.assertEqual(self.call("DELETE", path, auth=False)[0], 401)
        self.assertEqual(self.call("DELETE", path, auth="errado")[0], 401)
        self.assertEqual(len(self.entries()), 3)

    def test_FORGET_delete_never_reaches_the_post_routes(self):
        self.make_engine()
        # Sem corpo, uma rota de POST que recebesse o DELETE responderia 400 ("corpo JSON ausente"),
        # nao 404. (Sem corpo tambem evita a corrida de socket do Windows: o 404 sai sem ler o corpo.)
        for path in ("/v1/jobs", "/v1/schedules", "/v1/route", "/v1/policy", "/v1/auth/start"):
            with self.subTest(path=path):
                self.assertEqual(self.call("DELETE", path)[0], 404)
        self.assertEqual(self.engine.store.jobs(), [])
        self.assertEqual(self.engine.store.schedules(), [])
        self.assertEqual(self.engine.config.policy()["version"], 1)


class ForgetAll(MemoryBase):
    def seed(self):
        self.make_engine()
        self.remember("a", "um")
        self.remember("b", "dois")
        self.remember("a", "tres", workspace="projeto-x")

    def test_FORGET_all_counts_and_empties_every_workspace(self):
        self.seed()
        self.assertEqual(self.call("DELETE", "/v1/memory"), (200, {"deleted": 3}))
        self.assertEqual(self.entries("default"), [])
        self.assertEqual(self.entries("projeto-x"), [])
        self.assertEqual(self.call("DELETE", "/v1/memory"), (200, {"deleted": 0}))

    def test_FORGET_all_counts_past_the_list_limit_of_50(self):
        self.make_engine()
        for number in range(60):
            self.remember("k%d" % number, "texto %d" % number)
        self.assertEqual(len(self.entries()), 50)
        self.assertEqual(self.call("DELETE", "/v1/memory"), (200, {"deleted": 60}))

    def test_FORGET_all_can_be_limited_to_one_workspace(self):
        self.seed()
        self.assertEqual(self.call("DELETE", "/v1/memory?workspace=projeto-x"), (200, {"deleted": 1}))
        self.assertEqual(sorted(self.by_text()), ["dois", "um"])
        self.assertEqual(self.entries("projeto-x"), [])

    def test_FORGET_all_refuses_a_blank_bad_or_unknown_query_instead_of_wiping(self):
        self.seed()
        for query in ("?workspace=", "?workspace=../x", "?workspace=a%20b", "?worspace=projeto-x", "?q=um"):
            with self.subTest(query=query):
                code, resp = self.call("DELETE", "/v1/memory" + query)
                self.assertEqual(code, 400)
                self.assertEqual(resp["error"]["code"], "invalid_request")
                self.assertEqual(len(self.entries("default")) + len(self.entries("projeto-x")), 3)

    def test_FORGET_all_requires_the_token(self):
        self.seed()
        self.assertEqual(self.call("DELETE", "/v1/memory", auth=False)[0], 401)
        self.assertEqual(len(self.entries("default")) + len(self.entries("projeto-x")), 3)


class LegacyMemory(MemoryBase):
    def build_legacy_db(self, trigger=None):
        self.home.mkdir(parents=True)
        path = self.home / "engine.sqlite3"
        with contextlib.closing(sqlite3.connect(str(path))) as db:
            db.execute(OLD_MEMORY_DDL)
            db.executemany("INSERT INTO memory VALUES(?,?,?,?)", LEGACY)
            if trigger:
                db.execute(trigger)
            db.commit()
        return path

    def test_LEGACY_old_rows_are_completed_without_losing_anything(self):
        self.build_legacy_db()
        self.make_engine()
        got = self.entries("default") + self.entries("projeto-x")
        self.assertEqual(len(got), 3)
        self.assertEqual(len({e["id"] for e in got}), 3)
        by_key = {(e["key"], e["text"]): e for e in got}
        for _, key, value, updated in LEGACY:
            item = by_key[(key, value)]
            self.assertEqual(item["value"], value)
            self.assertEqual(item["kind"], "preference")
            self.assertEqual(item["source"], "user")
            # so existia a data da ultima gravacao: ela vira createdAt
            self.assertEqual((item["createdAt"], item["updated"]), (updated, updated))
        # esquecer funciona nas lembrancas antigas e os ids sobrevivem a um reinicio
        ids = {(e["key"], e["text"]): e["id"] for e in got}
        self.assertEqual(self.call("DELETE", "/v1/memory/" + ids[("style", "verbose")])[0], 204)
        self.engine.close()
        self.make_engine()
        again = {(e["key"], e["text"]): e["id"] for e in self.entries("default") + self.entries("projeto-x")}
        self.assertEqual(again, {k: v for k, v in ids.items() if k != ("style", "verbose")})

    def test_LEGACY_completion_is_all_or_nothing(self):
        path = self.build_legacy_db(
            "CREATE TRIGGER falha BEFORE UPDATE ON memory WHEN NEW.value='pt-BR simples' "
            "BEGIN SELECT RAISE(ABORT,'disco cheio'); END")
        from aura_engine.store import Store
        opened, real_connect = [], sqlite3.connect
        # o Store que falha no construtor nao devolve a conexao: guardo para fechar (Windows trava o arquivo)
        self.addCleanup(lambda: [connection.close() for connection in opened])

        def spy(*args, **kwargs):
            opened.append(real_connect(*args, **kwargs))
            return opened[-1]
        with unittest.mock.patch("aura_engine.store.sqlite3.connect", side_effect=spy):
            with self.assertRaises(sqlite3.Error):
                Store(path)
        for connection in opened:
            connection.close()
        with contextlib.closing(sqlite3.connect(str(path))) as db:
            self.assertEqual([r[1] for r in db.execute("PRAGMA table_info(memory)")],
                             ["workspace", "key", "value", "updated"])
            self.assertEqual(sorted(db.execute("SELECT workspace,key,value,updated FROM memory")),
                             sorted(LEGACY))
            db.execute("DROP TRIGGER falha")
            db.commit()
        self.make_engine()  # sem a falha, a mesma base completa tudo
        self.assertEqual(len(self.entries("default") + self.entries("projeto-x")), 3)


if __name__ == "__main__":
    unittest.main()
