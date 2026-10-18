# Dictionnaire des mesures

`raw/` conserve les observations individuelles ; `observations.csv` les mesures normalis?es. Les profils et pilotes sont exclus des statistiques principales. `summary.csv` donne n, moyenne, m?diane, ?cart type et variance d??chantillon (diviseur n?1). `pairs.csv` donne apr?s/avant par paire ; une valeur sup?rieure ? 1 indique un d?bit sup?rieur. La variation des moyennes vaut 100 ? (moyenne apr?s / moyenne avant ? 1).

Les dur?es Java utilisent System.nanoTime, en nanosecondes ; les d?bits sont par seconde. CPU moteur et allocations concernent le thread moteur ; CPU applicatif concerne le processus. Les octets allou?s ne sont pas une taille retenue. Le pic de heap est ?chantillonn? toutes les 10 ms. La taille des couleurs est mesur?e par Instrumentation, en octets, hors objets enum partag?s et autres index.

Les percentiles HTTP utilisent le rang sup?rieur (nearest rank), sur les requ?tes concurrentes enregistr?es avec dur?e, statut et erreur. Les p95/p99 sont des statistiques par essai, avec peu de requ?tes ; leur pr?cision est limit?e. La fen?tre applicative exclut pr?paration et ?chauffement. Les comparaisons d?terministes v?rifient tous les ?tats m?tier captur?s et le SHA-256 spatial.

JFR : ExecutionSample est un ?chantillonnage CPU, les poids ObjectAllocationSample sont une estimation des octets allou?s. Les nombres de samples ne sont pas des dur?es exactes. Les moniteurs affich?s ont un seuil de 1 ms. La dur?e totale des collections GC et les pauses GCPhasePause sont deux mesures distinctes. Les tailles, budgets et param?tres exacts sont dans les JSON et le pilote Java.
