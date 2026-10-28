"""PATH do motor: o do processo + os lugares onde as CLIs instaladas no Termux ficam.

O motor sobe por um script e o PATH dele pode nao ter ~/.local/bin nem o prefixo do npm; sem isto,
um `claude` recem-instalado existiria mas o motor diria "nao esta instalado"."""
import os
import shutil


def extra_bin_dirs():
    home = os.path.expanduser("~")
    dirs = [os.path.join(home, ".local", "bin")]
    for var in ("PREFIX", "NPM_CONFIG_PREFIX"):
        value = os.environ.get(var)
        if value:
            dirs.append(os.path.join(value, "bin"))
    dirs.append(os.path.join(home, ".npm-global", "bin"))
    return dirs


def search_path():
    parts = [p for p in os.environ.get("PATH", os.defpath).split(os.pathsep) if p]
    for directory in extra_bin_dirs():
        if directory not in parts and os.path.isdir(directory):
            parts.append(directory)
    return os.pathsep.join(parts)


def which(name):
    return shutil.which(name, path=search_path())


def child_env(extra=None):
    """Ambiente para filhos: o do processo com o PATH ampliado (o filho acha o `claude` recem-instalado)."""
    env = dict(os.environ)
    env["PATH"] = search_path()
    if extra:
        env.update(extra)
    return env
