# Diagnostic préalable — coût des compteurs

État de départ : `180012fc1c1cf883a0dca233806093440baa113c`, après fusion de l'étape 01 dans `refacto_metriques`. Branche d'étude : `opti/02-compteurs`. Le stockage compact est conservé. Aucun fichier de production n'est modifié par cette étude.

L'inspection de `src/main` ne trouve ni `AtomicLong`, ni `LongAdder`, ni autre compteur atomique. Les compteurs techniques de Simulation sont déjà des `long` et `long[]`, protégés par le moniteur Simulation pour les actions, commandes et copies HTTP. Les statistiques de PerformanceTracker et Stock sont protégées par ce même moniteur dans leur utilisation par Simulation. Ces deux classes ne promettent pas une synchronisation autonome à leurs appelants externes.

Les scores Board sont un EnumMap de Long (boxing, pas atomiques), et ses statistiques de frontières sont des long. Leur protection est le moniteur Board, distinct du moniteur Simulation. FrontierIndex est une structure interne dont les méthodes sont appelées sous Board. La synchronisation de la carte des cibles FrontierPlacementStrategy n'est pas un compteur atomique et reste hors périmètre.

Le filtre HTTP emploie un troisième moniteur, le sien. Il protège ses fenêtres, génération et total des attentes. Le marqueur volatile de fin d'essai est lu hors du moniteur Simulation par le filtre : sa publication doit être conservée. Les BigInteger de stock évitent un dépassement de capacité documenté ; les remplacer par des long changerait leur sémantique.

Décision provisoire : piste « supprimer des atomiques redondants » non applicable. Aucun faux avant/après ne sera construit. Une baseline actuelle sera mesurée en JVM neuves : quatre scénarios moteur (cinq répétitions), deux tailles applicatives avec et sans polling (trois répétitions par condition, ordre alterné), rejeux 11/22/33 répétés, profils JFR séparés sur DEFAULT. Les exigences d'appariement avant/après n'ont pas de candidat auquel s'appliquer ; les tableaux l'indiqueront comme non applicable. Les résultats de l'étape 01 ne seront pas réutilisés comme une baseline actuelle ni additionnés à un gain hypothétique.

Tests initiaux : Maven verify sous JDK 27, 70 tests, zéro échec et erreur. Journal conservé dans `target/opti-02/tests-initial.log`, à joindre à la remise. Le lancement initial sous sandbox n'a pas démarré Maven ; l'exécution autorisée hors sandbox a réussi. Aucun échec métier observé.

La seule adaptation Java du banc ajoute au relevé applicatif le temps actif exact déjà fourni par PerformanceTracker, le marqueur d'erreur et les attentes moteur. Le pilote distingue polling/absence de polling et exporte P50 en plus de P95/P99. Les commandes de contrôle et snapshots de début/fin sont hors de la fenêtre de requêtes périodiques ; « sans polling » ne veut pas dire sans HTTP de pilotage.
