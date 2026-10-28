"""Contrato da reforma: invariantes 1–8, persistência e roteamento offline."""
import hashlib
import importlib
import itertools
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor

from aura_engine.adapters import ActionError


class ReformLedgerTest(unittest.TestCase):
    def setUp(self):
        try:
            self.module = importlib.import_module('aura_engine.reform_ledger')
        except ModuleNotFoundError:
            self.fail('ReformLedger ainda não implementado')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.ledger = self.module.ReformLedger(self.root)

    def scope(self):
        return self.ledger.lock_scope('/input/org.example.player.apk', 'rebuild', 95)

    def add(self, kind, payload, key=None):
        return self.ledger.append(kind, payload, key or kind)

    def approval(self, target, kind='tool_call', cost_class=None):
        return self.add('approval', dict(target=target, kind=kind, cost_class=cost_class,
                                        consequence='Executar a operação', approved_by='owner'),
                        'approve:' + target)

    def tool(self, key='install', **changes):
        payload = dict(tool='install', input_hash='a' * 64, mutates_device=True,
                       risk='write', cost_class='free', action='install',
                       package='org.example.player.rebuilt')
        payload.update(changes)
        return self.ledger.record_tool_call(idempotency_key=key, **payload)

    def test_1_and_2_any_apk_without_legal_acceptance(self):
        for index, apk in enumerate(('com.android.settings.apk', 'COM.ANDROID.phone.apk',
                                      'Câmera de ré.apk', 'vendor.rearview.apk',
                                      'vendor.reverse.apk', 'copyright.apk')):
            with self.subTest(apk=apk):
                ledger = self.module.ReformLedger(self.root / str(index))
                try:
                    entry = ledger.lock_scope(apk_path=apk, mode='rebuild', goal=95)
                except (ActionError, TypeError) as exc:
                    self.fail('Qualquer APK deve ser aceito sem aceite: ' + str(exc))
                self.assertEqual(apk, entry['payload']['apk_path'])
                self.assertNotIn('owner_confirmed', entry['payload'])
                self.assertTrue(ledger.verify_chain())

    def test_scope_is_idempotent_and_fixed(self):
        entry = self.scope()
        self.assertEqual(entry, self.scope())
        with self.assertRaises(ActionError):
            self.ledger.lock_scope('other.apk', 'rebuild', 90)

    def test_2_reversal_uses_last_evaluation(self):
        self.scope()
        self.add('obligation_added', {'id': 'one'})
        self.assertIsNone(self.ledger.reversal_pct())
        self.add('coverage_evaluated', {'satisfied': 1, 'total': 4}, 'cov1')
        self.assertEqual(25, self.ledger.reversal_pct())
        self.add('coverage_evaluated', {'satisfied': 3, 'total': 4}, 'cov2')
        self.assertEqual(75, self.ledger.reversal_pct())

    def test_3_rebuild_uses_latest_counts(self):
        self.scope()
        self.assertIsNone(self.ledger.rebuild_pct())
        self.add('rebuild_progress', {'ready': 1, 'total': 5}, 'build1')
        self.add('rebuild_progress', {'ready': 2, 'total': 5}, 'build2')
        self.assertEqual(40, self.ledger.rebuild_pct())

    def test_4_fidelity_requires_test_run(self):
        self.scope()
        self.assertIsNone(self.ledger.fidelity())
        for test_id in (None, '', '  ', 123):
            with self.subTest(test_id=test_id), self.assertRaises(ActionError):
                self.add('fidelity_measured', {'passed': 3, 'total': 4, 'test_run_id': test_id})
        self.assertIsNone(self.ledger.fidelity())
        self.add('fidelity_measured', {'passed': 3, 'total': 4, 'test_run_id': 'run-123'})
        self.assertEqual(75, self.ledger.fidelity())

    def test_counts_reject_invalid_values_zero_total_is_unmeasured(self):
        self.scope()
        for kind, field, read in (('coverage_evaluated', 'satisfied', self.ledger.reversal_pct),
                                  ('rebuild_progress', 'ready', self.ledger.rebuild_pct),
                                  ('fidelity_measured', 'passed', self.ledger.fidelity)):
            for n, total in ((-1, 4), (5, 4), (True, 4), (1, -1), (1, 0), (1, 2.5)):
                with self.subTest(kind=kind, n=n, total=total), self.assertRaises(ActionError):
                    self.add(kind, {field: n, 'total': total, 'test_run_id': 'run'})
            self.add(kind, {field: 0, 'total': 0, 'test_run_id': 'run'})
            self.assertIsNone(read())

    def test_tools_are_recorded_without_special_approval(self):
        self.scope()
        for tool in ('install', 'uninstall', 'adb_uninstall', 'arbitrary_new_tool'):
            with self.subTest(tool=tool):
                entry = self.tool(key=tool, tool=tool, risk='read',
                                  package='org.example.player')
                self.assertEqual(tool, entry['payload']['tool'])
                self.assertTrue(entry['payload']['mutates_device'])
                self.assertEqual(entry, self.tool(key=tool, tool=tool))
        self.assertTrue(self.ledger.verify_chain())

    def test_raw_scope_has_no_legal_gate(self):
        entry = self.add('scope_locked', dict(apk_path='com.android.settings.apk',
                                             mode='rebuild', goal=90))
        self.assertEqual('scope_locked', entry['kind'])

    def test_6_paid_model_needs_specific_cost_approval(self):
        self.scope()
        call = dict(provider='gemini_api', cost_class='paid', tokens=123, idempotency_key='model1')
        with self.assertRaises(ActionError):
            self.ledger.record_model_call(**call)
        self.approval('model1', 'model_call', 'free')
        with self.assertRaises(ActionError):
            self.ledger.record_model_call(**call)
        self.add('approval', dict(target='model1', kind='model_call', cost_class='paid',
                                 consequence='Cobrança desta chamada', approved_by='owner'), 'paid-ok')
        self.assertEqual(123, self.ledger.record_model_call(**call)['payload']['tokens'])
        with self.assertRaises(ActionError):
            self.add('model_call', dict(provider='gemini_api', cost_class='paid', tokens=1), 'other')
        self.ledger.record_model_call('agy', 'subscription', 42, 'agy1')

    def test_7_restart_replays_same_entry_without_appending(self):
        self.scope()
        self.assertIsNone(self.ledger.resume_point())
        self.add('step_done', {'step': 'inventory', 'proof': 'ev1'}, 'step1')
        last = self.add('step_done', {'step': 'rebuild', 'proof': 'ev2'}, 'step2')
        self.add('checkpoint', {})
        before = (self.root / 'ledger.jsonl').read_bytes()
        restarted = self.module.ReformLedger(self.root)
        self.assertEqual(last, restarted.resume_point())
        self.assertEqual(last, restarted.append('step_done', {'step': 'changed'}, 'step2'))
        self.assertEqual(before, (self.root / 'ledger.jsonl').read_bytes())

    def test_8_summary_claims_have_source_seq(self):
        scope = self.scope()
        cov = self.add('coverage_evaluated', {'satisfied': 1, 'total': 4})
        build = self.add('rebuild_progress', {'ready': 2, 'total': 5})
        measured = self.add('fidelity_measured', {'passed': 3, 'total': 4, 'test_run_id': 'run'})
        model = self.ledger.record_model_call('agy', 'subscription', 42, 'agy1')
        summary = self.ledger.summary()
        self.assertEqual({'reversal', 'rebuild', 'fidelity', 'scope', 'chain_ok', 'model_calls'}, set(summary))
        for name, pct, seq in (('reversal', 25, cov['seq']), ('rebuild', 40, build['seq']),
                               ('fidelity', 75, measured['seq'])):
            self.assertEqual({'pct': pct, 'seq': seq}, summary[name])
        self.assertEqual(scope, summary['scope'])
        self.assertEqual([model], summary['model_calls'])
        self.assertTrue(summary['chain_ok'])

    def test_empty_summary(self):
        self.assertEqual(dict(reversal=None, rebuild=None, fidelity=None, scope=None,
                              chain_ok=True, model_calls=[]), self.ledger.summary())

    def test_hash_chain_matches_canonical_json_and_preserves_prefix(self):
        first = self.scope()
        prefix = (self.root / 'ledger.jsonl').read_bytes()
        second = self.add('checkpoint', {'z': 1, 'a': 'ação'})
        self.assertTrue((self.root / 'ledger.jsonl').read_bytes().startswith(prefix))
        self.assertEqual([1, 2], [first['seq'], second['seq']])
        self.assertEqual(first['hash'], second['prev_hash'])
        body = {k: v for k, v in second.items() if k != 'hash'}
        canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
        self.assertEqual(hashlib.sha256((first['hash'] + canonical).encode()).hexdigest(), second['hash'])
        self.assertTrue(self.ledger.verify_chain())

    def test_tampering_invalidates_reads_and_blocks_appends(self):
        self.scope()
        self.add('coverage_evaluated', {'satisfied': 1, 'total': 4})
        path = self.root / 'ledger.jsonl'
        good = path.read_bytes()
        for bad in (good.replace(b'"satisfied":1', b'"satisfied":4'),
                    good + b'{broken', b'[]\n', b'\xff\n', good.splitlines(keepends=True)[1]):
            path.write_bytes(bad)
            with self.subTest(bad=bad[:20]):
                self.assertFalse(self.ledger.verify_chain())
                summary = self.ledger.summary()
                self.assertFalse(summary['chain_ok'])
                self.assertIsNone(summary['reversal'])
                with self.assertRaises(ActionError):
                    self.add('checkpoint', {})
                with self.assertRaises(ActionError):
                    self.ledger.reversal_pct()
        path.write_bytes(good)
        self.assertTrue(self.ledger.verify_chain())

    def test_sqlite_index_rebuilt_from_log_on_restart(self):
        self.scope()
        entry = self.add('step_done', {'step': 'inventory', 'proof': 'ev1'})
        index = self.root / 'index.sqlite3'
        self.assertTrue(index.is_file())
        index.unlink()
        restarted = self.module.ReformLedger(self.root)
        self.assertEqual(entry, restarted.append('step_done', {}, 'step_done'))
        with sqlite3.connect(index) as db:
            self.assertEqual(2, db.execute('SELECT COUNT(*) FROM entries').fetchone()[0])

    def test_concurrent_instances_deduplicate(self):
        self.scope()
        def add(_):
            return self.module.ReformLedger(self.root).append('checkpoint', {}, 'same')
        with ThreadPoolExecutor(max_workers=4) as pool:
            entries = list(pool.map(add, range(12)))
        self.assertTrue(all(entry == entries[0] for entry in entries))
        self.assertEqual(2, len((self.root / 'ledger.jsonl').read_text().splitlines()))
        self.assertTrue(self.ledger.verify_chain())

    def test_choose_model_all_boolean_combinations(self):
        for agy, key, free, approved in itertools.product((False, True), repeat=4):
            expected = ('agy', 'subscription') if agy else (
                ('gemini_api', 'free') if key and free and approved else ('blocked', 'paid'))
            with self.subTest(agy=agy, key=key, free=free, approved=approved):
                self.assertEqual(expected, self.module.choose_model(agy, key, free, approved))
