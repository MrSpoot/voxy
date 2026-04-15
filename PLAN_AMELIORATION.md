# Plan d'amelioration

Date: 2026-04-14
Projet: `voxy`

## Objectif

Ce plan priorise les ameliorations structurelles du projet pour fiabiliser la base technique avant d'ajouter de nouvelles fonctionnalites. L'ordre est volontaire:

1. corriger les risques de coherence
2. stabiliser l'architecture
3. rendre le projet testable
4. optimiser ensuite

## Priorite 0 - Correctifs critiques

### 1. Fiabiliser le `WorldStreamer`

Priorite: critique  
Impact: tres eleve  
Effort: moyen

Objectif:
- empecher la publication de chunks devenus obsoletes apres un deplacement rapide du joueur

Actions:
- ajouter une revalidation avant `publishBuiltChunk()`
- ignorer les chunks asynchrones qui ne sont plus dans la zone utile
- distinguer explicitement les etats `queued`, `building`, `ready`, `published`, `obsolete`

Pourquoi:
- aujourd'hui, un chunk termine peut encore etre publie alors qu'il n'est plus desire
- cela peut gonfler la memoire CPU/GPU et rendre l'etat charge moins previsible

### 2. Ajouter un pipeline de chunks "dirty"

Priorite: critique  
Impact: tres eleve  
Effort: moyen a eleve

Objectif:
- garantir qu'une modification de bloc mette bien a jour le mesh et le rendu

Actions:
- ajouter un marquage `dirty` sur les chunks modifies
- declencher un remeshing apres `setBlockAtWorld()`
- gerer aussi les chunks voisins quand une bordure change
- republier les donnees GPU apres remeshing

Pourquoi:
- actuellement, modifier les blocs ne garantit pas une mise a jour du rendu
- c'est un point bloquant pour toute vraie logique de gameplay

### 3. Securiser le cycle de vie des ressources natives

Priorite: critique  
Impact: eleve  
Effort: faible

Objectif:
- eviter les fuites de ressources GLFW/OpenGL/ImGui en cas d'erreur

Actions:
- entourer `Game.run()` avec `try/finally`
- clarifier l'ordre de `cleanup()`
- ajouter des logs plus explicites au demarrage et a l'arret

Pourquoi:
- une exception pendant le boot ou la boucle de jeu peut laisser des ressources natives ouvertes

## Priorite 1 - Base d'architecture

### 4. Clarifier le role de `World`

Priorite: haute  
Impact: eleve  
Effort: moyen

Objectif:
- faire de `World` la vraie source de verite metier

Actions:
- centraliser les operations de lecture/ecriture du monde dans `World`
- limiter l'acces direct a `ChunkManager`
- exposer des operations metier claires: lecture, ecriture, invalidation, chargement

Pourquoi:
- aujourd'hui, `World` coordonne deja mieux qu'avant, mais reste encore trop leger pour servir de frontiere architecturale forte

### 5. Formaliser la chaine monde -> mesh -> rendu

Priorite: haute  
Impact: tres eleve  
Effort: moyen

Objectif:
- rendre explicite le flux de donnees entre simulation CPU et rendu GPU

Actions:
- definir une chaine claire: `block change -> dirty chunk -> meshing -> upload sync -> render state`
- separer proprement les donnees du monde et les donnees de rendu
- eviter les effets de bord implicites dans `ChunkManager`

Pourquoi:
- c'est la condition pour ajouter du gameplay sans multiplier les couplages fragiles

### 6. Reduire les statiques globaux

Priorite: haute  
Impact: moyen a eleve  
Effort: moyen

Objectif:
- rendre le projet plus testable et plus extensible

Actions:
- reduire la dependance a `BlockRegistry.initialize()`
- injecter plus clairement la configuration de generation et de meshing
- conserver `GenerationEngine` seulement comme compatibilite temporaire, puis le supprimer

Pourquoi:
- les statiques globaux compliquent les tests, les variantes de configuration et le support futur de plusieurs mondes

## Priorite 2 - Qualite et testabilite

### 7. Mettre en place une vraie base de tests

Priorite: haute  
Impact: tres eleve  
Effort: moyen

Objectif:
- securiser les refactors et les optimisations

Actions:
- ajouter `junit-jupiter-engine` dans le `pom.xml`
- creer `src/test/java`
- commencer par des tests unitaires sur:
- `Chunk`
- `ChunkManager`
- `ChunkMesher`
- `NoiseWorldGenerator`
- `WorldStreamer`

Pourquoi:
- aujourd'hui, le projet n'a pas de vraie base de tests executable et maintenable

### 8. Tester les invariants de `Chunk`

Priorite: haute  
Impact: eleve  
Effort: faible a moyen

Objectif:
- fiabiliser la structure de stockage la plus sensible du projet

Actions:
- tester les transitions uniforme -> palette -> compactage
- tester les lectures/ecritures packed
- tester les coordonnees limites
- verifier la coherence entre `setAllBlocks()` et `setBlock()`

Pourquoi:
- cette classe est centrale pour les performances et la validite du monde

### 9. Ajouter des tests de contrat sur le meshing

Priorite: haute  
Impact: eleve  
Effort: moyen

Objectif:
- eviter les regressions visuelles et logiques sur les faces generees

Actions:
- comparer `LEGACY` et `GREEDY` sur des cas simples
- tester les frontieres de chunks
- tester la transparence, le cutout et l'AO

Pourquoi:
- le meshing est une couche critique et facile a casser discretement

## Priorite 3 - Performance structuree

### 10. Optimiser la generation de chunk

Priorite: moyenne  
Impact: moyen a eleve  
Effort: moyen

Objectif:
- reduire le cout CPU de generation initiale

Actions:
- construire un tableau brut de voxels
- utiliser `setAllBlocks()` plutot que `setBlock()` voxel par voxel
- mesurer avant/apres avec les stats existantes

Pourquoi:
- la generation actuelle fait beaucoup de travail fin-grain inutile a l'initialisation

### 11. Revoir le `RenderPipeline`

Priorite: moyenne  
Impact: moyen  
Effort: moyen a eleve

Objectif:
- soit assumer un vrai pipeline composable, soit simplifier l'abstraction actuelle

Actions:
- faire consommer effectivement les `RenderTarget` par les passes si l'on garde cette direction
- sinon supprimer les abstractions partiellement utilisees
- preparer explicitement les futurs usages: post-process, composition, depth prepass, shadow map

Pourquoi:
- aujourd'hui, l'abstraction existe mais n'est pas encore pleinement traversee par le code

### 12. Reduire les reallocations et uploads evitables

Priorite: moyenne  
Impact: moyen  
Effort: moyen

Objectif:
- stabiliser le cout GPU et limiter le churn memoire

Actions:
- verifier la strategie de croissance de `ChunkFaceArena`
- verifier les reuploads frequents de `ChunkMultiDrawBatch`
- enrichir les metriques de churn GPU

Pourquoi:
- la base de rendu est deja avancee, mais il faut rendre son cout plus previsible

## Priorite 4 - Evolutivite long terme

### 13. Preparer la persistance du monde

Priorite: moyenne  
Impact: eleve a long terme  
Effort: eleve

Objectif:
- distinguer monde genere et monde reellement modifie

Actions:
- introduire une couche save/load
- stocker les modifications persistantes des chunks
- preparer la serialisation des blocs modifies

Pourquoi:
- sans persistence, le monde reste fortement lie au generateur

### 14. Preparer le multimonde et une eventuelle simulation headless

Priorite: moyenne  
Impact: moyen a long terme  
Effort: moyen

Objectif:
- eviter un futur blocage architectural

Actions:
- reduire encore les couplages entre `engine` et `game`
- separer davantage simulation et rendu
- eviter les dependances globales qui supposent une seule instance de monde

Pourquoi:
- cela facilitera plus tard un mode serveur, des tests headless ou plusieurs mondes simultanes

## Ordre recommande

1. Fiabiliser `WorldStreamer`
2. Ajouter le pipeline de chunks dirty
3. Securiser le cycle de vie natif
4. Mettre en place la base de tests
5. Clarifier `World` et la synchronisation monde -> rendu
6. Optimiser la generation
7. Revoir le `RenderPipeline`
8. Preparer la persistance

## Roadmap proposee

### Sprint 1

- priorite 0 complete

### Sprint 2

- base de tests
- clarification de `World`
- formalisation de la chaine monde -> mesh -> rendu

### Sprint 3

- optimisation de generation
- stabilisation du meshing
- reduction du couplage global

### Sprint 4

- evolution du pipeline de rendu
- preparation persistence
- preparation extensibilite long terme

## Resume executif

Le projet a deja une base de rendu solide, mais sa prochaine etape n'est pas d'ajouter plus d'effets ou plus d'optimisations GPU. La priorite est de rendre robuste le contrat entre monde, streaming, meshing et rendu. Tant que cette chaine n'est pas fiabilisee, chaque nouvelle fonctionnalite risque d'ajouter de la dette technique.
