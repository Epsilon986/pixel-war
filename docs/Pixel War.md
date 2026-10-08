# Pixel War

## 1. Présentation du projet

Pixel War est un projet Java orienté principalement vers l'étude de l'optimisation et des performances backend.

Le projet consiste à simuler une partie dans laquelle plusieurs joueurs placent des pixels de leur couleur sur un board partagé.

L'objectif fonctionnel est volontairement simple au départ. De nouvelles règles seront ajoutées progressivement afin d'augmenter la complexité des traitements backend et de permettre plusieurs itérations d'optimisation.

Le projet n'est actuellement pas encore implémenté.

L'application devra être construite progressivement, en commençant par une version fonctionnelle simple avant d'introduire des optimisations.

---

## 2. Objectifs

### Objectif fonctionnel

Simuler une Pixel War opposant plusieurs joueurs sur un board partagé.

Chaque joueur possède une couleur et cherche à occuper la plus grande partie possible du board avec ses pixels.

### Objectif technique principal

Utiliser le projet comme support pour expérimenter différentes stratégies d'architecture et d'optimisation backend.

La première implémentation doit privilégier :

- la simplicité ;
- la lisibilité ;
- la correction fonctionnelle ;
- la mesurabilité.

Elle ne doit pas chercher à être prématurément optimisée.

Les optimisations seront introduites dans des itérations ultérieures afin de pouvoir comparer leur impact.

---

## 3. Stack technique

Backend :

- Java

Le choix du framework, des bibliothèques et des composants techniques supplémentaires n'est pas encore défini.

Toute décision technique structurante non explicitement définie dans ce document doit être considérée comme un choix d'implémentation et non comme une règle fonctionnelle.

---

## 4. Concepts principaux

### Board

Le board représente l'espace de jeu.

Il est composé d'une grille de pixels.

Sa taille doit être configurable afin de permettre des simulations avec différentes charges.

Exemples de tailles possibles :

- petit board pour les tests fonctionnels ;
- board moyen pour le développement ;
- grand board pour les tests de performance.

La taille exacte par défaut reste à définir.

### Pixel

Un pixel correspond à une cellule du board.

Un pixel peut être :

- libre ;
- occupé par la couleur d'un joueur.

Une cellule du board ne peut avoir qu'une seule couleur à un instant donné.

### Joueur

La simulation comporte initialement 4 joueurs.

Chaque joueur possède :

- un identifiant ;
- une couleur unique ;
- un stock de pixels disponibles ;
- un intervalle définissant la fréquence à laquelle il peut effectuer une action de pose.

Le comportement utilisé pour choisir les positions où les joueurs posent leurs pixels reste à définir.

---

## 5. Règles de base

### 5.1 Pose d'un pixel

Un joueur peut poser un pixel sur le board lorsqu'il est autorisé à effectuer une action.

La fréquence de pose est déterminée par un intervalle configurable.

Le comportement exact lorsqu'un joueur tente de poser un pixel sur une cellule déjà occupée reste à définir.

### 5.2 Objectif d'un joueur

Chaque joueur cherche à recouvrir le maximum de cellules du board avec sa couleur.

La condition exacte de victoire et la condition de fin d'une simulation restent à définir.

---

## 6. Stock de pixels

Chaque joueur possède un stock de pixels disponibles.

Lorsqu'un joueur obtient plusieurs pixels dans son stock, il peut les utiliser afin d'effectuer plusieurs poses au cours d'une même action.

Cette mécanique permet de créer ponctuellement des modifications importantes du board.

Les points suivants restent à préciser :

- taille initiale du stock ;
- taille maximale éventuelle ;
- consommation exacte du stock lors d'une pose ;
- comportement lorsqu'un joueur ne possède plus de pixels ;
- nombre maximal de pixels pouvant être posés simultanément.

---

## 7. Règle de conversion par voisinage

Lorsqu'un pixel est entouré sur ses quatre côtés directs par des pixels appartenant à une même couleur, ce pixel prend cette couleur.

Les voisins considérés sont uniquement :

- haut ;
- bas ;
- gauche ;
- droite.

Les diagonales ne sont pas prises en compte.

Exemple :

```text
. R .
R B R
. R .
```

Le pixel bleu `B` est entouré par quatre pixels rouges `R`.

Il devient donc rouge :

```text
. R .
R R R
. R .
```

Le comportement lorsqu'une conversion provoque immédiatement une nouvelle conversion doit être déterminé explicitement lors de l'implémentation de cette règle.

Deux stratégies pourront notamment être étudiées :

- application unique après chaque action ;
- propagation jusqu'à stabilisation du board.

Aucune stratégie n'est encore définie comme règle fonctionnelle finale.

---

## 8. Patterns

Le jeu permettra de définir des patterns de pixels.

Lorsqu'un joueur réalise un pattern donné avec sa couleur, il reçoit des pixels supplémentaires dans son stock.

Exemple conceptuel :

```text
R R R
. R .
. R .
```

La forme exacte des patterns n'est pas encore définie.

Le système devra à terme permettre de définir plusieurs patterns.

Pour chaque pattern devront pouvoir être précisés :

- sa forme ;
- les positions relatives des pixels ;
- le nombre de pixels ajoutés au stock ;
- les règles permettant d'éviter ou non qu'un même pattern soit récompensé plusieurs fois.

La détection de patterns constitue volontairement une fonctionnalité susceptible d'être coûteuse en calcul et pourra faire l'objet d'optimisations spécifiques.

---

## 9. Simulation

La partie doit pouvoir fonctionner comme une simulation autonome.

Les joueurs doivent pouvoir effectuer leurs actions sans intervention humaine afin de générer une charge continue sur le backend.

Le comportement automatique des joueurs reste à définir.

Une première implémentation simple pourra utiliser une stratégie volontairement basique, par exemple un choix de position pseudo-aléatoire.

Cette stratégie ne constitue pas une règle fonctionnelle définitive et pourra évoluer indépendamment du moteur de jeu.

---

## 10. Paramétrage

À terme, les principaux paramètres de simulation devront pouvoir être modifiés sans changer le code métier.

Les paramètres identifiés à ce stade sont notamment :

- largeur du board ;
- hauteur du board ;
- nombre de joueurs ;
- intervalle de pose de chaque joueur ;
- stock initial ;
- règles de stock ;
- patterns actifs ;
- récompenses associées aux patterns.

D'autres paramètres pourront être ajoutés au fil des itérations.

---

## 11. Interface utilisateur

Le projet doit disposer d'un front simple.

Le front n'est pas l'objectif principal du projet.

Son rôle est principalement de permettre :

- la visualisation du board en temps réel ;
- la visualisation de l'évolution de la partie ;
- l'affichage des principaux indicateurs de performance backend.

Le design de l'interface n'est pas prioritaire.

---

## 12. Observabilité et performances

L'application doit être pensée dès le départ afin de permettre la mesure des performances.

La première version n'a pas besoin d'être optimisée, mais elle doit être observable.

Les indicateurs envisagés incluent notamment :

- nombre de poses par seconde ;
- nombre de modifications du board par seconde ;
- temps moyen de traitement d'une action ;
- temps maximal de traitement ;
- nombre de conversions de pixels ;
- nombre de détections de patterns ;
- temps consacré à la détection de patterns ;
- utilisation CPU ;
- utilisation mémoire.

La liste définitive des métriques sera affinée au cours du projet.

---

## 13. Philosophie d'implémentation

Le projet doit être développé de manière itérative.

Il est important de ne pas introduire d'optimisations complexes avant d'avoir une implémentation fonctionnelle et mesurable.

Chaque évolution importante doit idéalement suivre le cycle suivant :

1. implémenter une version fonctionnelle simple ;
2. vérifier son comportement avec des tests ;
3. mesurer ses performances ;
4. identifier un bottleneck ;
5. proposer une optimisation ;
6. implémenter cette optimisation ;
7. comparer les métriques avant et après modification.

Une optimisation ne doit pas être considérée comme bénéfique sans mesure permettant de la comparer à l'implémentation précédente.

Il est donc important qu'après chaque itération, une analyse des performances soit réalisée, pour cela il faudra utiliser différents outils de benchmark ainsi qu'un outil de profiling  

Une analyse type pourra être un profiling sur les fonctions principales, une détections des fonctions critiques, l'application d'un micro benchmark sur ces fonctions

Outil de profiling sûrement intégré dans intelliJ

---

## 14. Itérations envisagées

Les étapes ci-dessous représentent une direction générale et pourront évoluer.

### Itération 1 — Moteur minimal

Objectif : obtenir une première simulation fonctionnelle.

Fonctionnalités :

- board configurable ;
- 4 joueurs ;
- une couleur par joueur ;
- pose de pixels ;
- intervalle entre les actions ;
- simulation automatique simple ;
- état du board consultable.

Aucune optimisation spécifique n'est attendue.

### Itération 2 — Visualisation et métriques

Ajouter :

- front minimal ;
- visualisation du board ;
- métriques backend principales ;
- possibilité d'observer l'évolution de la simulation.

### Itération 3 — Stock de pixels

Ajouter :

- stock par joueur ;
- accumulation de pixels ;
- poses multiples.

### Itération 4 — Conversion par voisinage

Ajouter la règle :

> Un pixel entouré sur ses quatre côtés par des pixels d'une même couleur prend cette couleur.

Mesurer l'impact de cette règle sur le traitement des modifications du board.

### Itération 5 — Patterns

Ajouter :

- définition des patterns ;
- détection des patterns ;
- récompenses ajoutées au stock.

Cette itération doit notamment permettre d'étudier le coût de recherche de patterns sur un grand board.

### Itérations suivantes — Optimisation

Explorer différentes stratégies d'optimisation en fonction des bottlenecks observés.

Les optimisations ne doivent pas être définies prématurément dans les spécifications fonctionnelles.

---

## 15. Principes pour l'agent d'implémentation

Lors de l'implémentation du projet :

- considérer ce document comme la source principale des exigences fonctionnelles ;
- privilégier une implémentation simple avant toute optimisation ;
- ne pas inventer de règle métier lorsqu'elle n'est pas explicitement définie ;
- lorsqu'une règle est ambiguë, isoler le comportement derrière une abstraction facilement modifiable ;
- séparer autant que possible les règles métier des problématiques techniques ;
- conserver le board et le moteur de simulation indépendants de l'interface utilisateur ;
- écrire des tests pour les règles métier avant d'introduire des optimisations ;
- maintenir la possibilité de mesurer les performances des différentes implémentations ;
- éviter les optimisations prématurées ;
- documenter les hypothèses prises lorsqu'un comportement n'est pas encore spécifié.

---

## 16. Points fonctionnels encore à définir

Les éléments suivants ne sont volontairement pas encore figés :

- taille du board par défaut ;
- comportement lorsqu'un pixel est posé sur une cellule déjà occupée ;
- stratégie utilisée par les joueurs pour choisir leurs positions ;
- stock initial d'un joueur ;
- règles précises de consommation du stock ;
- nombre maximal de poses simultanées ;
- définition des patterns ;
- récompenses associées aux patterns ;
- comportement lorsqu'un pattern est reproduit plusieurs fois ;
- ordre d'application des différentes règles ;
- propagation éventuelle des conversions ;
- condition de fin de partie ;
- condition de victoire ;
- métriques de performance définitives.

Ces éléments devront être spécifiés progressivement avant ou pendant les itérations correspondantes.