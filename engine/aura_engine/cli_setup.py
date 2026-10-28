"""Instala as CLIs dentro do Termux: claude, codex, gemini, agy, gh, rclone.

Acao `kind: tools, action: install, provider: <nome>` (HTTP: POST /v1/tools/install).
Mesma regra de rede da cadeia REA: dados moveis esperam o Wi-Fi (nao falha; rodar de novo retoma),
download grande so avisa. Cada comando passa por adapters.run_process (os testes trocam por um falso).
"""
import shlex

from . import adapters, rea_chain

NODE_PKGS = ["pkg", "install", "-y", "nodejs-lts", "git"]
NPM = {"claude": "@anthropic-ai/claude-code", "codex": "@openai/codex", "gemini": "@google/gemini-cli"}
EXE = {"claude": "claude", "codex": "codex", "gemini": "gemini", "agy": "agy", "gh": "gh", "rclone": "rclone"}
PKG = {"gh": "gh", "rclone": "rclone"}
AGY_URL = "https://antigravity.google/cli/install.sh"
# Caminho A do projeto MIT github.com/ferrumclaudepilgrim/claude-code-android (glibc-runner + binario
# linux-arm64 oficial remendado). O Claude Code so publica binario glibc: o npm nem sempre roda no Termux.
# Script de terceiro NUNCA roda sem pin + checksum: a URL fixa o COMMIT (nao a branch `main`) e o sha256 do
# install.sh desse commit e conferido antes de executar. Para atualizar: troque os dois juntos, depois de ler o diff.
CLAUDE_ANDROID_COMMIT = "a0477923457be0bd2d09580d46c697b0fb9d5c32"  # v2.9.5, 26/10/2026
CLAUDE_ANDROID_SHA256 = "307ee7ef73055fc6a8c6b55cb8c9eadf16ec945ef9207e4f22f5e79f38d1ccaa"
CLAUDE_ANDROID_URL = ("https://raw.githubusercontent.com/ferrumclaudepilgrim/claude-code-android/"
                      + CLAUDE_ANDROID_COMMIT + "/install.sh")
PROVIDERS = tuple(EXE)


def _works(exe, home, engine):
    if not adapters.installed(exe):
        return False
    try:
        adapters.run_process([exe, "--version"], home, engine, 60)
        return True
    except adapters.ActionError:
        return False


def _node_ready():
    return all(adapters.installed(x) for x in ("node", "npm", "git"))


def _npm_steps(provider):
    steps = [] if _node_ready() else [("node", [NODE_PKGS])]
    return steps + [("npm", [["npm", "install", "-g", NPM[provider]]])]


def _claude_fallback(home):
    script = str(home / "tools" / "claude-code-android-install.sh")
    # So executa se o sha256 bater (sha256sum -c falha = `&&` nao roda o script). O instalador faz 2 perguntas
    # (Termux novo? pacotes extras?): responde nao e nao, sem tocar no resto.
    run = ('echo "%s  %s" | sha256sum -c - && printf "n\\nn\\n" | bash %s'
           % (CLAUDE_ANDROID_SHA256, shlex.quote(script), shlex.quote(script)))
    return [("glibc-runner", [["curl", "-fsSL", "-o", script, CLAUDE_ANDROID_URL], ["sh", "-c", run]])]


def _agy_steps(home):
    script = str(home / "tools" / "agy-install.sh")
    return [("agy", [["curl", "-fsSL", "-o", script, AGY_URL], ["bash", script]])]


def _run_steps(steps, provider, home, engine, job_id, notices):
    emit = engine.store.emit
    for name, commands in steps:
        event = {"id": job_id, "name": "%s:%s" % (provider, name)}
        if rea_chain.step_downloads(commands):
            mb = rea_chain.estimate_download_mb((name, commands), home, engine)
            if mb is not None and mb > rea_chain.NOTICE_MB:
                notices.append({"step": name, "mb": mb})
                emit("download.notice", {"id": job_id, "step": name, "mb": mb})
        emit("tool.install", dict(event, state="installing", pct=0))
        try:
            for argv in commands:
                adapters.run_process(argv, home, engine, 3600)
        except adapters.ActionError as err:
            if err.code == "cancelled":
                raise
            emit("tool.install", dict(event, state="failed", pct=0))
            raise
        emit("tool.install", dict(event, state="ready", pct=100))


def install(provider, engine):
    home = engine.home
    (home / "tools").mkdir(parents=True, exist_ok=True)
    job_id = getattr(engine.context, "job_id", None)
    exe = EXE[provider]
    if _works(exe, home, engine):
        engine.store.emit("tool.install", {"id": job_id, "name": provider, "state": "ready", "pct": 100})
        return {"provider": provider, "state": "ready", "already_installed": True, "via": None}
    if rea_chain.network_blocks(engine):
        engine.store.emit("tool.install", {"id": job_id, "name": provider, "state": "waiting_wifi", "pct": 0})
        return {"provider": provider, "state": "waiting_wifi", "via": None,
                "message": "Estou em dados móveis. Conecte ao Wi-Fi e toque em Tentar de novo."}
    notices, via = [], None
    try:
        if provider in NPM:
            _run_steps(_npm_steps(provider), provider, home, engine, job_id, notices)
            via = "npm"
            if not _works(exe, home, engine):
                raise adapters.ActionError("process_failed", "%s --version falhou" % exe)
        elif provider == "agy":
            _run_steps(_agy_steps(home), provider, home, engine, job_id, notices)
            via = "installer"
        else:
            _run_steps([(provider, [["pkg", "install", "-y", PKG[provider]]])],
                       provider, home, engine, job_id, notices)
            via = "pkg"
    except adapters.ActionError as err:
        if err.code == "cancelled":
            raise
        if provider == "claude":
            # npm falhou ou nao roda (so ha binario glibc): tenta o caminho glibc-runner
            try:
                _run_steps(_claude_fallback(home), provider, home, engine, job_id, notices)
                via = "glibc-runner"
            except adapters.ActionError as err2:
                raise adapters.ActionError(
                    "setup_failed", "Não consegui instalar o claude (npm e glibc-runner falharam): %s"
                    % adapters.redact(str(err2))[:300])
        elif provider == "codex":
            raise adapters.ActionError(
                "indisponivel_no_termux", "O Codex não instalou neste Termux "
                "(sem fonte confirmada para Android): %s" % adapters.redact(str(err))[:300])
        else:
            raise adapters.ActionError(
                "setup_failed", "Falhou instalar %s: %s. Toque em Tentar de novo."
                % (provider, adapters.redact(str(err))[:300]))
    if not _works(exe, home, engine):
        code = "indisponivel_no_termux" if provider == "codex" else "setup_failed"
        raise adapters.ActionError(code, "%s --version não respondeu depois de instalar" % exe)
    return {"provider": provider, "state": "ready", "already_installed": False, "via": via,
            "download_notices": notices}


def run(request, engine):
    if request.get("action") != "install":
        raise adapters.ActionError("unsupported_action", "Tools action unsupported")
    provider = request.get("provider")
    if provider not in EXE:
        raise adapters.ActionError("invalid_request", "provider must be one of: " + ", ".join(PROVIDERS))
    return install(provider, engine)
