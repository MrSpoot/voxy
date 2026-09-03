# Analyse du projet Voxy

Date: 2026-05-06

## Perimetre

Ce document resume l'etat observe du projet a partir des documents existants et d'une lecture du code source principal. Il ne contient pas de recommandation de prochaine etape ni de roadmap.

Documents consultes:

- `ARCHITECTURE_REVIEW.md`
- `AUDIT_RENDER_PERFORMANCE.md`
- `BENCHMARKING.md`
- `PLAN_AMELIORATION.md`
- `RUNTIME_PROFILING_CHECKLIST.md`
- `pom.xml`
- code source sous `src/main/java`

## Vue generale

`voxy` est un projet Java/LWJGL de moteur ou jeu voxel. La base actuelle couvre deja:

- une boucle applicative avec fenetre, input, monde, gameplay, rendu et profiling;
- un monde divise en chunks;
- un streaming asynchrone de chunks;
- une generation procedurale via `NoiseWorldGenerator`;
- un systeme de meshing avec greedy meshing;
- un rendu OpenGL moderne pour les chunks;
- une couche de gameplay minimale avec deplacement, raycast, destruction et placement de blocs;
- des outils de benchmark et de profiling runtime.

Le projet n'est donc plus seulement un prototype graphique. Il contient deja plusieurs sous-systemes structures autour du monde, du rendu, du streaming et de la mesure de performances.

## Build et environnement

Le projet utilise Maven avec wrapper:

- `mvnw.cmd` pour Windows;
- `mvnw` pour Linux/macOS.

La version Java cible est JDK 25, imposee par le `pom.xml` avec `maven.compiler.release` et `maven-enforcer-plugin`.

Le jar executable attendu est:

```text
target/voxy-0.0.1.jar
```

La classe principale configuree pour le jar shade est:

```text
org.weaw.Game
```

## Organisation du code

Les principaux packages sont:

- `org.weaw`: point d'entree applicatif;
- `org.weaw.engine.graphics`: renderer, pipeline, passes, textures, buffers GPU;
- `org.weaw.engine.input`: gestion des actions clavier/souris;
- `org.weaw.engine.window`: fenetre GLFW;
- `org.weaw.game`: chunks, monde, streaming, meshing, lumiere;
- `org.weaw.game.generation`: generateurs de monde;
- `org.weaw.game.utils`: blocs, registre, builders de mesh, utilitaires voxel;
- `org.weaw.gameplay`: joueur, controller, interaction;
- `org.weaw.runtime`: benchmark, options de lancement, JFR, CSV et resume runtime.

Cette separation donne deja une structure lisible entre moteur, monde, gameplay et runtime.

## Etat du rendu

Le rendu est une des parties les plus avancees du projet.

Elements observes:

- pipeline par passes;
- passes de chunks opaque, cutout et transparent;
- passe outline de bloc cible;
- passes FXAA, fog, tone mapping et HUD;
- debug UI via ImGui;
- `RenderContext` pour partager les ressources;
- `RenderStats` pour exposer des compteurs;
- rendu de chunks en vertex pulling;
- donnees de faces stockees en buffers GPU compacts;
- shaders specialises sous `src/main/resources/shaders`.

Les audits indiquent que plusieurs optimisations importantes ont deja ete realisees:

- greedy meshing;
- reduction forte du nombre de faces;
- mutualisation du `BlockTextureManager`;
- baisse des allocations dans certains chemins chauds;
- instrumentation plus fine des passes et uploads.

## Etat du monde et du streaming

Le monde est centralise dans `World`, qui coordonne:

- `ChunkManager`;
- `WorldStreamer`;
- `WorldGenerator`;
- `WorldLightingSystem`;
- options de runtime comme lumiere dynamique, remesh et unloads.

`WorldStreamer` gere:

- les positions de chunks desirees autour du joueur;
- la generation asynchrone;
- le meshing asynchrone;
- la publication limitee par frame;
- les unloads progressifs;
- les chunks dirty prioritaires;
- l'annulation de taches de chargement obsoletes;
- un budget CPU par update.

Le streaming est deja plus robuste qu'un chargement synchrone simple. Le code contient un systeme de tokens et d'etats internes pour eviter de publier des taches obsoletes.

## Etat des chunks

`Chunk` utilise un stockage optimise:

- mode uniforme quand tout le chunk contient le meme bloc;
- passage en palette quand le contenu varie;
- donnees compactees bit par bit;
- compactage de palette;
- copie de chunk pour le remesh asynchrone;
- index interne des blocs emetteurs de lumiere.

Cette structure est sensible, car elle porte a la fois la memoire du monde, les lectures/ecritures de blocs et une partie de la logique de lumiere.

## Gameplay actuel

Le gameplay est minimal mais deja connecte au monde:

- joueur avec position, yaw et pitch;
- controle via `PlayerController`;
- collisions ou checks de solidite via le monde;
- raycast de bloc;
- destruction de bloc;
- placement de blocs lumineux;
- selection de lampes via la molette;
- synchronisation du bloc cible avec le HUD/rendu.

Le gameplay utilise `World.trySetBlockAtWorld()`, ce qui marque les chunks concernes comme dirty et declenche ensuite le pipeline de remesh.

## Lumiere

Le projet contient un systeme de lumiere dynamique cote monde:

- collecte des chunks a mettre a jour;
- batch limite par frame;
- propagation autour des chunks modifies;
- deltas de lumiere pour synchroniser le rendu;
- flags runtime pour isoler la lumiere dynamique ou les uploads.

La lumiere est integree aux donnees de profiling, ce qui permet deja de mesurer son cout relatif.

## Profiling et benchmark

Le projet dispose d'un mode benchmark documente:

```text
java -jar target/voxy-0.0.1.jar --benchmark
```

Capacites documentees:

- benchmark deterministe;
- seed configurable;
- duree configurable;
- render distance configurable;
- resolution configurable;
- export JFR;
- export CSV frame par frame;
- export JSON de resume;
- flags pour isoler certains sous-systemes.

Les documents indiquent que ces outils ont deja ete verifies sur des runs courts.

## Dependances principales

Le projet s'appuie notamment sur:

- LWJGL;
- OpenGL;
- GLFW;
- STB;
- JOML;
- ImGui Java;
- Logback;
- Lombok;
- Flow Noise;
- JUnit Jupiter API.

Le `pom.xml` contient aussi des dependances natives qui ne semblent pas forcement centrales dans le code actuel, comme `assimp`, `nanovg` et `openal`.

## Tests

Le projet dispose maintenant d'une premiere base de tests JUnit 5 executable avec:

```text
.\mvnw.cmd test
```

La configuration Maven utilise la dependance `junit-jupiter`, ce qui inclut l'API et le moteur d'execution JUnit 5.

Tests actuellement presents:

- `ChunkTest`;
- `ChunkManagerTest`;
- `ChunkMesherTest`.
- `WorldTest`;
- `WorldStreamerTest`.

Ces tests couvrent deja:

- l'etat initial uniforme d'un chunk;
- le passage du stockage uniforme vers le stockage palette;
- le compactage retour vers un chunk uniforme;
- `setAllBlocks()` sur entree uniforme;
- l'independance de `Chunk.copy()`;
- les bornes de coordonnees;
- le marquage de chunks queued dans `ChunkManager`;
- la publication, le remesh et l'unload de chunks;
- un contrat minimal de meshing pour un bloc opaque unique en modes `LEGACY` et `GREEDY`.
- la lecture/ecriture de blocs via `World`;
- le fallback de lecture vers le generateur quand un chunk n'est pas charge;
- le marquage dirty d'un chunk modifie;
- le marquage dirty des chunks voisins lors d'une modification sur une bordure;
- un cycle deterministe `WorldStreamer` couvrant generation, meshing, publication et remesh.

L'etat actuel reste volontairement initial:

- la base de tests existe et passe;
- les tests ne lancent ni OpenGL ni GLFW;
- les zones encore peu couvertes sont `WorldLightingSystem`, `NoiseWorldGenerator`, les cas complexes de meshing et les interactions avec le renderer OpenGL.

## Forces du projet

Les points solides observes sont:

- architecture de rendu deja mature pour un projet voxel;
- streaming asynchrone structure;
- instrumentation runtime avancee;
- mode benchmark reproductible;
- stockage de chunks non trivial et optimise;
- separation croissante entre monde, rendu et gameplay;
- prise en compte des problemes de remesh et de lumiere apres modification de blocs.

## Risques et zones fragiles

Les zones les plus sensibles observees sont:

- faible presence de tests automatises;
- complexite concurrente dans le streaming, les remesh et les deltas d'upload;
- dependance residuelle a des registres ou classes globales comme `BlockRegistry`, `Blocks` et certaines compatibilites historiques;
- classes volumineuses dans l'UI debug et le point d'entree applicatif;
- pipeline de rendu riche, avec certaines abstractions qui peuvent devenir couteuses si elles ne sont pas gardees coherentes;
- cible JDK 25, qui impose un environnement recent pour compiler et verifier le projet.

## Synthese

Voxy dispose actuellement d'une base technique serieuse pour un moteur voxel Java/LWJGL. Le rendu, le streaming, le benchmark et le profiling sont deja bien plus avances que dans un prototype minimal. La partie monde et gameplay existe et commence a etre raccordee proprement au rendu via dirty chunks, remesh et synchronisation de lumiere.

Le projet reste cependant encore fragile sur la verification automatisee et sur les contrats internes entre monde, streaming, meshing, lumiere et rendu. Les classes critiques portent beaucoup de logique et demandent une validation rigoureuse pour accompagner les futures evolutions.
