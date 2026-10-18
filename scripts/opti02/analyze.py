"""Analyze a single retained production version; never invent an optimized counterpart."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def dump(path, value):
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")


def write_csv(path, rows):
    if rows:
        with Path(path).open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)


def stats(values):
    return dict(n=len(values), mean=statistics.mean(values), median=statistics.median(values),
                stdev=statistics.stdev(values) if len(values) > 1 else None,
                sampleVariance=statistics.variance(values) if len(values) > 1 else None,
                minimum=min(values), maximum=max(values))


def business(run, point):
    return run[point]["business"] if run["mode"] == "application" else run[point]


def analyze(root):
    runs = [load(path) for path in sorted((root / "raw").glob("*.json"))]
    observations, audit, groups = [], [], {}
    for run in runs:
        for point in ("before", "after"):
            state = business(run, point)
            for total, per_player in (("actions", "actions"), ("attempts", "attempts"),
                                      ("modifications", "modifications"), ("conversions", "conversionsReceived")):
                assert state[total] == sum(player[per_player] for player in state["players"]), (run["runId"], point, total)
            assert state["boardChanges"] == state["modifications"] + state["conversions"]
            assert sum(state["counts"].values()) == run["size"] ** 2
            assert state["modifications"] <= state["attempts"]
            assert all(int(player["pixelsConsumed"]) == 0 for player in state["players"])  # disabled stock in this campaign
        before, after = business(run, "before"), business(run, "after")
        assert run["executedActions"] == after["actions"] - before["actions"]
        assert not run["errors"], run["runId"]
        if run["mode"] == "application":
            assert run["activeElapsedNanos"] >= 20e9
            assert not run["after"]["failure"]
        audit.append(dict(runId=run["runId"], mode=run["mode"], size=run["size"], initial=run["initial"],
                          seed=run["seed"], polling=run.get("polling", "none"), actions=run["executedActions"],
                          attempts=after["attempts"]-before["attempts"], modifications=after["modifications"]-before["modifications"],
                          conversions=after["conversions"]-before["conversions"],
                          eliminatedBefore=sum(p["eliminated"] for p in before["players"]),
                          eliminatedAfter=sum(p["eliminated"] for p in after["players"]),
                          httpRequests=len(run.get("requests", [])), httpErrors=len(run["errors"]),
                          wallNanos=run["elapsedNanos"], activeNanos=run.get("activeElapsedNanos"), invariantChecks=True))
        if run["mode"] not in ("engine", "application") or "-profile-" in run["runId"]:
            continue
        metrics = {key: run[key] for key in ("elapsedNanos", "activeElapsedNanos", "attemptsPerSecond", "modificationsPerSecond",
                   "cpuNanos", "processCpuNanos", "allocatedBytesPerAction", "peakObservedHeapBytes", "executedActions",
                   "httpP50Nanos", "httpP95Nanos", "httpP99Nanos") if run.get(key) is not None}
        if run["mode"] == "application":
            a, b = run["before"].get("engineBlockedNanos"), run["after"].get("engineBlockedNanos")
            if a is not None and b is not None:
                metrics["engineBlockedNanos"] = b-a
        for metric, value in metrics.items():
            key = run["mode"], run["size"], run["initial"], run["seed"], run.get("polling", "none"), metric
            groups.setdefault(key, []).append(value)
            observations.append(dict(runId=run["runId"], mode=key[0], size=key[1], initial=key[2], seed=key[3], polling=key[4], metric=metric, value=value))
    summary = [dict(zip(("mode", "size", "initial", "seed", "polling", "metric"), key), **stats(values)) for key, values in sorted(groups.items())]
    write_csv(root / "observations.csv", observations)
    write_csv(root / "summary.csv", summary)
    write_csv(root / "workload-verification.csv", audit)
    replays = []
    for size in (500, 4000):
        for initial in ("empty", "full"):
            for seed in (11, 22, 33):
                pair = [run for run in runs if run["mode"] == "replay" and run["size"] == size and run["initial"] == initial and run["seed"] == seed]
                assert len(pair) == 2, (size, initial, seed)
                a, b = pair
                same = a["before"] == b["before"] and a["after"] == b["after"] and a["spatialSha256"] == b["spatialSha256"]
                replays.append(dict(size=size, initial=initial, seed=seed, equivalent=same, actions=a["executedActions"],
                                    spatialSha256=a["spatialSha256"], runs=[x["runId"] for x in pair], comparison="repeatability of identical production code, not optimization equivalence"))
    dump(root / "replay-verification.json", replays)
    assert all(x["equivalent"] for x in replays)
    engine_checks = []
    for size in (500, 4000):
        for initial in ("empty", "full"):
            group = [r for r in runs if r["mode"] == "engine" and r["size"] == size and r["initial"] == initial]
            assert len(group) == 5
            same = all(r["before"] == group[0]["before"] and r["after"] == group[0]["after"] and r["spatialSha256"] == group[0]["spatialSha256"] for r in group)
            engine_checks.append(dict(size=size, initial=initial, repetitions=len(group), identicalBusinessAndSpatialState=same))
            assert same
    dump(root / "engine-consistency.json", engine_checks)
    source = root / "builds/baseline/source"
    manifest, counters = {}, []
    for path in sorted((source / "src/main").rglob("*")):
        if path.is_file():
            name = path.relative_to(source).as_posix()
            manifest[name] = hashlib.sha256(path.read_bytes()).hexdigest()
            if path.suffix == ".java":
                lines = path.read_text(encoding="utf-8-sig").splitlines()
                matches = [{"line": n, "text": line.strip()} for n, line in enumerate(lines, 1)
                           if re.search(r"Atomic|LongAdder|DoubleAdder|LongAccumulator|private .*long|private .*int|synchronized|reset\(", line)]
                counters.append(dict(file=name, matches=matches))
    atomics = [dict(file=x["file"], **m) for x in counters for m in x["matches"] if re.search(r"Atomic\w*|LongAdder|DoubleAdder|LongAccumulator", m["text"])]
    dump(root / "source-inventory.json", dict(atomicMatchesInProduction=atomics, sources=manifest, accessLocations=counters,
                                             note="Text search locations support the manual access audit; this is not a synchronization proof by itself."))
    assert not atomics, "Reassess the decision: production atomics exist"
    plots(root, summary)
    report(root, runs, summary)
    print(f"{len(runs)} runs, {len(summary)} statistical rows, 12 successful repeated replays; no production candidate.")


def plots(root, summary):
    os.environ.setdefault("MPLCONFIGDIR", str(root / "matplotlib-cache"))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
    figures = root / "figures"
    figures.mkdir(exist_ok=True)
    plt.rcParams.update({"figure.dpi": 150, "font.size": 10, "axes.spines.top": False, "axes.spines.right": False})
    engine = [row for row in summary if row["mode"] == "engine" and row["metric"] == "attemptsPerSecond"]
    fig, ax = plt.subplots(figsize=(9, 5))
    ax.bar(range(len(engine)), [r["mean"]/1e6 for r in engine], yerr=[r["stdev"]/1e6 for r in engine], capsize=4, color="#526d82")
    ax.set_xticks(range(len(engine)), [f"{r['size']} × {r['size']}\n{'vide' if r['initial']=='empty' else 'rempli'}" for r in engine])
    ax.set_ylabel("Millions de tentatives / s")
    ax.set_title("Baseline actuelle · 5 JVM neuves par scénario · moyenne ± écart type")
    fig.tight_layout(); fig.savefig(figures / "baseline-engine.png"); plt.close(fig)
    fig, ax = plt.subplots(figsize=(8, 5))
    for offset, polling, label, color in ((-.18,"no-polling","Sans polling", "#526d82"),(.18,"polling","Avec polling", "#238b71")):
        rows = [next(row for row in summary if row["mode"] == "application" and row["size"] == size and row["polling"] == polling and row["metric"] == "attemptsPerSecond") for size in (500,4000)]
        ax.bar(np.arange(2)+offset, [r["mean"]/1e6 for r in rows], .34, yerr=[r["stdev"]/1e6 for r in rows], capsize=4, label=label, color=color)
    ax.set_xticks([0,1], ["500 × 500", "4000 × 4000"]); ax.set_ylabel("Millions de tentatives / seconde active")
    ax.set_title("Même code · charge HTTP différente · 3 JVM par condition")
    ax.legend(); fig.text(.02,.01,"Même code, pas un gain d'optimisation. SMALL atteint un vainqueur : nombreux essais sans changement.\nLes débits de modifications sont présentés séparément dans le rapport ; états finaux différents.",fontsize=8)
    fig.tight_layout(rect=(0,.07,1,1)); fig.savefig(figures / "baseline-application.png"); plt.close(fig)
    for path in sorted((root / "profiles").glob("*.summary.json")):
        data = load(path)
        for field, label, suffix, scale in (("cpuExclusiveSamples", "Échantillons CPU exclusifs", "cpu", 1),
                                           ("allocationEstimatedBytesByClass", "Mio alloués estimés", "allocations", 2**20),
                                           ("monitorWaitNanos", "Attentes cumulées de moniteurs (ms), seuil 1 ms", "moniteurs", 1e6)):
            items = sorted(data[field].items(), key=lambda x:x[1], reverse=True)[:10]
            fig, ax = plt.subplots(figsize=(12,6))
            if items:
                ax.barh(range(len(items)), [v/scale for _,v in items], color="#526d82")
                ax.set_yticks(range(len(items)), [k.replace("org.pixelwar.pixelwar.domain.","") for k,_ in items]); ax.invert_yaxis()
            else:
                ax.text(.5,.5,"Aucun événement retenu par ce profil / seuil",ha="center",transform=ax.transAxes)
            ax.set_xlabel(label); ax.set_title(path.stem.replace(".summary", ""))
            fig.text(.02,.015,"JFR réel séparé du débit principal. CPU échantillonné, pas un temps exact ; allocations estimées.\nLes moniteurs ne mesurent pas le coût des atomiques ; absence d'événement ≠ absence d'attente.",fontsize=8)
            fig.tight_layout(rect=(0,.07,1,1)); fig.savefig(figures / f"{path.stem}-{suffix}.png"); plt.close(fig)
    dump(root / "plot-dependencies.json", dict(python=sys.version, matplotlib=matplotlib.__version__, numpy=np.__version__))


def fmt(value, scale=1, digits=2):
    return "—" if value is None else f"{value/scale:,.{digits}f}".replace(",", " ").replace(".", ",")


def report(root, runs, summary):
    meta, env = load(root / "builds/baseline/build.json"), load(root / "environment.json")
    p = "02-compteurs/"
    get = lambda mode,size,initial,polling,metric: next(row for row in summary if row['mode']==mode and row['size']==size and row['initial']==initial and row['polling']==polling and row['metric']==metric)
    lines = [f"""# Optimisation 02 — étude du coût des compteurs

## Décision et résultat

**Piste non applicable dans cette architecture : aucun compteur atomique redondant n'existe en production.** Les compteurs techniques sont déjà des `long` ou `long[]` sous le moniteur Simulation, ceux du Board sous Board, et les fenêtres HTTP sous le filtre. Aucun gain de production n'est annoncé et aucun changement de compteur, verrou, gameplay, API ou métrique permanente n'est livré.

L'étape 01 a été conservée : les couleurs occupent un `byte[]`. Le départ de cette branche `opti/02-compteurs` est `180012fc1c1cf883a0dca233806093440baa113c`, état fonctionnel de `refacto_metriques`. La référence V3 historique et la branche de départ sont conservées. La référence **effectivement mesurée** est `{meta['commit']}` : son code de production est identique au départ, et son commit ajoute seulement le banc et le diagnostic. Le binaire mesuré porte le SHA-256 `{meta['jarSha256']}` ; celui de l'agent/banc est `{meta['harnessSha256']}` ([build](02-compteurs/build-baseline.json)). Le [patch](02-compteurs/patches/etude.patch) et le [contrôle de production](02-compteurs/production-preservation.json) délimitent le changement.

La mission demande explicitement, en l'absence d'atomiques redondants, de livrer l'inventaire, les profils et cette conclusion sans fabriquer d'optimisation. Les tableaux avant/après, accélération et gain marginal sont donc **non applicables**, et non égaux à zéro mesuré. Les mesures ci-dessous décrivent une baseline actuelle. Cinq répétitions moteur et trois répétitions applicatives par condition remplacent les paires avant/après sans candidat ; ce n'est pas une comparaison de deux implémentations.

La branche active du dossier principal a changé pendant la campagne. Les JVM mesurées proviennent d'une archive Git isolée et n'ont pas été affectées. L'étude et la remise ont été finalisées dans `target/opti-02/worktree` ; `refacto_metriques` reste active et inchangée dans le dossier principal. Le [journal d'exécution](02-compteurs/incidents.md) distingue ces incidents de pilotage d'une régression du moteur.

Les tests Maven de la référence reconstruite passent : **70 tests, zéro échec, zéro erreur**. Douze couples de rejeux répétés vérifient les mêmes états métier et SHA-256 spatiaux sur 500/4000, vide/rempli et graines 11/22/33. Ils prouvent la répétabilité du banc sur un code identique ; ils ne sont pas présentés comme un avant/après d'optimisation.

## Environnement et protocole

Les observations complètes sont dans [environment.json]({p}environment.json), [versions JVM]({p}java-version.txt), [Maven]({p}maven-version.txt), [PowerShell]({p}powershell-version.txt), [dépendances Python]({p}python-dependencies.txt), [versions de tracé]({p}plot-dependencies.json) et [invocations]({p}invocations.jsonl). CPU : {env['Cpu']['Name'].strip()}, {env['Cpu']['NumberOfCores']} cœurs physiques et {env['Cpu']['NumberOfLogicalProcessors']} processeurs logiques ; RAM visible {env['Computer']['TotalPhysicalMemory']} octets. OS : {env['Os']['Caption']} {env['Os']['Version']} {env['Os']['OSArchitecture']}. Caches CIM : L2 {env['Cpu']['L2CacheSize']} Kio, L3 {env['Cpu']['L3CacheSize']} Kio ; L1 et topologie fine non mesurées. JDK OpenJDK 27 (`27+35-2325`), compilation ciblant Java 21 ; mêmes flags `-Xms2g -Xmx2g -XX:+UseG1GC`, même agent Instrumentation.

Chaque essai est une JVM neuve. Moteur : chauffe de 3 s sur un jeu jetable, puis recréation du plateau et RNG de départ ; création et GC de préparation hors chronométrage. Plateau rempli par les mêmes couleurs pseudo-aléatoires (`Random(seed+1000)`), stratégie `Random(seed)` ; ordre joueur tournant et actions réelles de Simulation. Les poses de préparation remplissent aussi la file de candidats de conversion : la première passe dense reste dans le travail mesuré, comme à l'étape 01. Ce scénario n'est pas un plateau rempli déjà stabilisé. Budget calibré sur 5 000 actions puis figé dans [budgets.json]({p}budgets.json), cible d'environ 2 s estimée par la calibration, sans garantie de durée réelle constante. Cinq répétitions par taille/état, graine 11. Les rejoués ont 10 000 actions, sans chauffe, deux exécutions indépendantes par cas et graine.

Application : scheduler réel à 10 ns, fenêtre demandée de 20 s après 5 s de chauffe, trois JVM par taille et condition. Ordre des conditions alterné sans/avec puis avec/sans, puis sans/avec. La chauffe applicative poursuit le même jeu, comme à l'étape 01 ; sa stabilité JIT n'est pas démontrée statistiquement. Sans polling : aucune requête périodique, mais démarrage/pause/reprise et snapshots de début/fin restent nécessaires. Avec polling : chaque seconde, quatre GET concurrents `/api/simulation`, `/api/simulation/scores`, `/api/performance`, `/api/board/preview`, réponses intégrales et aperçu 500 × 500 inchangés. Boucles synchrones par batch : si un batch excède la période, aucune cadence obtenue de 4 req/s n'est présumée ; les requêtes réelles figurent dans les JSON. Port local libre choisi par essai.

Paramètres de jeu identiques à l'étape 01 : frontier, exploration 0,10, échantillon 10, bonus faible adversaire 8, maintien cible 20 actions, 10 poses maximum/action, conversion incrémentale simultanée active, stock désactivé pour les charges reproductibles. Les règles de consommation conditionnelle lorsque le stock est activé sont conservées et couvertes par les tests existants. Une action ignorée pour un joueur éliminé ne gonfle pas les compteurs. États et éliminations réels sont conservés par essai.

Adaptations du banc 01 : classe CounterExperiment isolée sous `scripts/opti02` ; ajout aux relevés, par getters existants, du temps actif exact et de l'erreur moteur ; conditions HTTP distinctes et percentile P50. Le seul AtomicLong du banc protège son sampler mémoire concurrent : le retirer serait incorrect. Le code de production et ses tests n'ont pas été modifiés.

La base de débit applicative est désormais le **delta exact de temps actif** du PerformanceTracker, et non la durée murale externe du pilote. Les deux sont enregistrés. Cela respecte la mission 02 mais empêche d'annoncer directement un gain cumulé à partir des anciennes tables 01. Lors de la campagne initiale le chronomètre mural débute juste avant resume ; le script de livraison le démarre après le retour de resume pour garantir la fenêtre demandée. Les fenêtres actives réellement obtenues sont vérifiées individuellement à au moins 20 s ; aucun essai trop court ne peut passer l'analyse. CPU moteur : delta du thread moteur collecté aux transitions ; CPU processus : delta MXBean entre snapshots, avec petite enveloppe de pilotage, pas uniquement le moteur. HTTP : durée murale client par GET, lecture complète comprise ; P50/P95/P99 nearest rank sur les succès, erreurs séparées. Le pic de heap est échantillonné toutes les 10 ms ; les octets alloués/action sont des deltas de ressources, pas la mémoire retenue.

Pas d'affinité, de fréquence CPU ni de température imposées ; autres activités Windows possibles. Aucune attribution causale d'un écart entre conditions à une instruction de compteur, aucun compteur matériel de cache-misses. G1, Instrumentation et le sampler sont conservés dans tous les essais. JFR est réservé à trois JVM séparées : moteur DEFAULT rempli, application DEFAULT sans et avec polling, seuil JavaMonitorEnter de 1 ms.

""", Path(__file__).with_name("inventaire.md").read_text(encoding="utf-8"), "\n\n## Mesures actuelles et absence de candidat\n\n"]
    lines += ["Unité : temps de charge moteur en secondes, débit en tentatives/s. σ et variance utilisent l'échantillon (diviseur n−1). Toutes les valeurs non arrondies sont dans [summary.csv](02-compteurs/summary.csv) et [observations.csv](02-compteurs/observations.csv). Le temps d'une fenêtre applicative ne constitue pas le temps d'une charge fixe.\n\n",
              "| Scénario | n | Temps moyen / médiane / σ (s) | Variance (s²) | Débit moyen / médiane / σ (tentatives/s) | Variance ((tentatives/s)²) | Après / gain |\n|---|---:|---|---:|---|---:|---|\n"]
    cases = [("engine",size,initial,"none") for size in (500,4000) for initial in ("empty","full")]+[("application",size,"empty",poll) for size in (500,4000) for poll in ("no-polling","polling")]
    for mode,size,initial,polling in cases:
        t = get(mode,size,initial,polling,"elapsedNanos"); r = get(mode,size,initial,polling,"attemptsPerSecond")
        assert t['n'] == (5 if mode=='engine' else 3)
        name=f"{mode} {size}, {initial}, {polling}"
        lines.append(f"| {name} | {t['n']} | {' / '.join(fmt(t[k],1e9,3) for k in ('mean','median','stdev'))} | {fmt(t['sampleVariance'],1e18,6)} | {' / '.join(fmt(r[k]) for k in ('mean','median','stdev'))} | {fmt(r['sampleVariance'])} | Non applicable |\n")
    lines.append("\n**Limite métier de SMALL applicatif : les six fenêtres commencent sans élimination et finissent avec trois joueurs éliminés. Après le vainqueur, le scheduler continue d'exécuter les poses du survivant, essentiellement sans changement. Les appels des joueurs éliminés sont exclus des actions, mais les tentatives réelles du survivant restent comptées selon la définition existante. Leur taux élevé ne mesure donc pas une capacité utile de guerre territoriale et ne doit pas être présenté comme un gain.** Le nombre de modifications et conversions réellement réalisées reste disponible par fenêtre ; faute d'horodatage du dernier affrontement, le débit limité à la phase contestée n'est pas mesurable rétrospectivement. Les données de fin de partie ne sont ni effacées ni remplacées par un autre paramétrage. Ce scénario est une limite du banc et reste partiel pour comparer la capacité utile ; aucun gain n'en est déduit.\n\n![Débit moteur actuel](02-compteurs/figures/baseline-engine.png)\n\n![Conditions HTTP](02-compteurs/figures/baseline-application.png)\n\n| Scénario | Actions moyennes | Modifications/s moyennes | CPU moteur moyen (ms) | Alloué/action (octets) | Pic heap moyen (Mio) |\n|---|---:|---:|---:|---:|---:|\n")
    for mode,size,initial,polling in cases:
        keys=[("executedActions",1),("modificationsPerSecond",1),("cpuNanos",1e6),("allocatedBytesPerAction",1),("peakObservedHeapBytes",2**20)]
        lines.append(f"| {mode} {size}, {initial}, {polling} | "+" | ".join(fmt(get(mode,size,initial,polling,k)['mean'],scale) for k,scale in keys)+" |\n")
    sizes = {size: next(r['colorsLayout']['totalArrayBytes'] for r in runs if r['mode']=='engine' and r['size']==size) for size in (500,4000)}
    lines.append(f"\nLes actions, conversions et éliminations de chaque essai sont dans [workload-verification.csv](02-compteurs/workload-verification.csv). Les tableaux supplémentaires CPU processus, attentes et percentiles conservent leur dispersion dans summary.csv. Aucune amélioration d'allocation ou mémoire n'est attribuée à cette étude sans changement. La mémoire des couleurs reste celle du byte[] de l'étape 01 : **{sizes[500]} octets pour SMALL et {sizes[4000]} octets pour DEFAULT**, observés par Instrumentation, hors autres index.\n\n### Requêtes et fenêtres applicatives réellement obtenues\n\n| Essai | Temps actif / mural (s) | Actions | Requêtes / erreurs | P50 / P95 / P99 (ms) | CPU processus (ms) |\n|---|---|---:|---|---|---:|\n")
    for run in runs:
        if run['mode']=='application' and '-profile-' not in run['runId']:
            lines.append(f"| {run['runId']} | {fmt(run['activeElapsedNanos'],1e9,3)} / {fmt(run['elapsedNanos'],1e9,3)} | {run['executedActions']} | {len(run['requests'])} / {len(run['errors'])} | {' / '.join(fmt(run.get(k),1e6,3) for k in ('httpP50Nanos','httpP95Nanos','httpP99Nanos'))} | {fmt(run['processCpuNanos'],1e6)} |\n")
    lines.append("\nLes percentiles sans polling sont absents (—), pas nuls. Les 20 secondes sont un minimum demandé ; temps actif et temps mural peuvent différer à cause des commandes. Les 3 répétitions sont trop peu nombreuses pour prétendre à une estimation précise des queues de latence. Aucun percentile global mélangeant les tailles n'est calculé.\n\n## Profils réels et interprétation\n\n")
    for path in sorted((root/'profiles').glob('*.summary.json')):
        data=load(path); stem=path.stem
        lines.append(f"### {path.name}\n\n[Export intégral]({p}profiles/{path.name}) et [JFR original]({p}profiles/{data['recording']}) dans l'archive de remise. CPU inclusif, exclusif et piles d'allocations sont conservés.\n\n")
        cpu=sorted(data['cpuExclusiveSamples'].items(),key=lambda x:x[1],reverse=True)[:5]
        alloc=sorted(data['allocationEstimatedBytesByClass'].items(),key=lambda x:x[1],reverse=True)[:5]
        lines.append("CPU exclusif dominant : "+" ; ".join(f"`{k}` : {v} samples" for k,v in cpu)+".\n\n")
        lines.append("Allocations estimées : "+" ; ".join(f"`{k}` : {fmt(v/2**20)} Mio" for k,v in alloc)+".\n\n")
        lines.append(f"Collections GC (durée totale) : {fmt(data['gcCollectionDurationNanos'],1e6)} ms ; pauses GCPhasePause : {fmt(data['gcStopTheWorldPauseNanos'],1e6)} ms. Ce sont deux mesures distinctes.\n\n| Classe du moniteur | Événements ≥ 1 ms | Attente cumulée (ms) |\n|---|---:|---:|\n")
        for key,value in sorted(data['monitorWaitNanos'].items(),key=lambda x:x[1],reverse=True):
            lines.append(f"| `{key}` | {data['monitorEvents'][key]} | {fmt(value,1e6)} |\n")
        if not data['monitorWaitNanos']: lines.append("| Aucun événement retenu | 0 | 0 observé à ce seuil |\n")
        for suffix in ('cpu','allocations','moniteurs'):
            lines.append(f"\n![JFR {suffix}]({p}figures/{stem}-{suffix}.png)\n")
    lines.append(f"""
Les incréments primitifs sont susceptibles d'être inlinés ; un profil échantillonné ne sépare pas leur coût de leur méthode englobante. Aucune absence de symbole AtomicLong dans une pile n'est interprétée comme preuve de coût nul. C'est l'inspection des champs et de tous les chemins d'accès qui établit l'absence de candidat. Les moniteurs mesurent un autre mécanisme que les instructions atomiques ; leur fréquence ne mesure pas le coût d'un CAS. Les temps cumulés de threads peuvent dépasser le temps mural et les événements inférieurs au seuil ne sont pas comptés.

Les méthodes et classes dominantes affichées ci-dessus orientent une prochaine hypothèse vers la stratégie de frontière, les allocations de coordonnées/collections et le coût de lecture/aperçu sous polling, selon le scénario. Cette étude ne modifie aucun de ces chemins et ne prouve pas qu'une refonte particulière les améliorerait. Les profils ne sont pas des mesures de scalabilité parallèle.

## Correction, tests et limites

[Tests de construction]({p}logs/build-baseline.log), [rejeux]({p}replay-verification.json), [invariants]({p}workload-verification.csv) et [inventaire des sources]({p}source-inventory.json) sont conservés. Les invariants vérifient pour chaque snapshot global la somme des compteurs par joueur, l'attribution des conversions, changements = modifications + conversions et somme des couleurs = surface, avec le même état spatial pour les rejeux. Les durées et mesures JVM sont exclues de l'égalité. Le stock est désactivé dans les benchmarks ; la validation du stock activé reste celle des tests métier existants.

Aucun compteur modifié, donc aucun nouveau test concurrent de remplacement n'est ajouté. Les tests existants couvrent notamment pose alliée sans débit du stock, action sans stock, attribution après pose sans changement, conversions différées/simultanées, erreur partielle, reset, ancienne génération et pause/stop. Le test scheduler historique utilise des attentes temporisées et ne constitue pas une nouvelle preuve exhaustive de tous les entrelacements ; le test du filtre utilise des latches, avec libération temporisée. Cette limite est déclarée, sans présenter ces tests comme un nouveau protocole par barrières. Le maintien bit pour bit du code de production est ici la garantie de non-régression du changement livré (banc/documentation uniquement).

La cohérence ne s'exige que dans un même snapshot sous son verrou. Deux requêtes différentes peuvent naturellement voir des actions différentes. Les objets Stock et PerformanceTracker ne sont pas déclarés thread-safe isolément. Un Board injecté doit respecter ses propres méthodes synchronisées ; il ne rend pas les mutations externes transactionnelles avec les métriques Simulation.

Aucun échec de solution n'a été inventé. Le lancement Maven initial sous sandbox a échoué avant tests ; le journal initial disponible et la reconstruction autorisée ont réussi. Une éventuelle panne du banc doit rester dans les journaux et ne serait pas une régression des compteurs. Pas de JMH : aucun microbenchmark facultatif ni optimisation fictive n'est nécessaire pour établir la non-applicabilité.

## Conclusion et reproduction

**Conserver la version actuelle ; ne pas modifier les compteurs.** Accélération temporelle `temps avant / temps après`, variation du débit `100 × (débit après / débit avant − 1)` et réduction de temps `100 × (1 − temps après / temps avant)` : non applicables faute de candidat après. Le gain marginal 02 et le gain cumulé depuis V3 restent non mesurés ; on n'additionne pas les pourcentages de l'étape 01. La comparaison polling/sans polling est une comparaison de charges différentes sur le même code, pas un avant/après de production.

Commande principale exécutée depuis la racine du dépôt, avec JDK et versions de Python documentés :

```powershell
$env:PYTHONPATH='C:/Users/gui35/ideaProjects/pixel-war/target/opti-01/python-libs'
.\\scripts\\benchmark-opti-02.ps1 -Reference {meta['commit']} -Java 'C:/Users/gui35/.jdks/openjdk-27/bin/java.exe' -Python 'C:/Python314/python.exe' -ResultsDir 'C:/Users/gui35/ideaProjects/pixel-war/target/opti-02/results' -Phase All -Resume
```

Installer au besoin `scripts/opti02/requirements.txt` dans un environnement Python isolé. Utiliser un ResultsDir neuf et omettre `-Resume` pour refaire toute la campagne. Le pilote reconstruit depuis `git archive`, teste, calibre, lance les JVM, exporte les JFR et appelle `scripts/opti02/analyze.py` pour les CSV, figures et ce rapport. `-Resume` ne reprend que les essais manquants et vérifie les commits. Le banc Java exact est dans le commit mesuré ; les scripts d'analyse et de remise sont dans le commit de livraison. L'archive contient aussi le binaire et l'agent réellement mesurés, les références, sources du banc et patch. Le dépôt Git avec la référence indiquée, JDK complet, dépendances Maven, Windows PowerShell et Python sont nécessaires pour recompiler ; aucune clé ni dossier .git complet n'est livré.

Le script `scripts/opti02/deliver.py <results> <repo> <archive>` publie les preuves et crée `target/audit-opti-02.zip`, contrôle SHA-256 et liens Markdown après extraction. [Index]({p}INDEX.md), [dictionnaire]({p}DICTIONNAIRE.md). JFR remplace le rôle de pprof ; le pilote PowerShell orchestre les forks à la place d'Hyperfine, mais exclut le démarrage JVM du chronométrage Java. Leur acceptation au barème relève de l'évaluateur.

## Correspondance avec le barème

| Critère | Points | Statut | Section et preuve | Reste / limite |
|---|---:|---|---|---|
| Environnement et métrologie | 3 | Partiel | Environnement/protocole, CSV individuels, 5 répétitions moteur, 3 par condition HTTP | Pas de candidat donc pas de paires avant/après ; SMALL applicatif atteint un vainqueur, taux de tentatives non représentatif de travail utile ; fréquence/affinité et stabilité JIT non contrôlées |
| Diagnostic et profiling réel | 5 | Couvert pour cette étude | Inventaire et trois JFR DEFAULT, CPU/allocations/moniteurs et vues annotées | Coût exact des incréments inlinés non isolé ; pas de counters matériel |
| Journal d'optimisation | 5 | Couvert pour cette étude | Diagnostic préalable, inventaire de tous les accès, aucun retrait de verrou | Aucun gain ni preuve de scalabilité parallèle |
| Échec constructif | 3 | Non traité | Non-applicabilité établie sans expérience artificielle | Pas de tentative de production réellement régressive |
| Reproductibilité et synthèse | 4 | Couvert pour cette étude | Script unique de campagne, synthèse, sources, raw, figures, archive et manifeste | Dépôt et dépendances requis ; archive vérifiée après extraction |
| Gouvernance IA | +2 | Non traité | Aucun constitution.md trouvé avec quatre directives | Le prompt de mission ne suffit pas à valider ce bonus |

Cette remise est une étude de choix de synchronisation. Elle ne valide ni l'audit final, ni réseau/persistance, ni parallélisme. Les profils avant/après sont non applicables en l'absence de modification ; les profils actuels exigés pour le diagnostic sont fournis. Les départs remplis applicatifs ne sont pas mesurés, comme à l'étape 01. Tout résultat absent reste explicitement absent.
""")
    (root / "02-compteurs.md").write_text("".join(lines), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    analyze(args.results.resolve())
