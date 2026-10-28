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

    def test_engine_ci_follows_android_and_design_changes_for_exact_sha(self):
        workflow = (ROOT / ".github/workflows/engine-ci.yml").read_text(encoding="utf-8")
        for dependency in ("android/**", "docs/design/**", ".github/workflows/android-ci.yml",
                           "scripts/check_design_coverage.py", "scripts/test_design_coverage.py"):
            self.assertGreaterEqual(workflow.count('"' + dependency + '"'), 2, dependency)

    def test_debug_apk_upload_requires_release_gate(self):
        workflow = ANDROID_CI.read_text(encoding="utf-8")
        gate = "python3 scripts/check_design_coverage.py --release"
        upload = "- name: Upload debug APK"
        self.assertIn(gate, workflow)
        self.assertIn(upload, workflow)
        self.assertLess(workflow.index(gate), workflow.index(upload))
        self.assertIn(
            "if: success() && github.event_name == 'push' && github.ref == 'refs/heads/main' && steps.release_coverage.outputs.ready == 'true'",
            workflow)


if __name__ == "__main__":
    unittest.main()
