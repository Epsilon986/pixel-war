# Diagnostic initial — optimisation 01

Écrit avant le changement de représentation, le 18 octobre 2026. Référence expérimentale : `01fdacf`, issue de `edd05c5` et complétée par les six fichiers de métriques non suivis présents dans le workspace utilisateur, puis par un pilote commun. La branche de référence et ces fichiers locaux sont conservés.

Le plateau stocke les couleurs dans `CellState[][]`. Les mutations centrales mettent à jour des totaux, deux index `int[]` de cases vides, quatre index de frontières et les candidats de conversion. L’accès est O(1), synchronisé, avec contrôle séparé de x et y. Le vide est l’enum `EMPTY`.

Les 68 tests existants passent. Quatre calibrations, avec départ vide et rempli sur 500 × 500 et 4000 × 4000, ont fixé les budgets à respectivement 127 000, 78 000, 65 000 et 18 000 actions. Les futures comparaisons emploieront ces mêmes budgets pour les deux versions.

Avec OpenJDK 27, G1, `-Xms2g -Xmx2g`, l’agent Instrumentation mesure 64 080 016 octets pour les seuls tableaux de couleurs DEFAULT : 4 001 tableaux. Les objets enum partagés et les index sont exclus de cette mesure.

Le profil moteur DEFAULT rempli, graine 11, 72 000 actions, sans HTTP, contient notamment ces sommets de pile CPU : `FrontierPlacementStrategy.score` 214 échantillons, `Board.updateFrontiers` 73, `Board.get` 35 et `ArrayList.grow` 19. Ce sont des échantillons exclusifs, pas des temps absolus ni une preuve de cache-misses. Le scoring domine ce profil ; une baisse du volume des couleurs pourrait aider les lectures, mais le calcul d’index et le décodage peuvent aussi coûter du CPU.

Les poids d’allocations JFR sont estimés : environ 211 millions d’octets pour des tableaux `CellState[]`, 99 millions pour des positions et 36 millions pour des `Long`. Ils ne doivent pas être confondus avec la mémoire persistante du tableau de couleurs. Cette mission ne supprime pas les `values()` ou objets temporaires des stratégies.

Les profils moteur et application avec polling sont conservés dans le dossier de campagne, puis inclus dans la remise finale. Le profil moteur seul n’observe logiquement pas la contention HTTP. L’audit final de l’étape précisera les profils applicatifs, les statistiques avant/après et les limites.

Décision expérimentale : conduire le changement isolé vers `byte[]`, puis juger séparément empreinte persistante, débit moteur et comportement HTTP, sans annoncer de gain à ce stade.
