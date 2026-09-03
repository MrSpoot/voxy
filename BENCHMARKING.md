# Benchmarking Voxy

Le streaming sparse des chunks est actif par defaut. Utiliser
`-Dvoxy.sparseChunkStreaming=false` pour comparer avec l'ancien chargement du
cylindre vertical complet. La fenetre de debug memoire affiche les chunks
materialises, les chunks virtuels vides/uniformes, la bulle d'interaction et le
taux de succes du cache de classification.

Le mode peut aussi etre coupe avec `--disable-sparse-streaming`.

## Prerequis

- JDK 25 doit etre utilise pour compiler et lancer le projet.
- Le Maven Wrapper fournit Maven 3.9.16, version minimale acceptee par le build.
- Verifier la version active avec `java -version`.
- Verifier la version vue par Maven avec `mvnw.cmd -version` sur Windows ou `./mvnw -version` sur Linux/macOS.

## Build reproductible

Windows :

```powershell
.\mvnw.cmd -DskipTests package
```

Linux/macOS :

```bash
./mvnw -DskipTests package
```

Le jar lanceable est genere dans `target/voxy-0.0.1.jar`.
Le build selectionne automatiquement les natives Windows, Linux, macOS Intel ou macOS Apple Silicon selon la plateforme.

## Lancer le jeu

```powershell
java -jar target/voxy-0.0.1.jar
```

Le manifeste du jar active l'acces natif requis par LWJGL sous JDK 25. Pour un
lancement direct depuis IntelliJ IDEA ou avec un classpath, ajouter l'option VM
`--enable-native-access=ALL-UNNAMED`.

## Lancer le benchmark deterministe

Le benchmark fixe :

- la seed du monde
- la position initiale
- la trajectoire camera/joueur
- la distance de rendu
- la resolution de fenetre
- la duree de chaque phase

Commande par defaut :

```powershell
java -jar target/voxy-0.0.1.jar --benchmark
```

Comportement par defaut :

- warm-up : `5s`
- chargement stationnaire : jusqu'a convergence, avec timeout a `60s`
- parcours deterministe : `30s`
- stabilisation finale : `10s`
- seed : `1052002`
- spawn : `16,48,48`
- render distance : `16`
- fenetre : `1280x720`
- export JFR automatique : `target/profile.jfr`
- export CSV runtime automatique : `target/profiling/runtime-profile.csv`
- export resume runtime automatique : `target/profiling/runtime-summary.json`

Options utiles :

```powershell
java -jar target/voxy-0.0.1.jar --benchmark --benchmark-warmup=5 --benchmark-loading-timeout=60 --benchmark-duration=45 --benchmark-settle=10 --benchmark-seed=12345 --benchmark-render-distance=20 --benchmark-window=1920x1080 --benchmark-spawn=32,64,32
```

Budgets memoire et hauteur mondiale :

```powershell
java -jar target/voxy-0.0.1.jar --benchmark --memory-cpu-mib=1024 --memory-inflight-mib=128 --memory-gpu-mib=512 --memory-gpu-transient-mib=640 --memory-max-loaded-chunks=32768 --world-min-chunk-y=-4 --world-max-chunk-y=3
```

Sans option, le budget CPU des chunks vaut 35 % du heap JVM, borne entre 384 Mio et 1,5 Gio. Le budget GPU des chunks vaut 512 Mio et le monde couvre les chunks verticaux `-4..3`.

Equivalents via proprietes JVM :

```powershell
java -Dvoxy.benchmark=true -Dvoxy.benchmark.durationSeconds=45 -Dvoxy.benchmark.seed=12345 -Dvoxy.benchmark.renderDistanceChunks=20 -Dvoxy.benchmark.window=1920x1080 -Dvoxy.benchmark.spawn=32,64,32 -jar target/voxy-0.0.1.jar
```

## Flags d'isolation

Pour isoler un sous-systeme pendant un benchmark :

- `--disable-dynamic-lighting` : coupe la collecte et la propagation de lumiere cote monde
- `--disable-light-upload` : coupe l'usage et l'upload de lumiere cote rendu
- `--disable-ao` : coupe l'ambient occlusion pendant le meshing opaque
- `--disable-remesh` : empeche la soumission des remesh asynchrones
- `--disable-unloads` : empeche les unloads de chunks
- `--disable-transparent-chunks` : coupe le meshing transparent et retire la pass transparente du renderer

Exemple :

```powershell
java -jar target/voxy-0.0.1.jar --benchmark --benchmark-duration=5 --disable-dynamic-lighting --disable-ao --disable-transparent-chunks
```

## Profiling JFR

- En mode benchmark, `target/profile.jfr` est exporte automatiquement a la fin du run.
- Pour profiler un run manuel hors benchmark, utiliser `--profile-jfr`.

Exemple :

```powershell
java -jar target/voxy-0.0.1.jar --profile-jfr
```

## Profiling runtime CSV

- En mode benchmark, `target/profiling/runtime-profile.csv` est exporte automatiquement a la fin du run.
- En mode benchmark, `target/profiling/runtime-summary.json` est exporte automatiquement a la fin du run.
- Pour exporter les stats runtime hors benchmark, utiliser `--profile-runtime`.
- Le fichier contient une ligne par frame avec les timings CPU, les compteurs de streaming et les temps de render pass.
- Les timings et compteurs du monde agregent uniquement les ticks reellement executes pendant la frame. Une frame sans tick monde exporte zero pour ces evenements.
- Le CSV inclut aussi des stats par pass pour `opaque`, `cutout` et `transparent` :
  `resident_meshes`, `visible_meshes`, `draw_calls`, `drawn_faces`, `mesh_upload_ms`, `light_upload_ms`.
- Le CSV expose aussi le budget CPU du monde, la memoire reservee aux taches, la memoire GPU des chunks, le nombre de chunks a eclairage compact et la distance demandee/effective.
- Les colonnes sparse ajoutent la cible materialisee, les chunks virtuels, l'equivalent de l'ancien cylindre, le pourcentage evite et les statistiques du cache de classification.
- Les timings `chunk_gen_ms` et `chunk_mesh_ms` agregent du travail fait en threads de fond. Ils peuvent donc depasser le frame time d'une frame isolee.

Exemple :

```powershell
java -jar target/voxy-0.0.1.jar --profile-runtime
```

Le fichier `runtime-summary.json` ajoute :

- moyennes, min, max, p50, p95 et p99
- compteurs lents `>16.67 ms` et `>33.33 ms`
- totaux de chunks generes, meshed, remeshed, publies et unload
- section `sparse_streaming` et etat final de convergence du streamer
- timings normalises par mise a jour monde dans `stage_per_world_update_ms`
- flags d'isolation actifs pendant le run
- repartition du stage dominant hors warm-up entre generation, meshing et lumiere
- temps de convergence et statistiques separees pour `WARMUP`, `LOADING`, `TRAVERSAL` et `SETTLE`
- detail du meshing : snapshot, classification des faces, fusion greedy et construction des buffers
- nombre de builds annules avant publication

## Matrice de reference

Le script Windows execute les distances `16`, `24` et `32`, avec et sans
streaming sparse, trois fois chacune. Chaque run conserve son JFR, son CSV et
son resume JSON dans un dossier distinct :

```powershell
.\scripts\run-benchmark-matrix.ps1
```

Pour valider rapidement le runner sans lancer la matrice complete :

```powershell
.\scripts\run-benchmark-matrix.ps1 -RenderDistances 16 -Repeats 1 -WarmupSeconds 0 -LoadingTimeoutSeconds 10 -TraversalSeconds 2 -SettleSeconds 1
```

Une optimisation du meshing n'est retenue que si la mediane de
`chunk_mesh_ms` progresse d'au moins 10 % sur les trois repetitions, sans
divergence des tests de surface visible LEGACY/GREEDY. Les travaux GPU plus
avances restent differes tant que la memoire GPU est sous 75 % du budget et
que le p99 GPU reste sous 8 ms.

## Comparer deux runs

Methode recommandee :

1. lancer un benchmark de reference
2. relancer le meme benchmark avec un seul flag d'isolation
3. comparer `runtime-summary.json` puis confirmer dans `runtime-profile.csv`

Exemple de comparaison simple :

```powershell
java -jar target/voxy-0.0.1.jar --benchmark --benchmark-duration=5
java -jar target/voxy-0.0.1.jar --benchmark --benchmark-duration=5 --disable-dynamic-lighting
```
