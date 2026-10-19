# Pixel War — Mission Codex O3 : positions primitives dans la sélection de frontière

## Mission

Réduire les allocations temporaires de `PlacementStrategy.Position` pendant l’échantillonnage des cibles de `FrontierPlacementStrategy`, sans modifier les coordonnées choisies, les règles du jeu ni l’ordre des tirages pseudo-aléatoires.

**Une seule optimisation : manipuler les candidats avec des primitifs, puis matérialiser au plus la position finale requise par l’interface existante.** Livrer le code, une comparaison reproductible et un rapport d’étape autonome avec ses preuves. Ne pas annoncer un gain sans mesure.

Le numéro O3 désigne la troisième expérience de la campagne : O1 a compacté le board, O2 a conclu à l’absence d’atomiques redondants et n’a livré aucun gain de production. Ne pas présenter O3 comme la troisième amélioration effectivement réalisée.

## 1. Point de départ et hypothèse vérifiable

Lire les règles V3 actuelles, le rapport O1, l’étude O2 et le code réel. Partir de la dernière version fonctionnelle retenue sur une branche dédiée, en préservant la branche et les fichiers locaux de l’utilisateur.

Dans les sources fournies avec O2 :

- `PlacementStrategy.Position` est un record `(int x, int y)` ;
- `FrontierPlacementStrategy.choose` appelle `Board.randomFrontierPosition` pour chaque occurrence de l’échantillon ;
- cette méthode tire déjà un identifiant primitif dans `FrontierIndex`, puis construit une nouvelle `Position` ;
- `conquestScore`, `score`, `potential` et `after` transmettent cette position pour lire ses coordonnées ;
- la stratégie renvoie le candidat gagnant ; exploration et repli construisent déjà au plus la position choisie.

Le profil applicatif DEFAULT avec polling de l’étude O2 estime environ 966 Mio alloués en objets `Position`. C’est un volume échantillonné sur une exécution profilée, pas une mémoire retenue ni une mesure exclusive de cette boucle. D’autres chemins peuvent aussi créer des positions.

**Hypothèse :** éviter la matérialisation de chaque candidat peut réduire les octets alloués par décision et le coût associé. La JVM peut déjà éliminer certains objets ; encodage/décodage et différences d’inlining peuvent limiter le gain. L’effet sur le débit et le GC doit être mesuré séparément.

Vérifier ces constats dans le dépôt actuel avant modification. Si ce chemin utilise déjà des primitifs, livrer un diagnostic de non-applicabilité plutôt qu’une optimisation fictive. Ne pas choisir une autre refonte dans cette mission.

## 2. Implémentation ciblée

### Tirage des candidats

Ajouter à `Board` un accès interne approprié qui renvoie directement un identifiant `int` de cellule de frontière. Réutiliser `FrontierIndex.randomId(random)` et le même ordre de stockage. Si l’absence de candidat nécessite une sentinelle, utiliser une valeur hors domaine, par exemple `-1`, et documenter son traitement.

Conserver les validations de couleur, la synchronisation et les contrats des méthodes existantes. `randomFrontierPosition` peut devenir un adaptateur vers cet accès primitif, pour ne pas casser ses appelants ou tests. Cet adaptateur ne doit pas être utilisé par la boucle optimisée.

### Évaluation et choix

Dans le chemin de frontière de `choose` :

1. conserver l’identifiant du meilleur candidat dans un `int` ;
2. tirer chaque occurrence comme avant, avec remise ;
3. décoder ses coordonnées en variables primitives locales si nécessaire ;
4. évaluer le score et le bonus avec des paramètres primitifs ;
5. appliquer exactement le même départage par réservoir ;
6. construire une seule `Position` pour le résultat final.

Conserver la formule `id = y * width + x`, ses limites et les contrôles existants. Une division ou un modulo supplémentaire n’est pas gratuitement performant : décrire l’endroit où le décodage est fait et le mesurer. Ne pas allouer de tableau `int[2]`, utiliser d’`Integer`, de stream, de tuple ou de lambda capturante par candidat.

Adapter `conquestScore`, `score`, `potential` et `after` à des coordonnées primitives, sans changement de formule, d’arrondi, d’ordre de lecture ou de court-circuit logique. Si des tests utilisent les signatures prenant une `Position`, conserver de petits adaptateurs vers une implémentation commune pour éviter deux algorithmes divergents.

Ne pas retourner une position mutable réutilisée : le contrat d’objet résultat reste celui d’avant. Ne pas créer un pool d’objets. L’objectif porte sur les positions candidates, pas sur une nouvelle abstraction globale de toutes les coordonnées.

### Tirages strictement conservés

Conserver le `nextDouble()` d’exploration, y compris lorsque la frontière est vide ; ne pas inverser l’ordre du test et du tirage. Conserver nombre, type, borne et ordre des appels au générateur : tirage de candidat, départages, exploration x puis y et sélection de cible faible.

Ne pas dédupliquer l’échantillon : des occurrences répétées participent au départage actuel. Ne pas trier les candidats ou modifier l’ordre des ensembles de frontière. Ne pas ajouter de tirage pour remplacer une sentinelle normalement impossible sous le verrou ; analyser un tel cas comme une incohérence à traiter selon le contrat existant.

### Hors périmètre

Conserver exploration et repli actuels, qui n’ont pas besoin de candidats temporaires. Préserver stock conditionnel, éliminations, cible prioritaire, scores, ordre des poses, conversion simultanée et différée, verrous, scheduler, API et paramètres.

Ne pas optimiser les `CellState.values()`, les `Long`/`EnumMap`, les listes de conversions, les compteurs, les frontières ou les lectures HTTP. Ne pas factoriser d’autres calculs du scoring, pré-calculer le maximum adverse ou retirer des synchronisations dans la même expérience. Ces pistes peuvent apparaître dans la conclusion, sans être implémentées.

## 3. Baseline et validation fonctionnelle

Avant le changement, conserver commit, éventuel diff local, hashes du binaire, environnement, configuration et résultats des tests. Lire les profils existants et capturer un profil actuel si le code diffère de la référence O2. La référence O2 mesurée était `c5a8777bcd9e967ca23ea451a06489b76a3e8ad6` ; ne pas supposer qu’elle est encore le HEAD.

Ajouter des tests ciblés :

- exploration 0 et 1, frontière vide, repli sur case vide et plateau rempli ;
- échantillon d’une occurrence et de plusieurs occurrences, doublons et scores ex æquo ;
- bords, coins et dimensions rectangulaires ;
- score local, anticipation de conversion, bonus de faiblesse et priorité inchangés ;
- absence de mutation pendant l’évaluation, mise à jour entre poses et reset ;
- compatibilité des adaptateurs conservés.

Comparer l’ancienne et la nouvelle sélection sur les mêmes états préparés et des générateurs identiques. Un générateur traçant les appels doit vérifier les valeurs de retour ainsi que les types et bornes des tirages. Ne pas se contenter d’un résultat spatial vaguement similaire.

Effectuer un rejeu réel du moteur sur 500/4000, départ vide/rempli, graines 11/22/33, avec ordre explicite des actions. Comparer empreinte spatiale, comptes de couleurs, frontières, actions, tentatives, modifications, conversions, stock, éliminations et cibles prioritaires ; exclure temps et métriques JVM. Préserver les tests existants de pause/stop/reset et de concurrence, sans refonte de ces mécanismes.

Documenter distinctement la suppression des constructions dans le code source et les allocations effectivement observées : une diminution théorique de dix objets à un ne prouve pas une division des allocations globales par dix.

## 4. Banc de mesure avant/après

Réutiliser l’outillage O1/O2, avec un pilote et une instrumentation identiques dans les deux versions. Conserver les références avant/après séparées du code d’analyse. Ne pas exécuter les deux variantes simultanément.

### Charge déterministe : résultat principal

- SMALL 500 × 500 et DEFAULT 4000 × 4000, départ vide et rempli préparés de manière identique via les mécanismes métier.
- Conserver les candidats de conversion créés par la préparation du plateau rempli si telle est la convention du banc ; ne pas stabiliser seulement une variante.
- Même graine de performance, JVM exacte, flags, GC, heap, paramètres frontier, stock, conversions et cadence demandée.
- Chauffer la JVM, puis recréer l’état et les RNG avant mesure. Exclure préparation, demande de GC, empreinte et comparaison du temps métier.
- Fixer un budget d’actions commun avant/après, calibré sur la baseline. Au moins cinq paires dans des JVM neuves, ordre avant/après alterné ; graine 11 pour le principal, 11/22/33 pour les rejeux.
- Vérifier l’état des joueurs avant/après. Si la charge atteint un vainqueur, ne pas présenter tout son débit comme une capacité d’affrontement. Choisir et figer un budget commun se terminant avant cette phase, ou publier séparément le scénario post-victoire. Ne pas déplacer cette limite dans une seule variante.

### Application : résultat complémentaire

Comparer scheduler réel sans polling et avec le même polling qu’O2 : quatre GET concurrents par batch cible d’une seconde, réponses complètes, aperçu inchangé. Trois paires par taille/condition, JVM neuves et chauffe documentée.

**Corriger explicitement le biais découvert dans O2 :** surveiller et enregistrer le passage au vainqueur. Pour la comparaison de capacité utile, utiliser des fenêtres identiques où au moins deux joueurs restent en jeu. Si vingt secondes sont impossibles sur SMALL, choisir un protocole plus court ou un état initial contesté reproductible, le figer des deux côtés et documenter la différence avec O2. Ne pas changer le gameplay pour prolonger la partie.

Une fenêtre ayant déjà atteint la victoire doit être conservée mais marquée non représentative d’un affrontement. Sans horodatage fiable, ne pas recalculer rétrospectivement un débit « avant victoire » depuis des totaux. Un sondage périodique ne donne qu’un intervalle de détection ; déclarer sa précision.

Enregistrer temps actif exact et temps mural. La base des tentatives/s doit être identique avant/après ; utiliser le delta de temps actif pour l’application comme O2. Ne pas comparer directement ce débit aux anciens chiffres O1 basés sur la durée murale du pilote.

## 5. Mesures et analyse attendues

| Mesure | Rôle et précaution |
|---|---|
| Octets alloués/action moteur | Indicateur principal ; delta du thread moteur, même périmètre |
| Profil des allocations de `Position` | Contrôler les piles d’allocation ; poids JFR estimés, pas mémoire retenue |
| Temps d’une charge fixe et tentatives/s | Déterminer si le gain mémoire devient un gain de traitement |
| Temps CPU de la charge fixe | Préciser thread moteur ou processus |
| Modifications/s, conversions et éliminations | Vérifier le travail métier et la phase de jeu |
| GC et heap échantillonnée | Mesures complémentaires ; aucune absence de GC généralisée depuis une fenêtre courte |
| HTTP P50/P95/P99 et erreurs | Client, transfert compris ; ne pas mélanger avec percentiles serveur |

Capturer CPU, allocations et moniteurs JFR avant/après dans des essais distincts des mesures principales. Pour les allocations `Position`, rapprocher le volume du nombre de décisions/tentatives effectuées ; un moteur plus rapide peut allouer davantage par seconde tout en allouant moins par action.

Produire observations individuelles, moyenne, médiane, écart-type et variance d’échantillon par scénario. Ne pas agréger tailles ou graines différentes. Fournir rapports appariés et dispersion ; ne pas revendiquer de significativité sans analyse appropriée.

Formules : accélération = temps avant / temps après ; gain de débit = 100 × (après / avant − 1) ; réduction d’allocations = 100 × (1 − octets/action après / avant). Une baisse de mémoire persistante n’est pas l’objectif présumé ici.

## 6. Rapport et remise pour l’audit final

### Rapport autonome

Écrire `docs/optimisations/03-positions-primitives.md` en français, avec : contexte, références exactes, environnement, hypothèse, diagnostic préalable, changement isolé, validation, protocole, résultats avant/après, analyse et décision. Il doit être compréhensible sans cette conversation.

Ouvrir par une **synthèse d’une page maximum**, directement intégrable au rapport final de cinq à six pages :

1. problème et hypothèse en quelques phrases ;
2. modification concrète et invariants conservés ;
3. petit tableau avant/après : octets/action, débit et temps CPU pour les scénarios principaux ;
4. validation et limites ;
5. décision conserver/rejeter et prochain goulot observé.

Placer ensuite en annexes les statistiques complètes, inventaire des fichiers, logs, protocole détaillé et captures. Fournir graphiques d’allocations/action et débit issus des données, ainsi que vues CPU/allocations réelles annotées. Ne pas fabriquer un flamegraph à partir d’un résumé textuel.

Si la réduction d’allocations est observée sans gain stable de débit, le dire. Si les résultats régressent, conserver les preuves et argumenter le retour arrière ou le compromis. Ne pas ajouter une autre optimisation pour masquer le résultat.

### Matrice du barème

| Critère | Points | Pièces de cette étape |
|---|---:|---|
| Environnement et métrologie | 3 | Matériel, caches disponibles, runtime, protocole, répétitions et statistiques |
| Diagnostic réel | 5 | Profils CPU/allocations avant/après, piles de `Position`, limites du profilage |
| Journal d’ingénierie | 5 | Justification de la réduction d’objets, code isolé, équivalence et décision |
| Échec constructif | 3 | Seulement si une variante réellement mesurée échoue ; jamais une régression inventée |
| Reproductibilité et synthèse | 4 | Script unique, références, CSV/JSON, figures et archive vérifiée |
| Gouvernance IA | +2 | État du fichier dédié et de ses quatre directives, sans présumer le bonus acquis |

Renseigner section/pièce et statut pour chaque critère ; ne pas attribuer une note. O3 traite les allocations, pas à elle seule la scalabilité parallèle, le réseau ou la persistance. Expliquer les outils Java équivalents aux outils Go cités, sans présumer une dispense de l’évaluateur.

### Archive à transmettre

Créer `target/audit-opti-03.zip` : rapport autonome, synthèse courte, données individuelles et dictionnaire des unités, profils originaux et exports, figures, scripts de mesure/analyse, configuration effective, journaux de tests et rejeux, commits, hashes des binaires et patch du changement. Pas de secrets ni de dossier `.git` complet.

Chaque essai identifie variante, commit, binaire, graine, état initial, budget exécuté, chauffe, compteurs début/fin, phase de jeu, durées, instrumentation et erreurs. Le script `scripts/benchmark-opti-03.ps1` doit reconstruire la comparaison et la synthèse en une commande, avec prérequis et commande réellement utilisée documentés.

Vérifier les empreintes SHA-256, les liens après extraction et la cohérence tableaux/données. Distinguer gain marginal face au départ O3 et gain cumulé face à V3 : le second reste non mesuré sans reprise d’un protocole commun. O2 n’ayant pas modifié la production, ne pas lui attribuer une portion du gain.

## 7. Critères de fin

- [ ] Boucle d’échantillonnage et scoring sans construction de `Position` par candidat.
- [ ] Au plus la matérialisation du résultat final dans le chemin optimisé ; contrats existants conservés.
- [ ] Même séquence de tirages, mêmes cibles et mêmes états métier dans les rejeux.
- [ ] Avant/après sur SMALL et DEFAULT, allocations/action et temps de charge fixe mesurés.
- [ ] Biais post-victoire identifié et exclu des conclusions de capacité utile.
- [ ] Rapport autonome, synthèse d’une page, annexes et archive de preuves vérifiées.
- [ ] Conclusion basée sur les résultats réels ; manques et régressions signalés.

Références : sources et preuves de `audit-opti-02.zip`, rapport O1, supports J2_AM (allocations) et J2_PM (métrologie), barème et rapport d’audit d’exemple. La mission contient des hypothèses à tester, aucun nouveau gain mesuré.
