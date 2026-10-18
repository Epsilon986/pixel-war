# Mesures de performance

Le tableau de bord suit les sept mesures de [list_metriques.md](list_metriques.md). Il utilise `GET /api/performance` et remplace l’ancien tableau détaillé par sept cartes. L’endpoint historique `/api/metrics` reste disponible pour les tests et les scripts existants ; l’interface ne le sollicite plus.

## Essai à charge fixe

Le formulaire permet de choisir un nombre N d’actions et une graine aléatoire. Son lancement réinitialise le plateau, les stocks, les compteurs, la stratégie et les mesures, puis crée la stratégie avec cette graine. Le moteur se met en pause après exactement N actions, même au milieu d’un tour de quatre joueurs. Les callbacks d’une ancienne génération ne peuvent pas poursuivre un essai précédent.

Une action est une action effectivement exécutée par un joueur non éliminé. Elle comprend les tentatives de pose, le choix de stratégie, les mises à jour du plateau et la passe de conversion configurée. Les poses sans changement sont des tentatives ; les joueurs éliminés sont ignorés. N n’est donc pas un nombre de pixels modifiés.

L’essai reproductible exige le stock désactivé : avec la recharge liée au temps réel, une même graine ne garantit pas la même charge sur deux machines ou deux versions. L’interface explique cette condition et le serveur refuse le lancement si le stock est activé. La simulation libre avec stock reste utilisable et mesurée.

Pour comparer deux versions, conserver N, la graine, les dimensions, la stratégie et ses coefficients, le nombre de poses par action, les conversions, la JVM, la heap et les conditions de polling. Répéter les essais après chauffe de la JVM. La préparation et le reset ne font pas partie du temps d’exécution des actions ; ils apparaissent toutefois dans la latence de la commande HTTP de lancement.

## Définition des sept mesures

| Mesure | Calcul et portée |
|---|---|
| Temps pour N actions | Temps actif entre le début de la première action et la mise en pause finale. Les pauses et arrêts intermédiaires sont exclus. Avant achèvement, la valeur est signalée comme partielle. En simulation libre, c’est le temps actif mesuré. |
| Tentatives/s | Nombre de tentatives depuis le reset, divisé par cette durée active en secondes. La valeur est zéro avant toute durée mesurable. |
| Octets alloués/action | Delta des octets alloués par le thread moteur, obtenu via `com.sun.management.ThreadMXBean`, divisé par le nombre d’actions. C’est une allocation cumulée, pas une variation de heap après GC. Les allocations des threads HTTP sont exclues. |
| Temps CPU pour la charge | Somme des deltas de temps CPU du thread moteur, via `ThreadMXBean`. Les temps passés en pause, en arrêt ou avant la première action sont exclus, tout comme le travail des threads HTTP. Le temps CPU inclut l’ordonnancement et l’instrumentation du thread moteur. |
| Heap maximale observée | Maximum de heap utilisée de toute la JVM, obtenu via `MemoryMXBean`. Échantillonnage pendant l’activité toutes les 100 ms lorsque le moteur peut observer cette échéance, aux lectures et à la fin des segments. Les allocations du serveur et du polling sont incluses. Un pic très bref entre deux observations peut être manqué. |
| Attente des verrous | Deltas de `ThreadInfo.getBlockedTime` avec profil de contention JVM activé. Deux cumuls distincts : thread moteur et requêtes HTTP achevées. La résolution est d’une milliseconde. Il s’agit des attentes d’acquisition de moniteurs `synchronized`, pas du sommeil, de `wait()` ou d’une estimation à partir du temps total. |
| Latence HTTP P95/P99 | Durée serveur mesurée par filtre autour de chaque requête `/api/`, attente du contrôleur et sérialisation comprises. Lectures GET et commandes sont séparées. Chaque fenêtre conserve les 512 dernières requêtes admises de son type. Percentile par rang supérieur : valeur triée à `ceil(p × nombre de requêtes)`. |

CPU et allocations sont échantillonnés aux bornes et lors des points d’observation, sans appel JVM par tentative de pixel. Les valeurs absentes avant la première action ou non prises en charge par la JVM sont exposées à `null` et affichées « Indisponible ». Les percentiles sans échantillon ne sont pas présentés comme zéro. Chaque groupe HTTP affiche son nombre de requêtes : avec peu d’échantillons, P95 et P99 peuvent coïncider.

Le profil de contention utilise les compteurs JVM des threads. Il ne permet pas d’attribuer chaque attente à un objet précis. Un enregistrement JFR reste nécessaire pour identifier les moniteurs et les piles impliquées dans une contention observée.

Les latences HTTP excluent le réseau, le transfert restant après retour du filtre et le rendu navigateur. Elles mesurent l’impact du moteur sur le traitement côté serveur, sans prétendre mesurer le temps de bout en bout de l’utilisateur.

## Cycle de vie des résultats

- Pause et Stop figent les ressources du segment moteur ; reprendre ouvre un nouveau segment sans compter les ressources dépensées pendant l’interruption.
- À la fin d’un essai, les ressources moteur sont figées. Les requêtes démarrées après cette fin sont exclues des statistiques HTTP : le polling au repos ne dilue pas les résultats. Les requêtes déjà en cours peuvent encore être enregistrées à leur achèvement.
- En simulation libre, les fenêtres HTTP restent glissantes et les cumuls continuent sur le temps actif.
- Reset ou le lancement d’un nouvel essai efface les fenêtres et les cumuls. Les requêtes anciennes encore en cours sont écartées par génération ; la commande qui lance le nouvel essai reste incluse dans sa fenêtre de commandes.
- Un essai achevé ne peut pas être repris au-delà de N : il faut lancer un nouvel essai ou effectuer Reset. Un essai interrompu avant N peut être repris.

L’instrumentation a elle-même un coût. Les comparaisons doivent utiliser les mêmes mesures activées et le même polling. Le maximum de heap ne constitue pas une mesure de mémoire native ou de mémoire totale du processus.

## API

```http
POST /api/simulation/trial
Content-Type: application/json

{"actions":100000,"seed":42}
```

N doit être compris entre 1 et 1 000 000 000. Une valeur invalide est rejetée avant toute réinitialisation. L’interface accepte des graines entières sur 32 bits pour les transmettre sans perte de précision JavaScript ; l’API accepte un entier Java `long`.

`GET /api/performance` retourne :

```json
{
  "engine": {
    "targetActions": 100000,
    "seed": 42,
    "completed": false,
    "failure": null,
    "measurements": {
      "actions": 0,
      "attempts": 0,
      "elapsedNanos": 0,
      "attemptsPerSecond": 0,
      "cpuNanos": null,
      "allocatedBytesPerAction": null,
      "peakObservedHeapBytes": 0,
      "engineBlockedNanos": null
    }
  },
  "http": {
    "reads": {"samples": 0, "p95Nanos": null, "p99Nanos": null},
    "commands": {"samples": 0, "p95Nanos": null, "p99Nanos": null},
    "completedRequestsBlockedNanos": null,
    "windowCapacity": 512
  }
}
```

Cet exemple illustre le schéma ; la heap observée en fonctionnement est celle de la JVM. Les durées JSON sont en nanosecondes, la mémoire en octets. `targetActions` et `seed` sont `null` en simulation libre. Une erreur d’action est exposée dans `failure` et n’est pas présentée comme un essai réussi.

## Validation du 18 octobre 2026

- `mvnw.cmd -B -ntp verify` : **68 tests passent**, zéro échec, zéro erreur ; compilation Java 21 sous OpenJDK 27 et génération du JAR.
- Tests des deltas CPU et allocations, des pauses, du maximum observé et des capacités JVM indisponibles.
- Arrêt à N = 17 au milieu d’un tour, reproductibilité du plateau et des scores, invalidation des tâches anciennes, validation des paramètres et refus du stock pour les essais contrôlés.
- Calcul indépendant des percentiles, fenêtre bornée, effacement par génération, gel après essai et test avec une vraie attente sur un moniteur.
- Parcours réel dans Edge headless, profil SMALL : cinq commandes, sept cartes, essai N = 10 003 et graine 42, arrêt exact, aucune erreur JavaScript, pas de débordement horizontal à 390 px. Captures locales dans `target/metrics-desktop.png` et `target/metrics-mobile.png`.

La vérification navigateur teste les flux et les valeurs affichées ; elle ne constitue pas un benchmark de performance. Le script `scripts/browser-smoke.mjs` attend un serveur avec stock désactivé et un endpoint DevTools d’un navigateur de test.
