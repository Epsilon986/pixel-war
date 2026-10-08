# Pixel War

Simulation Java conçue pour étudier les performances backend. La V1 fournit une baseline volontairement simple, conforme à [la définition du projet](docs/Pixel%20War.md) et à [la spécification V1](docs/V1.md).

## Lancer le projet

Prérequis : JDK 21 ou supérieur, `JAVA_HOME` configuré et accès Internet pour le premier téléchargement Maven. Aucun service externe ni base de données n'est nécessaire.

Sous PowerShell :

```powershell
.\mvnw.cmd spring-boot:run
```

Sous Linux/macOS : `./mvnw spring-boot:run`.

Ouvrir http://localhost:8080 puis cliquer sur **Start**. La simulation démarre dans l'état `STOPPED`. La page affiche l'aperçu, les scores, les compteurs, les latences et les métriques JVM. Elle interroge l'API toutes les secondes, après la fin du cycle précédent pour éviter l'accumulation des requêtes.

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target/pixel-war-0.0.1-SNAPSHOT.jar
```

## Configuration

Les propriétés peuvent être modifiées dans `src/main/resources/application.properties`, par variables d'environnement Spring ou par arguments au démarrage, sans recompilation.

| Propriété | Défaut | Description |
|---|---:|---|
| `pixelwar.board.width` | 4000 | Largeur du board |
| `pixelwar.board.height` | 4000 | Hauteur du board |
| `pixelwar.players.interval-ms` | 10 | Intervalle de chaque joueur en ms |
| `pixelwar.preview.width` | 200 | Largeur maximale de l'aperçu |
| `pixelwar.preview.height` | 200 | Hauteur maximale de l'aperçu |

Toutes ces valeurs doivent être strictement positives. Une valeur invalide fait échouer le démarrage avec un message indiquant les propriétés concernées.

```powershell
java -jar target/pixel-war-0.0.1-SNAPSHOT.jar --pixelwar.board.width=1000 --pixelwar.board.height=1000 --pixelwar.players.interval-ms=2
java -jar target/pixel-war-0.0.1-SNAPSHOT.jar --spring.profiles.active=small
```

| Profil Spring | Board | Cellules |
|---|---|---:|
| défaut, sans argument | 4000 × 4000 | 16 000 000 |
| `small` | 500 × 500 | 250 000 |
| `stress` | 8000 × 8000 | 64 000 000 |
| `extreme` | 12000 × 12000 | 144 000 000 |

Les profils utilisent des noms minuscules. Adapter la heap avec `java -Xmx2g -jar ...` selon la machine et le profil. Le tableau contient une référence enum par cellule ; les parcours de statistiques génèrent aussi des allocations temporaires. Les grands profils peuvent épuiser la mémoire.

## Règles et cycle de vie

Quatre joueurs fixes, rouge, bleu, vert et jaune, choisissent une cellule pseudo-aléatoire sur le board complet à chaque action. Une pose écrase la couleur précédente. Reposer la même couleur compte comme une tentative sans modification. Une tâche périodique par joueur s'exécute sur un pool de quatre threads ; les retards peuvent être rattrapés par le scheduler.

Choix de la V1 pour les comportements non précisés :

- Start depuis STOPPED conserve le board et les compteurs ; utiliser Reset pour une partie vide.
- Pause conserve le board et gèle le temps actif ; Resume poursuit la simulation.
- Stop conserve le board et les métriques, et annule les tâches des joueurs.
- Reset arrête les joueurs, vide le board et remet les compteurs et latences à zéro.
- Répéter Start en RUNNING, Pause en PAUSED, Resume en RUNNING ou Stop est sans effet supplémentaire. Pause depuis STOPPED, Resume depuis STOPPED et Start depuis PAUSED renvoient HTTP 409.
- Le temps écoulé est le temps actif cumulé, hors pause et arrêt. Les débits sont des moyennes depuis le dernier reset sur ce temps actif.
- La latence utilise `System.nanoTime()` et inclut l'attente du verrou ainsi que le choix de la position et la pose ; elle exclut l'attente entre deux actions.
- L'aperçu sous-échantillonne les cellules à intervalles réguliers, sans agrégation. Il peut manquer des pixels, particulièrement au début sur un grand board. Ses dimensions sont limitées à celles du board.

Aucune victoire automatique, stock, conversion ou détection de patterns en V1. Les données restent en mémoire et sont perdues au redémarrage.

## API

| Méthode | Route | Résultat |
|---|---|---|
| POST | `/api/simulation/start` | Démarrage |
| POST | `/api/simulation/pause` | Pause |
| POST | `/api/simulation/resume` | Reprise |
| POST | `/api/simulation/stop` | Arrêt |
| POST | `/api/simulation/reset` | Réinitialisation |
| GET | `/api/simulation` | État, temps actif en ms, configuration, tentatives, modifications |
| GET | `/api/simulation/scores` | Joueurs, cellules possédées, pourcentage |
| GET | `/api/metrics` | Débits, latences en ns, compteurs par joueur, heap en octets, threads, CPU, cellules par couleur |
| GET | `/api/board/preview` | Dimensions et tableau de couleurs `cells[y][x]` |
| GET | `/health` | `OK` |

Les commandes ne nécessitent pas de corps et renvoient l'état courant. La charge CPU est un ratio entre 0 et 1, ou `null` si indisponible ; ce n'est pas un pourcentage. Les états métier sont `EMPTY`, `RED`, `BLUE`, `GREEN`, `YELLOW`.

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/simulation/start
Invoke-RestMethod http://localhost:8080/api/metrics
Invoke-RestMethod -Method Post http://localhost:8080/api/simulation/pause
```

Chaque réponse est cohérente au moment de sa lecture. Les différents endpoints interrogés par le navigateur peuvent représenter des instants légèrement différents.

## Architecture et tests

- `domain/Board` : tableau `CellState[][]`, lecture, écriture, parcours complet pour les scores et sous-échantillonnage.
- `domain/Simulation` : joueurs, scheduler, cycle de vie et métriques, indépendant de Spring et HTTP.
- `PixelWarConfiguration` : configuration externe et validation avant allocation.
- `SimulationController` : API REST et erreurs de transition.
- `static/` : page HTML, CSS et JavaScript sans framework.

Le moteur protège toutes ses opérations par un verrou global ; le board protège également ses propres accès. Une pause ou un reset attend la fin de la pose en cours avant de retourner. Les tâches d'un ancien démarrage sont identifiées pour empêcher une pose tardive après un redémarrage. Les scores et statistiques parcourent le board sous verrou, ce qui provoque volontairement de la contention. Aucun cache ni compteur incrémental des scores n'est introduit.

Les tests utilisent de petits boards. Ils couvrent les limites, l'écrasement, les poses sans changement, le reset, les scores, l'aperçu, les transitions, les compteurs, le gel de l'activité, la validation de configuration et les endpoints REST.

## Mesurer la baseline

Conserver la même JVM, heap, configuration, durée et fréquence de polling pour comparer deux versions. Faire une phase de chauffe avant les mesures. Relever les débits, latences et heap avec et sans navigateur : les parcours globaux de scores et métriques participent à la charge.

Pour mesurer le coût HTTP d'un parcours complet (application lancée, board DEFAULT) :

```powershell
1..10 | ForEach-Object { (Measure-Command { Invoke-RestMethod http://localhost:8080/api/simulation/scores | Out-Null }).TotalMilliseconds }
```

Cette mesure inclut HTTP et sérialisation, et ne constitue pas un microbenchmark de la méthode Java.

Pour enregistrer un profil JFR avec les outils du JDK :

```powershell
java -XX:StartFlightRecording=filename=target/pixelwar-v1.jfr,duration=60s,settings=profile -jar target/pixel-war-0.0.1-SNAPSHOT.jar
```

Pendant l'enregistrement, démarrer la simulation et consulter le front. Ouvrir le fichier dans IntelliJ (selon édition) ou JDK Mission Control. Examiner les allocations de `Board.counts`, les parcours complets et l'attente des moniteurs. Après identification d'un point chaud, un microbenchmark JMH pourra isoler son coût dans une itération ultérieure.

Les débits ne garantissent pas les 400 tentatives/s théoriques : la contention, le scheduler, le GC et le polling interviennent. Aucun gain de performance n'est revendiqué pour cette baseline.

Une [première observation de la V1](docs/V1-baseline.md) consigne les mesures HTTP et les constats JFR effectués sur la machine de développement, ainsi que leurs limites.
