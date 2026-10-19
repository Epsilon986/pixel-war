# Exécution et reproduction

Prérequis : dépôt aux références indiquées, Windows PowerShell, OpenJDK 27, Maven Wrapper et son cache, Python 3.14 avec matplotlib==3.11.2 et numpy==2.5.4 (requirements.txt). Les mesures doivent être séquentielles sur une machine au repos.

Commande unique depuis la racine, après installation des dépendances Python :

```powershell
./scripts/benchmark-opti-03.ps1 -BeforeRef ea462e19e55e6f8f7366358918f07d9dfed6f7bb -AfterRef f61ad8fb6c3bb186dc7043954918ca8498db0513 -Java C:/Users/gui35/.jdks/openjdk-27/bin/java.exe -ResultsDir target/opti-03-reproduction
```

Exécution réelle : tests avant par `mvnw.cmd -B -ntp verify`, puis banc par phases `Calibrate`, `Engine`, `All -Resume` avec les mêmes arguments et ResultsDir=target/opti-03-results. Une phase finale `Analyze -Resume` réexporte les mêmes profils avec l’exporteur hors mesure enrichi des piles spécifiques à Position, puis reconstruit le rapport et l’archive. La calibration utilise ea462e1 des deux côtés ; Engine/All/Analyze utilisent avant ea462e1 et après f61ad8fb6c3bb186dc7043954918ca8498db0513. Les versions finales exactes figurent dans build-before.json et build-after.json. Les invocations sont conservées dans invocations.jsonl.

Pour les tracés réels, les dépendances déjà installées pour O1 ont été réutilisées via `$env:PYTHONPATH=(Resolve-Path target/opti-01/python-libs).Path`, sans réinstallation. Cette variable n’est pas requise si requirements.txt est installé dans l’environnement Python utilisé. Les commandes de benchmark ont été autorisées hors bac à sable pour CIM, Maven, JVM, serveurs loopback et lecture de ces bibliothèques.

Le script All lance calibration si absente, cinq paires moteur/scénario, douze paires de rejeux, trois paires applicatives/taille/condition et les JFR séparés. Il exporte les profils, exécute analyze.py puis deliver.py pour l’archive. Phases supplémentaires : Prepare, Replay, Application, Profiles, Analyze. Resume vérifie le commit des résultats existants ; utiliser un répertoire neuf après tout changement du banc ou de configuration. Le budget est figé dans budgets.json après calibration ; il ne doit pas être modifié d’un seul côté.

L’analyse et l’archive peuvent aussi être régénérées :

```powershell
python scripts/opti03/analyze.py target/opti-03-results
python scripts/opti03/deliver.py target/opti-03-results . target/audit-opti-03.zip
```

Logs : compilation/tests des deux archives, échec initial du nouveau jeu de test et commandes d’export des JFR. Incidents : refus CIM/Maven en sandbox, puis exécution autorisée ; jeu de test EMPTY corrigé, sans changement du moteur après f61ad8f. La chauffe et le protocole court SMALL sont détaillés dans le rapport.
