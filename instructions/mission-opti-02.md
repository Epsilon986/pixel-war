# Pixel War — Mission Codex : optimisation 02, coût des compteurs

## Mission et décision d’ingénierie

Évaluer puis réduire le coût de synchronisation des compteurs techniques du moteur, **sans changer le verrou global ni les règles V3**. La piste retenue est de remplacer des atomiques redondants par des compteurs primitifs ordinaires lorsque tous les accès sont déjà protégés par le même verrou, ou lorsque leur confinement est effectivement démontré.

Ce n’est pas une instruction de remplacer tous les compteurs par `AtomicLong` ou `LongAdder`. L’objectif est de choisir la synchronisation minimale correcte, puis de mesurer son intérêt réel. Si les compteurs sont déjà des primitifs correctement protégés, documenter ce constat et ne pas inventer une optimisation.

Livrer un rapport d’audit d’étape autonome, ses mesures et ses preuves, utilisables ultérieurement pour rédiger l’audit final. Le code seul et un résumé de chat ne suffisent pas.

## 1. Point de départ et périmètre

Lire le dépôt, les règles V3 actuelles, le rapport et les résultats de l’optimisation 01. Vérifier si le board compact a été conservé, rejeté ou n’a pas été implémenté. Partir du dernier état fonctionnel effectivement retenu, sur une branche dédiée ; conserver les modifications locales et la référence V3 historique.

Le présent descriptif a été préparé sans résultats de l’étape 01 ni inspection du code actuel. Il ne prouve pas que les compteurs sont un goulot ni même qu’ils utilisent des atomiques.

Conserver : stratégie, graines et ordre des tirages, bonus, stock consommé seulement sur changement effectif, éliminations, frontières, scores, conversion incrémentale simultanée, scheduler, API et cycle de vie. Ne pas reprendre les anciennes règles de stock de la V2.

Hors périmètre : suppression ou partition du verrou global, parallélisation des joueurs, snapshots HTTP, nouvelle représentation du board, modification des frontières, agrégation différée des événements, refonte de l’interface et ajout de métriques permanentes.

## 2. Diagnostic obligatoire avant modification

Exécuter les tests et une baseline actuelle SMALL 500 × 500 et DEFAULT 4000 × 4000. Capturer un profil CPU et allocations, et les attentes de synchronisation disponibles, sur DEFAULT. Réutiliser le banc de mesure de l’étape 01 en conservant ses définitions et en documentant les adaptations nécessaires.

Inventorier les compteurs globaux et par joueur : actions, tentatives, modifications, conversions, stock consommé/rechargé, actions sans stock et statistiques de durée. Distinguer les scores métier des statistiques techniques. Inspecter aussi les compteurs présents dans Board ou dans la maintenance des frontières, sans les modifier automatiquement.

Écrire dans le rapport une table :

| Compteur ou groupe | Type actuel | Écrivains et lecteurs | Protection réelle | Exactitude requise | Décision motivée |
|---|---|---|---|---|---|
| À relever dans le code | `long`, atomique, adder, etc. | Moteur, HTTP, reset, tâche de collecte… | Moniteur précis, verrou, confinement ou aucune | Individuelle et/ou ensemble cohérent | Conserver ou simplifier |

Vérifier chaque chemin : incrément, lecture API, copie/export, getter, reset, arrêt, erreur et tâche asynchrone. Le fait que les écritures soient protégées ne suffit pas si une lecture ne l’est pas. Un moniteur `Board` et un moniteur `Simulation` ne sont pas automatiquement équivalents.

Décrire la fréquence des mises à jour et leur position dans les piles chaudes. L’absence de méthode atomique visible dans un profil échantillonné n’est pas la preuve d’un coût nul : l’inlining et la précision du profil limitent cette observation.

## 3. Règles de choix et implémentation

| Situation démontrée | Choix pour cette mission |
|---|---|
| Tous les accès au compteur sont sous le même verrou | Un `long` ordinaire peut remplacer l’atomique redondant |
| Confinement à un thread, publication et remise à zéro également sûres | Primitif possible après démonstration complète du cycle de vie |
| Accès concurrent hors du verrou, exactitude individuelle requise | Conserver l’atomique existant ; ne pas retirer sa protection |
| Plusieurs valeurs doivent être lues comme un ensemble cohérent | Conserver leur protocole de verrouillage commun ; plusieurs `AtomicLong` ne suffisent pas |
| Forte contention entre écrivains indépendants, somme approximative acceptable | `LongAdder` est un candidat pour une autre architecture ; cette situation n’est pas présumée ici |

Dans le code de production, limiter le changement aux compteurs dont la protection a été démontrée. Utiliser `long`, jamais `int`, pour conserver les capacités actuelles et les mêmes règles de dépassement éventuelles. Ne pas ajouter un `volatile` à la place d’un incrément atomique : `volatile` ne rend pas `++` atomique.

Préserver les mises à jour aux mêmes endroits et avec la même attribution. Ne pas déplacer tous les compteurs en fin d’action, car une erreur partielle pourrait changer leur sémantique. Ne pas utiliser `LongAdder.sumThenReset()` pour réaliser un reset concurrent exact. Conserver la cohérence des statistiques composées : total, nombre, minimum, maximum et moyenne.

Ne pas remplacer des calculs de durée ou leurs horloges dans la même expérience. Conserver noms, unités, JSON et comportement avant toute action ou passe.

### Si aucun atomique redondant n’existe

Ne pas fabriquer une refonte en ajoutant puis retirant des atomiques dans la baseline. Livrer l’inventaire, les profils et une conclusion « piste non applicable dans cette architecture ». Le rapport peut constituer une étude de choix, mais ne doit pas annoncer un gain de production ni une régression réelle qui n’a pas été observée.

Un microbenchmark comparant compteur sous verrou, `AtomicLong` et `LongAdder` est facultatif, dans le banc de mesure uniquement. Employer JMH, consommer les résultats, conserver la même opération logique et distinguer un écrivain, plusieurs écrivains et lecteur concurrent. Ne pas extrapoler le cas multithread à un moteur sérialisé. Ne pas compter ce microbenchmark comme une optimisation livrée.

## 4. Validation de l’exactitude

Conserver les tests métier et ajouter des tests ciblés pour les compteurs modifiés :

- égalité des totaux avant/après sur le même rejeu déterministe ;
- cohérence globale/par joueur, y compris conversions attribuées à la couleur bénéficiaire ;
- action sans stock, pose sans changement, conversions différées et statistiques sans observation ;
- consommation conditionnelle du stock selon les règles V3 ;
- lectures concurrentes pendant les actions, pause, stop et reset ;
- exclusion des mutations tardives après le retour des commandes ;
- remise à zéro et absence d’ancien compteur visible dans les lectures réalisées après reset.

Utiliser des barrières/latches ou un pilotage déterministe pour les scénarios de concurrence ; éviter les tests reposant seulement sur des attentes arbitraires. Vérifier les invariants valables pour un même état capturé sous verrou. Ne pas exiger l’égalité de réponses issues de requêtes différentes exécutées à des instants différents.

Comparer aussi l’état spatial et celui des joueurs avant/après, pas seulement les compteurs. Les durées et mesures JVM sont exclues de cette égalité.

## 5. Mesures avant/après

Identifier deux références : état retenu après étape 01 avec banc commun, puis même état avec la seule modification des compteurs. Même JVM exacte, flags, heap, configuration et instrumentation. Enregistrer environnement matériel et logiciel complet, y compris caches accessibles et gestion du bruit.

| Scénario | Objectif |
|---|---|
| Moteur déterministe, sans HTTP | Isoler le coût d’exécution à charge fixe |
| Application réelle, sans polling | Vérifier le coût avec le scheduler |
| Application réelle, polling contrôlé | Mesurer l’effet des lectures concurrentes |

Tester SMALL et DEFAULT, avec états initiaux vide et rempli déterministes pour le moteur. Pour l’application, reprendre les scénarios et endpoints de l’étape 01. Conserver la même fréquence et concurrence HTTP, sans réduire les données retournées.

Pour le moteur : chauffe JVM puis recréation du même état et des générateurs ; charge fixe calibrée sur la baseline et figée avant/après ; au moins cinq paires dans des JVM neuves par scénario principal, avec ordre avant/après alterné. Une graine peut être retenue pour les mesures principales, les graines 11/22/33 restant requises pour le rejeu fonctionnel. Calculer les statistiques par graine si plusieurs sont mesurées.

Pour l’application : chauffe documentée, fenêtres d’au moins 20 secondes, trois paires par taille et condition de polling. Comptabiliser travail réel, erreurs et éliminations ; ne pas gonfler le débit par des appels devenus sans effet. Utiliser les deltas de compteurs et de temps actif pour le moteur, et préciser la base temporelle des mesures HTTP et CPU.

| Mesure | Exigence |
|---|---|
| Temps de charge fixe et tentatives/s | Principaux indicateurs de gain moteur |
| Modifications/s et actions exécutées | Vérifier une charge métier comparable |
| Temps CPU consommé | Préciser thread ou processus et fenêtre |
| Latences HTTP P50/P95/P99, erreurs et requêtes | Décrire la charge réellement obtenue |
| Allocations/action et mémoire | Mesurer si pertinent ; une absence de gain mémoire est recevable |
| Attentes de moniteurs | Profils réels, seuils et limites ; pas d’estimation inventée |

Capturer JFR avant/après dans des essais séparés des mesures principales de débit. Montrer les vues CPU, allocations et contention réellement disponibles. Des atomiques utilisent des mécanismes différents des moniteurs ; une baisse d’événements de moniteur ne prouve pas une baisse de leur coût.

Produire moyenne, médiane, écart-type, variance et nombre d’essais pour temps et débit, avec unités et convention de variance. Ne pas mélanger tailles, graines et niveaux de polling en une seule moyenne. Conserver les valeurs brutes avant arrondi.

## 6. Rapport obligatoire et pièces à remettre

Écrire `docs/optimisations/02-compteurs.md` en français. Il doit se suffire à lui-même pour une future rédaction de l’audit, sans accès à la conversation :

1. Résumé du besoin, état de départ réel, hypothèse, résultats et décision.
2. Matériel, JVM, versions, commits, flags, configurations, chauffe, répétitions et commandes exactes.
3. Inventaire des compteurs, protection de chaque accès et diagnostic appuyé sur les profils.
4. Implémentation, fichiers modifiés, synchronisation conservée et preuve de correction.
5. Tests et rejeu : commandes, résultats réels et éventuels échecs.
6. Tableau avant/après par scénario : valeurs, variations, dispersion, unités et liens vers données sources.
7. Profils CPU/allocations/attentes avant/après, vues annotées et graphiques issus des données, avec scripts de génération.
8. Analyse : gain marginal après étape 01, effets sous polling et sans polling, coûts et limites ; prochain goulot sans nouvelle refonte.
9. Conclusion : conserver, rejeter ou piste non applicable. Un gain inférieur au bruit doit être déclaré non établi.

Calculer séparément accélération temporelle `temps avant / temps après`, variation du débit `100 × (après / avant − 1)` et réduction du temps `100 × (1 − après / avant)`. Le gain cumulé depuis V3 ne peut être annoncé que sur des mesures comparables ; sinon indiquer qu’il reste à mesurer. Ne pas additionner des pourcentages de gains successifs.

### Correspondance avec le barème

Ajouter une matrice avec statut, section et pièce justificative pour chaque critère :

| Critère | Points | Contribution de cette étape |
|---|---:|---|
| Environnement et métrologie | 3 | Environnement, protocole et statistiques reproductibles |
| Diagnostic et profiling réel | 5 | Captures réelles annotées et interprétation du coût des compteurs |
| Journal d’optimisation | 5 | Choix de synchronisation et exactitude ; ne vaut pas preuve de scalabilité parallèle |
| Échec constructif | 3 | Seulement si une tentative réelle mesurée régresse ou échoue ; sinon critère restant |
| Reproductibilité et synthèse | 4 | Script unique, données, calculs, références et figures |
| Gouvernance IA | +2 | Vérifier le fichier existant et ses quatre directives ; ne pas présumer le bonus acquis |

Statuts : couvert, partiel, non traité ou non applicable justifié. L’axe réseau/persistance et le parallélisme restent à traiter si requis ; cette étape ne valide pas tout le barème. Expliquer les équivalents Java aux outils Go et les différences avec Hyperfine ; leur acceptation finale relève de l’évaluateur.

Ne jamais fabriquer une régression pour obtenir les points d’échec constructif. Si la simplification ne change rien ou régresse, conserver les données et l’analyse, puis justifier la décision sans poursuivre une autre optimisation.

### Archive autonome

Fournir `target/audit-opti-02.zip` contenant rapport, index des pièces, CSV/JSON individuels, dictionnaire des métriques, profils réels, vues annotées, figures, scripts, journaux de tests, configuration, références de code et manifeste SHA-256. Inclure un patch si nécessaire pour rendre les modifications identifiables ; indiquer le dépôt requis pour reproduire le code. Exclure secrets et dossier `.git` complet.

Le script `scripts/benchmark-opti-02.ps1` doit lancer compilation, essais et génération de synthèse en une commande documentée, en réutilisant le banc existant. Chaque essai porte un identifiant et conserve commit, hash du binaire, graine, paramètres, état initial, ordre, instrumentation, durée, compteurs et erreurs. Vérifier les liens après extraction de l’archive.

Si une mesure, un profil ou DEFAULT manque, l’indiquer explicitement ; ne pas fournir une valeur théorique comme résultat observé. Conserver les problèmes de mémoire ou d’exécution et les commandes nécessaires. Le rapport est alors partiel.

## 7. Critères de fin

- [ ] État de l’étape 01 vérifié et références exactes enregistrées.
- [ ] Protection de chaque compteur modifié démontrée sur tous ses accès.
- [ ] Une seule optimisation, aucun retrait du verrou global.
- [ ] Tests et rejeu métier réussis ou échecs explicitement documentés.
- [ ] Mesures SMALL/DEFAULT, sans polling et avec polling, ou manques signalés.
- [ ] Rapport autonome, matrice du barème et archive de preuves vérifiée.
- [ ] Conclusion honnête : gain mesuré, gain non établi, régression ou non-applicabilité.

Références : supports J3_AM (verrous et atomiques), J3_PM (métriques concurrentes), J2_PM (métrologie), barème et rapport d’audit d’exemple fournis. Références Java : [AtomicLong](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicLong.html), [LongAdder](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/LongAdder.html), [modèle mémoire Java](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html).
