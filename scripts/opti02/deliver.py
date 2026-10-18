"""Publish the current counter study, package actual evidence, then verify extracted hashes/links."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("repo", type=Path)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    root, repo, destination = args.results.resolve(), args.repo.resolve(), args.archive.resolve()
    spec = importlib.util.spec_from_file_location("archive_helpers", repo / "scripts/opti01/package_audit.py")
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    proof = repo / "docs/optimisations/02-compteurs"
    proof.mkdir(parents=True, exist_ok=True)
    for name in ("raw", "logs", "figures"):
        shutil.copytree(root / name, proof / name, dirs_exist_ok=True)
    for pattern in ("*.csv", "*.json", "*.jsonl", "*.txt"):
        for path in root.glob(pattern):
            copy(path, proof / path.name)
    if (root / "incidents.md").exists():
        copy(root / "incidents.md", proof / "incidents.md")
    for path in (root / "profiles").glob("*.summary.json"):
        copy(path, proof / "profiles" / path.name)
    copy(root / "builds/baseline/build.json", proof / "build-baseline.json")
    initial_log = root.parent / "tests-initial.log"
    if initial_log.exists():
        copy(initial_log, proof / "logs/tests-initial.log")
    copy(root / "02-compteurs.md", repo / "docs/optimisations/02-compteurs.md")
    (proof / "patches").mkdir(exist_ok=True)
    diff = subprocess.check_output(["git", "diff", "180012f", "--", "src/main", "src/test"], cwd=repo)
    if diff:
        raise RuntimeError("Production/tests changed: re-audit the non-applicability conclusion")
    (proof / "production-preservation.json").write_text(json.dumps(dict(
        base="180012fc1c1cf883a0dca233806093440baa113c", productionDiff="", testsDiff="",
        checkedCommand="git diff 180012f -- src/main src/test", candidate="none", productionUnchanged=True), indent=2), encoding="utf-8")
    patch = subprocess.check_output(["git", "diff", "180012f", "--", "scripts/benchmark-opti-02.ps1", "scripts/opti02", "instructions/mission-opti-02.md"], cwd=repo)
    (proof / "patches/etude.patch").write_bytes(patch)
    (proof / "DICTIONNAIRE.md").write_text("""# Dictionnaire des observations

Une action est exécutée pour un joueur actif ; une tentative est une pose essayée ; une modification est un changement direct ; les conversions sont les changements d'une passe simultanée. Leurs deltas et les éliminations sont dans workload-verification.csv. Les snapshots métier de chaque JSON sont cohérents sous le moniteur Simulation. Les rejeux comparent code identique, graines et ordre identiques ; ils ne sont pas des paires avant/après d'optimisation.

`elapsedNanos` : durée murale du travail fixe Java, ou fenêtre externe du pilote applicatif. `activeElapsedNanos` : delta de temps actif exact pour l'application ; sa base définit les débits applicatifs. Unité des temps : nanoseconde. Débits : par seconde. CPU moteur : thread ; CPU processus : MXBean entre snapshots. Allocations/action : octets alloués, pas mémoire retenue. Pic heap échantillonné toutes les 10 ms ; il peut manquer des pics plus courts.

P50/P95/P99 HTTP : durées client des GET périodiques réussis, rang ceil(p×n), réponses lues intégralement ; erreurs et requêtes sont préservées. Null signifie absence d'observation, notamment sans polling. Les requêtes de pilotage ne participent pas à ces percentiles. Les 4 GET d'un batch sont concurrents, départ demandé chaque seconde, fin du batch attendue avant le suivant.

summary.csv regroupe séparément mode, taille, état initial, graine et condition HTTP : n, moyenne, médiane, écart type et variance d'échantillon (n−1), min/max. observations.csv préserve les valeurs non arrondies. Les modes calibration, replay et JFR sont exclus des statistiques principales. Aucune paire de variantes de production n'existe. Accélération temps = avant/après ; variation débit = 100×(après/avant−1) ; réduction temps = 100×(1−après/avant). Ces trois valeurs restent non applicables ici.

JFR ExecutionSample : échantillons CPU exclusifs ou inclusifs et piles, pas durée exacte. ObjectAllocationSample : poids estimés des allocations. JavaMonitorEnter : événements de durée au moins 1 ms et somme par classe de moniteur, pas coût des atomiques, ni totalité des petites attentes. Durée GarbageCollection et pauses GCPhasePause distinctes. Les durées cumulées de plusieurs threads peuvent dépasser le temps mural.

`source-inventory.json` inventorie les emplacements textuels et empreintes des sources de production réellement compilées ; l'analyse manuelle des accès reste nécessaire. `production-preservation.json` vérifie l'absence de changement de production et de tests depuis le départ. Chaque essai conserve référence, SHA du JAR et du banc/agent, graine, paramètres, ordre, compteurs et instrumentation. Le pic de heap du banc reste un AtomicLong car ses accès sont concurrents hors verrou Simulation.
""", encoding="utf-8")
    stage = Path(tempfile.mkdtemp(prefix="handoff-", dir=root.parent))
    staged_proof = stage / "docs/optimisations/02-compteurs"
    shutil.copytree(proof, staged_proof)
    for path in (root / "profiles").glob("*.jfr"):
        copy(path, staged_proof / "profiles" / path.name)
    index = ["# Index des preuves\n", "Les JFR originaux sont inclus dans l'archive ; seuls leurs exports sont versionnés.\n"]
    for path in sorted(staged_proof.rglob("*")):
        if path.is_file() and path.name != "INDEX.md":
            rel = path.relative_to(staged_proof).as_posix()
            index.append(f"- [{rel}]({rel})\n")
    for target in (proof / "INDEX.md", staged_proof / "INDEX.md"):
        target.write_text("\n".join(index), encoding="utf-8")
    copy(repo / "docs/optimisations/02-compteurs.md", stage / "docs/optimisations/02-compteurs.md")
    copy(repo / "scripts/benchmark-opti-02.ps1", stage / "scripts/benchmark-opti-02.ps1")
    for path in (repo / "scripts/opti02").glob("*"):
        if path.is_file():
            copy(path, stage / "scripts/opti02" / path.name)
    copy(repo / "scripts/opti01/package_audit.py", stage / "scripts/opti01/package_audit.py")
    copy(repo / "instructions/mission-opti-02.md", stage / "instructions/mission-opti-02.md")
    copy(repo / "instructions/regles.md", stage / "instructions/regles.md")
    source = root / "builds/baseline/source"
    copy(source / "target/pixel-war-0.0.1-SNAPSHOT.jar", stage / "binaries/baseline.jar")
    copy(source / "target/storage-agent.jar", stage / "binaries/storage-agent.jar")
    for folder in ("src/main", "src/test", ".mvn"):
        shutil.copytree(source / folder, stage / "source" / folder)
    for name in ("pom.xml", "mvnw", "mvnw.cmd"):
        copy(source / name, stage / "source" / name)
    (stage / "LISEZ-MOI.md").write_text("""# Étude 02 — compteurs

Lire le [rapport autonome](docs/optimisations/02-compteurs.md) et l'[index des preuves](docs/optimisations/02-compteurs/INDEX.md).

Décision : piste non applicable ; compteurs déjà ordinaires et protégés, aucun changement de production. Les JFR, binaire/agent réellement mesurés, raw, scripts et sources main/test sont inclus. Le dépôt avec la référence du rapport reste nécessaire à la commande fondée sur git archive. Cette remise ne constitue pas l'audit final.

MANIFEST-SHA256.json couvre tous les fichiers sauf lui-même ; VERIFICATION.json décrit le contrôle après extraction. Les JFR sont inclus ici, pas dans Git. Aucun dossier .git ni secret n'est inclus.
""", encoding="utf-8")
    destination.parent.mkdir(parents=True, exist_ok=True)
    (stage / "VERIFICATION.json").write_text("{}", encoding="utf-8")
    helpers.manifest(stage)
    helpers.archive(stage, destination)
    extracted = Path(tempfile.mkdtemp(prefix="verify-", dir=root.parent))
    with zipfile.ZipFile(destination) as archive:
        archive.extractall(extracted)
    result = helpers.verify(extracted)
    (stage / "VERIFICATION.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    helpers.manifest(stage)
    helpers.archive(stage, destination)
    final = Path(tempfile.mkdtemp(prefix="verify-final-", dir=root.parent))
    with zipfile.ZipFile(destination) as archive:
        archive.extractall(final)
    assert helpers.verify(final) == result
    digest = helpers.sha(destination)
    destination.with_suffix(".zip.sha256").write_text(digest + "  " + destination.name + "\n", encoding="ascii")
    print(json.dumps(dict(archive=str(destination), bytes=destination.stat().st_size, sha256=digest, verification=result), indent=2))


if __name__ == "__main__":
    main()
