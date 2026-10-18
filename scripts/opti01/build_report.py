"""Generate the self-contained French stage audit from actual experiment artifacts."""
from pathlib import Path
import argparse
import csv
import json
import statistics
from summarize import load


def fmt(value, scale=1, digits=2):
    return "non mesuré" if value is None else f"{value / scale:,.{digits}f}".replace(",", " ").replace(".", ",")


def build(root, output):
    rows = list(csv.DictReader((root / "summary.csv").open(encoding="utf-8-sig")))
    for row in rows:
        for key in ("n", "mean", "median", "stdev", "sampleVariance", "minimum", "maximum"):
            row[key] = float(row[key]) if row[key] else None
    def stat(mode, size, initial, version, metric):
        return next((row for row in rows if row["mode"] == mode and int(row["size"]) == size and row["initial"] == initial and row["version"] == version and row["metric"] == metric), None)
    before_build = load(root / "builds/before/build.json")
    after_build = load(root / "builds/after/build.json")
    budget = load(root / "budgets.json")
    replay = load(root / "replay-verification.json")
    if len(replay) != 32 or not all(item["equivalent"] for item in replay):
        raise RuntimeError(f"Expected 20 paired engine states plus 12 paired replays; found {len(replay)}")
    for mode, cases, count in (("engine", [(500,"empty"),(500,"full"),(4000,"empty"),(4000,"full")], 5), ("application", [(500,"empty"),(4000,"empty")], 3)):
        for size, initial in cases:
            for version in ("before", "after"):
                row = stat(mode, size, initial, version, "attemptsPerSecond")
                if row is None or row["n"] != count:
                    raise RuntimeError(f"Missing measured trials: {mode} {size} {initial} {version}")
    def variation(mode, size, initial):
        a = stat(mode,size,initial,"before","attemptsPerSecond")["mean"]
        b = stat(mode,size,initial,"after","attemptsPerSecond")["mean"]
        return 100*(b/a-1)
    p = "01-board-compact/"
    text = [f"""# Optimisation 01 — stockage compact du plateau

## Synthèse autonome

Pixel War simule quatre joueurs sur un plateau partagé. Le plateau DEFAULT de 4 000 × 4 000 contient 64 fois plus de cellules que SMALL de 500 × 500. Le besoin est d’étudier un gain de capacité du moteur sans modifier la partie simulée, tout en produisant des preuves réutilisables dans un audit final.

Cette étape remplace uniquement les couleurs de `CellState[][]` par un tableau linéaire `byte[]`, avec codes stables 0 à 4 et décodage constant. Les stratégies, tirages aléatoires, frontières, index de cases vides, compteurs, conversions, verrous, métriques affichées et paramètres de gameplay restent conservés.

Référence avant : **`{before_build['commit']}`** ; référence après : **`{after_build['commit']}`**. La branche de départ utilisateur est `refacto_metriques`, commit `edd05c5586beb3b48b6bc21d9283a5df2e76f074`. Le commit de préparation `5870112` copie les six fichiers de métriques non suivis nécessaires à une compilation complète ; il ne change pas leur contenu. La nouvelle branche `opti/01-board-compact` est dans un worktree isolé. La branche et les changements locaux de référence ont été conservés.

En comparaison des moyennes, les variations de tentatives/s moteur sont : **SMALL vide {fmt(variation('engine',500,'empty'))} %**, **SMALL rempli {fmt(variation('engine',500,'full'))} %**, **DEFAULT vide {fmt(variation('engine',4000,'empty'))} %**, **DEFAULT rempli {fmt(variation('engine',4000,'full'))} %**. Sous scheduler et polling, elles sont **SMALL {fmt(variation('application',500,'empty'))} %** et **DEFAULT {fmt(variation('application',4000,'empty'))} %**. Les dispersions et les rapports appariés sont présentés plus bas ; ces chiffres ne sont pas un gain unique agrégé.

La mesure par Instrumentation isole une baisse d’environ 75 % de la mémoire des tableaux de couleurs. Elle ne concerne ni les frontières ni les autres index. Les 70 tests après changement passent, contre 68 avant. **Les 32 comparaisons d’états finaux vérifient les mêmes empreintes SHA-256 spatiales et les mêmes compteurs et états métier.**

Une **action** est une exécution réelle pour un joueur non éliminé ; une **tentative** est un choix de coordonnée et une pose essayée ; une **modification directe** est une pose qui change une couleur ; une **conversion** est un changement décidé par les quatre voisins, détecté sur un état stable puis appliqué avec les autres décisions de la passe. Les appels ignorés pour les joueurs éliminés ne sont pas comptés comme actions.

Le pilote déterministe appelle l’action réelle séquentiellement, sans HTTP, avec un budget identique d’actions. Il ne mesure pas le scheduler ou le réseau. L’essai applicatif utilise le scheduler réel et des lectures HTTP concurrentes ; il mesure une fenêtre active, pas le temps d’un budget fixe. Aucun temps de victoire n’est déduit des débits.

## 1. Références, environnement et isolation

| Élément | Valeur observée |
|---|---|
| OS | Microsoft Windows 11 Famille, 10.0.26300, 64 bits |
| JVM | OpenJDK 27, build 27+35-2325 ; compilation ciblant Java 21 |
| CPU | Intel Core i5-13600KF, 14 cœurs physiques, 20 processeurs logiques |
| RAM visible | 34 188 517 376 octets |
| Caches rapportés par CIM | L2 : 20 480 Kio ; L3 : 24 576 Kio ; topologie détaillée et L1 non mesurées |
| Flags des JVM expérimentales | `-Xms2g -Xmx2g -XX:+UseG1GC`, même agent de mesure de taille |
| Collecteur | G1 ; heap de 2 Gio identique avant/après |
| Stock et gameplay | Stock désactivé, frontier, exploration 0,10, 10 candidats, bonus 8, priorité 20 actions, 10 tentatives/action, conversions activées |
| Cadence et aperçu | 10 ns demandées ; aperçu 500 × 500, inchangé dans les deux versions |
| Graines | 11 pour les performances ; 11, 22 et 33 pour les rejeux |
| État rempli | Couleur tirée par `Random(seed + 1000)` pour chaque cellule, via `Board.place` |

Les processus sont séquentiels, sans benchmark concurrent de cette campagne. Aucune affinité CPU, fréquence fixe, désactivation du turbo ou isolation matérielle n’a été imposée. Le système peut avoir une activité de fond ; la topologie P/E et les migrations de cœur ne sont pas contrôlées. Les répétitions et leur dispersion rendent ce bruit visible, sans prétendre le supprimer. Aucun compteur matériel de cache-misses n’a été mesuré.

Pièces : [environnement]({p}environment.json), [version JVM]({p}java-version.txt), [invocations]({p}invocations.jsonl), [référence du binaire avant]({p}build-before.json), [après]({p}build-after.json), [provenance des fichiers locaux]({p}provenance/local-files-before.json). Les SHA-256 des binaires sont aussi répétés dans les essais bruts. Les archives de sources étaient propres aux références mesurées ; les changements d’outillage d’analyse ultérieurs sont livrés séparément et n’affectent pas les deux binaires mesurés.

## 2. Diagnostic initial et hypothèse

Le stockage initial comporte un tableau de lignes puis une référence enum par cellule. Le [diagnostic écrit avant changement](01-diagnostic-initial.md) décrit les accesseurs, le marquage des mutations et les calibrations. Le profil moteur DEFAULT rempli avant changement observe 214 échantillons CPU exclusifs au sommet de `FrontierPlacementStrategy.score`, 73 dans `Board.updateFrontiers`, 35 dans `Board.get` et 19 dans `ArrayList.grow`. Ces échantillons ne prouvent pas des défauts de cache.

Les allocations échantillonnées avant contiennent notamment des tableaux d’enums issus des appels de stratégie, des objets de position et des `Long`. Ce ne sont pas les tableaux persistants de couleurs construits avant la fenêtre de profiling. Les autres candidats d’optimisation restent hors périmètre.

Hypothèse : réduire les références et les indirections peut aider les accès, mais la multiplication d’index et le décodage peuvent coûter du CPU. Le coût des stratégies et des structures dérivées peut rester dominant. L’intérêt mémoire doit donc être évalué séparément du débit.

## 3. Changement isolé

`Board.cells` devient un `byte[]`. L’index est `y * width + x`, après contrôle indépendant de x et y. Une table statique de cinq enums décode les lectures sans allocation par accès ; un `switch` encode les écritures. La table statique, partagée, n’est pas incluse dans la mesure des seuls tableaux par plateau.

Le produit des dimensions est vérifié en `long` avant toute allocation, puis borné à l’indexation `int`. Les dimensions non positives restent rejetées par `IllegalArgumentException`, et les produits trop grands par `ArithmeticException`. Une taille compatible avec `int` peut encore dépasser la limite effective des tableaux JVM ou la heap disponible : aucune allocation géante n’est promise.

Les voisins utilisent les offsets du tableau linéaire après contrôle des bords. Les lectures publiques renvoient toujours `CellState` ; les codes ne sont pas exposés en HTTP. `place`, `placeIfEmpty`, `placeForPlayer`, le reset et l’aperçu conservent leurs notifications et leur synchronisation. Les accès restent O(1), la conversion et le scoring gardent leurs algorithmes.

Le diff entre les deux références ne touche que `Board.java` et `CompactBoardTests.java` : [patch]({p}patches/storage.patch). Aucun pooling, snapshot, parallélisme, changement de cadence, bonus, taille d’aperçu ou seconde optimisation n’est ajouté. Les scripts et le rapport d’audit sont des livrables d’expérience distincts.

## 4. Protocole de mesure

### Moteur déterministe

Chaque observation est une JVM neuve. Elle chauffe pendant trois secondes sur un plateau dédié, détruit cette simulation, puis recrée le même état initial et réinitialise les générateurs. Une GC explicite est demandée hors mesure après préparation, identiquement dans les deux versions. Le chronométrage exclut construction, préparation, GC demandée, comparaison des états et calcul de l’empreinte.

Le pilote lit le compteur privé d’actions en réflexion pour compter seulement les exécutions réelles. Il conserve la rotation explicite du premier joueur et s’arrête au budget exact, même après des éliminations. Il ajoute donc un léger coût de pilotage commun ; ce n’est pas un microbenchmark isolé de `Board.get`.

Budgets calibrés sur 5 000 actions de la baseline pour viser environ deux secondes, puis figés : SMALL vide **{budget['500-empty']}**, SMALL rempli **{budget['500-full']}**, DEFAULT vide **{budget['4000-empty']}**, DEFAULT rempli **{budget['4000-full']}**. L’évolution de l’état pendant une charge plus longue peut faire varier sa durée par rapport à la calibration. Cinq paires sont exécutées par scénario pour la graine 11, dans l’ordre A/B, B/A, A/B, B/A, A/B.

Le temps CPU et les allocations exactes par thread sont des deltas du thread pilote qui exécute le moteur. La heap est celle de toute la JVM, échantillonnée par un thread daemon toutes les 10 ms, avec observation initiale et finale. Un pic court peut être manqué ; ce n’est ni une heap retenue après GC ni la mémoire totale du processus. Les compteurs GC/JFR ne sont pas extrapolés depuis les allocations par action.

### Application avec polling

Trois paires de JVM neuves par taille, départ vide. Le pilote démarre les vrais contrôleurs, filtre, moteur et scheduler à travers une configuration Spring réservée au banc ; il ne modifie pas l’API de production. Le code de la simulation et les assets sont ceux du binaire concerné.

Après cinq secondes de chauffe, une pause permet une borne cohérente, puis la simulation reprend pendant une fenêtre d’au moins vingt secondes. Le client externe lance simultanément les quatre lectures `/api/simulation`, `/api/simulation/scores`, `/api/performance`, `/api/board/preview`, à une période cible de 1 000 ms, sans modifier l’aperçu. Chaque requête conserve route, durée, statut et erreur. La pause finale et les éventuels retards du dernier groupe peuvent légèrement allonger la fenêtre ; sa durée réelle est enregistrée et sert aux débits.

P95 et P99 HTTP sont calculés au rang supérieur sur les requêtes réussies individuelles côté client, transfert compris, contrairement aux percentiles serveur affichés dans l’application. Ils ne sont pas la dispersion des moyennes entre essais. Le CPU moteur, le CPU processus et les allocations moteur sont mesurés par deltas aux bornes. Les fenêtres applicatives n’exécutent pas une charge identique : un moteur plus rapide peut avoir avancé davantage pendant la chauffe et la mesure. Les états de départ/fin et les éliminations sont conservés dans les données brutes.

Le départ rempli applicatif est **non mesuré** : il est traité par les essais moteur et les profils moteur complémentaires, conformément à l’option de réduction de matrice du document. La chauffe applicative est fixe ; la stabilité de compilation/fréquence n’est pas démontrée par un test statistique indépendant. Cela limite l’attribution des petites variations.

### Profils séparés

JFR est désactivé dans les observations principales. Deux profils par version sont capturés à part : moteur DEFAULT rempli, graine 11, {4*budget['4000-full']} actions ; application DEFAULT vide avec polling et même fenêtre de vingt secondes. Configuration JFR `profile`, `jdk.JavaMonitorEnter` avec seuil 1 ms et piles, `jdk.ObjectAllocationSample` avec piles. Les durées et débits des essais profilés ne sont pas mélangés aux répétitions principales.

## 5. Validation fonctionnelle

Les tests avant et après sont conservés : [68 tests avant]({p}logs/build-before.log), [70 tests après]({p}logs/build-after.log). Les nouveaux cas couvrent cinq états, coins, rectangles, rejet du passage artificiel d’une ligne à l’autre, produit de dimensions, reset, conquête, pose sans changement, scores et aperçu comparés à un modèle 2D indépendant. Les tests antérieurs vérifient frontières, conversions simultanées et différées, stock conditionnel, éliminations, priorité, pause, stop, API et absence de mutations tardives.

Le rejeu supplémentaire couvre les deux tailles, les deux états initiaux et les graines 11, 22, 33 sur 10 000 actions. Il compare SHA-256 de toutes les cellules dans l’ordre des coordonnées, comptes de couleurs, tailles de frontières, actions, tentatives, modifications, conversions, actions sans stock, consommation/recharge, éliminations et cibles prioritaires. Les vingt paires de charges moteur principales sont également comparées. Les durées, heap et métriques JVM sont exclues de l’égalité : [32 comparaisons réussies]({p}replay-verification.json).

## 6. Résultats individuels et statistiques

Convention : moyenne arithmétique, médiane, écart-type et **variance d’échantillon** (diviseur n−1). Les unités au carré de la variance correspondent à celles indiquées dans les tableaux. Toutes les observations individuelles sont conservées avec davantage de précision que l’affichage. Les effets sont calculés séparément par scénario et graine, jamais en agrégeant SMALL avec DEFAULT.

Formules : accélération = temps avant / temps après ; variation débit = 100 × (débit après / débit avant − 1) ; réduction temps = 100 × (1 − temps après / temps avant) ; réduction mémoire = 100 × (1 − mémoire après / mémoire avant).

Pièces : [observations longues]({p}observations.csv), [statistiques complètes]({p}summary.csv), [rapports appariés individuels]({p}pairs.csv), [dispersion de ces rapports]({p}pair-summary.csv). Les lignes ci-dessous prennent les moyennes avant/après ; la dispersion des rapports n’est pas remplacée par le rapport de deux moyennes.
"""]
    metrics = [("elapsedNanos","Temps charge fixe",1e6,"ms"),("attemptsPerSecond","Tentatives/s",1,"/s"),("cpuNanos","CPU moteur",1e6,"ms"),("colorsArrayBytes","Tableaux de couleurs",2**20,"Mio"),("peakObservedHeapBytes","Heap maximale échantillonnée",2**20,"Mio"),("allocatedBytesPerAction","Allocations/action",1,"o/action"),("httpP95Nanos","HTTP P95",1e6,"ms")]
    for mode, cases in (("engine",[(500,"empty"),(500,"full"),(4000,"empty"),(4000,"full")]),("application",[(500,"empty"),(4000,"empty")])):
        for size, initial in cases:
            name = f"{'Moteur' if mode == 'engine' else 'Application avec polling'} — {size} × {size}, {'vide' if initial == 'empty' else 'rempli'}"
            text += [f"\n### {name}\n", "| Mesure | Avant | Après | Variation | Dispersion et n | Pièce |\n|---|---:|---:|---:|---|---|\n"]
            for metric, label, scale, unit in metrics:
                a, b = stat(mode,size,initial,"before",metric), stat(mode,size,initial,"after",metric)
                if mode == "application" and metric == "elapsedNanos":
                    text.append(f"| {label} | non mesuré | non mesuré | non applicable | fenêtre active ≥ 20 s, pas une charge fixe | [données]({p}summary.csv) |\n")
                    continue
                if not a or not b:
                    text.append(f"| {label} | non mesuré | non mesuré | non mesuré | hors de cet essai | [protocole]({p}environment.json) |\n")
                    continue
                delta = 100*(b['mean']/a['mean']-1) if a['mean'] else None
                dispersion = f"σ {fmt(a['stdev'],scale)} / {fmt(b['stdev'],scale)} {unit} ; n={int(a['n'])}/{int(b['n'])}"
                text.append(f"| {label} | {fmt(a['mean'],scale)} {unit} | {fmt(b['mean'],scale)} {unit} | {fmt(delta)} % | {dispersion} | [mesures]({p}summary.csv) |\n")
            text += ["\n| Mesure | Version | Moyenne | Médiane | Écart-type | Variance d’échantillon | n |\n|---|---|---:|---:|---:|---:|---:|\n"]
            for metric, scale, unit in (("elapsedNanos",1e6,"ms"),("attemptsPerSecond",1,"tentatives/s")):
                for version in ("before","after"):
                    row = stat(mode,size,initial,version,metric)
                    text.append(f"| {'Temps charge' if mode=='engine' and metric=='elapsedNanos' else 'Durée fenêtre' if metric=='elapsedNanos' else 'Débit'} ({unit}) | {version} | {fmt(row['mean'],scale)} | {fmt(row['median'],scale)} | {fmt(row['stdev'],scale)} | {fmt(row['sampleVariance'],scale*scale)} {unit}² | {int(row['n'])} |\n")
            if mode == "engine":
                a = stat(mode,size,initial,"before","elapsedNanos")['mean']; b = stat(mode,size,initial,"after","elapsedNanos")['mean']
                text.append(f"\nAccélération calculée sur les durées moyennes : **{fmt(a/b,1,3)}×** ; réduction du temps : **{fmt(100*(1-b/a))} %**.\n")
    text += [f"""
### Figures issues des données brutes

![Débit moteur et dispersion]({p}figures/throughput-engine.png)

![Débit applicatif et dispersion]({p}figures/throughput-application.png)

![Empreinte des tableaux de couleurs]({p}figures/colors-footprint.png)

Les barres de débit représentent moyenne ± écart-type d’échantillon ; l’empreinte des couleurs est une taille de disposition mesurée, constante pour une taille/version donnée, pas une allocation/action ni une heap après GC.

## 7. Profils réels CPU, allocations et attentes

Les figures CPU comptent `jdk.ExecutionSample`, méthode au sommet de pile : portée **exclusive**. Elles ne sont pas un temps absolu, ni des compteurs de défauts de cache. Les exports contiennent aussi les méthodes inclusives, comptées au plus une fois par pile, et les piles complètes. Les événements natifs éventuels ne sont pas assimilés à ces échantillons Java.

Les figures allocations agrègent le champ `weight` de `jdk.ObjectAllocationSample` par classe. Ce sont des **octets estimés par échantillonnage**, pas les compteurs exacts d’allocations du thread ni la mémoire retenue. Les objets persistants préparés avant le profil, dont le board, n’y figurent pas comme coût de création du travail mesuré.

![CPU moteur comparatif]({p}figures/profile-4000-full-cpu.png)

![Allocations moteur comparatives]({p}figures/profile-4000-full-allocations.png)

![CPU applicatif comparatif]({p}figures/application-profile-4000-empty-cpu.png)

![Allocations applicatives comparatives]({p}figures/application-profile-4000-empty-allocations.png)

Ces vues annotées et exports jouent le rôle d’analyse de piles des profils pprof dans les supports, avec JFR plutôt que pprof. Cette substitution Java n’est pas présentée comme une approbation explicite de l’évaluateur. Une part relative peut augmenter sans augmentation du coût absolu ; comparer les budgets, durées et événements avant d’attribuer une cause.

| Profil | Version | Échantillons CPU Java | Échantillons allocations | GC observées | Durée cumulée des cycles GC | Pauses STW observées | Attentes `Simulation` ≥ 1 ms |
|---|---|---:|---:|---:|---:|---:|---:|
"""]
    profile_top = []
    for prefix, title in (("profile-4000-full","moteur rempli"),("application-profile-4000-empty","application vide")):
        for version in ("before","after"):
            data = load(root / "profiles" / f"{prefix}-s11-p0-{version}.summary.json")
            waits = sum(value for key,value in data['monitorWaitNanos'].items() if key.endswith('Simulation'))
            text.append(f"| {title} | {version} | {data['eventCounts'].get('jdk.ExecutionSample',0)} | {data['allocationSamples']} | {data['eventCounts'].get('jdk.GarbageCollection',0)} | {fmt(data.get('gcCollectionDurationNanos'),1e6)} ms | {fmt(data.get('gcStopTheWorldPauseNanos'),1e6)} ms | {fmt(waits,1e6)} ms |\n")
            methods = sorted(data['cpuExclusiveSamples'].items(),key=lambda item:item[1],reverse=True)[:5]
            classes = sorted(data['allocationEstimatedBytesByClass'].items(),key=lambda item:item[1],reverse=True)[:3]
            profile_top.append(f"- **{title}, {version}** : CPU exclusif " + "; ".join(f"`{method}` ({count})" for method,count in methods) + ". Allocations estimées : " + "; ".join(f"`{name}` ({fmt(value,2**20)} Mio)" for name,value in classes) + ".")
    text += ["\n" + "\n".join(profile_top) + f"""

Les attentes cumulées de plusieurs threads peuvent dépasser une durée murale ; seuls les événements au-dessus du seuil sont observés. L’absence d’attente métier dans un essai mono-thread n’est pas une absence de contention du serveur. Une durée de cycle GC n’est pas une pause STW ; les deux colonnes proviennent de types d’événements distincts. On ne conclut pas à « zéro GC serveur » à partir d’un compteur métier d’allocations.

Profils réels, exports et données associées : [index des pièces]({p}INDEX.md). Les `.jfr` volumineux sont inclus dans le dossier de remise et l’archive, et peuvent rester hors Git ; les exports JSON et figures sont versionnés. Les données individuelles des essais profilés restent séparées des statistiques principales.

## 8. Analyse causale et décision

La baisse de taille des tableaux de couleurs est directement attribuable au passage des références 2D au tableau d’octets : elle est observée séparément par Instrumentation, avec en-têtes et alignements. L’agent n’instrumente pas les accès ; il mesure la disposition hors chronométrage. Les tailles des autres index ne sont pas créditées à cette optimisation.

Les variations de débit moteur sont compatibles avec un effet du changement de représentation, sur une charge dont l’état spatial final et les compteurs sont identiques. Elles ne démontrent pas des cache-misses supprimés : calcul d’index, décodage, inlining, compilation et localité peuvent tous contribuer. Les scénarios les plus dispersés ne permettent pas d’affirmer un gain stable de quelques pourcents. La [dispersion des rapports appariés]({p}pair-summary.csv) montre les paires favorables ou défavorables.

Le résultat applicatif ajoute scheduler, verrous, sérialisation, GC et polling. Il ne mesure pas le même nombre d’actions, et les trajectoires au début de sa fenêtre peuvent différer après une chauffe temporelle. Une variation applicative ne doit donc pas être attribuée uniquement au coût d’une lecture du board. La mémoire totale échantillonnée reste sensible au GC et aux allocations HTTP ; elle n’est pas la mesure isolée des couleurs.

**Décision : conserver le stockage compact pour sa réduction directement vérifiée de l’empreinte persistante et son équivalence fonctionnelle, en publiant séparément les résultats de débit et leurs régressions éventuelles.** Aucune modification supplémentaire n’est engagée pour améliorer artificiellement le résultat. Les allocations temporaires des stratégies (`values()`, positions, objets de conversion) et les attentes HTTP observées constituent des candidats à profiler plus finement lors d’une étape suivante, pas des optimisations réalisées ici.

Le résultat ne prédit ni un vainqueur ni le temps d’élimination : la géométrie, les 64 fois plus de cellules et la stratégie restent des contraintes de gameplay distinctes.

## 9. Reproduction, dictionnaire et remise

La reproduction du code nécessite le dépôt avec les deux références Git indiquées. Les [patchs]({p}patches/) rendent visibles la préparation de référence et le changement isolé ; l’archive ne contient pas de `.git` complet ni de secrets.

Prérequis : PowerShell Windows, JDK complet 27 identique pour les deux versions, Maven wrapper et accès aux dépendances, Python et les versions de `scripts/opti01/requirements.txt`. Matplotlib {load(root/'plot-dependencies.json')['matplotlib']} et NumPy {load(root/'plot-dependencies.json')['numpy']} ont produit les figures. L’environnement de campagne a installé ces paquets dans un dossier isolé sous `target` ; aucune modification du Python système n’est nécessaire.

Commande principale effectivement exécutée depuis le worktree :

```powershell
$env:PYTHONPATH='C:/Users/gui35/ideaProjects/pixel-war/target/opti-01/python-libs'
.\\scripts\\benchmark-opti-01.ps1 -BeforeRef 01fdacf -AfterRef d1b48a6 -Java 'C:/Users/gui35/.jdks/openjdk-27/bin/java.exe' -ResultsDir 'C:/Users/gui35/ideaProjects/pixel-war/target/opti-01/results' -Phase All -Resume
```

Avant cela, `-Phase Calibrate` a figé les budgets sur la référence et `-Phase Profiles` a conservé le diagnostic avant modification. Une exécution sans `-Resume`, dans un répertoire de résultats neuf, reconstruit les deux versions et reproduit toute la campagne en une commande. `-Resume` permet de reprendre uniquement les fichiers manquants et vérifie la référence des essais existants. L’analyse et les figures se reconstruisent aussi avec `python scripts/opti01/summarize.py <résultats>` ; le rapport est généré par `build_report.py`.

Le pilote PowerShell remplace le rôle d’orchestration des forks d’Hyperfine, mais les chronométrages Java excluent volontairement démarrage JVM et préparation. JFR remplace le rôle de profiling de pprof. JMH n’est pas utilisé car aucune opération isolée n’est présentée comme microbenchmark. L’adéquation de ces substitutions au barème reste à l’appréciation de l’évaluateur.

Les premières mises au point du banc ont corrigé des chemins absolus et le traitement de stderr sous Windows PowerShell, notamment les avertissements Mockito ; elles n’ont pas été présentées comme des régressions de stockage. Les références et commits de préparation en gardent la trace. Les durées effectives et erreurs de la campagne mesurée sont dans les essais individuels. Les essais principaux réussis ne sont pas remplacés silencieusement par SMALL.

Dictionnaire, unités et formules détaillés : [DICTIONNAIRE.md]({p}DICTIONNAIRE.md). Journaux de construction, tests, fichiers de provenance et commandes : [INDEX.md]({p}INDEX.md). L’archive `target/audit-opti-01.zip` contient rapport autonome, figures, relevés, profils réels, scripts, patchs et manifeste SHA-256. Les liens relatifs et empreintes sont vérifiés après extraction ; le résultat du contrôle est inclus.

## 10. Correspondance avec le barème

| Critère | Points | Statut et preuves de cette étape | Limites pour l’audit final |
|---|---:|---|---|
| Environnement et métrologie | 3 | **partiel** : sections 1 et 4, environnement, 5 paires moteur et 3 paires applicatives, statistiques complètes | Bruit OS, fréquence/affinité non fixées, L1/topologie fine et stabilité JIT non démontrées ; substitution à Hyperfine expliquée |
| Diagnostic matériel et profiling réel | 5 | **couvert pour cette étape** : sections 2 et 7, quatre JFR réels, exports CPU inclusifs/exclusifs, allocations et moniteurs, vues annotées | Aucun compteur de cache-misses ; validation de la substitution à pprof revient à l’évaluateur |
| Journal et démarche d’ingénierie | 5 | **couvert pour cette étape** : diagnostic préalable, changement isolé, patch, équivalence et résultats séparés mémoire/moteur/application | Cette étape ne couvre pas à elle seule concurrence, réseau ou persistance |
| Échec constructif | 3 | **partiel** : les rapports appariés rendent visibles d’éventuelles paires défavorables ; section 8 ne masque pas les régressions | Aucun échec d’architecture distinct n’est inventé ; une fluctuation isolée ne prouve pas une variante contre-productive |
| Reproductibilité et synthèse | 4 | **couvert pour cette étape** : script à deux références, données, 32 comparaisons d’état, figures, archive et manifeste | Nécessite dépôt/références et prérequis ; pas d’audit final de toutes les optimisations |
| Gouvernance technique IA | +2 | **non traité** : aucun `constitution.md` satisfaisant quatre directives n’a été trouvé dans ce dépôt | La présence du prompt ne suffit pas ; adaptation dédiée à prévoir |

Aucune note acquise n’est attribuée. Ce rapport est une étape mémoire/localité, pas un audit final ni une couverture intégrale des axes du cours. Les départs remplis applicatifs, les compteurs matériels et l’isolation thermique restent explicitement non mesurés ; les profils et figures annoncés sont fournis, sans capture reconstituée.
"""]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("".join(text), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.results, args.output)
