# Revue d'architecture

Date: 2026-04-14
Projet: `voxy`

## Resume

Le projet a une bonne base technique, surtout sur le rendu voxel. La partie graphique montre deja des choix modernes et relativement propres: pipeline par passes, streaming asynchrone, mutualisation de ressources GPU, instrumentation de debug.

En revanche, l'architecture globale n'est pas encore completement mature pour faire evoluer proprement le projet vers un vrai jeu ou moteur extensible. Le principal probleme n'est pas le rendu, mais le couplage entre rendu, streaming et etat du monde.

En bref:

- le rendu est deja une base solide
- la couche `game/world` est encore trop minimale
- certaines abstractions existent mais ne sont pas encore pleinement traversees
- le projet compile proprement, mais ne dispose pas encore de vraie couverture de tests

## Points forts

### 1. Base de rendu bien structuree

Le systeme de rendu est le sous-ensemble le plus mature du projet.

Points positifs:

- separation en passes `opaque`, `cutout`, `transparent`, `debug`
- orchestration claire via `Renderer` puis `RenderPipeline`
- mutualisation des ressources partagees dans `RenderContext`
- stats de rendu deja exposees
- support d'un debug UI utile via ImGui

Cette partie est deja coherente pour continuer a optimiser ou enrichir le rendu.

### 2. Streaming de chunks deja serieux

Le `WorldStreamer` n'est pas un simple chargement synchrone. Il fait deja:

- generation asynchrone
- publication incrementalement limitee
- file de chunks termines
- logique de chargement et de dechargement

Pour un projet de cette taille, c'est une bonne base.

### 3. Structure de chunk plus avancee que sur un simple prototype

Le modele `Chunk` est bien pense:

- stockage uniforme quand possible
- stockage palette sinon
- compactage de palette
- packing binaire pour limiter la memoire

Ce n'est pas du code jetable. C'est une base exploitable.

### 4. Choix GPU credibles

Le projet part deja sur une direction moderne:

- arene GPU mutualisee
- `vertex pulling`
- draw indirect
- gestion de batchs pour les chunks

Ce sont de bons choix pour un moteur voxel simple a moyen terme.

## Points faibles

### 1. Le rendu pilote encore le monde

C'est le probleme architectural principal.

Aujourd'hui, `Renderer` cree `WorldStreamer` et appelle son `update()` pendant `render()`. Cela veut dire que:

- le streaming depend du rendu
- l'etat du monde depend du framerate
- la simulation et le rendu ne sont pas vraiment separes

Cette direction devient vite limitante si vous voulez:

- une boucle de jeu propre
- du headless
- des tests de logique
- un serveur plus tard
- une simulation independante du GPU

### 2. La couche `World` est encore trop pauvre

`World` encapsule surtout `ChunkManager` et ne joue pas encore le role de source de verite metier.

Symptomes:

- peu de logique de monde
- abstraction tres mince
- methode `containsChunk()` non fiable telle qu'elle est ecrite

Aujourd'hui, `World` n'est pas encore le centre du domaine. C'est un wrapper leger.

### 3. Le maillage depend encore trop de la generation statique

Le meshing des chunks s'appuie encore sur `GenerationEngine::getBlockAtWorld`.

Cela veut dire qu'on reste tres proche d'un monde procedurale reconstitue "a la volee", plutot que d'un monde reellement porte par son etat charge.

Ce point posera probleme des qu'il faudra:

- modifier des blocs
- sauvegarder le monde
- recharger un etat persistant
- gerer les bordures de chunks modifiees
- faire du multijoueur

### 4. Usage important de statiques globaux

Le projet utilise encore plusieurs points globaux:

- `BlockRegistry`
- `Blocks`
- `GenerationEngine`

C'est pratique pour avancer vite, mais cela limite:

- les tests
- les mondes multiples
- la configuration
- l'injection de dependances
- le modding

### 5. Certaines abstractions sont encore inachevees

Le pipeline declare des `RenderTarget`, mais leur usage n'est pas encore completement traverse dans les passes.

Cela donne une impression de bonne architecture, mais avec une partie encore "sur papier". Il faut soit:

- finir l'integration propre des FBOs dans les passes

ou

- simplifier tant que ce n'est pas reellement utile

### 6. Pas de vraie strategie de test

La build Maven passe, mais elle ne valide surtout que la compilation.

Aujourd'hui:

- `src/test` est pratiquement vide
- pas de tests de non regression
- pas de validation des structures critiques

Sur un projet avec packing de chunks, generation et synchro de streaming, c'est une dette importante.

### 7. Quelques classes commencent deja a devenir monolithiques

`DebugImGuiPass` en particulier est deja volumineuse.

Ce n'est pas un probleme critique immediat, mais c'est typiquement le genre de classe qui devient difficile a maintenir si elle continue a grossir.

### 8. Build un peu trop lourde pour l'usage reel

Le `pom.xml` embarque des dependances natives qui ne semblent pas utilisees dans le code actuel, notamment:

- `assimp`
- `nanovg`
- `openal`

Ce n'est pas bloquant, mais cela alourdit la maintenance, le packaging et la lisibilite du projet.

## Est-ce que l'architecture est bonne ?

### Oui, partiellement

Elle est bonne sur la partie rendu et sur la direction technique generale du moteur voxel.

Le projet n'est pas un prototype desorganise. Il y a deja:

- une separation de packages claire
- des sous-systemes identifies
- des optimisations structurelles deja presentes
- une intention d'evolutivite reelle

### Non, pas encore completement

L'architecture n'est pas encore suffisamment stabilisee pour des evolutions plus ambitieuses cote gameplay et monde.

La faiblesse principale n'est pas la performance brute, mais l'absence de frontiere nette entre:

- application
- simulation du monde
- streaming
- rendu

## Priorites de changement

## Priorite 1

Ce sont les changements a faire en premier. Ils auront le plus gros impact sur la qualite de l'architecture.

### 1. Decoupler le streaming du rendu

Objectif:

- sortir `WorldStreamer` de `Renderer`
- faire porter le cycle de mise a jour par la boucle de jeu ou une couche `Game/Engine`

Effet attendu:

- meilleure separation simulation/rendu
- code plus testable
- base plus propre pour le futur

C'est la priorite numero 1.

### 2. Faire de `World` la source de verite du monde

Objectif:

- donner a `World` un vrai role fonctionnel
- y centraliser l'acces coherent aux chunks et aux blocs
- corriger les abstractions encore factices

Effet attendu:

- meilleure coherence metier
- moins de contournements directs
- base plus propre pour sauvegarde, edition et gameplay

### 3. Arreter de mailler contre la generation brute

Objectif:

- utiliser un `WorldBlockProvider` branche sur l'etat reel du monde
- ne plus dependre directement de `GenerationEngine::getBlockAtWorld` pour la coherence du mesh

Effet attendu:

- architecture compatible avec monde modifiable
- meilleure coherence des bordures de chunks
- base plus saine pour la persistence

## Priorite 2

Ce sont les chantiers a engager juste apres le decouplage principal.

### 4. Reduire les etats globaux

Objectif:

- encapsuler la configuration de generation
- limiter les points statiques
- preparer une architecture plus injectable

Effet attendu:

- tests plus faciles
- meilleure extensibilite
- moins de couplage cache

### 5. Ajouter des tests sur les composants critiques

Cibles prioritaires:

- `Chunk`
- `ChunkManager`
- `WorldStreamer`
- `ChunkMesher`
- generation de terrain

Effet attendu:

- securiser les refactors
- eviter les regressions silencieuses
- permettre de faire evoluer l'architecture plus vite

### 6. Clarifier le role des `RenderTarget`

Objectif:

- soit finaliser un vrai pipeline FBO
- soit simplifier l'abstraction si elle n'est pas encore necessaire

Effet attendu:

- architecture plus honnete
- moins de complexite inutile

## Priorite 3

Ce sont les ameliorations importantes, mais non urgentes.

### 7. Decouper `DebugImGuiPass`

Objectif:

- extraire les panneaux ou sous-composants UI
- eviter que cette classe devienne un bloc difficile a maintenir

### 8. Nettoyer le `pom.xml`

Objectif:

- retirer les dependances non utilisees
- garder une base plus lisible et plus legere

### 9. Faire evoluer la synchronisation du `ChunkManager` si necessaire

Aujourd'hui, `synchronized` reste acceptable.

Mais si le projet grossit avec plus de systemes concurrents, il faudra probablement evoluer vers une strategie plus fine:

- separation lecture/ecriture
- snapshots mieux maitrises
- contention reduite

## Plan d'action recommande

### Etape 1

Refactor structurel minimal:

- deplacer `WorldStreamer` hors de `Renderer`
- introduire une vraie phase `update()`
- garder `render()` uniquement pour l'affichage

### Etape 2

Refactor domaine:

- enrichir `World`
- brancher le meshing sur l'etat reel du monde
- preparer les futures operations sur les blocs

### Etape 3

Securisation:

- ajouter des tests unitaires et de non regression sur les structures critiques

### Etape 4

Nettoyage technique:

- simplifier ou finaliser les `RenderTarget`
- decouper les grosses classes
- nettoyer les dependances Maven

## Conclusion

Le projet a deja une bonne base, surtout sur le rendu. Ce n'est pas un chantier fragile ou mal organise.

La vraie prochaine etape n'est pas d'ajouter une optimisation GPU de plus, mais de clarifier l'architecture autour du monde et du cycle de vie de l'application.

Si vous devez choisir une seule direction maintenant, prenez celle-ci:

1. decoupler streaming et rendu
2. faire de `World` la vraie source de verite
3. brancher le meshing sur l'etat reel du monde

Une fois ces trois points corriges, le projet deviendra beaucoup plus evolutif.
