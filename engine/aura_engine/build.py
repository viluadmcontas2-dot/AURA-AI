"""Identidade do codigo do motor: sha256 curto do conteudo de aura_engine/.

O app compara o `engine_build` do /v1/health com o do motor que viaja no APK e reinstala/reinicia quando diferem.
Algoritmo (o app calcula igual): para cada arquivo de aura_engine/ (recursivo, sem __pycache__ e sem *.pyc),
em ordem pelo caminho relativo com "/" (comparacao de bytes UTF-8), alimenta o sha256 com
  caminho_relativo + b"\\0" + conteudo_em_bytes + b"\\0"
e devolve os 12 primeiros caracteres hexadecimais."""
import hashlib
import pathlib

PACKAGE_DIR = pathlib.Path(__file__).resolve().parent
LENGTH = 12


def engine_build(root=None):
    root = pathlib.Path(root or PACKAGE_DIR)
    files = []
    for path in root.rglob("*"):
        parts = path.relative_to(root).parts
        if "__pycache__" in parts or path.suffix == ".pyc" or not path.is_file():
            continue
        files.append(("/".join(parts), path))
    digest = hashlib.sha256()
    for rel, path in sorted(files, key=lambda item: item[0].encode("utf-8")):
        digest.update(rel.encode("utf-8") + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()[:LENGTH]
