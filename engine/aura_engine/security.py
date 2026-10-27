"""Policy and workspace boundary. Never let model text grant permissions."""
import pathlib
import re
import os
import secrets


class RiskPolicy:
    def classify(self, request):
        kind = request.get("kind")
        action = request.get("action", "")
        if kind == "system":
            return "read" if action in {"health", "capabilities", "pwd", "doctor"} else "critical"
        if kind == "adb":
            return {"devices": "read", "getprop": "read", "screenshot": "read",
                    "connect": "write", "shell": "critical", "install": "critical"}.get(action, "critical")
        if kind == "files":
            return {"list": "read", "read": "read", "write": "write",
                    "mkdir": "write", "delete": "critical"}.get(action, "critical")
        if kind == "github":
            return {"status": "read", "list": "read", "diff": "read",
                    "clone": "write", "fetch": "write", "commit": "write",
                    "push": "critical", "create_repo": "critical",
                    "delete_repo": "critical"}.get(action, "critical")
        if kind == "drive":
            return {"list": "read", "download": "write", "upload": "critical"}.get(action, "critical")
        if kind == "skills":
            return "read" if action in ("list", "read") else "critical"
        if kind == "shell":
            return "critical"
        if kind in {"claude_cli", "gemini_cli", "agy_cli"}:
            return "write"
        if kind in {"claude_api", "gemini_api"}:
            return "spend"
        return "critical"


def workspace_name(name):
    if not isinstance(name, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}", name):
        raise ValueError("invalid workspace identifier")
    return name


def resolve_inside(root, user_path="."):
    root = pathlib.Path(root).resolve()
    if not isinstance(user_path, str) or not user_path or pathlib.Path(user_path).is_absolute():
        raise ValueError("path must be nonempty and workspace-relative")
    if ".." in pathlib.PurePath(user_path).parts:
        raise ValueError("parent traversal is forbidden")
    result = (root / user_path).resolve()
    if not result.is_relative_to(root):
        raise ValueError("path escapes workspace")
    return result


def safe_argv(argv):
    if not isinstance(argv, list) or not (1 <= len(argv) <= 128):
        raise ValueError("argv must contain between 1 and 128 elements")
    if any(not isinstance(x, str) or not x or "\x00" in x or len(x) > 10000 for x in argv):
        raise ValueError("invalid process argument")
    if "/" in argv[0] or "\\" in argv[0] or argv[0].startswith("-"):
        raise ValueError("executable must be an installed command name")
    return list(argv)


def load_or_create_token(home):
    """Create or load the Termux-scoped 256-bit bearer credential without opening the job DB."""
    home = pathlib.Path(home)
    home.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(home, 0o700)
    token_file = home / "token"
    if not token_file.exists():
        try:
            fd = os.open(str(token_file), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            pass  # Another authorised local engine process created it concurrently.
        else:
            with os.fdopen(fd, "w") as stream:
                stream.write(secrets.token_urlsafe(32))
    os.chmod(token_file, 0o600)
    token = token_file.read_text("utf-8").strip()
    if len(token) < 32:
        raise RuntimeError("Invalid local pairing token; refusing to start")
    return token
