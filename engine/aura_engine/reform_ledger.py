"""Ledger local; registra decisões, não executa ferramentas/modelos.

JSONL é a fonte de verdade; SQLite indexa chaves e serializa escritores.
Hash = SHA256(prev_hash + JSON ordenado de todos os campos exceto hash), UTF-8.
Gênese: prev_hash vazio, seq=1. Sem âncora externa não é possível detectar
remoção de sufixo ou recálculo de toda a cadeia por terceiros.
Aprovações devem vir do chamador autenticado, nunca do modelo.
"""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time

from .adapters import ActionError


KINDS = {'scope_locked', 'obligation_added', 'tool_call', 'evidence', 'unknown',
         'step_done', 'coverage_evaluated', 'rebuild_progress', 'model_call',
         'approval', 'fidelity_measured', 'checkpoint'}
COUNTS = {'coverage_evaluated': 'satisfied', 'rebuild_progress': 'ready',
          'fidelity_measured': 'passed'}


def _canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False)


def _hash(entry):
    body = {key: value for key, value in entry.items() if key != 'hash'}
    return hashlib.sha256((entry['prev_hash'] + _canonical(body)).encode('utf-8')).hexdigest()


def _require(condition, message):
    if not condition:
        raise ActionError('reform_refused', message)


def _text(value):
    return isinstance(value, str) and bool(value.strip())


def _last(entries, kind):
    return next((entry for entry in reversed(entries) if entry['kind'] == kind), None)


def _percent(entry):
    if entry is None or entry['payload']['total'] == 0:
        return None
    payload = entry['payload']
    return 100 * payload[COUNTS[entry['kind']]] / payload['total']


def choose_model(has_agy, has_gemini_key, key_is_free, approved_paid):
    """§4: agy primeiro; Gemini gratuito exige aceite; pago fica bloqueado.

    approved_paid é o único aceite disponível nesta assinatura, usado também
    para API gratuita. Aprovar nunca transforma um plano pago em gratuito.
    """
    if has_agy:
        return 'agy', 'subscription'
    if has_gemini_key and key_is_free and approved_paid:
        return 'gemini_api', 'free'
    return 'blocked', 'paid'


class ReformLedger:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.directory / 'ledger.jsonl'
        self.index_path = self.directory / 'index.sqlite3'
        with self._transaction() as db:
            try:
                entries = self._read()
            except ActionError:
                return  # permite consultar chain_ok=False
            self._index(db, entries)

    @contextmanager
    def _transaction(self):
        db = sqlite3.connect(str(self.index_path), timeout=15)
        try:
            db.execute('BEGIN IMMEDIATE')
            db.execute('CREATE TABLE IF NOT EXISTS entries('
                       'seq INTEGER PRIMARY KEY, idempotency_key TEXT UNIQUE NOT NULL, '
                       'hash TEXT NOT NULL)')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _index(db, entries):
        # Recupera queda entre fsync(JSONL) e commit(SQLite).
        db.execute('DELETE FROM entries')
        db.executemany('INSERT INTO entries VALUES(?,?,?)',
                       [(e['seq'], e['idempotency_key'], e['hash']) for e in entries])

    def _read(self):
        entries, keys, previous = [], set(), ''
        try:
            with self.path.open(encoding='utf-8') as source:
                for seq, line in enumerate(source, 1):
                    entry = json.loads(line)
                    if (not line.endswith('\n') or not isinstance(entry, dict)
                            or set(entry) != {'seq', 'ts', 'kind', 'idempotency_key',
                                              'prev_hash', 'hash', 'payload'}
                            or type(entry['seq']) is not int or entry['seq'] != seq
                            or entry['prev_hash'] != previous
                            or not _text(entry['idempotency_key'])
                            or entry['idempotency_key'] in keys
                            or entry['hash'] != _hash(entry)):
                        raise ValueError('chain')
                    self._validate(entry['kind'], entry['payload'], entry['idempotency_key'], entries)
                    keys.add(entry['idempotency_key'])
                    previous = entry['hash']
                    entries.append(entry)
        except FileNotFoundError:
            return []
        except (ValueError, TypeError, KeyError, UnicodeError, ActionError):
            raise ActionError('invalid_chain', 'Ledger inválido: restaure uma cópia íntegra.') from None
        return entries

    def verify_chain(self):
        with self._transaction():
            try:
                self._read()
                return True
            except ActionError:
                return False

    @staticmethod
    def _approved(entries, key, kind, cost_class=None):
        for entry in entries:
            payload = entry['payload']
            if (entry['kind'] == 'approval' and payload.get('target') == key
                    and payload.get('kind') == kind
                    and (cost_class is None or payload.get('cost_class') == cost_class)):
                return True
        return False

    def _validate(self, kind, payload, key, entries):
        _require(isinstance(kind, str) and kind in KINDS, 'Escolha um tipo de entrada válido.')
        _require(isinstance(payload, dict), 'Informe os dados como objeto JSON.')
        scope = _last(entries, 'scope_locked')
        if kind == 'scope_locked':
            _require(scope is None, 'O escopo já está travado: crie outro projeto.')
            _require(_text(payload.get('apk_path')), 'Informe o caminho do APK.')
            _require(_text(payload.get('mode')), 'Informe o modo da reforma.')
            goal = payload.get('goal')
            _require(type(goal) in (int, float) and 0 <= goal <= 100,
                     'Informe uma meta entre 0 e 100.')
            return
        _require(scope is not None, 'Defina o escopo antes de continuar.')
        if kind in COUNTS:
            done, total = payload.get(COUNTS[kind]), payload.get('total')
            _require(type(done) is int and type(total) is int and 0 <= done <= total,
                     'Informe contagens inteiras: concluídas entre zero e total.')
            if kind == 'fidelity_measured':
                _require(_text(payload.get('test_run_id')), 'Execute um teste real e informe test_run_id.')
        elif kind == 'approval':
            _require(_text(payload.get('target')) and _text(payload.get('consequence'))
                     and _text(payload.get('approved_by'))
                     and payload.get('kind') in ('tool_call', 'model_call'),
                     'Informe alvo, tipo, consequência e quem aprovou.')
        elif kind == 'tool_call':
            _require(_text(payload.get('tool')) and _text(payload.get('input_hash')),
                     'Informe a ferramenta e o hash da entrada.')
            _require(type(payload.get('mutates_device')) is bool, 'Informe se altera o aparelho.')
        elif kind == 'model_call':
            _require(_text(payload.get('provider')) and payload.get('cost_class') in
                     ('free', 'subscription', 'paid'), 'Informe provedor e classe de custo.')
            _require(type(payload.get('tokens')) is int and payload['tokens'] >= 0,
                     'Informe a quantidade de tokens consumidos.')
            if payload['cost_class'] == 'paid':
                _require(self._approved(entries, key, kind, 'paid'),
                         'Custo bloqueado: aprove especificamente esta chamada paga.')
        elif kind == 'step_done':
            _require(_text(payload.get('step')) and _text(payload.get('proof')),
                     'Informe o passo concluído e sua prova.')

    def append(self, kind, payload, idempotency_key):
        """Mesma chave devolve a entrada original, mesmo se o payload mudou."""
        _require(_text(idempotency_key), 'Informe uma chave estável para retomar sem duplicar.')
        with self._transaction() as db:
            entries = self._read()
            self._index(db, entries)
            row = db.execute('SELECT seq FROM entries WHERE idempotency_key=?',
                             (idempotency_key,)).fetchone()
            if row is not None:
                return entries[row[0] - 1]
            self._validate(kind, payload, idempotency_key, entries)
            entry = dict(seq=len(entries) + 1, ts=time.time(), kind=kind,
                         idempotency_key=idempotency_key,
                         prev_hash=entries[-1]['hash'] if entries else '', payload=payload)
            try:
                entry['hash'] = _hash(entry)
                line = _canonical(entry) + '\n'
            except (TypeError, ValueError):
                raise ActionError('reform_refused', 'Use somente dados JSON finitos.') from None
            with self.path.open('a', encoding='utf-8') as target:
                target.write(line)
                target.flush()
                os.fsync(target.fileno())
            db.execute('INSERT INTO entries VALUES(?,?,?)',
                       (entry['seq'], idempotency_key, entry['hash']))
            return json.loads(line)

    def lock_scope(self, apk_path, mode, goal):
        payload = dict(apk_path=apk_path, mode=mode, goal=goal)
        key = 'scope:' + hashlib.sha256(_canonical(payload).encode()).hexdigest()
        return self.append('scope_locked', payload, key)

    def record_tool_call(self, tool, input_hash, mutates_device, risk, cost_class,
                         idempotency_key, **details):
        payload = dict(details, tool=tool, input_hash=input_hash, mutates_device=mutates_device,
                       risk=risk, cost_class=cost_class)
        return self.append('tool_call', payload, idempotency_key)

    def record_model_call(self, provider, cost_class, tokens, idempotency_key):
        return self.append('model_call', dict(provider=provider, cost_class=cost_class, tokens=tokens),
                           idempotency_key)

    def _latest(self, kind):
        with self._transaction():
            return _last(self._read(), kind)

    def fidelity(self):
        return _percent(self._latest('fidelity_measured'))

    def reversal_pct(self):
        return _percent(self._latest('coverage_evaluated'))

    def rebuild_pct(self):
        return _percent(self._latest('rebuild_progress'))

    def entries(self, since=0):
        """Entradas com seq > since, em ordem. Cadeia inválida levanta invalid_chain."""
        with self._transaction():
            return [entry for entry in self._read() if entry['seq'] > since]

    def find(self, idempotency_key):
        """Entrada gravada com essa chave, ou None (não grava nada)."""
        with self._transaction():
            return next((e for e in self._read() if e['idempotency_key'] == idempotency_key), None)

    def resume_point(self):
        """Entrada step_done inteira, incluindo seq e prova; None se não existe."""
        return self._latest('step_done')

    def summary(self):
        """Medidas {pct, seq}; escopo/chamadas inteiros para auditoria.

        Cadeia inválida não fornece afirmações derivadas de dados adulterados.
        """
        result = dict(reversal=None, rebuild=None, fidelity=None, scope=None,
                      chain_ok=True, model_calls=[])
        with self._transaction():
            try:
                entries = self._read()
            except ActionError:
                result['chain_ok'] = False
                return result
        for name, kind in (('reversal', 'coverage_evaluated'), ('rebuild', 'rebuild_progress'),
                           ('fidelity', 'fidelity_measured')):
            entry = _last(entries, kind)
            if entry is not None:
                result[name] = dict(pct=_percent(entry), seq=entry['seq'])
        result['scope'] = _last(entries, 'scope_locked')
        result['model_calls'] = [entry for entry in entries if entry['kind'] == 'model_call']
        return result
