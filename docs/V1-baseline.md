# Première observation de la baseline V1

Mesure exploratoire du 8 octobre 2026, sous Windows, Oracle JDK 25.0.1, 12 processeurs logiques disponibles, heap limitée à 1 Gio. Le code est compilé avec `--release 21`. L'exécution sur une JVM 21 reste à confirmer : seuls les JDK 15 et 25 sont installés sur la machine utilisée.

## Scénario exécuté

JAR Spring Boot lancé sur le port 18080 avec la configuration par défaut : board 4000 × 4000, quatre joueurs, intervalle 10 ms, aperçu 200 × 200. Un enregistrement JFR de 30 secondes avec `settings=profile` a couvert le démarrage et une courte phase d'activité. Le fichier local est `target/pixelwar-v1.jfr`, ignoré par Git.

Après démarrage manuel de la simulation, dix requêtes successives sur `/api/simulation/scores` ont été chronométrées par PowerShell `Measure-Command`, puis les métriques ont été consultées et la simulation mise en pause. La page statique a répondu HTTP 200. Les tests MockMvc vérifient séparément toutes les routes de contrôle, les métriques, les scores et l'aperçu.

| Mesure | Observation |
|---|---:|
| Durée HTTP scores moyenne, 10 lectures | 150,03 ms |
| Minimum | 129,53 ms |
| Maximum | 272,40 ms |
| Débit moyen de tentatives au relevé | environ 397 / s |
| Latence moyenne des actions au relevé | 8,18 ms |
| Latence maximale des actions au relevé | 246,25 ms |
| Heap utilisée au relevé, avant le parcours des statistiques | environ 102 Mio |

Ces durées incluent HTTP, sérialisation, contention et effets de chauffe. Il n'y avait pas de navigateur effectuant du polling pendant cette mesure. L'activité n'a duré qu'environ deux secondes : ce relevé valide le fonctionnement et identifie des pistes, sans constituer un benchmark stabilisé ou une mesure de saturation.

## Profil observé et suites

Les échantillons JFR montrent `Long.valueOf` appelé depuis `Board.counts` lors des parcours des métriques. Le comptage naïf met à jour une `EnumMap<CellState, Long>` pour chaque cellule : les allocations de compteurs constituent une piste à mesurer.

Des événements `jdk.JavaMonitorEnter` montrent les threads des joueurs attendant le moniteur de `Simulation`, avec notamment des attentes autour de 126–128 ms pendant les consultations. Les lectures globales sous verrou affectent donc la latence des poses, même si le scheduler rattrape ensuite une partie du retard.

Aucune optimisation de ces opérations n'a été introduite. La prochaine analyse pourra comparer, après chauffe et sur une durée plus longue, le moteur sans lecture, avec scores périodiques, puis avec le polling complet du front. Un microbenchmark JMH de `Board.counts` permettra ensuite de distinguer le coût du parcours et des allocations du coût HTTP et de la contention.

La vérification finale comprend 10 tests automatisés, le packaging du JAR et un contrôle syntaxique du JavaScript. Le rendu et les interactions dans un navigateur n'ont pas été vérifiés automatiquement.
