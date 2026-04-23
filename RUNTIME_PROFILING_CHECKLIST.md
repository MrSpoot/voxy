# Runtime Profiling Checklist

Cette checklist liste ce qu'il faut ajouter au projet pour permettre une vraie analyse des points de friction runtime pendant le chargement, le streaming de chunks, le remesh et la lumiere.

L'objectif est simple : rendre les performances observables, reproductibles et comparables d'un run a l'autre.

## Priorite 1 - Indispensable

Statut global :
- fait le 23/04/2026
- build valide avec `.\mvnw.cmd -DskipTests package`
- benchmark valide avec `java -jar target/voxy-0.0.1.jar --benchmark --benchmark-duration=5`
- export JFR verifie dans `target/profile.jfr`
- reserve connue : LWJGL emet encore un warning runtime sur Java 25 (`Unsupported JNI version detected`), sans bloquer le lancement observe

### 1. Ajouter un wrapper de build executable
Description : ajouter `mvnw` ou `gradlew` au repository pour que le projet puisse etre compile et lance de facon reproductible sans dependre d'une installation locale specifique.

Statut : fait

Pourquoi c'est prioritaire :
- permet de lancer le projet ici sans hypothese sur la machine
- evite les ecarts entre environnements
- rend les tests et benchmarks automatisables

Attendu :
- `mvnw.cmd` et `mvnw` ou `gradlew.bat` et `gradlew`
- une commande documentee pour lancer le jeu

### 2. Aligner l'environnement Java avec la version cible
Description : le projet cible actuellement Java 25. Il faut que le runtime et les outils de compilation disponibles correspondent a cette version.

Statut : fait

Pourquoi c'est prioritaire :
- sans ca, impossible de compiler ou verifier proprement les changements
- tout audit runtime est bloque si le projet ne peut pas etre lance

Attendu :
- JDK 25 installe et utilise par defaut pour le projet
- version Java verifiable avec une commande simple

### 3. Ajouter un mode benchmark deterministe
Description : creer un mode de lancement qui fixe le spawn, la seed, la trajectoire camera et la duree du test pour obtenir des mesures comparables.

Statut : fait

Pourquoi c'est prioritaire :
- un test manuel n'est jamais exactement reproductible
- impossible de comparer objectivement deux optimisations si le scenario change a chaque run

Attendu :
- seed fixe
- position joueur/camera fixe
- trajectoire scriptable ou automatique
- duree de benchmark fixe, par exemple `30s`

### 4. Ajouter un export de profil CPU exploitable
Description : generer un profil runtime standard, idealement via Java Flight Recorder, pour identifier les hotspots CPU reels.

Statut : fait

Pourquoi c'est prioritaire :
- les FPS seuls ne disent pas ou le temps est perdu
- il faut un profil natif pour confirmer les points chauds suspects

Attendu :
- export automatique d'un fichier `.jfr`
- chemin de sortie stable, par exemple `target/profile.jfr`
- option de lancement documentee

## Priorite 2 - Fortement recommande

Statut global :
- fait le 23/04/2026
- export CSV verifie dans `target/profiling/runtime-profile.csv`
- 523 lignes observees sur un benchmark de 5 secondes

### 5. Ajouter des timings internes par sous-systeme
Description : mesurer le temps CPU de chaque grande etape du moteur pour savoir quelle partie consomme pendant le streaming.

Statut : fait

Pourquoi c'est important :
- permet de separer generation, meshing, lumiere, uploads GPU et rendu
- rend les regressions visibles tout de suite

Sous-systemes utiles a mesurer :
- generation de chunk
- construction de mesh
- ambient occlusion
- propagation de lumiere
- publication de chunk
- unload de chunk
- upload mesh GPU
- upload lumiere GPU
- temps de `World.update`
- temps de chaque render pass

### 6. Exporter les stats frame par frame en CSV ou JSON
Description : enregistrer les mesures de runtime dans un fichier simple a relire et comparer.

Statut : fait

Pourquoi c'est important :
- permet de corriger avec des donnees
- permet de comparer avant/apres une optimisation
- permet d'identifier les pics et non seulement les moyennes

Attendu :
- fichier dans `target/profiling/`
- une ligne par frame ou par echantillon
- format lisible par Excel, Python ou autre outil externe

Exemples de colonnes utiles :
- `frame`
- `fps`
- `frame_ms`
- `world_update_ms`
- `chunk_gen_ms`
- `chunk_mesh_ms`
- `chunk_light_ms`
- `gpu_mesh_upload_ms`
- `gpu_light_upload_ms`
- `loaded_chunks`
- `queued_tasks`

### 7. Ajouter des compteurs de charge du streaming
Description : enregistrer l'etat des files et des operations en cours pour comprendre si le moteur est sature par la production ou par la consommation.

Statut : fait

Pourquoi c'est important :
- les timings seuls ne suffisent pas toujours
- la saturation vient souvent du volume de travail en attente

Compteurs utiles :
- chunks charges
- chunks visibles
- chunks en attente
- remesh en attente
- uploads en attente
- unloads en attente
- chunks publies par frame
- chunks unload par frame

## Priorite 3 - Tres utile pour aller plus loin

Statut global :
- fait le 23/04/2026
- flags d'isolation verifies sur benchmark
- export resume verifie dans `target/profiling/runtime-summary.json`

### 8. Ajouter des flags pour activer/desactiver des sous-systemes
Description : pouvoir couper selectivement certaines parties du pipeline pour isoler leur cout.

Statut : fait

Pourquoi c'est utile :
- permet de mesurer l'impact exact d'un systeme
- accelere fortement les investigations

Flags conseilles :
- desactiver la lumiere dynamique
- desactiver l'upload lumiere
- desactiver l'AO
- desactiver les remesh
- desactiver les unloads
- desactiver les chunks transparents

### 9. Ajouter des resumes agreges de fin de run
Description : produire un resume avec moyenne, max, min et percentiles sur les donnees du benchmark.

Statut : fait

Pourquoi c'est utile :
- les percentiles montrent les vrais spikes
- les moyennes seules masquent souvent les frames catastrophiques

Attendu :
- moyenne frame time
- max frame time
- p95
- p99
- nombre total de chunks generes
- nombre total de remesh

### 10. Ajouter une documentation de benchmark
Description : documenter comment lancer un benchmark, ou lire les fichiers et comment comparer deux runs.

Statut : fait

Pourquoi c'est utile :
- evite de reinventer la procedure a chaque test
- rend les mesures fiables meme plusieurs semaines plus tard

Attendu :
- commande de lancement
- options disponibles
- emplacement des sorties
- methode recommandee pour comparer deux benchmarks

## Priorite 4 - Confort d'analyse

### 11. Ajouter un overlay debug temps reel dedie au streaming
Description : afficher en jeu un resume compact des couts et des volumes de travail pendant le chargement.

Pourquoi c'est utile :
- permet de voir immediatement ce qui monte pendant les chutes de FPS
- pratique pour les iterations rapides sans ouvrir les fichiers exportes

Infos utiles a afficher :
- frame time
- temps generation
- temps meshing
- temps lumiere
- uploads GPU
- chunks charges
- files de taches

### 12. Ajouter des marqueurs ou phases benchmark nommees
Description : separer explicitement les phases comme "start", "streaming actif", "settle", "fin de run".

Pourquoi c'est utile :
- aide a comprendre si le cout vient du debut du chargement ou de la stabilisation
- facilite la lecture des exports et profils

## Ordre recommande d'implementation

1. Wrapper de build
2. JDK 25 correctement configure
3. Mode benchmark deterministe
4. Export JFR
5. Timings internes par sous-systeme
6. Export CSV/JSON
7. Compteurs de streaming
8. Flags d'isolation
9. Resume agreges
10. Overlay debug streaming

## Definition du minimum viable pour une vraie analyse runtime

Si tu veux debloquer rapidement une vraie session d'analyse, le minimum a ajouter est :

1. `mvnw` ou `gradlew`
2. JDK 25
3. un mode benchmark deterministe
4. un export `.jfr`

Avec seulement ca, on peut deja lancer, profiler, comparer et identifier les hotspots principaux de facon fiable.
