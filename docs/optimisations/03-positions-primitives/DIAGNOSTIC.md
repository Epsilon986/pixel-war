# Diagnostic préalable O3

Le code de production de départ est identique à c5a8777 (référence O2 mesurée), vérifié par git diff sur src/main. Les 70 tests avant passent. Le profil O2 DEFAULT avec polling estime 1 013 007 032 octets de PlacementStrategy$Position. Ce poids inclut tous les chemins de cette classe, pas exclusivement l’échantillonnage.

Board.randomFrontierPosition tire déjà un int, puis alloue une Position ; choose le fait jusqu’à dix fois par décision. Hypothèse : supprimer ces candidats matérialisés réduit les allocations/action. La JVM peut éliminer des objets et les coûts du scoring/Board demeurent. Aucun gain de débit, aucune élimination de cache-misses ou baisse de mémoire persistante n’est présumé. Les nouveaux profils avant/après sont séparés du débit principal.
