# Pixel War — Baseline V2

## Environnement et validation

Mesures exploratoires du 8 octobre 2026, sous Windows, Oracle GraalVM JDK 21.0.7, 12 processeurs logiques disponibles, heap limitée à 1 Gio. Le code cible Java 21.

Validation réalisée :

- `mvnw verify` sous Java 21 : 24 tests passent, JAR exécutable généré ;
- contrôle syntaxique du JavaScript et du script PowerShell ;
- test réel du board DEFAULT, 4000 × 4000 ;
- parcours des cinq commandes dans Chrome headless sur le serveur DEFAULT, sans erreur JavaScript ;
- captures desktop et mobile et absence de débordement horizontal en largeur 390 px ;
- enregistrement JFR et inspection des échantillons d'exécution et des attentes de moniteurs ;
- test HTTP déterministe du score, de l'aperçu, du stock et des compteurs après conversion.

Les artefacts locaux sont conservés dans `target/` et ignorés par Git : `v2-benchmark.json`, `pixelwar-v2.jfr`, `v2-profile.json`, `v2-desktop.png`, `v2-mobile.png`.

Le checkout de départ contenait le squelette Spring Boot et V2.md, mais pas le moteur, le frontend ou un rapport V1 exploitable. Le socle a été reconstruit avec la V2. Aucune comparaison contrôlée à l'ancien binaire V1 n'a pu être réalisée ; les relevés V1 évoqués dans la conversation utilisent d'autres conditions et ne permettent pas d'attribuer un écart au seul coût du stock.

## Scénarios SMALL

Commande exécutée :

```powershell
.\scripts\benchmark-v2.ps1 -Java 'C:/Users/gui35/.jdks/graalvm-jdk-21.0.7/bin/java.exe' -WarmupSeconds 2 -MeasureSeconds 3
```

Une JVM neuve est lancée pour chaque scénario. Board de 500 × 500, intervalle d'action de 1 000 ns, aperçu de 200 × 200. Capacité de stock : 1 000 000. Les scénarios avec recharge suffisante utilisent un stock initial de 1 000 000 et une recharge de 1 000 000 pixels toutes les 1 000 ns ; aucune action sans stock n'y a été observée.

Le scénario limitant utilise un stock initial de zéro et une recharge d'un pixel toutes les 1 000 000 ns, soit théoriquement 4 000 poses/s pour les quatre joueurs.

Après deux secondes de chauffe, une pause permet de relever le début de la fenêtre de mesure, puis la simulation reprend. Une autre pause termine la fenêtre. Les débits ci-dessous utilisent les deltas de compteurs et de temps actif, excluant ces pauses. La latence moyenne de la fenêtre est calculée par différence des totaux dérivés des moyennes cumulées.

Le polling reproduit en HTTP les lectures état, scores, métriques et aperçu, en séquence puis avec une seconde d'attente. Il inclut HTTP et sérialisation, mais pas le rendu navigateur ni les requêtes concurrentes du vrai frontend.

| Scénario | Polling | Temps actif mesuré | Actions/s | Tentatives/s | Modifications directes/s | Passe moyenne |
|---|---|---:|---:|---:|---:|---:|
| Une pose, sans conversion | non | 3,035 s | 2 001 434 | 2 001 434 | 1 501 159 | — |
| Une pose, sans conversion | oui | 3,221 s | 1 800 361 | 1 800 361 | 1 349 699 | — |
| Dix poses, sans conversion | non | 3,018 s | 1 202 862 | 12 028 625 | 8 555 727 | — |
| Dix poses, sans conversion | oui | 3,261 s | 1 055 424 | 10 554 244 | 7 525 125 | — |
| Recharge limitante, sans conversion | non | 3,013 s | 2 882 446 | 4 000 | 3 946 | — |
| Recharge limitante, sans conversion | oui | 3,223 s | 2 781 811 | 4 000 | 3 950 | — |
| Une pose, avec conversion | non | 3,249 s | 2 375 | 2 375 | 2 349 | 0,414 ms |
| Une pose, avec conversion | oui | 4,452 s | 2 327 | 2 327 | 2 295 | 0,415 ms |
| Dix poses, avec conversion | non | 3,198 s | 1 059 | 10 591 | 9 991 | 0,936 ms |
| Dix poses, avec conversion | oui | 3,921 s | 1 032 | 10 321 | 9 763 | 0,946 ms |

Les latences moyennes d'action vont d'environ 0,00008 ms dans le scénario limitant sans polling à 3,67 ms pour dix poses avec conversion sans polling. Ces latences incluent l'attente du verrou ; elles peuvent donc dépasser largement le temps de la passe elle-même.

Les conversions réelles sont rares sur ces boards partiellement remplis : de zéro à environ 11 conversions/s sur ces essais. Une passe parcourt cependant toutes les cellules intérieures même sans conversion.

Les charges CPU relevées en fin de fenêtre sont comprises entre 8,6 % et 17,2 % de la capacité globale du processus telle que rapportée par la JVM ; elles ne représentent pas une moyenne indépendante sur toute la fenêtre. La heap utilisée au relevé varie de 33 à 81 Mio et dépend du cycle GC.

## Board DEFAULT et profil JFR

JAR démarré sous Java 21 avec le board 4000 × 4000, la configuration V2 par défaut, une heap de 1 Gio et `settings=profile` pour JFR.

Un essai court sans navigateur pendant la phase d'activité a produit 141 actions et 1 410 tentatives en 3,448 secondes actives, soit environ **409 tentatives/s**. La passe de conversion moyenne prend **24,34 ms**. Aucune conversion réelle n'a été observée pendant cette phase et la heap utilisée au relevé était d'environ 87,7 Mio.

Le profil comprend aussi les commandes et les lectures du test navigateur effectué ensuite. Sur les 331 échantillons d'exécution exportés, 243 ont `Board.get` en tête de pile, principalement dans le parcours de voisinage. D'autres échantillons montrent `EnumMap.put` et `Board.counts` lors des lectures de statistiques.

Des événements d'attente sur le moniteur de `Simulation` montrent notamment des durées d'environ 359 et 380 ms pour des threads joueurs. La synchronisation globale empêche l'entrelacement des actions mais limite le parallélisme et affecte la latence des commandes et des lectures.

La passe globale est donc un coût important, même avec un board presque vide. Réduire seulement l'intervalle d'action ne peut pas supprimer ce coût. L'implémentation conserve ce comportement volontairement naïf, conformément à V2.md.

## Board préparé avec motifs convertibles

Le programme exploratoire `scripts/ConversionProbe.java` prépare un damier rouge/bleu de 500 × 500 avant chaque passe. Toutes les 248 004 cellules intérieures sont convertibles simultanément. La préparation est exclue du chronométrage.

```powershell
java -Xmx1g --class-path target/classes scripts/ConversionProbe.java
```

Après cinq passes de chauffe, vingt passes mesurées ont produit 4 960 080 conversions au total. Durée moyenne : **9,836 ms**, minimum : 3,395 ms, maximum : 26,729 ms.

Ce résultat inclut détection, création de la liste des changements et application. Le board est recréé pour chaque répétition ; l'état du GC peut donc affecter le temps mesuré. Ce test exploratoire n'est pas un microbenchmark JMH et ne représente pas une partie aléatoire. Il complète les tests déterministes des règles.

## Limites et prochaines mesures

Chaque scénario n'a été exécuté qu'une fois, avec une chauffe et une fenêtre courtes. Les positions sont aléatoires sans seed fixe. Les durées réelles dépassent parfois les trois secondes demandées en raison du polling et de l'attente de la fin d'une action. Ces résultats identifient des coûts ; ils ne garantissent aucun débit sur une autre machine.

La comparaison à un binaire V1 conservé, une durée plus longue, plusieurs répétitions et un microbenchmark JMH restent nécessaires pour une analyse quantitative plus robuste. Les premières pistes sont le parcours de voisinage, la contention du verrou global, les allocations de statistiques et les crédits de stock. Aucune optimisation de ces composants n'a été appliquée pendant cette itération.
