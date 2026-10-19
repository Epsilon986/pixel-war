"""Package actual O3 evidence and verify hashes and Markdown links after extraction."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('results',type=Path)
    parser.add_argument('repo',type=Path)
    parser.add_argument('archive',type=Path)
    args=parser.parse_args()
    root,repo,archive=[p.resolve() for p in (args.results,args.repo,args.archive)]
    sys.dont_write_bytecode=True
    spec=importlib.util.spec_from_file_location('package_helpers',repo/'scripts/opti01/package_audit.py')
    helpers=importlib.util.module_from_spec(spec); spec.loader.exec_module(helpers)
    proof=repo/'docs/optimisations/03-positions-primitives'; proof.mkdir(parents=True,exist_ok=True)
    for folder in ('raw','logs','figures'):
        shutil.copytree(root/folder,proof/folder,dirs_exist_ok=True)
    for pattern in ('*.csv','*.json','*.jsonl','*.txt'):
        for path in root.glob(pattern):
            if path.name!='archive-verification.json': helpers.copy(path,proof/path.name)
    for path in (root/'profiles').glob('*.summary.json'):
        helpers.copy(path,proof/'profiles'/path.name)
    for version in ('before','after'):
        helpers.copy(root/f'builds/{version}/build.json',proof/f'build-{version}.json')
        harness_sources=root/f'builds/{version}/harness-sources'
        if not harness_sources.exists():
            # First campaign compiled these exact archived files, before the offline exporter was enriched.
            harness_sources=root/f'builds/{version}/source/scripts/opti03'
        for path in harness_sources.glob('*.java'):
            helpers.copy(path,proof/'measured-harness-sources'/version/path.name)
    helpers.copy(root/'03-positions-primitives.md',proof.parent/'03-positions-primitives.md')
    helpers.copy(root/'SYNTHESE.md',proof/'SYNTHESE.md')
    before=json.loads((root/'builds/before/build.json').read_text(encoding='utf-8-sig'))['commit']
    after=json.loads((root/'builds/after/build.json').read_text(encoding='utf-8-sig'))['commit']
    patch=subprocess.check_output(['git','diff',before,after,'--','src/main','src/test'],cwd=repo)
    (proof/'patches').mkdir(exist_ok=True)
    (proof/'patches/optimisation.patch').write_bytes(patch)
    sources={}
    for version in ('before','after'):
        base=root/f'builds/{version}/source'
        sources[version]={p.relative_to(base).as_posix():helpers.sha(p) for p in sorted((base/'src/main').rglob('*')) if p.is_file()}
    dump=lambda path,obj: path.write_text(json.dumps(obj,indent=2,ensure_ascii=False),encoding='utf-8')
    dump(proof/'source-hashes.json',sources)
    dump(proof/'source-scope.json',dict(before=before,after=after,productionChanged=[p for p,h in sources['before'].items() if sources['after'].get(p)!=h],
                                      originalBranch='refacto_metriques',originalCommit='a73c938c68e11174b5687c094efcf20d505b1b0f'))
    initial_diff=subprocess.check_output(['git','diff','c5a8777',before,'--','src/main'],cwd=repo)
    assert not initial_diff, 'Pre-O3 production differs from O2: refresh initial diagnostic'
    (proof/'DIAGNOSTIC.md').write_text('''# Diagnostic préalable O3

Le code de production de départ est identique à c5a8777 (référence O2 mesurée), vérifié par git diff sur src/main. Les 70 tests avant passent. Le profil O2 DEFAULT avec polling estime 1 013 007 032 octets de PlacementStrategy$Position. Ce poids inclut tous les chemins de cette classe, pas exclusivement l’échantillonnage.

Board.randomFrontierPosition tire déjà un int, puis alloue une Position ; choose le fait jusqu’à dix fois par décision. Hypothèse : supprimer ces candidats matérialisés réduit les allocations/action. La JVM peut éliminer des objets et les coûts du scoring/Board demeurent. Aucun gain de débit, aucune élimination de cache-misses ou baisse de mémoire persistante n’est présumé. Les nouveaux profils avant/après sont séparés du débit principal.
''',encoding='utf-8')
    (proof/'DICTIONNAIRE.md').write_text('''# Champs et calculs

Une action : exécution pour un joueur actif ; tentative : pose essayée ; modification : changement direct ; conversion : changement simultané décidé sur état stable. Les snapshots before/after contiennent couleurs, tailles de frontières et joueurs (stock, compteurs, éliminations, cible prioritaire). L’état plein comporte des changements de préparation supplémentaires : vérifier les deltas boardChanges = modifications + conversions, pas les totaux absolus.

elapsedNanos : durée murale de la charge fixe ou du pilote applicatif. activeElapsedNanos : delta de temps actif exact de l’application. cpuNanos : delta du thread moteur ; processCpuNanos : processus, application seulement. Temps en nanosecondes, débits par seconde. allocations/action : octets du thread moteur, pas mémoire retenue ; allocatedBytesPerAction. peakObservedHeapBytes : pic échantillonné toutes les 10 ms, dépendant du GC et susceptible de manquer des pics courts. gcBefore/gcAfter : compteurs JVM complémentaires incluant les observations après travail ; leurs millisecondes ne sont pas un CPU moteur. Les profils donnent séparément GarbageCollection et GCPhasePause.

attemptsPerSecond = delta tentatives × 1e9 / durée ; modificationsPerSecond idem. Base de durée : elapsedNanos moteur, activeElapsedNanos application. Accélération temps = avant/après ; variation débit = 100 × (après/avant − 1) ; réduction allocations = 100 × (1 − après/avant). pairs.csv donne après/avant pour chaque métrique et paire, pair-summary.csv sa dispersion en pourcentage, sans fusion de tailles ou états.

summary.csv : n, moyenne, médiane, écart type et variance d’échantillon (n−1), min/max. observations.csv conserve chaque mesure. Calibration, rejeu et JFR exclus des statistiques principales. httpP50/P95/P99Nanos : nearest rank ceil(p×n) sur GET réussis, transfert complet compris, commandes exclues. Null : non mesuré. requests et errors : données client individuelles et échecs. Une moyenne de P95 entre JVM n’est pas un P95 global.

representativeContest : au moins deux joueurs en jeu du début à la fin sans victoire observée. Éliminations monotones. victoire moteur : observation après chaque tour ; application : observateur interne au pas demandé de 10 ms, retards de scheduling/verrou possibles. lastContestedNanos et victoryDetectedNanos bornent l’instant relatif au début ; victoryDetectedActions donne le premier nombre d’actions constaté. Ne pas interpoler un débit avant victoire. Les essais non représentatifs sont conservés et exclus des conclusions de capacité utile.

JFR ExecutionSample : CPU exclusif et inclusif, piles complètes. ObjectAllocationSample : poids d’octets estimés par classe et pile, pas compte exact. JavaMonitorEnter : seuil 1 ms, somme des durées et nombres par classe ; absences d’événement ne signifient pas coût nul. Les durées de plusieurs threads peuvent dépasser le temps mural. Classes de Position : allocations globales de cette classe ; piles d’allocation : classes mêlées, ne pas assimiler tous leurs poids à Position.

commit/binarySha256/harnessSha256 : source et binaires réellement utilisés. configuration, runtime, initial, seed, warmupSeconds, requestedActions/executedActions, requestedSeconds, pairOrder et completedUtc : traçabilité. Application : chauffe métier jetable de 3 s plus warmupSeconds de scheduler (0,2 s) ; moteur : warmupSeconds sur un jeu jetable recréé avant mesure. Couleurs compactes et flags identiques. Les JAR de banc peuvent différer par horodatage ZIP ; les classes ont été comparées octet par octet.
''',encoding='utf-8')
    (proof/'COMMANDES.md').write_text(f'''# Exécution et reproduction

Prérequis : dépôt aux références indiquées, Windows PowerShell, OpenJDK 27, Maven Wrapper et son cache, Python 3.14 avec matplotlib==3.11.2 et numpy==2.5.4 (requirements.txt). Les mesures doivent être séquentielles sur une machine au repos.

Commande unique depuis la racine, après installation des dépendances Python :

```powershell
./scripts/benchmark-opti-03.ps1 -BeforeRef {before} -AfterRef {after} -Java C:/Users/gui35/.jdks/openjdk-27/bin/java.exe -ResultsDir target/opti-03-reproduction
```

Exécution réelle : tests avant par `mvnw.cmd -B -ntp verify`, puis banc par phases `Calibrate`, `Engine`, `All -Resume` avec les mêmes arguments et ResultsDir=target/opti-03-results. Une phase finale `Analyze -Resume` réexporte les mêmes profils avec l’exporteur hors mesure enrichi des piles spécifiques à Position, puis reconstruit le rapport et l’archive. La calibration utilise ea462e1 des deux côtés ; Engine/All/Analyze utilisent avant ea462e1 et après {after}. Les versions finales exactes figurent dans build-before.json et build-after.json. Les invocations sont conservées dans invocations.jsonl.

Pour les tracés réels, les dépendances déjà installées pour O1 ont été réutilisées via `$env:PYTHONPATH=(Resolve-Path target/opti-01/python-libs).Path`, sans réinstallation. Cette variable n’est pas requise si requirements.txt est installé dans l’environnement Python utilisé. Les commandes de benchmark ont été autorisées hors bac à sable pour CIM, Maven, JVM, serveurs loopback et lecture de ces bibliothèques.

Le script All lance calibration si absente, cinq paires moteur/scénario, douze paires de rejeux, trois paires applicatives/taille/condition et les JFR séparés. Il exporte les profils, exécute analyze.py puis deliver.py pour l’archive. Phases supplémentaires : Prepare, Replay, Application, Profiles, Analyze. Resume vérifie le commit des résultats existants ; utiliser un répertoire neuf après tout changement du banc ou de configuration. Le budget est figé dans budgets.json après calibration ; il ne doit pas être modifié d’un seul côté.

L’analyse et l’archive peuvent aussi être régénérées :

```powershell
python scripts/opti03/analyze.py target/opti-03-results
python scripts/opti03/deliver.py target/opti-03-results . target/audit-opti-03.zip
```

Logs : compilation/tests des deux archives, échec initial du nouveau jeu de test et commandes d’export des JFR. Incidents : refus CIM/Maven en sandbox, puis exécution autorisée ; jeu de test EMPTY corrigé, sans changement du moteur après f61ad8f. La chauffe et le protocole court SMALL sont détaillés dans le rapport.
''',encoding='utf-8')
    stage=Path(tempfile.mkdtemp(prefix='audit-opti-03-',dir=root.parent))
    staged=stage/'docs/optimisations/03-positions-primitives'
    shutil.copytree(proof,staged)
    helpers.copy(proof.parent/'03-positions-primitives.md',stage/'docs/optimisations/03-positions-primitives.md')
    profiles=list((root/'profiles').glob('*.jfr'))
    assert len(profiles)==6, 'All six original JFR recordings are required'
    for path in profiles: helpers.copy(path,staged/'profiles'/path.name)
    for version in ('before','after'):
        metadata=json.loads((root/f'builds/{version}/build.json').read_text(encoding='utf-8-sig'))
        for name in ('pixel-war-0.0.1-SNAPSHOT.jar','storage-agent.jar'):
            expected=metadata['jarSha256' if name.startswith('pixel-war') else 'harnessSha256']
            assert helpers.sha(root/f'builds/{version}/source/target/{name}').lower()==expected.lower()
            helpers.copy(root/f'builds/{version}/source/target/{name}',stage/f'binaries/{version}-{name}')
    for path in (repo/'scripts/opti03').glob('*'):
        if path.is_file(): helpers.copy(path,stage/'scripts/opti03'/path.name)
    helpers.copy(repo/'scripts/benchmark-opti-03.ps1',stage/'scripts/benchmark-opti-03.ps1')
    helpers.copy(repo/'scripts/opti01/package_audit.py',stage/'scripts/opti01/package_audit.py')
    helpers.copy(repo/'instructions/mission-opti-03.md',stage/'instructions/mission-opti-03.md')
    for base in (proof,staged):
        items=['# Index des preuves\n\nLes originaux JFR sont inclus dans l’archive ; seuls les exports sont versionnés.\n']
        for path in sorted(base.rglob('*')):
            if path.is_file() and path.name!='INDEX.md':
                rel=path.relative_to(base).as_posix(); items.append(f'- [{rel}]({rel})\n')
        (base/'INDEX.md').write_text(''.join(items),encoding='utf-8')
    (stage/'LISEZ-MOI.md').write_text('''# Remise O3

Ouvrir [le rapport](docs/optimisations/03-positions-primitives.md), [la synthèse](docs/optimisations/03-positions-primitives/SYNTHESE.md) et [l’index](docs/optimisations/03-positions-primitives/INDEX.md). Les JFR originaux, données, binaires, scripts et manifeste sont inclus. La reproduction nécessite le dépôt aux commits du rapport. Aucun dossier .git n’est livré. Le manifeste couvre tous les fichiers sauf lui-même ; la vérification est effectuée après extraction.
''',encoding='utf-8')
    dump(stage/'VERIFICATION.json',{})
    helpers.manifest(stage)
    if archive.exists():
        archive.rename(archive.with_name(archive.name+'.previous-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')))
    helpers.archive(stage,archive)
    first=Path(tempfile.mkdtemp(prefix='verify-opti-03-',dir=root.parent))
    with zipfile.ZipFile(archive) as z: z.extractall(first)
    verified=helpers.verify(first)
    dump(stage/'VERIFICATION.json',verified); helpers.manifest(stage); helpers.archive(stage,archive)
    final=Path(tempfile.mkdtemp(prefix='verify-opti-03-final-',dir=root.parent))
    with zipfile.ZipFile(archive) as z: z.extractall(final)
    assert helpers.verify(final)==verified
    digest=helpers.sha(archive)
    archive.with_suffix('.zip.sha256').write_text(digest+'  '+archive.name+'\n',encoding='ascii')
    result=dict(archive=str(archive),sha256=digest,bytes=archive.stat().st_size,verification=verified)
    dump(root/'archive-verification.json',result)
    print(json.dumps(result,indent=2))


if __name__=='__main__': main()
