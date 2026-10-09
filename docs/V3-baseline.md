# Pixel War — V3 : stratégie de frontière

Implémentation et mesures exploratoires du 10 octobre 2026 sur la branche `feat/v3`.

## Décisions et implémentation

Les quatre joueurs utilisent par défaut `FrontierPlacementStrategy`, avec une exploration uniforme de 10 % et dix candidats tirés avec remise. Le score est `2 × voisins alliés + bonus adversaire + 8 × conversions alliées potentielles`. L’anticipation examine uniquement les centres voisins intérieurs ; elle ne modifie aucune cellule. Un départage par réservoir choisit aléatoirement parmi les occurrences de score égal dans l’échantillon.

`RandomPlacementStrategy` conserve la sélection V2 parmi les cases vides, puis parmi toutes les cases sur un plateau rempli. Les générateurs pseudo-aléatoires sont injectables. `PositionStrategy` reste un alias de compatibilité pour les fixtures V2.

Les frontières appartiennent au plateau. Chaque changement effectif via `Board.place` réévalue la cellule et ses voisins directs pour les quatre couleurs, conversions comprises. Les ensembles utilisent une liste dense d’identifiants et des index inverses primitifs alloués par blocs de 4 096 cellules. La suppression échange avec le dernier élément. Aucun objet par cellule et aucun parcours de frontière ne sont nécessaires pour sélectionner une cible. Les blocs ne sont pas libérés à chaque suppression ; le reset libère les blocs et réinitialise la capacité des listes.

Le scheduler, les règles de pose, l’élimination, les stocks et la conversion incrémentale V2 sont conservés. **Décision utilisateur : une tentative ne consomme du stock que si elle change une cellule.** Cela remplace la phrase initiale de la proposition V3 qui prévoyait une consommation à chaque tentative. Les cibles adverses sont encore refusées tant qu’il reste des cases vides ; les joueurs éliminés ne sont pas réactivés par le repli aléatoire.

## Mémoire

Sur le plateau 4 000 × 4 000, les tables de références des quatre index nécessitent environ 125 ko si l’on compte huit octets par référence, avant allocation des blocs. La capacité initiale des listes représente 256 octets au total.

Dans un cas très fragmenté, les index inverses peuvent allouer jusqu’à 256 millions d’octets et les listes d’identifiants jusqu’à 256 millions d’octets supplémentaires. Ce majorant est conservateur ; les quatre frontières ne couvrent pas nécessairement simultanément tout le plateau. Il faut ajouter le plateau, les index de cases vides, les candidats de conversion, les objets JVM et les autres allocations. Les grands profils nécessitent donc une heap adaptée.

`frontiers.storageBytes` estime la capacité des tableaux primitifs et des tables de références, hors en-têtes d’objets et alignements. Cette valeur diffère de la heap JVM utilisée.

## Validation

`mvnw.cmd -B -ntp verify` réussit : **48 tests, zéro échec et zéro erreur**, sous OpenJDK 27, avec compilation ciblant Java 21. Le JAR exécutable est généré. Le JavaScript passe `node --check` sous Node.js 24.21.0.

Les tests V2 sont conservés. La V3 ajoute notamment :

- reconstruction indépendante des frontières après poses, conversions, placements sans changement et resets, sur 96 dimensions de petits plateaux ;
- bords, coins, dimensions de un, suppressions, déduplication et indépendance des plateaux ;
- passes successives sur un damier ;
- exploration 0 et 1, repli uniforme, score et absence de mutation pendant l’évaluation ;
- meilleur score, départage et reproductibilité des graines ;
- mise à jour entre plusieurs poses d’une action, respect du stock et absence de sélection sans stock ;
- validation de configuration et exposition API des paramètres et des métriques.

## Protocole de comparaison moteur

Windows, OpenJDK 27, Intel Core i5-13600KF, 20 processeurs logiques, heap maximale `-Xmx1g`. Plateau de 100 × 100, dix tentatives par action, stock désactivé, conversions incrémentales activées, intervalle configuré à 10 ns, exploration 0,10, échantillon de dix candidats. Graines : 11, 22 et 33.

Deux états initiaux sont utilisés : plateau vide et plateau entièrement rempli avec des couleurs uniformément aléatoires. Pour chaque graine, les deux stratégies partent du même état. Les frontières sont maintenues dans les deux modes : la comparaison isole surtout la sélection et les trajectoires produites, pas le surcoût du suivi face à une V2 sans index de frontières.

Le programme `scripts/FrontierBenchmark.java` appelle l’action réelle de `Simulation` sur un seul thread. Il conserve la rotation du premier joueur et les règles d’élimination. Les callbacks automatiques du scheduler sont remplacés par ce pilotage pour contrôler les fenêtres. Il ne mesure ni les délais réels du scheduler, ni HTTP, ni le rendu, ni la contention de plusieurs threads.

Pour les performances : chauffe de 300 ms puis fenêtre d’une seconde par scénario. Le board conserve l’état atteint après la chauffe. Ces fenêtres comparent donc les stratégies à durée égale, mais pas à un état spatial final identique. Le temps CPU est celui du thread de mesure, exprimé en pourcentage d’un cœur ; la heap est un relevé final dépendant du GC. Aucun polling n’est effectué.

Pour le gameplay : une simulation neuve effectue exactement 50 000 tentatives, sans chauffe de son état. Les parts de territoire et la proportion de voisins occupés de même couleur sont relevées tous les 10 000 essais. Ces relevés sont hors du benchmark de performance. Cette proportion est un indicateur de cohésion locale, pas une mesure de composantes connexes.

Tous les scénarios tournent dans une seule JVM. L’ordre des stratégies alterne pour la graine 22. La chauffe courte et l’absence de répétitions indépendantes limitent l’interprétation quantitative ; ce protocole n’est pas un microbenchmark JMH.

Commande :

```powershell
.\scripts\benchmark-v3.ps1 -Java 'C:/Users/gui35/.jdks/openjdk-27/bin/java.exe' -BoardSize 100
```

## Débits observés

Moyennes arithmétiques des trois graines, arrondies :

| Départ | Stratégie | Actions/s | Tentatives/s | Modifications/s | Conversions/s | Changements/s | Action moyenne | Maintenance par changement |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Vide | random | 398 020 | 3 980 200 | 2 985 182 | 167 751 | 3 152 933 | 2,48 µs | 0,18 µs |
| Vide | frontier | 175 095 | 1 750 953 | 1 681 009 | 133 873 | 1 814 882 | 5,68 µs | 0,10 µs |
| Rempli | random | 395 495 | 3 954 945 | 2 965 484 | 166 879 | 3 132 363 | 2,50 µs | 0,18 µs |
| Rempli | frontier | 178 109 | 1 781 089 | 1 736 526 | 138 111 | 1 874 637 | 5,59 µs | 0,10 µs |

Le thread de mesure utilise environ 97 % à 98 % d’un cœur. La heap finale moyenne par groupe varie de 7,9 à 12,4 Mio, avec un GC non contrôlé. Les frontières finales totalisent environ 19 700 candidats dans le mode random, contre 410 à 416 dans le mode frontier. Le stockage final estimé varie de 199 à 284 Kio : une petite frontière peut conserver des blocs et une capacité précédemment alloués.

Sur cet essai, la sélection avec dix candidats et scoring réduit le débit. La maintenance moyenne plus faible de frontier est compatible avec une meilleure localité et des frontières plus petites, mais ce protocole ne permet pas d’isoler ces causes.

## Résultat spatial à 50 000 tentatives

| Départ | random : voisins occupés de même couleur | frontier : voisins occupés de même couleur |
|---|---:|---:|
| Vide, graines 11/22/33 | 28,73 % / 29,49 % / 29,51 % | 82,76 % / 81,75 % / 81,99 % |
| Rempli, graines 11/22/33 | 29,31 % / 28,85 % / 29,12 % | 92,25 % / 93,14 % / 92,35 % |

Après départ vide, random remplit le plateau ; frontier conserve environ 15 % de cellules vides à ce nombre d’essais, car les tentatives adverses ou alliées peuvent être refusées. Les paires contenant une case vide sont exclues de l’indicateur. Les parts frontier ne totalisent donc pas 100 % dans ce scénario.

Après départ rempli, les parts random restent entre 24,53 % et 25,50 %. Les parts frontier vont de 20,85 % à 30,03 %. Aucun vainqueur n’est observé dans ces fenêtres. La stratégie produit des zones plus contiguës, sans garantie de domination durable.

Plateau rempli, graine 11, après 50 000 tentatives :

| random | frontier |
|---|---|
| ![Plateau aléatoire](v3-results/full-random-11.png) | ![Territoires de frontière](v3-results/full-frontier-11.png) |

Les données détaillées de cet essai sont conservées dans [results.csv](v3-results/results.csv) et [evolution.csv](v3-results/evolution.csv). Le script régénère ses résultats et les images dans `target/v3-benchmark/`.

## Test HTTP du plateau par défaut et contention

Le JAR V3 a aussi été lancé isolément avec le plateau 4 000 × 4 000, heap de 1 Gio et enregistrement JFR `settings=profile`. Cinq cycles de lectures HTTP concurrentes état/scores/métriques/aperçu, espacés d’une seconde, ont été exécutés. Start, Pause, Resume, Stop et Reset réussissent ; le reset efface les compteurs et les frontières.

Au relevé : 1 917 400 tentatives, environ 35 468 actions/s cumulées, heap utilisée 494,3 Mio, stockage estimé des frontières 252,3 Mio et maintenance moyenne de 0,58 µs par changement. Ce test court sur un plateau en remplissage, avec polling et profilage, n’est pas comparable aux fenêtres moteur du tableau précédent.

Le profil JFR contient quinze événements `jdk.JavaMonitorEnter` sur `Simulation`, avec une attente maximale enregistrée de 87,3 ms. Le verrou global reste donc une source de latence pour les lectures concurrentes. Les événements dépendent des seuils JFR et ne décrivent pas toutes les attentes. Aucun changement de concurrence n’est introduit dans cette V3.

## Limites

Ces résultats valident le comportement local et illustrent le compromis entre cohésion spatiale, coût de sélection et mémoire. Des fenêtres plus longues, plusieurs JVM indépendantes, une comparaison complète des deux stratégies sur le plateau 4 000 × 4 000 et des mesures avec rendu navigateur restent utiles pour une étude robuste. Les coefficients du score et le taux d’exploration restent des hypothèses configurables ; aucune condition de victoire ni personnalité par joueur n’est ajoutée.
