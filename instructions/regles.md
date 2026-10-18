# Pixel War — Règles d’optimisation applicables à Java

Synthèse des huit supports « Optimisations & Performances Backend », adaptée à Pixel War. Les principes proviennent des supports ; les applications au projet et les priorités ci-dessous sont des propositions, pas des gains mesurés.

## 1. Priorités pour le projet

La conversion incrémentale est déjà implémentée. Le prochain goulot doit être identifié sur cette version : les anciens profils décrivent la baseline avec scan complet. La stratégie de frontière V3 est une proposition de gameplay ; son coût doit être évalué séparément.

| Ordre | Action proposée | Pourquoi pour Pixel War |
|---|---|---|
| 1 | Refaire baseline et JFR après conversion incrémentale | Identifier où part désormais le temps |
| 2 | Si les lectures dominent, maintenir les scores par mutation | Éviter un parcours du board pour chaque consultation |
| 3 | Si le polling bloque le moteur, réduire le travail HTTP sous verrou | Scores, aperçu et métriques partagent actuellement la synchronisation du moteur |
| 4 | Si allocations ou accès mémoire dominent, comparer structures primitives et buffers réutilisés | Candidats, conversions et futures frontières peuvent devenir volumineux |
| 5 | Mesurer la stratégie de frontière contre le mode aléatoire | Séparer son intérêt visuel de son surcoût de calcul |

Ces priorités sont conditionnelles : ne pas engager toutes les transformations à la fois.

## 2. Les règles à retenir

### Mesurer avant de modifier, puis mesurer à nouveau

Établir une version de référence, profiler CPU, allocations, mémoire vivante, pauses GC et attentes de verrous. Une optimisation locale très rapide peut n’apporter qu’un faible gain au serveur si une autre étape domine.

Pour Pixel War, conserver Java/JVM, heap, dimensions, cadence, stock, conversions, stratégie et polling identiques entre les essais. Mesurer après chauffe sur plusieurs répétitions, avec fenêtres de temps actif et deltas de compteurs. Rapporter la dispersion des résultats et les erreurs HTTP, pas seulement une moyenne.

Employer JFR pour le moteur complet et JMH pour une opération isolée. En JMH : chauffe, plusieurs forks, résultats consommés ou retournés, état préparé hors mesure et profil d’allocations. Pour un traitement qui vide ses candidats, rétablir une charge représentative avant chaque mesure : sinon le benchmark finit par mesurer une liste vide.

Une graine fixe ne suffit pas à garantir un rejeu identique si l’ordre d’exécution varie. Comparer les optimisations sémantiquement équivalentes avec le même ordre de poses ; comparer les stratégies différentes sur plusieurs graines et à nombre de tentatives égal.

*Sources : J1_AM, p. 3 et 5–8 ; J2_PM, p. 2–5 et 7.*

### Réduire le travail avant de réduire le coût d’une instruction

Préférer une transformation de complexité à une micro-optimisation. La conversion incrémentale applique déjà ce principe : examiner les régions affectées au lieu du plateau entier.

Application suivante possible : maintenir des compteurs de cellules par couleur dans le point central de mutation. Sur un changement effectif, décrémenter l’ancienne couleur et incrémenter la nouvelle, pour les poses comme pour les conversions. Les scores deviennent accessibles sans scan ; reset et préparation des tests utilisent la même discipline. Vérifier ces compteurs contre un parcours complet indépendant.

Pour V3, mettre à jour seulement la cellule modifiée et ses quatre voisines, puis évaluer un nombre borné de candidats. Parcourir toute la frontière à chaque pose recréerait un coût proportionnel à sa taille.

*Sources : J1_AM, p. 5–7 ; J1_PM, p. 8. Application incrémentale aux scores : proposition pour Pixel War.*

### Choisir des représentations compactes et économes en accès mémoire

Des tableaux de primitives évitent les objets dispersés et le boxing. Si le profil justifie une évolution du board, comparer le tableau 2D actuel à un `byte[]` indexé par `y * width + x`, avec un code pour le vide et chaque couleur. Ne pas confondre `Pixel[]`, tableau de références, avec des cellules stockées directement dans un tableau primitif.

Pour 4000 × 4000, hors en-têtes et capacités inutilisées :

| Structure | Charge utile approximative |
|---|---:|
| Board à un octet par cellule | 16 Mo |
| Un bit par cellule | 2 Mo |
| Un identifiant `int` par cellule | 64 Mo |
| Quatre index de positions `int`, un par joueur | 256 Mo |

Ces chiffres illustrent le compromis CPU/RAM, notamment pour les frontières V3. Déduplication par bits et liste dense d’identifiants sont utiles, mais la sélection aléatoire et la suppression nécessitent une représentation adaptée. Budgéter aussi les capacités conservées après un scénario dense.

Tester plateaux clairsemés, fragmentés et denses. Valider la multiplication des dimensions avant une allocation et préserver les règles des bords lors du passage à un index 1D.

*Sources : J1_AM, p. 7 ; J1_PM, p. 6–8 ; J2_AM, p. 3 et 9.*

### Réduire les allocations fréquentes, sans imposer « zéro allocation » partout

Dans les boucles mesurées comme coûteuses, examiner les listes de conversions, objets de coordonnées, collections d’entiers et agrégations de métriques. Préallouer raisonnablement et réutiliser des buffers appartenant au moteur ou au board lorsque leur durée de vie le permet.

Conserver deux phases de conversion : stocker les décisions dans un buffer, puis les appliquer. Réutiliser le buffer ne doit jamais introduire une application pendant la détection.

Mesurer octets alloués par opération, débit d’allocation et pauses GC. Une heap utilisée faible à un instant donné ne signifie pas un faible volume d’allocations. Ne pas recycler un tableau encore visible dans une réponse HTTP ou un snapshot publié. Préférer une réutilisation locale explicite à un pool global d’objets simples.

*Sources : J2_AM, p. 3–5 et 9 ; J2_PM, p. 6 ; J3_PM, p. 8.*

### Réduire les sections critiques en préservant la cohérence

Déplacer formatage et sérialisation hors verrou à partir d’une copie cohérente et indépendante. Si les lectures HTTP dominent, envisager un petit snapshot immutable contenant scores, métriques et aperçu, publié à une cadence distincte des actions. Définir sa fraîcheur, sa version et son comportement après reset.

Une publication par référence `volatile` ou `AtomicReference` peut permettre aux lecteurs de consulter ce snapshot sans verrou métier. Cela ne rend pas sûr un board mutable partagé. Copier les 16 millions de cellules après chaque action serait un mauvais compromis ; cibler les données réellement affichées.

Garder l’action atomique vis-à-vis des autres actions et des commandes de contrôle. Les tests de pause, stop et reset doivent toujours exclure les mutations tardives.

*Sources : J3_AM, p. 4–7 ; J4_AM, p. 7. Snapshot d’aperçu : adaptation proposée.*

### Ajouter du parallélisme seulement à un travail indépendant suffisamment lourd

Quatre threads joueurs attendant le même verrou global n’accélèrent pas le moteur. Une passe incrémentale examinant peu de candidats peut coûter moins cher que la distribution des tâches.

Si un futur traitement lourd se parallélise, borner workers **et file d’attente**, définir la saturation et mesurer le gain avec plusieurs tailles de pool. En Java, un `ThreadPoolExecutor` avec file bornée permet ce contrôle ; un pool fixe avec file illimitée ne borne pas le travail en attente.

Une détection parallèle de conversions exigerait un état de lecture stable et une fusion des décisions avant application. Paralléliser les actions changerait potentiellement leur ordre et le résultat métier. Les threads virtuels ne multiplient pas la capacité CPU disponible.

*Sources : J3_AM, p. 2–4 et 8 ; J3_PM, p. 2–7.*

### Optimiser les échanges selon leur coût réel

Mesurer fréquence de polling, volume d’aperçu, temps de construction, sérialisation et latences P50/P95/P99 sous charge. L’aperçu 500 × 500 cité dans l’analyse contient jusqu’à 250 000 cellules : sa fréquence et sa représentation peuvent compter davantage qu’un changement de protocole.

Comparer d’abord un aperçu plus petit, un regroupement des lectures ou un snapshot partagé. Si le payload domine réellement, évaluer un encodage compact ou des deltas, avec version de board et resynchronisation complète après perte de messages ou reset.

Séparer compression, format de données et transport dans les comparaisons. HTTP/2, WebSocket ou Protobuf ne suppriment pas un calcul coûteux sous verrou. Mesurer aussi débit demandé, débit obtenu et erreurs pour détecter une saturation masquée par les moyennes.

*Source : J4_AM, p. 2–7.*

## 3. Recettes Go à adapter avec discernement

| Sujet des supports | Adaptation Java |
|---|---|
| Pile, tas et analyse d’échappement | La JVM peut éliminer certaines allocations par remplacement scalaire. Un objet local n’est pas automatiquement alloué sur la pile ; mesurer les allocations restantes. |
| Tri des champs d’une structure | L’ordre déclaré ne garantit pas la disposition mémoire des objets Java. Utiliser JOL pour observer la JVM testée ; privilégier les primitives dans les collections volumineuses. |
| `sync.Pool` | Pas de transposition automatique. Réutilisation locale, ownership clair et limite de capacité avant d’envisager un pool. |
| Compteurs atomiques | Sous le verrou existant, des compteurs ordinaires peuvent suffire. `AtomicLong` protège une valeur, pas un ensemble cohérent de métriques ; `LongAdder` convient à une agrégation concurrente dont la lecture peut être approximative. |
| Faux partage et padding | À étudier seulement avec des écritures réellement parallèles et un diagnostic adapté. Un ajout manuel de champs ne garantit pas l’alignement voulu. |
| Annulation par contexte | Interruption et timeouts Java nécessitent la coopération des traitements. Ne pas interrompre une action à mi-mutation au détriment des invariants. |

Les facteurs de gain du cours sont des exemples, pas des objectifs pour Pixel War. Une p-value faible ne remplace ni un protocole contrôlé ni un gain utile. Un index B-tree n’est pas généralement O(1), un atomique n’est pas gratuit, et un cache Redis ne garantit pas à lui seul la cohérence des données.

## 4. À réserver à une évolution avec persistance

Le support J4_PM concerne surtout un besoin absent du moteur actuel. Si l’historique des parties devient nécessaire : écrire les mesures par lots hors de la boucle de pose, borner le pool de connexions, indexer selon les requêtes et vérifier les plans avec `EXPLAIN (ANALYZE, BUFFERS)`. Un scan séquentiel peut rester le bon plan selon la sélectivité.

Un cache doit avoir une limite mémoire et une stratégie d’invalidation. Ne pas ajouter Redis ou une base pour accélérer des mutations déjà effectuées en mémoire. Les formules de taille de pool présentées dans le cours sont des points de départ à valider sous charge.

*Source : J4_PM, p. 2–8.*

## 5. Contrat de validation d’une optimisation

Pour chaque changement : conserver la référence, documenter le goulot, modifier une seule hypothèse, vérifier l’équivalence fonctionnelle et comparer avant/après dans les mêmes conditions. Inclure scénarios denses et coûts mémoire, pas seulement le cas favorable.

Les invariants clés restent : conversion simultanée sans cascade dans une passe, stock cohérent, attribution des conversions, suivi de toutes les mutations, reset complet et aucune mutation après le retour de pause ou stop.

Un rapport court doit contenir : **diagnostic → cause → changement → résultats et limites**. La stratégie V3 modifie les décisions de gameplay : son résultat n’a pas à être identique au mode aléatoire, mais les règles du moteur doivent rester respectées.

## Sources

Supports fournis, Christophe Lecroq, Sup de Vinci, 5–9 octobre 2026. Les pages citées correspondent aux pages des PDF, couverture incluse.

| Référence | Fichier | Sujet retenu |
|---|---|---|
| J1_AM | `support-performance-for-backend-j1_am.pdf` | Méthode, complexité, compromis RAM/CPU, Amdahl |
| J1_PM | `support-performance-for-backend-j1_pm.pdf` | Localité mémoire et tableaux |
| J2_AM | `support-performance-for-backend-j2_am.pdf` | Allocations, disposition mémoire, faux partage |
| J2_PM | `support-performance-for-backend-j2_pm.pdf` | Benchmarks, profils et réutilisation |
| J3_AM | `support-performance-for-backend-j3_am.pdf` | Verrous, atomiques, publication de copies |
| J3_PM | `support-performance-for-backend-j3_pm.pdf` | Concurrence bornée et annulation |
| J4_AM | `support-performance-for-backend-j4_am.pdf` | Payloads, réseau et percentiles |
| J4_PM | `support-performance-for-backend-j4_pm.pdf` | Persistance, caches et rapport d’audit |

Contexte du projet : `V2.md`, `V2-baseline.md`, `analyse-performance.md`, `conversion-incrementale.md` et proposition `V3-strategie-frontiere.md`. Aucun code source ni nouveau profil d’exécution n’a été audité pour cette synthèse.

Références primaires pour les adaptations Java : [JMH](https://github.com/openjdk/jmh), [JOL](https://github.com/openjdk/jol), [HotSpot Java 21 : compilation et échappement](https://docs.oracle.com/en/java/javase/21/vm/java-hotspot-virtual-machine-performance-enhancements.html), [ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html), [JEP 444 : threads virtuels](https://openjdk.org/jeps/444).
