"""Provas negativas do gate de design; execução exclusiva no GitHub Actions."""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts/check_design_coverage.py"
ANDROID_CI = ROOT / ".github/workflows/android-ci.yml"


class DesignCoverageGateTest(unittest.TestCase):
    def test_release_rejects_missing_fn_21_even_when_all_other_rows_pass(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "scripts").mkdir()
            shutil.copyfile(CHECKER, root / "scripts/check_design_coverage.py")
            screens = root / "docs/design/telas"
            screens.mkdir(parents=True)
            lines = ["| ID | Tela/Função | Status | Evidência |",
                     "|---|---|---|---|"]
            for i in range(1, 46):
                name = f"Screen-{i:02d}.dc.html"
                (screens / name).write_text("fixture", encoding="utf-8")
                lines.append(f"| UI-{i:02d} | `{name}` | feito | https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/1 |")
            for i in range(1, 21):
                lines.append(f"| FN-{i:02d} | Função {i} | feito | https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/1 |")
            (root / "docs/design/COBERTURA.md").write_text("\n".join(lines), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(root / "scripts/check_design_coverage.py"), "--release"],
                text=True, capture_output=True, check=False, timeout=10)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("FN-21", result.stdout + result.stderr)

    def test_release_rejects_unverifiable_evidence_on_all_66_items(self):
        """66 statuses feitos não equivalem a 66 provas verificáveis."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "scripts").mkdir()
            shutil.copyfile(CHECKER, root / "scripts/check_design_coverage.py")
            screens = root / "docs/design/telas"
            screens.mkdir(parents=True)
            rows = ["| ID | Tela/Função | Status | Evidência |",
                    "|---|---|---|---|"]
            for i in range(1, 46):
                name = f"Screen-{i:02d}.dc.html"
                (screens / name).write_text("fixture", encoding="utf-8")
                rows.append(f"| UI-{i:02d} | `{name}` | feito | OK |")
            for i in range(1, 22):
                rows.append(f"| FN-{i:02d} | Função {i} | feito | OK |")
            (root / "docs/design/COBERTURA.md").write_text("\n".join(rows), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(root / "scripts/check_design_coverage.py"), "--release"],
                text=True, capture_output=True, check=False, timeout=10)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("UI-01", result.stdout + result.stderr)
            self.assertIn("prova", (result.stdout + result.stderr).lower())

    def test_release_accepts_full_job_urls_and_device_report(self):
        """Referências auditáveis podem ser registradas sem inventar sucesso de CI."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "scripts").mkdir()
            shutil.copyfile(CHECKER, root / "scripts/check_design_coverage.py")
            screens = root / "docs/design/telas"
            screens.mkdir(parents=True)
            report_dir = root / "docs/design/evidence"
            report_dir.mkdir()
            device_ids = {
                "UI-05", "UI-06", "UI-08", "UI-09", "UI-14",
                "UI-15", "UI-16", "UI-35", "UI-43", "UI-44",
                "FN-01", "FN-02", "FN-10", "FN-11", "FN-13",
                "FN-15", "FN-16", "FN-18", "FN-20",
            }
            for ident in device_ids:
                (report_dir / f"{ident}.md").write_text(
                    "Dispositivo: fixture-sintetico\n"
                    "Procedimento: procedimento-fixture\n"
                    "Resultado: aprovado\n"
                    "Registro: id-fixture\n", encoding="utf-8")
            job = "https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/123/job/456"
            rows = ["| ID | Tela/Função | Status | Evidência |",
                    "|---|---|---|---|"]
            for i in range(1, 46):
                name = f"Screen-{i:02d}.dc.html"
                (screens / name).write_text("fixture", encoding="utf-8")
                ident = f"UI-{i:02d}"
                evidence = f"docs/design/evidence/{ident}.md" if ident in device_ids else job
                rows.append(f"| {ident} | `{name}` | feito | {evidence} |")
            for i in range(1, 22):
                ident = f"FN-{i:02d}"
                evidence = f"docs/design/evidence/{ident}.md" if ident in device_ids else job
                rows.append(f"| {ident} | Função {i} | feito | {evidence} |")
            (root / "docs/design/COBERTURA.md").write_text("\n".join(rows), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(root / "scripts/check_design_coverage.py"), "--release"],
                text=True, capture_output=True, check=False, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            failed_report = report_dir / "FN-10.md"
            failed_report.write_text(
                failed_report.read_text(encoding="utf-8").replace(
                    "Resultado: aprovado", "Resultado: falha observada"),
                encoding="utf-8")
            failed = subprocess.run(
                [sys.executable, str(root / "scripts/check_design_coverage.py"), "--release"],
                text=True, capture_output=True, check=False, timeout=10)
            self.assertNotEqual(failed.returncode, 0, failed.stdout)
            self.assertIn("FN-10", failed.stdout + failed.stderr)

    def test_release_does_not_accept_ci_job_as_physical_termux_proof(self):
        """CI verde não prova RUN_COMMAND, voz, IME e outros efeitos físicos."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "scripts").mkdir()
            shutil.copyfile(CHECKER, root / "scripts/check_design_coverage.py")
            screens = root / "docs/design/telas"
            screens.mkdir(parents=True)
            job = "https://github.com/viluadmcontas2-dot/AURA-AI/actions/runs/123/job/456"
            rows = ["| ID | Tela/Função | Status | Evidência |",
                    "|---|---|---|---|"]
            for i in range(1, 46):
                name = f"Screen-{i:02d}.dc.html"
                (screens / name).write_text("fixture", encoding="utf-8")
                rows.append(f"| UI-{i:02d} | `{name}` | feito | {job} |")
            for i in range(1, 22):
                rows.append(f"| FN-{i:02d} | Função {i} | feito | {job} |")
            (root / "docs/design/COBERTURA.md").write_text("\n".join(rows), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(root / "scripts/check_design_coverage.py"), "--release"],
                text=True, capture_output=True, check=False, timeout=10)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("FN-10", result.stdout + result.stderr)

    def test_engine_ci_follows_android_and_design_changes_for_exact_sha(self):
        workflow = (ROOT / ".github/workflows/engine-ci.yml").read_text(encoding="utf-8")
        for dependency in ("android/**", "docs/design/**", ".github/workflows/android-ci.yml",
                           "scripts/check_design_coverage.py", "scripts/test_design_coverage.py"):
            self.assertGreaterEqual(workflow.count('"' + dependency + '"'), 2, dependency)

    def test_debug_apk_is_available_for_internal_testing_only(self):
        """Debug artifact is not a release and must retain independent coverage reporting."""
        workflow = ANDROID_CI.read_text(encoding="utf-8")
        gate = "python3 scripts/check_design_coverage.py --release"
        upload = "- name: Upload debug APK"
        self.assertIn(gate, workflow)
        self.assertIn(upload, workflow)
        self.assertLess(workflow.index(gate), workflow.index(upload))
        upload_block = workflow.split(upload, 1)[1].split("\n      - name:", 1)[0]
        self.assertRegex(upload_block, r"(?m)^\s*if: success()\s*$")


    def test_design_coverage_runs_for_android_only_changes(self):
        """An Android-only PR must exercise the design gate on that exact SHA."""
        workflow = (ROOT / ".github/workflows/design-coverage.yml").read_text(encoding="utf-8")
        self.assertGreaterEqual(workflow.count('"android/**"'), 2,
                                "push and pull_request must both include android/**")

    def test_design_coverage_runs_for_engine_only_changes(self):
        """Engine-only changes can implement FN requirements and must run the design gate."""
        workflow = (ROOT / ".github/workflows/design-coverage.yml").read_text(encoding="utf-8")
        self.assertGreaterEqual(workflow.count('"engine/**"'), 2,
                                "push and pull_request must both include engine/**")

    def test_normal_accepts_task_column_from_pr2(self):
        """A matriz de execucao do PR #2 acrescenta Tarefa sem alterar o gate."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "scripts").mkdir()
            shutil.copyfile(CHECKER, root / "scripts/check_design_coverage.py")
            screens = root / "docs/design/telas"
            screens.mkdir(parents=True)
            rows = ["| ID | Tela/Função | Status | Evidência | Tarefa |",
                    "|---|---|---|---|---|"]
            for i in range(1, 46):
                name = f"Screen-{i:02d}.dc.html"
                (screens / name).write_text("fixture", encoding="utf-8")
                rows.append(f"| UI-{i:02d} | `{name}` | pendente | - | T17 |")
            for i in range(1, 22):
                rows.append(f"| FN-{i:02d} | Função {i} | pendente | - | T20 |")
            (root / "docs/design/COBERTURA.md").write_text("\n".join(rows), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(root / "scripts/check_design_coverage.py")],
                text=True, capture_output=True, check=False, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)



    def test_normal_rejects_ui_row_without_its_own_screen(self):
        """Cada uma das 45 linhas UI deve referenciar exatamente uma tela distinta."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "scripts").mkdir()
            shutil.copyfile(CHECKER, root / "scripts/check_design_coverage.py")
            screens = root / "docs/design/telas"
            screens.mkdir(parents=True)
            rows = ["| ID | Tela/Função | Status | Evidência | Tarefa |",
                    "|---|---|---|---|---|"]
            for i in range(1, 46):
                name = f"Screen-{i:02d}.dc.html"
                (screens / name).write_text("fixture", encoding="utf-8")
                description = (
                    "`Screen-01.dc.html` e `Screen-02.dc.html`" if i == 1 else
                    "Sem tela associada" if i == 2 else f"`{name}`"
                )
                rows.append(f"| UI-{i:02d} | {description} | pendente | - | T17 |")
            for i in range(1, 22):
                rows.append(f"| FN-{i:02d} | Função {i} | pendente | - | T20 |")
            (root / "docs/design/COBERTURA.md").write_text("\n".join(rows), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(root / "scripts/check_design_coverage.py")],
                text=True, capture_output=True, check=False, timeout=10)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("UI-02", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
