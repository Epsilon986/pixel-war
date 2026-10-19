# O3 — positions primitives dans la sélection de frontière

## Synthèse intégrable à l’audit final

Pixel War doit traiter DEFAULT (4000 × 4000, 64 fois SMALL) sans modifier la partie simulée. Après O1 (board compact) et O2 (aucun atomique redondant à retirer), O3 vise les objets temporaires de sélection. Le profil O2 DEFAULT avec polling estimait 1 013 007 032 octets d’objets Position, soit environ 966 Mio ; ce poids échantillonné ne prouvait pas un coût exclusif de la boucle.

Le changement tire les mêmes identifiants de frontière, garde le meilleur dans un `int` et évalue avec des coordonnées primitives. Une `Position` est construite à la fin ; exploration, repli, ordre RNG, formule de score, synchronisations et règles sont conservés. Le décodage modulo/division est fait une fois par occurrence, puis pour le gagnant. Les signatures historiques restent des adaptateurs.

| Scénario | Octets/action avant → après | k tentatives/s avant → après | CPU moteur (s) avant → après |
|---|---:|---:|---:|
| 500 empty | 2 755,59 → 1 458,52 | 1 038,12 → 969,32 | 0,49 → 0,53 |
| 500 full | 4 925,75 → 3 630,33 | 757,99 → 768,54 | 0,64 → 0,65 |
| 4000 empty | 4 677,07 → 3 381,17 | 507,94 → 484,63 | 1,96 → 2,11 |
| 4000 full | 5 590,16 → 4 294,62 | 254,85 → 247,88 | 1,00 → 1,03 |

Les moyennes portent sur cinq JVM par variante et scénario, graine 11. Les dispersions figurent en annexe. Validation : 70 tests avant, 73 après ; douze rejeux réels (500/4000, vide/rempli, graines 11/22/33) et toutes les paires moteur/profil vérifient état métier et empreinte spatiale. Les tests tracent les types, bornes, retours et l’ordre RNG contre la stratégie historique figée. Les fenêtres ayant atteint la victoire sont exclues des conclusions de capacité utile (0 essai(s) principal(aux) signalé(s)). Le gain cumulé face à V3 reste non mesuré.

Décision : conserver pour la réduction d’allocations, en signalant les régressions de débit mesurées. Les accès Board et le scoring restent des coûts à examiner dans les profils. Les autres optimisations sont hors périmètre.

