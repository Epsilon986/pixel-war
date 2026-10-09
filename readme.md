# Pixel War — V2

Simulation Java destinée à l'étude des performances backend. Quatre joueurs automatiques posent des pixels sur un board partagé, utilisent un stock rechargeable et déclenchent une passe de conversion par voisinage après leurs poses.

La détection des conversions est désormais incrémentale : elle vérifie les cellules modifiées et leurs voisins directs, puis conserve les candidats issus des conversions pour la passe suivante. Les décisions restent simultanées, sans cascade dans la même action. Le fonctionnement et la validation sont décrits dans [docs/conversion-incrementale.md](docs/conversion-incrementale.md). Les descriptions de parcours global et les mesures ci-dessous correspondent à la baseline historique V2.

Le périmètre et les règles sont décrits dans [docs/V2.md](docs/V2.md). Le moteur conserve une baseline simple : tableau Java 2D, verrou global, parcours complet pour les scores et pour les conversions, sans cache ni propagation jusqu'à stabilisation.

## Lancement

Prérequis : JDK 21 ou supérieur, `JAVA_HOME` configuré. Le wrapper télécharge Maven et les dépendances au premier lancement. Aucun service externe ni base de données n'est nécessaire.

```powershell
.\mvnw.cmd spring-boot:run
```

Ouvrir http://localhost:8080 et cliquer sur **Start**. L'application commence dans l'état `STOPPED`.

Pour un petit board plus facile à remplir et à observer :

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=small'
```

Tests et génération du JAR :

```powershell
.\mvnw.cmd verify
java -Xmx1g -jar target/pixel-war-0.0.1-SNAPSHOT.jar
```

Sous Linux/macOS, remplacer `.\mvnw.cmd` par `./mvnw`.

## Configuration

Configurer les propriétés dans `src/main/resources/application.properties`, via les variables d'environnement Spring ou avec `--propriete=valeur` au démarrage du JAR. Elles sont validées avant allocation du board et ne sont pas modifiables à chaud.

| Propriété | Défaut | Description |
|---|---:|---|
| `pixelwar.board.width` | 4000 | Largeur du board |
| `pixelwar.board.height` | 4000 | Hauteur du board |
| `pixelwar.preview.width` | 200 | Largeur maximale de l'aperçu |
| `pixelwar.preview.height` | 200 | Hauteur maximale de l'aperçu |
| `pixelwar.players.interval-ns` | 1000 | Intervalle entre actions de chaque joueur, en ns |
| `pixelwar.players.max-pixels-per-action` | 10 | Limite des tentatives par action |
| `pixelwar.stock.initial` | 100 | Pixels initiaux par joueur |
| `pixelwar.stock.capacity` | 1000 | Capacité du stock |
| `pixelwar.stock.refill-amount` | 100 | Pixels crédités par période |
| `pixelwar.stock.refill-interval-ns` | 1000000 | Période de recharge en ns, soit 1 ms |
| `pixelwar.conversion.enabled` | true | Activer la passe globale de voisinage |

Toutes les valeurs numériques doivent être positives, sauf le stock initial qui peut valoir zéro. Le stock initial et la limite de poses ne doivent pas dépasser la capacité. Les dimensions et les stocks initial/capacité utilisent des entiers Java `int` ; intervalles et montant de recharge utilisent `long`. Le booléen de conversion accepte `true` ou `false`. Une configuration invalide empêche le démarrage avec une erreur explicite.

La propriété `pixelwar.players.interval-ms` est remplacée par `interval-ns` : 1 ms = 1 000 000 ns. Le scheduler vise la cadence demandée sans garantir une précision nanoseconde. La contention, la recharge et le coût du parcours de conversion limitent le débit réel.

| Profil Spring | Board | Cellules |
|---|---|---:|
| défaut, sans argument | 4000 × 4000 | 16 000 000 |
| `small` | 500 × 500 | 250 000 |
| `stress` | 8000 × 8000 | 64 000 000 |
| `extreme` | 12000 × 12000 | 144 000 000 |

Adapter la heap avec `-Xmx` selon le profil. Les grands profils ont volontairement une consommation mémoire et un coût de parcours élevés.

```powershell
java -Xmx1g -jar target/pixel-war-0.0.1-SNAPSHOT.jar --spring.profiles.active=small --pixelwar.players.interval-ns=100 --pixelwar.stock.refill-amount=1000
```

Pour isoler le stock et les poses multiples des conversions : ajouter `--pixelwar.conversion.enabled=false`.

## Règles

Chaque joueur possède une couleur stable : RED, BLUE, GREEN ou YELLOW. Une cellule non occupée vaut EMPTY. À chaque action, le joueur recharge son stock selon le temps actif puis tente `min(stock, max-pixels-per-action)` poses à des coordonnées indépendantes pseudo-aléatoires. Plusieurs poses peuvent viser la même cellule.

Chaque tentative réussie consomme un pixel, même si la cellule a déjà la couleur du joueur. Une pose écrase une couleur adverse. Une action sans stock ne tire aucune coordonnée et ne lance pas de passe de conversion.

La recharge est calculée au début de l'action. Les périodes complètes écoulées sont traitées ensemble ; la fraction restante est conservée. Les crédits dépassant la capacité sont perdus, sans réserve pour plus tard. Les compteurs de crédits utilisent `BigInteger` pour éviter un dépassement numérique après un très grand montant ou une longue durée. Une lecture HTTP n'effectue aucune recharge.

Une fois toutes les poses terminées, la conversion parcourt les cellules intérieures du board. Une cellule vide ou adverse prend la couleur commune de ses quatre voisins directs non vides. Les diagonales, les bords et les coins sont exclus. Les conversions sont détectées avant toute application : une seule passe s'effectue, sans cascade pendant la même action. Une autre passe pourra convertir à nouveau des cellules lors d'une action ultérieure.

Une conversion ne consomme ni ne crédite de stock. Elle est attribuée au joueur de la couleur obtenue, même si un autre joueur a déclenché l'action. Une action avec des poses sans changement direct déclenche tout de même la conversion.

Le verrou global protège l'action entière et les lectures. Pause, Stop et Reset attendent sa fin, conversions comprises. Leurs réponses garantissent qu'aucune ancienne tâche ne modifie ensuite le board avant une reprise ou un nouveau démarrage autorisé. Une erreur inattendue arrête la simulation et conserve les poses déjà réalisées ; aucun rollback n'est introduit.

## Cycle de vie

- **Start** depuis STOPPED conserve le board, le stock, les échéances et les compteurs.
- **Pause** conserve l'état et annule les tâches ; le temps de recharge est suspendu.
- **Resume** reprend le temps actif et crée de nouvelles tâches sans rattraper le temps de pause.
- **Stop** annule les tâches et conserve toutes les données pour consultation.
- **Reset** vide le board, rétablit les stocks initiaux et remet les échéances, durées et compteurs à zéro.

Start en RUNNING, Pause en PAUSED, Resume en RUNNING et Stop répété sont sans effet supplémentaire. Pause ou Resume depuis STOPPED et Start depuis PAUSED renvoient HTTP 409. Les données restent uniquement en mémoire ; il n'y a pas de victoire automatique.

## API et métriques

| Méthode | Route | Résultat |
|---|---|---|
| POST | `/api/simulation/start` | Démarrage |
| POST | `/api/simulation/pause` | Pause |
| POST | `/api/simulation/resume` | Reprise |
| POST | `/api/simulation/stop` | Arrêt |
| POST | `/api/simulation/reset` | Réinitialisation |
| GET | `/api/simulation` | État, configuration, temps actif, compteurs et stocks |
| GET | `/api/simulation/scores` | Cellules possédées et pourcentage par joueur |
| GET | `/api/metrics` | Métriques applicatives, JVM et board |
| GET | `/api/board/preview` | Dimensions et couleurs `cells[y][x]` |
| GET | `/health` | `OK` |

Les commandes n'ont pas de corps et renvoient l'état courant. Les erreurs de transition renvoient `{"error":"..."}`. La configuration expose `intervalNs`, `stock`, `maxPixelsPerAction` et `conversionEnabled`. `players` dans l'état expose `{player, stock, stockCapacity}`.

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/simulation/start
Invoke-RestMethod http://localhost:8080/api/metrics
```

Les compteurs conservent une distinction claire :

- `attempts` : tentatives de pose ; `modifications` : changements directs dus aux poses ;
- `actions`, `actionsWithoutStock`, `averagePixelsPerAction` : cycles, cycles vides et nombre moyen de tentatives ;
- `pixelsConsumed`, `pixelsRefilled`, `pixelsDiscardedAtCapacity` : stock dépensé, réellement crédité et crédits perdus ;
- `conversionPasses`, `conversions`, `conversionsReceived` par joueur : passes, changements par voisinage et attribution ;
- `boardChanges` : modifications directes + conversions, y compris deux changements de la même cellule pendant une action.

Les débits sont des moyennes sur le temps actif depuis le reset, hors pause et arrêt. La latence mesure désormais **l'action entière**, attente du verrou, recharge et conversions comprises : elle n'est plus une durée par pixel comme dans la V1. `conversionDurationNanos` expose total, moyenne, minimum et maximum des passes, hors attente initiale du verrou. Toutes ces durées sont en ns, sauf `elapsedMs`. La heap est en octets. La charge CPU est un ratio entre 0 et 1, ou `null` si indisponible.

Le front effectue un cycle de polling une seconde après le précédent, sans accumulation des lectures. Les différents endpoints peuvent représenter des instants légèrement différents. L'aperçu est un sous-échantillonnage, pas une agrégation ; il peut manquer des pixels. Les très grands compteurs JSON peuvent perdre de la précision d'affichage dans JavaScript au-delà de `Number.MAX_SAFE_INTEGER` ; les compteurs de crédit côté moteur restent exacts.

## Architecture et validation

- `domain/Board` : tableau 2D, poses, scores et aperçu.
- `domain/Stock` : recharge, capacité et consommation selon le temps actif.
- `domain/NeighborConversion` : détection puis application d'une passe globale.
- `domain/PositionStrategy` : sélection aléatoire, injectable pour les tests.
- `domain/Simulation` : scheduler, cycle de vie, verrou et métriques ; horloge injectable.
- `PixelWarConfiguration` et `SimulationController` : configuration Spring et API.
- `static/` : HTML, CSS et JavaScript sans framework.

Les tests utilisent des petits boards. Ils couvrent les limites, les écrasements, l'aperçu, les échéances de recharge, les grandes valeurs, les invariants de stock, les poses multiples, l'absence de cascade, l'attribution des conversions, les transitions et le rejet des anciennes tâches. Les tests HTTP vérifient aussi les stocks, un score et un aperçu après conversion déterministe.

## Mesurer la V2

Le script Windows [scripts/benchmark-v2.ps1](scripts/benchmark-v2.ps1) lance une JVM locale par scénario, chauffe le moteur et mesure les deltas de compteurs sur un temps actif documenté. Il compare actions simples/multiples, recharge limitante et conversions, avec ou sans requêtes de polling. Le polling reproduit les quatre lectures du front en séquence, sans rendu navigateur ; il ne constitue pas une mesure de l'interface complète.

```powershell
.\mvnw.cmd package
.\scripts\benchmark-v2.ps1 -Java 'java' -BoardSize 500 -WarmupSeconds 3 -MeasureSeconds 5
```

Résultats JSON et logs dans `target/`. Le script lance des processus cachés et arrête uniquement les JVM qu'il a lui-même créées. Un serveur existant ne doit pas utiliser le port choisi (18082 par défaut).

Pour un profil JFR :

```powershell
java -Xmx1g -XX:StartFlightRecording=filename=target/pixelwar-v2.jfr,duration=60s,settings=profile -jar target/pixel-war-0.0.1-SNAPSHOT.jar --spring.profiles.active=small
```

Démarrer la simulation pendant l'enregistrement. Ouvrir le fichier dans IntelliJ ou JDK Mission Control ; examiner le parcours de conversion, les allocations des statistiques et les attentes du moniteur. Les passes globales peuvent fortement ralentir le board DEFAULT même avec une cadence en nanosecondes. Pour observer une partie plus rapidement, utiliser SMALL ou désactiver la conversion lors d'un essai de charge des poses.

Les mesures et leurs limites sont consignées dans [docs/V2-baseline.md](docs/V2-baseline.md). Aucun gain de performance n'est revendiqué et aucun mécanisme de conversion incrémentale, de pattern ou de propagation jusqu'à stabilisation n'est ajouté.

Le programme `java -Xmx1g --class-path target/classes scripts/ConversionProbe.java` mesure une passe sur un board préparé avec des motifs convertibles. Ce relevé exploratoire complète la simulation aléatoire ; ce n'est pas un microbenchmark JMH.

Le script optionnel `node scripts/browser-smoke.mjs http://localhost:8080` vérifie les commandes et le rendu desktop/mobile si un Chrome headless avec un profil isolé expose son endpoint DevTools sur le port 19222. Il nécessite Node.js récent, sans dépendance npm. Les captures sont écrites dans `target/`.
