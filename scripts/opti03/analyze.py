"""Audit paired O3 observations, plot real measurements and write the French report."""
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import statistics
import sys


def load(p):
    return json.loads(Path(p).read_text(encoding='utf-8-sig'))


def dump(p, obj):
    Path(p).write_text(json.dumps(obj, indent=2, ensure_ascii=False), encoding='utf-8')


def csvwrite(p, rows):
    if rows:
        with Path(p).open('w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)


def stats(xs):
    return dict(n=len(xs), mean=statistics.mean(xs), median=statistics.median(xs),
                stdev=statistics.stdev(xs) if len(xs)>1 else None,
                sampleVariance=statistics.variance(xs) if len(xs)>1 else None,
                minimum=min(xs), maximum=max(xs))


def business(r, p):
    return r[p]['business'] if r['mode']=='application' else r[p]


METRICS = ('elapsedNanos', 'activeElapsedNanos', 'cpuNanos', 'processCpuNanos',
           'allocatedBytesPerAction', 'attemptsPerSecond', 'modificationsPerSecond',
           'peakObservedHeapBytes', 'httpP50Nanos', 'httpP95Nanos', 'httpP99Nanos',
           'gcCollections', 'gcCollectionMillis')


def analyze(root):
    runs = [load(p) for p in sorted((root/'raw').glob('*.json'))]
    groups, observations, checks = {}, [], []
    for r in runs:
        gc_before = r['before']['gc'] if r['mode']=='application' else r['gcBefore']
        gc_after = r['after']['gc'] if r['mode']=='application' else r['gcAfter']
        r['gcCollections'] = gc_after['collections']-gc_before['collections']
        r['gcCollectionMillis'] = gc_after['collectionMillis']-gc_before['collectionMillis']
        b, a = business(r,'before'), business(r,'after')
        for state in (b,a):
            for key, per in [('actions','actions'), ('attempts','attempts'), ('modifications','modifications'), ('conversions','conversionsReceived')]:
                assert state[key] == sum(p[per] for p in state['players']), (r['runId'], key)
            assert sum(state['counts'].values()) == r['size']**2
            # Preparation mutations are additional to the simulation counters on full boards.
            assert state['modifications'] <= state['attempts']
        assert a['boardChanges']-b['boardChanges'] == a['modifications']-b['modifications']+a['conversions']-b['conversions']
        assert not r['errors'], r['runId']
        assert r['executedActions']==a['actions']-b['actions']
        duration = r.get('activeElapsedNanos', r['elapsedNanos'])
        assert math.isclose(r['attemptsPerSecond'], (a['attempts']-b['attempts'])*1e9/duration, rel_tol=1e-10)
        assert all(int(p['pixelsConsumed'])==0 for p in a['players'])
        if r['mode']=='application':
            assert not r['after']['failure']
            assert duration >= r['requestedSeconds']*1e9
        else:
            assert r['requestedActions']==r['executedActions']
        useful = r.get('representativeContest',False)
        check = dict(runId=r['runId'], mode=r['mode'], version=r['version'], size=r['size'], initial=r['initial'],
                     seed=r['seed'], polling=r.get('polling','none'), actions=r['executedActions'],
                     attempts=a['attempts']-b['attempts'], modifications=a['modifications']-b['modifications'],
                     conversions=a['conversions']-b['conversions'], aliveBefore=sum(not p['eliminated'] for p in b['players']),
                     aliveAfter=sum(not p['eliminated'] for p in a['players']), representativeContest=useful,
                     victoryDetectedNanos=r.get('victoryDetectedNanos',r['after'].get('victoryDetectedNanos')),
                     victoryLastContestedNanos=r.get('lastContestedNanos',r['after'].get('lastContestedNanos')))
        checks.append(check)
        if r['mode'] in ('engine','application') and not r['runId'].startswith('application-profile'):
            for metric in METRICS:
                if r.get(metric) is not None:
                    key = (r['mode'],r['size'],r['initial'],r['seed'],r.get('polling','none'),r['version'],metric,useful)
                    groups.setdefault(key,[]).append(r[metric])
                    observations.append(dict(runId=r['runId'],mode=r['mode'],size=r['size'],initial=r['initial'],
                                             polling=r.get('polling','none'),version=r['version'],metric=metric,value=r[metric],representativeContest=useful))
    summary = [dict(zip(('mode','size','initial','seed','polling','version','metric','representativeContest'),key),**stats(values)) for key,values in groups.items()]
    csvwrite(root/'summary.csv',summary); csvwrite(root/'observations.csv',observations); csvwrite(root/'workload-verification.csv',checks)
    pairs, replay = [], []
    for r in runs:
        if r['version']!='before' or r['mode'] in ('calibration',): continue
        counterpart = next((s for s in runs if s['runId']==r['runId'].removesuffix('before')+'after'),None)
        assert counterpart, r['runId']
        if r['mode'] in ('engine','replay','profile'):
            same = r['before']==counterpart['before'] and r['after']==counterpart['after'] and r['spatialSha256']==counterpart['spatialSha256']
            assert same, r['runId']
            replay.append(dict(before=r['runId'],after=counterpart['runId'],identicalBusinessAndSpatialState=same,spatialSha256=r['spatialSha256']))
        if r['mode'] in ('engine','application') and not r['runId'].startswith('application-profile'):
            for metric in METRICS:
                if r.get(metric) is None or counterpart.get(metric) is None or r[metric]==0: continue
                pairs.append(dict(mode=r['mode'],size=r['size'],initial=r['initial'],polling=r.get('polling','none'),
                                  before=r['runId'],after=counterpart['runId'],metric=metric,
                                  afterOverBefore=counterpart[metric]/r[metric],
                                  variationPercent=100*(counterpart[metric]/r[metric]-1),
                                  bothRepresentative=r['representativeContest'] and counterpart['representativeContest']))
    csvwrite(root/'pairs.csv',pairs)
    paired_groups = {}
    for p in pairs:
        key=(p['mode'],p['size'],p['initial'],p['polling'],p['metric'],p['bothRepresentative'])
        paired_groups.setdefault(key,[]).append(p['variationPercent'])
    paired_summary = [dict(zip(('mode','size','initial','polling','metric','bothRepresentative'),key),**stats(values)) for key,values in paired_groups.items()]
    csvwrite(root/'pair-summary.csv',paired_summary)
    dump(root/'replay-verification.json',replay)
    dump(root/'validation.json',dict(runs=len(runs),comparisons=len(replay),functionalReplays=sum(r['mode']=='replay' for r in runs)//2,
                                    nonRepresentative=[r['runId'] for r in runs if r['mode'] in ('engine','application') and not r['runId'].startswith('application-profile') and not r['representativeContest']],
                                    workloadChecksPassed=True))
    plots(root,summary,runs)
    report(root,summary,runs,replay,paired_summary)
    print(json.dumps(load(root/'validation.json'),indent=2))


def plots(root, summary, runs):
    os.environ.setdefault('MPLCONFIGDIR',str(root/'matplotlib-cache'))
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    figures=root/'figures'; figures.mkdir(exist_ok=True)
    plt.rcParams.update({'figure.dpi':150,'font.size':10,'axes.spines.top':False,'axes.spines.right':False})
    for metric,title,unit,scale in [('allocatedBytesPerAction','Allocations moteur','Octets alloués / action',1),
                                    ('attemptsPerSecond','Débit moteur','Millions de tentatives / s',1e6)]:
        fig,ax=plt.subplots(figsize=(10,5))
        for offset,version,color,label in [(-.18,'before','#58728a','Avant'),(.18,'after','#208b72','Après')]:
            rows=[next(r for r in summary if r['mode']=='engine' and r['size']==size and r['initial']==initial and r['version']==version and r['metric']==metric)
                  for size in (500,4000) for initial in ('empty','full')]
            ax.bar(np.arange(4)+offset,[r['mean']/scale for r in rows],.34,yerr=[r['stdev']/scale for r in rows],capsize=4,color=color,label=label)
        ax.set_xticks(range(4),['SMALL vide','SMALL rempli','DEFAULT vide','DEFAULT rempli']); ax.set_ylabel(unit)
        ax.set_title(title+' · 5 paires · moyenne ± écart type'); ax.legend()
        fig.text(.02,.01,'JVM neuves, graine 11. Travail identique et états finaux vérifiés. Barres : dispersion entre JVM.',fontsize=8)
        fig.tight_layout(rect=(0,.04,1,1)); fig.savefig(figures/f'{metric}.png'); plt.close(fig)
    for scenario in ('profile-4000-full-s11-p0','application-profile-4000-empty-polling-s11-p0','application-profile-4000-empty-no-polling-s11-p0'):
        files=[root/'profiles'/f'{scenario}-{version}.summary.json' for version in ('before','after')]
        if not all(p.exists() for p in files): continue
        data=[load(p) for p in files]
        for field,label,scale,suffix in [('cpuExclusiveSamples','Échantillons CPU exclusifs',1,'cpu'),
                                          ('allocationEstimatedBytesByClass','Mio alloués estimés',2**20,'allocations'),
                                          ('monitorWaitNanos','Attentes cumulées (ms), seuil 1 ms',1e6,'moniteurs')]:
            keys=set()
            for d in data: keys.update(k for k,v in sorted(d[field].items(),key=lambda t:t[1],reverse=True)[:8])
            if field=='allocationEstimatedBytesByClass': keys.add('org.pixelwar.pixelwar.domain.PlacementStrategy$Position')
            keys=sorted(keys,key=lambda k:max(d[field].get(k,0) for d in data),reverse=True)[:12]
            fig,axes=plt.subplots(1,2,figsize=(16,6),sharey=True)
            for ax,d,version in zip(axes,data,('Avant','Après')):
                if keys:
                    ax.barh(range(len(keys)),[d[field].get(k,0)/scale for k in keys],color='#58728a' if version=='Avant' else '#208b72')
                    ax.set_yticks(range(len(keys)),[k.replace('org.pixelwar.pixelwar.domain.','') for k in keys])
                else: ax.text(.5,.5,'Aucun événement au seuil retenu',ha='center',transform=ax.transAxes)
                ax.set_title(version); ax.set_xlabel(label)
            axes[0].invert_yaxis(); fig.suptitle(scenario)
            fig.text(.02,.01,'Exports JFR réels : CPU exclusif échantillonné, poids d’allocations estimés, pas mémoire retenue.\nLes fenêtres applicatives effectuent des nombres d’actions différents : comparer aussi les octets/action. Aucun événement ≠ coût nul.',fontsize=8)
            fig.tight_layout(rect=(0,.07,1,.95)); fig.savefig(figures/f'{scenario}-{suffix}.png'); plt.close(fig)
    dump(root/'plot-dependencies.json',dict(python=sys.version,matplotlib=matplotlib.__version__,numpy=np.__version__))


def f(x,scale=1):
    return 'non mesuré' if x is None else f'{x/scale:,.2f}'.replace(',',' ').replace('.',',')


def report(root, summary, runs, comparisons, pair_summary):
    before,after=[load(root/f'builds/{v}/build.json') for v in ('before','after')]
    env=load(root/'environment.json'); validation=load(root/'validation.json'); p='03-positions-primitives/'
    def row(mode,size,initial,version,metric,polling='none'):
        return next(r for r in summary if r['mode']==mode and r['size']==size and r['initial']==initial and r['version']==version and r['metric']==metric and r['polling']==polling)
    def table(mode,metrics,conditions):
        out=['| Scénario / mesure | Avant, moyenne | Après, moyenne | Variation | n avant/après |','|---|---:|---:|---:|---:|']
        for size,initial,polling in conditions:
            for metric,label,scale in metrics:
                a=row(mode,size,initial,'before',metric,polling); b=row(mode,size,initial,'after',metric,polling)
                change=f(100*(b['mean']/a['mean']-1))+' %' if a['mean'] else 'non applicable (base nulle)'
                out.append(f"| {size} {initial} {polling} / {label} | {f(a['mean'],scale)} | {f(b['mean'],scale)} | {change} | {a['n']}/{b['n']} |")
        return '\n'.join(out)
    engine_conditions=[(s,i,'none') for s in (500,4000) for i in ('empty','full')]
    compact_lines=['| Scénario | Octets/action avant → après | M tentatives/s avant → après | CPU moteur (s) avant → après |',
                   '|---|---:|---:|---:|']
    for size,initial,polling in engine_conditions:
        cells=[]
        for metric,scale in [('allocatedBytesPerAction',1),('attemptsPerSecond',1e6),('cpuNanos',1e9)]:
            cells.append(' → '.join(f(row('engine',size,initial,v,metric)['mean'],scale) for v in ('before','after')))
        compact_lines.append(f"| {size} {initial} | "+' | '.join(cells)+' |')
    compact='\n'.join(compact_lines)
    lines=[f'''# O3 — positions primitives dans la sélection de frontière

## Synthèse intégrable à l’audit final

Pixel War doit traiter DEFAULT (4000 × 4000, 64 fois SMALL) sans modifier la partie simulée. Après O1 (board compact) et O2 (aucun atomique redondant à retirer), O3 vise les objets temporaires de sélection. Le profil O2 DEFAULT avec polling estimait 1 013 007 032 octets d’objets Position, soit environ 966 Mio ; ce poids échantillonné ne prouvait pas un coût exclusif de la boucle.

Le changement tire les mêmes identifiants de frontière, garde le meilleur dans un `int` et évalue avec des coordonnées primitives. Une `Position` est construite à la fin ; exploration, repli, ordre RNG, formule de score, synchronisations et règles sont conservés. Le décodage modulo/division est fait une fois par occurrence, puis pour le gagnant. Les signatures historiques restent des adaptateurs.

{compact}

Les moyennes portent sur cinq JVM par variante et scénario, graine 11. Les dispersions figurent en annexe. Validation : 70 tests avant, 73 après ; douze rejeux réels (500/4000, vide/rempli, graines 11/22/33) et toutes les paires moteur/profil vérifient état métier et empreinte spatiale. Les tests tracent les types, bornes, retours et l’ordre RNG contre la stratégie historique figée. Les fenêtres ayant atteint la victoire sont exclues des conclusions de capacité utile ({len(validation['nonRepresentative'])} essai(s) principal(aux) signalé(s)). Le gain cumulé face à V3 reste non mesuré.

Décision : conserver pour la réduction d’allocations, en signalant les régressions de débit mesurées. Les accès Board et le scoring restent des coûts à examiner dans les profils. Les autres optimisations sont hors périmètre.

## 1. Références, environnement et diagnostic

Départ utilisateur : `a73c938c68e11174b5687c094efcf20d505b1b0f`, branche `refacto_metriques` préservée. Branche de travail : `opti/03-positions-primitives`.
Avant mesuré : `{before['commit']}` ; après mesuré : `{after['commit']}`. Le commit de banc avant ne modifie pas la production. L’après inclut uniquement les deux fichiers de production de sélection et les tests. Voir [build avant]({p}build-before.json), [build après]({p}build-after.json) et [patch]({p}patches/optimisation.patch).

CPU : {env['Cpu']['Name'].strip()}, {env['Cpu']['NumberOfCores']} cœurs physiques / {env['Cpu']['NumberOfLogicalProcessors']} logiques ; RAM {env['Computer']['TotalPhysicalMemory']} octets. OS : {env['Os']['Caption']} {env['Os']['Version']} {env['Os']['OSArchitecture']}. Caches CIM : L2 {env['Cpu']['L2CacheSize']} Kio, L3 {env['Cpu']['L3CacheSize']} Kio ; L1 et topologie fine non mesurés.

OpenJDK 27, compilation Java 21 ; flags identiques `-Xms2g -Xmx2g -XX:+UseG1GC` ; agent Instrumentation commun. [Environnement effectif]({p}environment.json), [JVM]({p}java-version.txt), [Maven]({p}maven-version.txt), [dépendances de tracé]({p}plot-dependencies.json), [commandes]({p}COMMANDES.md). Aucun réglage d’affinité, température ou fréquence ; bruit Windows possible.

Inspection avant : Board.randomFrontierPosition tirait un id dans FrontierIndex puis matérialisait un record par occurrence ; choose renvoyait le meilleur objet. Les autres allocations (`CellState.values`, Long/EnumMap, conversions) sont conservées. Le code de production au départ est identique à la référence O2 `c5a8777`, ce qui autorise le profil existant comme diagnostic préalable ; les nouveaux profils comparent les deux variantes actuelles.

## 2. Changement et validation

`Board.randomFrontierId`, de visibilité package et synchronisé, conserve validation de couleur, ordre de stockage et FrontierIndex.randomId. La sentinelle `-1` désigne une frontière vide sans tirage ; l’adaptateur public la traduit en null. Dans choose, frontière vérifiée non vide et échantillonnage sous le même moniteur excluent une disparition en cours de boucle. Aucun nouveau tirage de récupération n’est ajouté.

`FrontierPlacementStrategy` conserve le nextDouble initial, les doublons, le réservoir de départage, les lectures/courts-circuits, l’arrondi de faiblesse et la priorité. Scoring et anticipation partagent une implémentation primitive ; les adaptateurs Position évitent une divergence. Aucun objet mutable réutilisé, pooling, boxing ou tableau de coordonnées par candidat. La réduction des constructions est une propriété du source ; les octets/action ci-dessous mesurent le résultat de la JVM.

Les tests PrimitivePositionTests confrontent la nouvelle sélection à ObjectFrontierReference, copie de l’avant : explorations 0/0,1/1, tailles d’échantillon 1/10, doublons forcés, égalités, vide/rempli, bords et rectangle, mutation entre poses, reset, score et bonus. Les tests existants de stock, conversions, API, pause/stop/reset restent exécutés.

Les rejeux mesurent 10 000 actions réelles avec premier joueur tournant par tour ; état initial et RNG recréés, empreinte SHA-256 cellule par cellule. Comparaison complète des snapshots métier : couleurs, tailles de frontières, compteurs par joueur, stock, éliminations et cibles prioritaires. Les durées et ressources JVM sont exclues. Les frontières sont comparées par tailles et leurs invariants sont couverts par les tests ; il ne s’agit pas d’une preuve exhaustive de leur stockage interne sur 16 millions de cellules. [Comparaisons]({p}replay-verification.json), [cohérence des charges]({p}workload-verification.csv), [bilan]({p}validation.json), [tests avant]({p}logs/build-before.log), [tests après]({p}logs/build-after.log).

## 3. Protocole de mesure

Une action est une exécution réelle pour un joueur non éliminé ; une tentative est une pose essayée ; une modification est un changement direct ; une conversion est un changement appliqué dans une passe simultanée sur état stable. Un appel pour un éliminé ne gonfle pas les actions.

Chaque JVM est neuve ; variantes séquentielles, ordre avant/après alterné dans les cinq paires moteur et trois paires applicatives par taille/condition. Moteur : chauffe de 3 s sur un jeu jetable, recréation du plateau et RNG, construction/préparation/GC/empreintes hors chronométrage. Le budget est calibré avant, cible 2 s, plafonné à 50 000 actions SMALL et 100 000 DEFAULT pour borner la phase de jeu, puis figé identiquement ; les durées réellement obtenues peuvent être inférieures à 2 s. [Budgets]({p}budgets.json). Le plein pseudo-aléatoire est préparé par Board.place avec Random(seed+1000), en conservant les candidats de conversion de préparation. Première passe dense incluse.

Paramètres : frontier, exploration 0,10, dix candidats et dix tentatives/action, bonus 8, priorité 20 actions, conversions actives, stock désactivé, cadence demandée 10 ns, aperçu 500 × 500. Graines : 11 pour les performances ; 11/22/33 pour les rejeux. Aucune comparaison directe à l’ancien débit O1 fondé sur le temps mural.

Application : chauffe métier jetable de 3 s avant création du serveur, puis 0,2 s du scheduler sur le jeu mesuré. SMALL utilise une fenêtre cible de 1 s pour limiter le risque de victoire, DEFAULT 20 s ; ce protocole est distinct des 5 s de chauffe et 20 s d’O2. Il ne démontre pas la stabilisation complète de la JVM HTTP sur SMALL : ce résultat court reste complémentaire. Le temps inclut la demande de pause finale ; la durée réelle est enregistrée, le temps actif exact définit le débit. Les batchs HTTP attendent les quatre GET concurrents de la seconde : simulation, scores, performance, preview ; réponses intégrales, latences client et transferts compris. Si un batch dépasse une seconde, la cadence réelle diminue et la fenêtre peut déborder. Sans polling : commandes et snapshots de pilotage uniquement.

La victoire est observée sans changer la production : moteur après chaque tour (au plus quatre appels), application par observateur interne toutes les 10 ms demandées sous Simulation. Le dernier constat contesté et le premier constat de victoire bornent le passage ; un retard de scheduling/verrou peut élargir cet intervalle, donc 10 ms n’est pas une borne garantie. Éliminations monotones : états début/fin et détection permettent d’exclure toute fenêtre post-victoire. L’observateur ajoute des prises de verrou identiques avant/après, y compris sans polling. Aucun débit partiel avant victoire n’est reconstruit à partir des totaux.

CPU principal : delta du thread moteur. Application : PerformanceTracker pour moteur et MXBean séparé pour processus. Allocations/action : delta du thread moteur, pas allocation retenue. Pic heap échantillonné toutes les 10 ms et dépendant du GC. Les compteurs GC moteur encadrent aussi les snapshots/empreintes après travail : ils restent complémentaires et ne sont pas attribués exclusivement au chronométrage métier.

## 4. Résultats détaillés

Le fichier [summary.csv]({p}summary.csv) conserve moyenne, médiane, écart type, variance d’échantillon (n−1), min/max et n pour chaque mesure, taille, état, condition et variante. [Observations]({p}observations.csv), [rapports par paire]({p}pairs.csv), [dispersion des variations appariées]({p}pair-summary.csv). Une variation de moyenne ne constitue pas un test de significativité.

{table('engine', [('elapsedNanos','temps fixe (s)',1e9),('allocatedBytesPerAction','octets/action',1),('attemptsPerSecond','tentatives/s',1),('modificationsPerSecond','modifications/s',1),('cpuNanos','CPU moteur (s)',1e9),('peakObservedHeapBytes','pic heap (Mio)',2**20)],engine_conditions)}

![Allocations moteur]({p}figures/allocatedBytesPerAction.png)

![Débit moteur]({p}figures/attemptsPerSecond.png)

### Application et phase de jeu

{table('application',[('attemptsPerSecond','tentatives/s actif',1),('modificationsPerSecond','modifications/s actif',1),('allocatedBytesPerAction','octets/action moteur',1),('cpuNanos','CPU moteur (s)',1e9),('processCpuNanos','CPU processus (s)',1e9)],[(s,'empty',poll) for s in (500,4000) for poll in ('no-polling','polling')])}

Les essais non représentatifs sont conservés dans les données et signalés : {', '.join(validation['nonRepresentative']) or 'aucun essai principal'}. Leur débit ne mesure pas la capacité d’affrontement. Les états applicatifs finaux peuvent différer : il s’agit de fenêtres à durée commune, pas de budgets d’actions identiques.

{table('application',[('httpP50Nanos','HTTP P50 (ms)',1e6),('httpP95Nanos','HTTP P95 (ms)',1e6),('httpP99Nanos','HTTP P99 (ms)',1e6)],[(s,'empty','polling') for s in (500,4000)])}

Les percentiles sont nearest rank ceil(p×n), par essai. Le tableau résume leur moyenne entre JVM ; ce n’est pas un percentile global fusionné. Erreurs et requêtes individuelles figurent dans chaque JSON. Sans polling, percentiles non mesurés, jamais zéro.

### Dispersion moteur et rapports appariés

| Scénario / mesure | Variante | Moyenne | Médiane | Écart type | Variance n−1 | n |
|---|---|---:|---:|---:|---:|---:|
''']
    for r in summary:
        if r['mode']=='engine' and r['metric'] in ('elapsedNanos','attemptsPerSecond','allocatedBytesPerAction','cpuNanos'):
            scale=1e9 if r['metric'].endswith('Nanos') else 1
            lines.append(f"| {r['size']} {r['initial']} / {r['metric']} ({'s' if scale==1e9 else 'unité native'}) | {r['version']} | {f(r['mean'],scale)} | {f(r['median'],scale)} | {f(r['stdev'],scale)} | {f(r['sampleVariance'],scale**2)} | {r['n']} |")
    lines.append('\n| Scénario / variation après÷avant − 1 (%) | Moyenne | Médiane | Écart type | Variance | n |\n|---|---:|---:|---:|---:|---:|')
    for r in pair_summary:
        if r['mode']=='engine' and r['metric'] in ('elapsedNanos','attemptsPerSecond','allocatedBytesPerAction'):
            lines.append(f"| {r['size']} {r['initial']} / {r['metric']} | {f(r['mean'])} | {f(r['median'])} | {f(r['stdev'])} | {f(r['sampleVariance'])} | {r['n']} |")
    lines.append('\n## 5. Profils réels et limites causales\n\nJFR CPU, allocations et moniteurs sont capturés dans des JVM séparées des mesures principales : moteur DEFAULT rempli et application DEFAULT sans/avec polling, avant/après. JavaMonitorEnter : seuil 1 ms, piles activées ; événements plus courts absents. CPU exclusif : sommet de pile, inclusif : méthode présente une fois par échantillon. Les poids ObjectAllocationSample sont estimés, pas des comptes exacts ni de la mémoire retenue. Les exports conservent les piles complètes ; les vues suivantes sont des barres comparatives des événements réellement collectés, pas des flamegraphs reconstruits. Les originaux JFR sont dans l’archive.\n')
    for scenario in ('profile-4000-full-s11-p0','application-profile-4000-empty-polling-s11-p0','application-profile-4000-empty-no-polling-s11-p0'):
        profiles=[root/'profiles'/f'{scenario}-{v}.summary.json' for v in ('before','after')]
        if not all(x.exists() for x in profiles): continue
        lines.append(f'\n### {scenario}\n\n| Variante | Actions | Position, octets estimés | Position, octets estimés/action | Échantillons CPU | GC / pauses (ms) |\n|---|---:|---:|---:|---:|---|')
        for version,path in zip(('before','after'),profiles):
            d=load(path); r=next(r for r in runs if r['runId']==f'{scenario}-{version}')
            weight=d['allocationEstimatedBytesByClass'].get('org.pixelwar.pixelwar.domain.PlacementStrategy$Position',0)
            lines.append(f"| {version} | {r['executedActions']} | {weight} | {f(weight/r['executedActions'])} | {d['eventCounts'].get('jdk.ExecutionSample',0)} | {f(d['gcCollectionDurationNanos'],1e6)} / {f(d['gcStopTheWorldPauseNanos'],1e6)} |")
            top=sorted(d['cpuExclusiveSamples'].items(),key=lambda t:t[1],reverse=True)[:4]
            pos_stacks=sorted(d.get('positionAllocationEstimatedBytesByStack',{}).items(),key=lambda t:t[1],reverse=True)
            lines.append(f"\n{version} : principales méthodes exclusives : {' ; '.join(k+' ('+str(v)+')' for k,v in top)}. [Export complet]({p}profiles/{path.name}).\n")
            for stack,weight in pos_stacks[:3]:
                frames=' → '.join(stack.split(';')[:4]).replace('org.pixelwar.pixelwar.domain.','')
                lines.append(f"\nPile d’allocations Position ({version}) : `{frames}` ; poids {weight} octets estimés, {f(weight/r['executedActions'])} octets estimés/action. Pile complète conservée dans l’export, sans fusion avec les allocations d’autres classes.\n")
        for suffix in ('cpu','allocations','moniteurs'):
            lines.append(f'\n![{scenario} {suffix}]({p}figures/{scenario}-{suffix}.png)\n')
    lines.append('''
Les profils remplis moteur comparent un même nombre d’actions, tandis que les fenêtres applicatives peuvent exécuter davantage d’actions après. Une hausse d’octets par seconde ou de poids total JFR ne réfute donc pas une baisse par action. Une part CPU relative peut augmenter alors que la durée baisse ; échantillons, temps CPU exact et octets/action ne sont pas interchangeables. Aucun cache-miss matériel n’est mesuré, aucune absence de GC n’est extrapolée au serveur entier.

## 6. Décision et suite
''')
    reductions=[]; gains=[]
    for size,initial,polling in engine_conditions:
        a=row('engine',size,initial,'before','allocatedBytesPerAction')['mean']; b=row('engine',size,initial,'after','allocatedBytesPerAction')['mean']
        reductions.append(100*(1-b/a))
        a=row('engine',size,initial,'before','attemptsPerSecond')['mean']; b=row('engine',size,initial,'after','attemptsPerSecond')['mean']
        gains.append(100*(b/a-1))
    lines.append(f"\nLes allocations moyennes/action baissent de {f(min(reductions))} à {f(max(reductions))} % selon le scénario moteur. Les variations moyennes de débit vont de {f(min(gains))} à {f(max(gains))} %. Les ratios par paire et leur dispersion empêchent de généraliser une accélération à toutes les charges.\n")
    lines.append('''
**Conserver le changement pour la suppression ciblée des allocations**, sous réserve des régressions de débit explicitement visibles dans les tableaux. Cette décision ne revendique pas une amélioration universelle : les différences d’inlining, la division/modulo et les coûts de scoring conservés peuvent limiter le gain. Les profils indiquent les prochains coûts à étudier (scoring/accès Board et autres allocations), sans engager une autre optimisation ici. O2 ne reçoit aucune portion du gain. Le gain marginal O3 est celui de cette expérience ; le gain cumulé face à V3 reste non mesuré faute de protocole commun repris.

L’échec initial d’un nouveau jeu de test (pose EMPTY interdite) a été corrigé en laissant la case vide à la préparation ; le journal d’échec est conservé. Il ne constitue pas un échec de performance de l’optimisation. Les régressions réellement mesurées doivent être appréciées avec la dispersion, sans en fabriquer une pour le barème.

## 7. Reproduction, remise et correspondance au barème

Le script PowerShell reconstruit des archives Git isolées, compile les deux références et le même banc Java depuis les scripts livrés. Il n’exécute pas les variantes simultanément. Les classes du banc sont comparées octet par octet ; les JAR ont leurs SHA-256 propres (horodatages ZIP possibles). Une reproduction requiert le dépôt aux commits du rapport, JDK 27, Maven/son cache, PowerShell Windows et Python avec les versions de requirements.txt. [Commande unique et phases réellement utilisées](03-positions-primitives/COMMANDES.md). Le moteur et ses tests de production sont identifiés par commit ; les scripts d’analyse/livraison sont dans l’archive et n’interviennent pas dans le gameplay.

L’archive `target/audit-opti-03.zip` contient rapport, synthèse courte, données et unités, scripts, profils originaux et exports, figures, journaux, JAR effectivement mesurés, patch et manifeste SHA-256. Une extraction indépendante vérifie les empreintes et les liens relatifs. Les JFR sont volumineux et restent hors Git ; leurs exports sont versionnés. La reproduction du code nécessite le dépôt : aucun dossier .git ni secret n’est embarqué.

| Critère | Points | Statut et pièces | Limite pour l’audit final |
|---|---:|---|---|
| Environnement et métrologie | 3 | Couvert : §1/3/4, environnement, observations, summary, paires | Fréquence/affinité/température non contrôlées ; fenêtre SMALL courte |
| Diagnostic réel | 5 | Couvert : §1/5, JFR avant/après, exports, vues CPU/allocations | Échantillonnage, seuil moniteurs, aucun compteur matériel |
| Journal d’ingénierie | 5 | Couvert : §2/3/6, code isolé, RNG et rejeux | O3 traite les allocations ; pas réseau/persistance/parallélisme |
| Échec constructif | 3 | Partiel : dispersions/régressions observées §4/6 ; incident de test conservé | Pas de variante de production rejetée artificiellement |
| Reproductibilité et synthèse | 4 | Couvert : commande, références, données, figures, archive et vérification | Prérequis Windows/Java/Python ; bruit machine possible |
| Gouvernance IA | +2 | Non traité : aucun constitution.md dédié constaté | Les quatre directives et bonus ne sont pas présumés acquis |

JFR/JMC tient le rôle de profils CPU/allocations comparable à pprof ; le pilote de JVM neuves, chauffe et statistiques tient le rôle métrologique attendu d’Hyperfine. Les différences Java/Go et l’adéquation finale au barème restent à apprécier par l’évaluateur. Aucun score n’est auto-attribué.
''')
    text='\n'.join(lines)
    (root/'03-positions-primitives.md').write_text(text,encoding='utf-8')
    # A short standalone extract for direct reuse; appendix starts at section 1.
    (root/'SYNTHESE.md').write_text(text.split('## 1. Références')[0],encoding='utf-8')


if __name__=='__main__':
    analyze(Path(sys.argv[1]).resolve())
