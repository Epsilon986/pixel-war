# Conversion incrémentale

La détection incrémentale remplace le parcours global de la baseline V2 à partir du 9 octobre 2026. Les rapports [V2-baseline.md](V2-baseline.md) et [analyse-performance.md](analyse-performance.md) décrivent l’implémentation antérieure.

## Fonctionnement

`Board.place` enregistre chaque modification effective et marque comme candidats la cellule modifiée ainsi que ses quatre voisins directs intérieurs. Toutes les poses, y compris les conversions et les placements de préparation des tests, utilisent cette méthode. Les placements refusés ou sans changement ne créent aucun candidat.

Une liste d’identifiants de cellules permet de parcourir uniquement les candidats. Un tableau de bits élimine les doublons. Le marquage et l’effacement de chaque candidat prennent un temps constant ; aucune recherche dans le reste du plateau n’est nécessaire.

`NeighborConversion.convert` réalise trois étapes sous le verrou du plateau :

1. Détecter toutes les conversions parmi les candidats, sans modifier les cellules.
2. Effacer les candidats qui viennent d’être examinés.
3. Appliquer les conversions ; ces modifications enregistrent leurs candidats pour la passe suivante.

La séparation entre détection et application conserve les décisions simultanées. Une conversion qui rend une autre cellule convertible ne déclenche donc pas de cascade dans la même passe. Ses effets restent en attente pour une passe ultérieure, même si cette passe n’est précédée d’aucune nouvelle modification directe.

Un reset efface les candidats : le plateau vide ne contient aucune conversion possible. Le suivi appartient au plateau, ce qui évite de mélanger les candidats de plusieurs plateaux. Les appels à `detect` restent sans effet sur le plateau et sur la file de candidats.

## Pourquoi les résultats sont conservés

La règle de conversion dépend uniquement de la couleur de la cellule centrale et de ses quatre voisins directs. Si aucune de ces cinq cellules n’a changé depuis la dernière vérification, la décision ne peut pas changer. Toute modification marque exactement les centres dont la décision peut être affectée. Une cellule convertible détectée lors de la passe est appliquée, puis sa modification marque à nouveau son voisinage.

L’ordre des résultats de `detect` suit désormais l’ordre d’insertion des candidats, plutôt que le parcours ligne par ligne. Les décisions et leur application simultanée ne dépendent pas de cet ordre.

## Coût et limites

La détection coûte O(C), où C est le nombre de candidats distincts depuis la dernière passe. Dix poses isolées créent au maximum cinquante candidats, avant ajout des candidats provenant de la passe précédente, au lieu d’environ seize millions de positions sur le plateau par défaut.

Le tableau de bits utilise environ deux millions d’octets pour un plateau de seize millions de cellules. La liste d’identifiants grandit selon les besoins et conserve sa capacité. Un plateau où presque toutes les cellules changent peut donc demander plusieurs dizaines de mégaoctets supplémentaires et nécessiter une passe proche d’un parcours complet. Les allocations des listes de conversions restent proportionnelles aux conversions détectées.

Le scheduler, le verrou global, les métriques et le déclenchement d’une passe après une action avec des tentatives sont conservés. Les lectures HTTP et l’aperçu peuvent encore limiter le débit après cette optimisation.

## Validation

Les tests comparent les conversions incrémentales à une implémentation indépendante du parcours complet :

- vingt scénarios aléatoires de cent passes avec poses multiples et resets ;
- vingt passes successives sur un damier, sans nouvelles poses, pour vérifier les oscillations et les effets différés ;
- comparaison des décisions, des conversions par couleur, des scores et de toutes les cellules ;
- contrôle des candidats distincts, des bords, des placements sans changement, des appels répétés à `detect` et de l’indépendance des plateaux.

Le script exploratoire suivant compare la détection complète et incrémentale sur les mêmes états d’un plateau presque vide :

```powershell
java -Xmx1g --class-path target/classes scripts/IncrementalConversionProbe.java
```

Il utilise un plateau de 4 000 × 4 000, une graine fixe, dix poses par passe, dix passes de chauffe et trente passes mesurées. La préparation, la comparaison des résultats et l’application sont exclues des chronométrages. Le parcours complet précède toujours la détection incrémentale : cela peut favoriser le cache de cette dernière. Ces résultats ne constituent ni un microbenchmark JMH ni une mesure du débit global de la simulation ; ils concernent une charge clairsemée.

### Résultats du 9 octobre 2026

Validation exécutée sous Windows avec OpenJDK 27, compilation ciblant Java 21 : `mvnw.cmd -B -ntp verify` réussit, avec **36 tests, zéro échec et zéro erreur**. Le JAR exécutable est généré.

Une exécution du script avec `-Xmx1g` a donné :

| Détection | Durée moyenne |
|---|---:|
| Parcours complet de référence | 16,557406 ms |
| Candidats incrémentaux | 0,017863 ms |

Les décisions de conversion étaient identiques pour les quarante passes, chauffe comprise. L’essai confirme la réduction du coût de détection sur ce plateau presque vide ; le débit d’actions du serveur, les plateaux denses et le coût de l’interface nécessitent des mesures distinctes.
