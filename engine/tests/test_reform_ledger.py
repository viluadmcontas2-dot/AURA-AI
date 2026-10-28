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
        return self.ledger.lock_scope('org.example.player', 'rebuild', True, 95)

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

    def test_1_scope_required_even_for_raw_append(self):
        for kind in ('checkpoint', 'tool_call', 'model_call', 'approval', 'step_done'):
            with self.subTest(kind=kind), self.assertRaises(ActionError):
                self.add(kind, {})
        for accepted in (False, 'yes', 1, None):
            with self.subTest(accepted=accepted), self.assertRaises(ActionError):
                self.ledger.lock_scope('org.example.player', 'rebuild', accepted, 95)
        self.scope()
        self.add('checkpoint', {})
        with self.assertRaises(ActionError):
            self.ledger.lock_scope('org.other.app', 'rebuild', True, 90)

    def test_1_protected_apps_and_raw_scope_cannot_bypass(self):
        for app in ('com.android.settings', 'COM.ANDROID.phone', 'camera de re',
                    'Câmera de ré', 'vendor.rearview', 'vendor.reverse'):
            with self.subTest(app=app), self.assertRaises(ActionError):
                self.add('scope_locked', dict(app=app, mode='rebuild', owner_confirmed=True, goal=90))
        self.assertTrue(self.ledger.verify_chain())

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

    def test_5_mutation_needs_prior_targeted_approval_and_medium_risk(self):
        self.scope()
        with self.assertRaises(ActionError):
            self.tool()
        self.approval('different')
        with self.assertRaises(ActionError):
            self.tool()
        self.approval('install')
        with self.assertRaises(ActionError):
            self.tool(risk='read')
        entry = self.tool()
        self.assertEqual('tool_call', entry['kind'])
        self.assertEqual(entry, self.tool())
        self.ledger.record_tool_call(tool='inventory', input_hash='b' * 64,
                                    mutates_device=False, risk='read', cost_class='free',
                                    idempotency_key='inventory')

    def test_5_original_never_removed_or_overwritten(self):
        self.scope()
        for action in ('uninstall', 'install'):
            self.approval(action)
            with self.subTest(action=action), self.assertRaises(ActionError):
                self.tool(key=action, action=action, package='org.example.player')

    def test_5_original_protection_cannot_be_hidden_by_tool_or_action(self):
        self.scope()
        self.approval('install')
        for tool, action in (('uninstall', 'inventory'), ('adb_uninstall', 'adb_uninstall'),
                             ('install', 'inventory')):
            with self.subTest(tool=tool, action=action), self.assertRaises(ActionError):
                self.tool(tool=tool, action=action, package='org.example.player')

    def test_5_malformed_action_is_refused_cleanly(self):
        self.scope()
        self.approval('install')
        for action in (None, 123, [], ''):
            with self.subTest(action=action), self.assertRaises(ActionError):
                self.tool(action=action)

    def test_5_raw_append_checks_mutation(self):
        self.scope()
        with self.assertRaises(ActionError):
            self.add('tool_call', dict(tool='install', mutates_device=True, risk='write'))
        with self.assertRaises(ActionError):
            self.tool(mutates_device='false')

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
