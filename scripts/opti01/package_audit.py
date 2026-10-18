"""Curate, hash, archive and verify the actual handoff. Never packages a .git directory."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile
from datetime import datetime, timezone


def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def manifest(stage):
    records = {path.relative_to(stage).as_posix(): sha(path) for path in sorted(stage.rglob("*"))
               if path.is_file() and path.name != "MANIFEST-SHA256.json"}
    (stage / "MANIFEST-SHA256.json").write_text(json.dumps(records, indent=2), encoding="utf-8")


def archive(stage, destination):
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
        for path in sorted(stage.rglob("*")):
            if path.is_file():
                output.write(path, path.relative_to(stage))


def verify(extracted):
    entries = json.loads((extracted / "MANIFEST-SHA256.json").read_text(encoding="utf-8"))
    errors = [name for name, value in entries.items() if not (extracted / name).is_file() or sha(extracted / name) != value]
    missing = []
    links = 0
    for path in extracted.rglob("*.md"):
        for target in re.findall(r"!?\[[^\]]*\]\(([^)]+)\)", path.read_text(encoding="utf-8-sig")):
            target = target.split("#", 1)[0].strip("<>")
            if not target or "://" in target or target.startswith("mailto:"):
                continue
            links += 1
            if not (path.parent / target).exists():
                missing.append(f"{path.relative_to(extracted)} -> {target}")
    if errors or missing:
        raise RuntimeError(json.dumps({"hashErrors": errors, "missingLinks": missing}, indent=2))
    return {"sha256VerifiedFiles": len(entries), "relativeLinksVerified": links, "missingLinks": [], "hashErrors": [],
            "verifiedAfterExtraction": True, "manifestSelfHashExcluded": True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("repo", type=Path)
    parser.add_argument("source_workspace", type=Path)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    root, repo, workspace = args.results.resolve(), args.repo.resolve(), args.source_workspace.resolve()
    stage = Path(tempfile.mkdtemp(prefix="audit-opti-01-", dir=root.parent))
    proof = stage / "docs/optimisations/01-board-compact"
    for name in ("01-board-compact.md", "01-diagnostic-initial.md"):
        copy(repo / "docs/optimisations" / name, stage / "docs/optimisations" / name)
    for path in (repo / "docs/optimisations/01-board-compact").rglob("*"):
        if path.is_file(): copy(path, proof / path.relative_to(repo / "docs/optimisations/01-board-compact"))
    for path in (root / "profiles").rglob("*.jfr"):
        copy(path, proof / "profiles" / path.name)
    for label in ("before", "after"):
        copy(root / "builds" / label / "source/target/pixel-war-0.0.1-SNAPSHOT.jar", stage / "binaries" / f"{label}.jar")
        copy(root / "builds" / label / "source/target/storage-agent.jar", stage / "binaries" / f"storage-agent-{label}.jar")
    copy(repo / "scripts/benchmark-opti-01.ps1", stage / "scripts/benchmark-opti-01.ps1")
    for path in (repo / "scripts/opti01").glob("*"):
        if path.is_file(): copy(path, stage / "scripts/opti01" / path.name)
    copy(workspace / "docs/Optimisation-01-board-compact-prompt-codex.md", stage / "instructions/mission.md")
    copy(workspace / "docs/Pixel-War-regles-optimisation.md", stage / "instructions/regles.md")
    (stage / "LISEZ-MOI.md").write_text("""# Remise — optimisation 01

Ouvrir [le rapport autonome](docs/optimisations/01-board-compact.md), puis [l’index des preuves](docs/optimisations/01-board-compact/INDEX.md).

Les JFR originaux sont présents dans les preuves, les deux JAR réellement mesurés dans `binaries/`, et les scripts dans `scripts/`. Le manifeste SHA-256 couvre tous les fichiers sauf lui-même. `VERIFICATION.json` décrit le contrôle après extraction.

La reproduction nécessite le dépôt Pixel War avec les références Git du rapport, JDK 27, Maven, PowerShell Windows et Python avec `scripts/opti01/requirements.txt`. Aucun dossier `.git` ni secret n’est inclus. Cette remise constitue une étape mémoire/localité, pas l’audit final.
""", encoding="utf-8")
    (stage / "VERIFICATION.json").write_text("{}", encoding="utf-8")
    manifest(stage)
    args.archive.parent.mkdir(parents=True, exist_ok=True)
    if args.archive.exists():
        suffix = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        args.archive.rename(args.archive.with_name(args.archive.name + ".previous-" + suffix))
    archive(stage, args.archive)
    first = Path(tempfile.mkdtemp(prefix="verify-first-", dir=root.parent))
    with zipfile.ZipFile(args.archive) as value: value.extractall(first)
    result = verify(first)
    (stage / "VERIFICATION.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    manifest(stage)
    archive(stage, args.archive)
    second = Path(tempfile.mkdtemp(prefix="verify-final-", dir=root.parent))
    with zipfile.ZipFile(args.archive) as value: value.extractall(second)
    final = verify(second)
    if final != result:
        raise RuntimeError("Final verification differs")
    digest = sha(args.archive)
    args.archive.with_suffix(".zip.sha256").write_text(digest + "  " + args.archive.name + "\n", encoding="ascii")
    print(json.dumps({"archive": str(args.archive), "sha256": digest, "bytes": args.archive.stat().st_size,
                      "extractedHandoff": str(second), "verification": final}, indent=2))


if __name__ == "__main__":
    main()
