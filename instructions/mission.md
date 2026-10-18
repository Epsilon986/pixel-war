# Pixel War — Mission Codex : optimisation 01, board compact

## Mission

À partir de la V3 fonctionnelle actuelle, implémenter et évaluer une seule optimisation : **remplacer le stockage des couleurs du board par un tableau primitif linéaire compact**, si cette représentation n’existe pas déjà.

Produire le code, sa validation, une comparaison reproductible avant/après et une analyse écrite des gains ou régressions. Ne pas annoncer de gain sans mesure. Travailler sur une branche dédiée issue du commit V3 fourni par l’utilisateur, sans modifier sa branche de référence ni ses changements locaux.

**Livrable obligatoire : un rapport d’audit d’étape autonome, accompagné de ses preuves, que l’utilisateur pourra transmettre à un autre assistant pour rédiger l’audit final sans devoir reconstituer le contexte de cette conversation. La mission n’est pas terminée avec le code ou un résumé dans le chat.**

Ce document est une instruction d’implémentation ; il ne contient aucun nouveau résultat mesuré.

## 1. Contexte à respecter

SMALL contient 500 × 500 cellules et DEFAULT 4000 × 4000, soit 64 fois plus. L’objectif est d’augmenter la capacité de traitement du moteur sur DEFAULT sans changer la partie simulée. Une réduction de mémoire peut être utile même si le débit progresse peu ; elle doit être distinguée d’une amélioration du temps de jeu.

La V3 actuelle possède déjà :

- une conversion incrémentale, avec détection sur état stable puis application simultanée ;
- des scores maintenus par les mutations, utilisés notamment par le bonus contre les adversaires affaiblis ;
- des frontières maintenues localement, avec listes et index primitifs ;
- un index des cases vides utilisé pour l’exploration et le repli ;
- des conquêtes autorisées avant remplissage complet ;
- un stock consommé seulement lorsqu’une pose change effectivement une cellule ;
- une élimination conservée jusqu’au reset, et une priorité de cible conservée pendant plusieurs actions.

Lire le code et les documents du dépôt avant d’intervenir : ils font autorité sur les détails. Le rapport V3 historique précède certaines règles actuelles ; ses débits ne constituent pas la baseline de cette mission. Il ne démontre pas que la représentation du board est aujourd’hui le principal goulot.

## 2. Hypothèse et inspection initiale

**Hypothèse :** une représentation compacte réduit le volume des couleurs en mémoire et les indirections lors des accès au voisinage. Cela pourrait améliorer le coût des lectures du scoring et des conversions. La multiplication d’index et le décodage peuvent aussi introduire un surcoût ; les frontières, la sélection ou les verrous peuvent continuer à dominer.

Avant toute modification métier :

1. Relever commit, état Git, JVM exacte, flags, GC, heap, OS, RAM, CPU, cœurs physiques/logiques et caches accessibles. Indiquer les informations indisponibles.
2. Inspecter `Board`, ses accesseurs et toutes ses mutations. Identifier le type actuel, les contrôles de coordonnées, le codage du vide et les structures dérivées.
3. Exécuter les tests existants et conserver leur résultat.
4. Capturer une baseline actuelle sur SMALL et DEFAULT et un profil JFR DEFAULT sous charge, en distinguant calcul, allocations et attentes de moniteurs.
5. Écrire le diagnostic initial et sa limite : le coût matériel supposé n’est pas une preuve de cache-misses.

Si le board utilise déjà un stockage primitif linéaire compact, le signaler avec les références de code et ne pas réaliser une fausse optimisation. Produire le diagnostic et proposer le prochain candidat justifié par le profil, sans lancer une deuxième refonte. Si le stockage diffère, conduire l’expérience même si le gain de débit attendu reste incertain, en évaluant séparément l’intérêt mémoire.

## 3. Implémentation attendue

### Représentation

Utiliser, pour les quatre couleurs et le vide, un `byte[]` contenant un code stable par cellule, par exemple zéro pour le vide et un à quatre pour les couleurs. Adapter le mapping aux types réels ; ne pas exposer ces codes dans l’API HTTP.

```text
index(x, y) = y × width + x
```

Vérifier dimensions et produit dans un type suffisamment large avant conversion en index et allocation. Conserver les validations et erreurs publiques existantes. Un tableau 1D ne doit pas autoriser une coordonnée horizontale invalide à accéder à la ligne suivante. Les limites d’allocation effectives de la JVM doivent rester explicites.

Prévoir un mapping constant et sans allocation par accès pour convertir code et couleur. Ne pas appeler une méthode créant un tableau, telle qu’un appel répété à `Enum.values()`, dans chaque lecture. Ne pas remplacer les index primitifs par des collections d’objets.

### Conservation du comportement

Conserver les signatures publiques utiles et le même point d’entrée pour chaque mutation réelle. Chaque changement continue à mettre à jour exactement les mêmes éléments : couleurs, scores, cases vides, frontières, candidats de conversion et compteurs.

Préserver l’ordre de mutation et de marquage, les contrôles de bords, les règles d’égalité et le nombre et l’ordre des tirages pseudo-aléatoires. Ne pas créer un accès rapide qui contourne les notifications de `Board.place`.

Conserver le verrouillage existant. Adapter les parcours devenus incompatibles avec le stockage, mais ne pas supprimer une synchronisation ou modifier les algorithmes de scoring, de frontière ou de conversion pendant cette mission.

Le reset remet les couleurs à vide et conserve les effets existants sur toutes les structures dérivées. L’aperçu et les réponses API restent fonctionnellement identiques.

### Limites de périmètre

Ne pas modifier le taux d’exploration, les bonus, l’échantillon de candidats, le stock, la cadence, les règles d’élimination, les métriques affichées ou la taille d’aperçu pour améliorer les résultats. Ne pas ajouter de parallélisme, snapshot HTTP, pooling, compression de frontières ou nouvel algorithme de scores. Les gains de telles évolutions seront évalués séparément.

## 4. Validation fonctionnelle

Conserver les tests actuels et compléter uniquement les risques introduits par le nouveau stockage :

- mapping des cinq états, lecture et écriture sur les coins, limites et dimensions rectangulaires ;
- rejet des coordonnées invalides et validation du produit des dimensions ;
- reset, mutation sans changement et conquête ;
- équivalence des couleurs, scores, cases vides, frontières et conversions avec une référence indépendante sur de petits plateaux ;
- conservation des poses multiples, de la consommation conditionnelle du stock et des effets différés des conversions ;
- maintien des tests de pause, stop, reset, API et aperçu.

Effectuer aussi un rejeu déterministe avant/après : mêmes états initiaux, graines et ordre explicite d’actions. Comparer les cellules finales, les compteurs métier et l’état des joueurs, y compris éliminations et cible prioritaire. Exclure durées et mesures JVM de l’égalité. Une simple égalité des scores ne suffit pas ; comparer aussi l’état spatial, directement ou via une empreinte déterministe robuste.

## 5. Comparaison reproductible

### Références et outillage

Conserver deux références Git exactes : baseline V3 avec le banc de mesure, puis même code avec le seul changement de stockage et ses adaptations. Le pilote de benchmark et sa configuration doivent être communs aux deux versions. Utiliser des worktrees ou des répertoires de build isolés ; ne pas écraser les modifications locales.

Fournir un script PowerShell adapté au dépôt, exécutable en une commande depuis sa racine, par exemple `scripts/benchmark-opti-01.ps1`. Il doit accepter les deux références, le chemin Java et le répertoire de résultats, compiler, lancer les essais et produire données brutes et synthèse. Documenter la commande exacte effectivement exécutée.

### Deux charges complémentaires

| Essai | Fonctionnement | Question |
|---|---|---|
| Moteur déterministe sans HTTP | Appeler les actions réelles séquentiellement avec un ordre contrôlé | Le stockage améliore-t-il le traitement métier ? |
| Application avec polling | Scheduler réel et lectures HTTP identiques avant/après | Le gain est-il visible dans les conditions d’utilisation ? |

Ne pas présenter le premier essai comme une mesure du scheduler ou du serveur. Un benchmark isolé des accès au board est facultatif, avec JMH si nécessaire ; son gain ne remplace pas celui du moteur.

### Scénarios et protocole

- Tester 500 × 500 et 4000 × 4000, avec stratégie frontier et paramètres V3 inchangés.
- Tester un départ vide et un état rempli à quatre couleurs préparé de façon déterministe. Préparation hors mesure, par les mécanismes métier prévus afin de garder les index cohérents.
- Conserver explicitement les graines 11, 22 et 33. Tous les essais comparés utilisent la même JVM, les mêmes flags et une heap suffisante identique. Ne pas utiliser Java 21 avant et Java 27 après.
- Pour le moteur, comparer un nombre fixé et identique d’actions réellement exécutées. Calibrer ce budget sur la baseline pour une durée mesurable, puis le figer pour les deux versions. Documenter les éventuelles éliminations et l’arrêt naturel ; ne pas compter des appels devenus sans effet comme du travail utile.
- Séparer chauffe de la JVM et état initial mesuré : après chauffe, recréer le même état et réinitialiser les générateurs. Exclure construction, préparation et comparaison d’état du chronométrage métier.
- Prévoir au moins cinq paires d’exécutions dans des JVM neuves pour chaque scénario moteur et graine retenue, en alternant l’ordre avant/après. Pour maîtriser la durée, une graine peut servir au scénario principal de performance ; les trois graines restent obligatoires pour le rejeu fonctionnel. Si plusieurs graines sont mesurées, produire les statistiques par graine et ne pas confondre variation de charge et bruit de mesure. Documenter le budget choisi.
- Pour l’essai applicatif, utiliser des fenêtres identiques d’au moins 20 secondes après chauffe stabilisée, avec fréquence, endpoints, concurrence et taille d’aperçu fixes. Répéter au moins trois paires par taille ; traiter le départ rempli comme scénario complémentaire si son coût rend la matrice trop longue.
- Enregistrer JFR séparément des mesures principales de débit pour éviter de confondre gain et surcharge de profilage. Décrire le surcoût si des essais instrumentés sont comparés.

Si une mesure échoue par manque de mémoire ou de temps, conserver l’échec et ses conditions ; ne pas remplacer silencieusement DEFAULT par SMALL. Un changement de budget ou de heap exige de refaire les deux versions concernées.

### Mesures à produire

| Mesure | Définition |
|---|---|
| Temps de la charge fixe | Temps écoulé pour le budget d’actions contrôlé |
| Tentatives/s et modifications/s | Deltas des compteurs sur la fenêtre ; signaler aussi actions réellement exécutées |
| Coût CPU | Temps CPU consommé par le travail mesuré ; préciser thread moteur ou processus |
| Empreinte du stockage des couleurs | Avant/après sur la JVM testée, séparée des index et des frontières |
| Mémoire JVM | Heap maximale échantillonnée pendant l’essai, période d’échantillonnage et dépendance au GC |
| Allocations | Octets/action si mesurables avec l’outil retenu, sinon profil d’allocations et limites explicites |
| Coût HTTP | Latence P95, nombre de requêtes, erreurs et débit sous polling |

Sur 4000 × 4000, le contenu d’un `byte[]` représente 16 000 000 octets, environ 15,26 Mio, hors en-tête et alignement. Ne pas supposer la taille des références de l’ancien stockage : l’observer, par exemple avec JOL. Ne pas attribuer toute la baisse de heap aux couleurs sans isoler le reste des structures. Le tableau est persistant : une réduction d’empreinte ne garantit pas une réduction d’octets alloués par action.

Calculer par scénario moyenne, médiane, écart-type et variance, avec unités et définition de la variance. Conserver les observations individuelles et la dispersion des rapports avant/après ; ne pas moyenner SMALL et DEFAULT en un gain unique.

## 6. Rapport d’audit d’étape à écrire

Créer `docs/optimisations/01-board-compact.md` en français, avec titres lisibles, unités explicites, tableaux et liens relatifs vers les preuves. Conserver les données brutes versionnables dans un sous-dossier associé. Le Markdown doit être lisible seul et convertible en PDF pour constituer un chapitre de l’audit final.

Les profils volumineux peuvent rester hors Git dans un emplacement documenté, mais doivent être inclus dans le dossier de remise à l’utilisateur. Ne pas se limiter à un chemin local inaccessible ou à une commande qui permettrait de les recréer : conserver les preuves de l’exécution réellement analysée. Si un profil ne peut pas être livré, le déclarer comme pièce manquante.

Le rapport doit contenir :

1. **Référence et environnement** : commits, configuration, matériel, versions, commandes, chauffe et répétitions.
2. **Diagnostic avant** : représentation réelle, profil CPU/allocations/verrous, coût supposé et preuves disponibles.
3. **Changement** : fichiers modifiés, ancien et nouveau stockage, complexité inchangée des accès et compromis de décodage/indexation.
4. **Validation** : tests, rejeu et invariants vérifiés, commandes et résultats réels.
5. **Résultats** : tableau par scénario, avant/après, dispersion et variation ; données manquantes indiquées comme non mesurées.
6. **Analyse causale** : expliquer les effets observés sans affirmer des cache-misses éliminés sur la seule base d’un profil CPU. Distinguer gain mémoire, gain moteur et gain applicatif.
7. **Décision** : conserver ou rejeter selon les preuves, et nommer le prochain goulot observé sans l’optimiser dans cette mission.

### Résumé autonome obligatoire

Ouvrir le rapport par une synthèse précisant : besoin métier, problème sur DEFAULT, périmètre de l’optimisation, baseline exacte, principaux résultats SMALL et DEFAULT, conclusion et limites. Définir une action, une tentative, une modification directe et une conversion. Distinguer les mesures du pilote déterministe de celles de l’application avec scheduler et polling.

Inclure le tableau suivant, rempli pour chaque scénario mesuré. Une donnée absente vaut « non mesuré », jamais zéro.

| Scénario et mesure | Avant | Après | Variation | Dispersion et nombre d’essais | Pièce justificative |
|---|---:|---:|---:|---|---|
| Temps de la charge fixe | À mesurer | À mesurer | À calculer | À calculer | CSV/JSON et identifiants d’essais |
| Tentatives/s | À mesurer | À mesurer | À calculer | À calculer | CSV/JSON |
| Temps CPU de la charge | À mesurer | À mesurer | À calculer | À calculer | Export de mesure |
| Empreinte des couleurs | À mesurer | À mesurer | À calculer | Méthode précisée | Mesure de disposition mémoire |
| Heap maximale échantillonnée | À mesurer | À mesurer | À calculer | À calculer | Échantillons JVM |
| Allocations/action | À mesurer si possible | À mesurer si possible | À calculer | Méthode précisée | JMH ou mesure dédiée |
| Latence HTTP P95 | À mesurer | À mesurer | À calculer | Par essai | Relevés HTTP |

Ne pas confondre un percentile de latence à l’intérieur d’un essai avec la dispersion des moyennes entre essais. Fournir au minimum moyenne, médiane, écart-type, variance et nombre d’échantillons pour la durée de la charge fixe et le débit. Indiquer les conventions de calcul, dont la variance d’échantillon ou de population.

### Preuves de profiling et illustrations

- Capturer des profils CPU et allocations **avant et après**, sur le même scénario DEFAULT et dans des conditions décrites. Inclure les attentes de moniteurs lorsque disponibles, avec les seuils de collecte.
- Produire au moins une vue CPU et une vue allocations comparatives annotées, à partir de JFR/JMC ou d’un outil Java adapté. Un flamegraph ou call tree réel est recevable comme pièce technique ; expliquer son équivalence au rôle des profils pprof du cours, sans affirmer que l’outil a été explicitement approuvé par l’évaluateur.
- Identifier les méthodes et piles importantes, la nature des valeurs (échantillons, temps ou octets), la portée inclusive/exclusive et les limites d’échantillonnage. Une part relative peut augmenter après optimisation alors que le coût absolu baisse.
- Fournir deux graphiques simples issus des mesures brutes : débit avant/après et empreinte du stockage avant/après, en séparant SMALL et DEFAULT, avec dispersion lorsque pertinente. Conserver les données et le script qui génère les figures ; ne pas dessiner des résultats supposés.
- Ne pas déduire « zéro GC du serveur » d’un benchmark métier à zéro allocation. Ne pas présenter une heap finale comme un pic ou une mémoire retenue après GC.

Si l’environnement ne permet pas une capture requise, livrer les mesures disponibles et signaler explicitement le manque : le rapport est alors partiel au regard de ce contrat. Ne pas inventer une capture, un nombre ou un succès de commande.

### Traçabilité des résultats

Chaque essai brut doit identifier : version et commit, éventuel diff non committé conservé, hash du binaire, configuration effective, taille du board, état initial, graine, ordre de passage, budget demandé et exécuté, chauffe, durée, compteurs début/fin, temps CPU, erreurs et instrumentation active.

Fournir un dictionnaire des champs, unités et formules ; conserver suffisamment de précision dans les données brutes et arrondir seulement l’affichage. Joindre les journaux de compilation/tests, commandes de capture et sorties des scripts. Vérifier que temps de charge fixe, nombre d’actions et débits se recoupent, en explicitant toute différence de périmètre.

### Dossier à remettre à l’utilisateur

Créer une archive `target/audit-opti-01.zip` contenant uniquement les livrables nécessaires :

- rapport Markdown autonome et index des pièces ;
- données CSV/JSON individuelles et synthèse calculée ;
- profils réels avant/après, vues annotées et graphiques ;
- script de benchmark, script de génération de la synthèse et des figures, commandes exactes et versions des dépendances ;
- résultats des tests et du rejeu, références Git et patch de l’optimisation si nécessaire ;
- manifeste des fichiers avec empreintes SHA-256.

Vérifier les chemins relatifs du rapport après extraction et la présence des pièces annoncées. L’archive doit permettre une analyse ultérieure sans accès à la conversation ; la reproduction du code nécessite le dépôt aux références indiquées, ce qui doit être précisé. Ne pas y inclure de secrets, de dossier `.git` complet ou d’artefacts sans rapport avec la mission.

Dans la réponse finale, fournir les chemins du rapport et de l’archive, les commandes exécutées, la conclusion mesurée et la liste des pièces manquantes éventuelles. Ne pas annoncer un rapport complet si des exigences restent non exécutées.

Formules à utiliser :

```text
accélération sur charge fixe = temps avant / temps après
variation du débit (%) = 100 × (débit après / débit avant − 1)
réduction du temps (%) = 100 × (1 − temps après / temps avant)
réduction de mémoire (%) = 100 × (1 − mémoire après / mémoire avant)
```

Si le gain est faible ou inférieur à la dispersion, l’indiquer. Si la mémoire baisse et le débit régresse, présenter les deux résultats et argumenter la décision. Ne pas fabriquer un échec pour remplir le barème : une tentative réellement contre-productive peut constituer un échec constructif documenté.

Ne pas déduire un temps de victoire du seul débit. À nombre de tentatives comparable, un gain de débit raccourcit potentiellement le calcul, mais les 64 fois plus de cellules et les trajectoires de conquête restent des contraintes différentes.

### Correspondance obligatoire avec le barème

Ajouter cette matrice à la fin du rapport, en remplaçant les attentes par des renvois précis aux sections et pièces. Utiliser les statuts « couvert », « partiel », « non traité » ou « non applicable justifié ». Ne pas attribuer soi-même une note acquise.

| Critère du barème | Points | Preuves attendues pour cette étape | Limite à signaler pour l’audit final |
|---|---:|---|---|
| Environnement et métrologie | 3 | Matériel, caches, runtime exact, isolation du bruit, chauffe, répétitions et statistiques complètes | Toute donnée indisponible ou substitution à Hyperfine doit être explicitée |
| Diagnostic matériel et profiling réel | 5 | Profils CPU/allocations réels, captures annotées, méthodes dominantes et cause argumentée | Une lecture du code ne prouve pas un effet cache ou matériel |
| Journal et démarche d’ingénierie | 5 | Hypothèse, changement isolé, justification mémoire/localité, résultats et décision | Cette étape traite l’axe mémoire ; elle ne couvre pas à elle seule concurrence, réseau et persistance |
| Échec constructif | 3 | Si une régression réelle survient : variante, protocole, chiffres, explication et décision | Sans tentative réellement infructueuse, critère encore non traité ; ne pas en inventer une |
| Reproductibilité et synthèse | 4 | Commande unique, références, données brutes, tableaux comparatifs, figures et archive | Indiquer les opérations manuelles ou prérequis restant nécessaires |
| Gouvernance technique IA | +2 | Référence à un `constitution.md` existant s’il satisfait les quatre directives, sinon état des manques | Ne pas déclarer le bonus couvert sur la seule présence de ce prompt ; création ou adaptation dédiée à prévoir |

Le barème cite Hyperfine, pprof et des pratiques Go. Expliquer le rôle des outils réellement utilisés en Java et les différences de protocole. L’adéquation finale de ces substitutions et le traitement d’un axe absent du projet relèvent de l’évaluateur : les rendre visibles, sans présumer une dispense.

Préserver la V3 comme référence historique de la campagne. Lors des optimisations suivantes, distinguer gain marginal par rapport à l’étape précédente et gain cumulé par rapport à cette V3, sur des scénarios inchangés. Cette étape ne doit pas être présentée comme une couverture intégrale du barème ou un audit final terminé.

## 7. Critères de fin

- [ ] Une seule optimisation de représentation, ou un constat documenté qu’elle existe déjà.
- [ ] Toutes les règles de la V3 actuelle sont conservées.
- [ ] Tests existants et rejeu déterministe passent.
- [ ] Baseline et version modifiée sont comparées sur SMALL et DEFAULT.
- [ ] Une commande reproduit les essais, avec données brutes et environnement.
- [ ] Rapport avant/après rempli avec résultats réellement obtenus, limites et décision.
- [ ] Profils avant/après et figures annotées réels livrés ; éventuels manques signalés.
- [ ] Données individuelles, dictionnaire des métriques et scripts permettent de reconstruire les tableaux.
- [ ] Matrice du barème remplie honnêtement et travaux restants identifiés.
- [ ] Archive de remise vérifiée, exploitable sans le contexte de la conversation.
- [ ] Aucune seconde optimisation engagée pour masquer un résultat décevant.

## Références et statut

Choix inspiré des supports J1_PM (localité et tableaux contigus), J2_AM (empreinte mémoire) et J2_PM (métrologie), et des exigences du barème : diagnostic réel, journal argumenté et reproductibilité. Ce choix est une hypothèse d’expérience, pas un classement prouvé des goulots actuels.

Documents projet lus pour cette mission : `v3(1).md` et `V3-baseline(1).md`. Le second est explicitement historique. Le dépôt réel doit confirmer les représentations et permettre de produire les nouveaux résultats.

Outils de référence : [JOL](https://github.com/openjdk/jol), [JMH](https://github.com/openjdk/jmh), [exemple officiel JMH : profilers](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java).
