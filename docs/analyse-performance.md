# Analyse du débit d’actions

> Analyse de l’implémentation avant optimisation. La détection incrémentale a depuis été implémentée ; son fonctionnement et les résultats de validation sont décrits dans [conversion-incrementale.md](conversion-incrementale.md).

Analyse du 9 octobre 2026, à partir du code et de la configuration du projet. Le débit d’environ **15 actions par seconde** est une observation rapportée ; aucune mesure du processus en cours n’a été réalisée pour cette analyse.

## Diagnostic principal

Le principal goulot d’étranglement identifié est la **passe globale de conversion exécutée après chaque action avec des tentatives de pose**.

La configuration actuelle dans [application.properties](../src/main/resources/application.properties) définit :

| Paramètre | Valeur |
|---|---:|
| Dimensions du plateau | 4 000 × 4 000 |
| Nombre de cellules | 16 000 000 |
| Intervalle demandé entre les tours | 10 ns |
| Maximum de tentatives par action | 10 |
| Stock | Désactivé |
| Conversions | Activées |
| Dimensions de l’aperçu | 500 × 500 |

Ces valeurs peuvent être remplacées au lancement par un profil Spring, des arguments ou des variables d’environnement. La configuration exposée dans l’interface permet de vérifier les valeurs réellement utilisées.

## Travail effectué à chaque action

Dans [Simulation.java](../src/main/java/org/pixelwar/pixelwar/domain/Simulation.java), `play` réalise les tentatives de pose puis appelle `conversion.convert(board)` lorsque le nombre de tentatives est supérieur à zéro et que les conversions sont activées. Avec le stock désactivé, une action effectue jusqu’à dix tentatives et déclenche une passe.

Dans [NeighborConversion.java](../src/main/java/org/pixelwar/pixelwar/domain/NeighborConversion.java), `detect` parcourt toutes les positions intérieures du plateau :

```java
for (int y = 1; y < board.height() - 1; y++) {
    for (int x = 1; x < board.width() - 1; x++) {
        // Vérification de la couleur et des voisins directs.
    }
}
```

Pour un plateau de 4 000 × 4 000, cela représente **3 998 × 3 998 = 15 984 004 positions examinées par passe**. Ce parcours s’effectue même sur un plateau presque vide et même lorsqu’aucune conversion n’est possible.

Chaque position entraîne au moins une lecture via `Board.get`. Les autres lectures de voisins dépendent des conditions, grâce à l’évaluation conditionnelle des `&&`. `Board.get` est une méthode synchronisée qui vérifie aussi les coordonnées. La détection maintient déjà le verrou du plateau : ces appels sont réentrants, mais leur coût participe au traitement du parcours.

La détection construit ensuite une liste de changements, puis l’application les effectue séparément. Cette séparation garantit l’absence de cascade au cours d’une même passe.

## Pourquoi quatre joueurs ne multiplient pas le débit

Le moteur utilise `Executors.newSingleThreadScheduledExecutor()`. Une seule tâche périodique appelle `playRound`, qui exécute successivement les actions des quatre joueurs encore actifs.

`playRound` est synchronisée, tout comme les lectures d’état, de scores, de métriques et d’aperçu. Les actions et ces lectures partagent donc un verrou global. Les quatre joueurs ne réalisent pas leurs conversions en parallèle ; les requêtes de l’interface peuvent attendre la fin d’un tour et occupent aussi le verrou pendant leurs lectures.

Augmenter simplement le nombre de threads ne supprimerait pas cette sérialisation tant que le verrou global est conservé.

## Interprétation des 15 actions par seconde

Un débit global de 15 actions/s correspond à un budget moyen d’environ :

```text
1 seconde / 15 ≈ 66,7 ms par action
```

Si chaque action déclenche une passe, le moteur examine alors environ :

```text
15 × 15 984 004 ≈ 240 millions de positions par seconde
```

Ce débit est cohérent avec un traitement dominé par le parcours global. Les 66,7 ms représentent un budget de temps actif par action, incluant les autres coûts et interruptions ; ce n’est pas une durée de conversion mesurée.

Une action peut contenir dix tentatives de pose : **15 actions/s peuvent donc représenter environ 150 tentatives/s** avec cette configuration. Les modifications réussies et les conversions sont des compteurs distincts.

La propriété `pixelwar.players.interval-ns=10` définit une cadence demandée au scheduler, pas une durée garantie. Lorsque le traitement d’un tour dépasse la période, les exécutions suivantes sont en retard et ne se chevauchent pas. Réduire encore cet intervalle n’accélère pas les conversions.

Le stock étant désactivé dans la configuration actuelle, la recharge ne limite pas ce débit.

## Coûts secondaires et métriques

L’interface demande périodiquement l’état, les scores, les métriques et un aperçu. L’aperçu actuel contient jusqu’à 250 000 cellules : sa préparation sous verrou, sa sérialisation et son affichage ajoutent du travail. Ils constituent une piste secondaire à mesurer, mais le parcours de conversion reste le coût principal identifié dans le code.

`actionsPerSecond` est calculé comme le nombre total d’actions divisé par le temps actif depuis le dernier reset. Il s’agit donc d’une **moyenne cumulée**, hors pause et arrêt, et non d’un débit instantané.

Le rapport existant [V2-baseline.md](V2-baseline.md) apporte un indice supplémentaire : un ancien essai sur le plateau de 4 000 × 4 000 avait relevé une passe moyenne de 24,34 ms, et les échantillons JFR montraient principalement `Board.get` dans le parcours de voisinage. Ces résultats ont été obtenus dans d’autres conditions et ne prouvent pas la durée des passes du processus actuel.

## Comment confirmer le diagnostic

1. Vérifier la configuration effective dans l’interface : dimensions, conversions activées et stock désactivé.
2. Comparer les métriques **« Passe moyenne / min / max »** et **« Action moyenne / min / max »**. Une passe moyenne proche du budget de 67 ms expliquerait l’essentiel du débit observé.
3. Relancer avec `pixelwar.conversion.enabled=false`, en conservant les autres paramètres, pour mesurer le coût des poses sans conversion. Cet essai change les règles de simulation et sert à isoler le coût des conversions.
4. Comparer des fenêtres de même durée après une chauffe, avec les mêmes conditions de polling. Réinitialiser les compteurs entre les essais ou calculer les deltas de compteurs et de temps actif.
5. Pour une attribution plus précise, enregistrer un profil JFR et examiner le temps CPU dans `NeighborConversion.detect`, les lectures `Board.get`, les attentes de moniteurs et les pauses GC.

## Optimisation prioritaire

La première piste est de remplacer le parcours complet par une **détection incrémentale des cellules susceptibles d’être affectées par les changements**.

Une telle optimisation doit préserver la règle actuelle : détecter les changements sur l’état précédant la conversion, puis les appliquer sans cascade pendant la même passe. Les candidats doivent tenir compte des poses, des conversions précédentes et des changements encore susceptibles de produire un effet lors d’une passe ultérieure. Vérifier uniquement les voisins des nouvelles poses sans conserver ces informations pourrait modifier le comportement.

Les pistes suivantes sont la réduction du coût des accès au plateau pendant les parcours, puis celle du temps passé sous le verrou global pour les lectures de l’interface. Les gains doivent être mesurés ; aucun gain chiffré n’est établi par cette analyse.
