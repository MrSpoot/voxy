# Audit de performance du rendu

Date: 2026-04-13
Projet: `voxy`
Type d'audit: audit statique du code de rendu et de streaming, sans profilage GPU temps reel

## Etat actuel

Statut global: audit partiellement realise et plusieurs optimisations prioritaires deja implementees.

Optimisations deja faites:

- `greedy meshing` implemente avec conservation d'un mode `LEGACY`
- format GPU et shaders adaptes aux quads greedy de taille variable
- `BlockTextureManager` mutualise au niveau du contexte de rendu
- suppression des allocations `Matrix4f` par chunk dans les passes chunk
- cache/version sur les snapshots de chunks pour eviter les reconstructions inutiles
- reduction des allocations temporaires dans le chemin chaud de calcul de la camera

Resultat mesure sur une scene identique:

- mode `LEGACY`: `193043` faces, `4.66 MB` de mesh buffers
- mode `GREEDY`: `53044` faces, `1.18 MB` de mesh buffers

Gains observes:

- environ `-72.5%` de faces
- environ `-74.7%` de memoire de mesh buffers

## Resume executif

Le renderer de chunks est deja sur une architecture moderne et pertinente pour un moteur voxel simple:

- rendu en `vertex pulling`
- stockage des faces dans un `SSBO`
- generation procedurale des sommets dans le vertex shader avec `gl_VertexID`
- selection de la face via `gl_InstanceID`
- separation nette entre streaming, meshing, rendu et debug stats

Le principal point limitant n'est plus l'absence de `greedy meshing`, car il est maintenant en place et apporte deja un gain tres net. Les prochains points de travail les plus interessants sont des optimisations CPU et architecturelles de second niveau:

- streaming et scheduling
- gestion des ressources GPU a grande echelle
- culling plus agressif
- instrumentation plus fine du temps de meshing et de rendu

## Reponse directe aux questions

### Le rendu actuel est-il en vertex pulling ?

Oui.

Le projet utilise bien du `vertex pulling` pour le rendu des chunks:

- les faces sont encodees dans un `SSBO`
- le shader reconstruit les sommets du quad a partir de `gl_VertexID`
- la face courante est lue via `gl_InstanceID`
- le draw call utilise `glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, instanceCount)`

Cela signifie que le moteur n'utilise pas un VBO classique avec position/uv/normale par sommet pour les chunks.

Ce n'est pas un rendu en `GL_POINTS`. C'est un rendu de quads de faces voxel reconstruits cote shader.

## Ce qui constitue une bonne architecture

### 1. Format GPU compact et pertinent

Le format actuel est bon pour un moteur voxel:

- `2 uints` par face greedy
- aucune duplication de 4 sommets complets cote CPU
- faible volume de donnees transferees au GPU
- pas de configuration complexe de vertex attributes

Ce choix est tres correct et ne constitue pas le premier probleme de performance.

### 2. Separation claire des responsabilites

Le code est bien decoupe:

- `WorldStreamer` gere generation et chargement
- `ChunkMesher` construit les donnees de mesh
- `Mesh` encapsule les buffers GPU
- `RenderPipeline` orchestre les passes
- `RenderStats` expose des compteurs utiles

Cette structure est saine et evolutive.

### 3. Frustum culling deja en place

Le rendu fait deja un culling par chunk avec AABB contre le frustum de la camera.

Ce n'est pas suffisant a lui seul pour scaler fortement, mais c'est une bonne base.

### 4. Pipeline de passes propre

La separation `opaque`, `cutout`, `debug` est logique et permettra d'ajouter plus tard:

- post-process
- transparence plus complexe
- ombres
- occlusion
- GPU-driven rendering

### 5. Reverse-Z deja coherent

Le couple projection/depth test semble pense pour du reverse-Z:

- `glDepthFunc(GL_GREATER)`
- `glClearDepth(0.0)`
- projection avec profondeur inversee

C'est une bonne pratique pour la precision du depth buffer.

## Points qui coutent actuellement en performance

### 1. Le greedy meshing est implemente et valide

Le `ChunkMesher` supporte maintenant:

- un mode `LEGACY`
- un mode `GREEDY`
- un encodage GPU compatible avec des quads de tailles variables

Impact positif observe:

- forte baisse du nombre de faces
- forte baisse de la memoire de mesh buffers
- meilleure base pour augmenter la distance de rendu ou ajouter d'autres systemes

Conclusion:

Cette optimisation est deja faite et constitue l'un des gains majeurs du projet.

### 2. Duplication des textures par passe

Ce probleme a ete corrige.

Le `BlockTextureManager` est maintenant partage au niveau du contexte de rendu.

Impact positif:

- plus de duplication de texture arrays entre passes
- moins de VRAM gaspillee
- architecture plus propre et plus previsible

Conclusion:

Cette optimisation est deja faite.

### 3. Travail CPU inutile a chaque frame dans les passes

Ce probleme a ete en bonne partie corrige.

Ce qui a ete corrige:

- plus de `new Matrix4f()` par chunk visible
- plus de reconstruction complete du snapshot des chunks si rien n'a change
- moins d'allocations temporaires dans le chemin chaud de la camera

Conclusion:

Le hot path de rendu chunk est deja plus propre. Il reste encore des gains possibles, mais le gros du travail evident a ete fait.

### 4. Le streaming retrie trop souvent

`WorldStreamer` recalcule puis trie encore toute la liste des positions desirees a chaque update.

Impact:

- cout CPU qui grimpera vite si les rayons de chargement augmentent
- travail encore fait meme quand le joueur ne change pas de chunk

Conclusion:

Ce point reste a faire et devient plus important maintenant que les optimisations les plus simples ont ete appliquees.

### 5. Render target partage cree mais pas vraiment exploite

Le pipeline cree un `RenderTarget` partage, mais les passes chunk ne bindent pas explicitement ce FBO.

Impact:

- un peu de complexite et de memoire en plus
- pas de benefice clair aujourd'hui

Conclusion:

Soit il faut vraiment rendre dans ce render target, soit simplifier tant qu'il n'est pas utilise.

## Optimisations classiques a faire en priorite

## Priorite 1: gains forts, cout raisonnable

### 1. Implementer un greedy meshing

Statut: fait

Benefices observes:

- `193043 -> 53044` faces
- `4.66 MB -> 1.18 MB` de mesh buffers
- support de quads de tailles variables dans le pipeline `vertex pulling`

Remarque:

Le projet garde maintenant aussi un mode `LEGACY` pour comparaison, debug et regression tests.

### 2. Mutualiser le BlockTextureManager

Statut: fait

Benefices observes:

- moins de VRAM
- moins de duplication d'upload
- logique plus propre

### 3. Eviter les allocations par frame

Statut: en grande partie fait

Optimisations simples:

- eviter `new Matrix4f()` par chunk: fait
- eviter la reconstruction complete des snapshots de chunks si rien n'a change: fait
- eviter de reconstruire inutilement certaines structures temporaires: partiellement fait

Benefices:

- baisse du temps CPU
- baisse de la pression GC

Reste possible:

- nettoyer encore certaines allocations dans les helpers de camera hors chemin critique
- instrumenter les temps CPU par phase pour verifier le gain reel

## Priorite 2: gains utiles apres les bases

### 4. Ajouter un culling de distance plus agressif

Tu fais deja du frustum culling, mais tu peux aller plus loin:

- distance max de rendu par layer
- culling plus dur pour les chunks hors zone d'interet
- eventuellement un culling vertical plus strict

### 5. Mieux gerer les chunks GPU

Si le nombre de chunks augmente fortement:

- reutilisation de buffers GPU
- arene de buffers au lieu de recreer souvent des objets GL
- gestion plus incrementalement orientee ressources

Ce point est toujours pertinent, mais il vient clairement apres les optimisations deja faites.

### 6. Optimiser le scheduler de streaming

Au lieu de recalculer et trier une grande liste chaque frame:

- ne relancer le calcul que quand le joueur change de chunk
- conserver une structure priorisee incrementalement

Ce point est maintenant une cible importante pour le prochain cycle d'optimisation.

## Priorite 3: evolutions plus avancees

### 7. Occlusion culling

A n'implementer qu'apres le greedy meshing et les optimisations CPU simples.

Possibilites:

- occlusion culling CPU simple
- HZB
- occlusion query
- culling GPU compute

### 8. Multi-draw indirect ou rendu GPU-driven

Le choix actuel `vertex pulling + SSBO` prepare bien ce type d'evolution.

Mais ce n'est pas le premier investissement a faire si la base n'a pas encore:

- greedy meshing
- gestion de ressources partagees
- allocations CPU reduites

## Ce que je ne considere pas comme prioritaire a remplacer

### 1. Remplacer le vertex pulling par un pipeline VBO classique

Ce n'est pas prioritaire.

Le format actuel est deja bon. Revenir a un VBO par sommet ne donnera probablement pas le meilleur gain par rapport au travail necessaire.

### 2. Changer le systeme de textures array

Le `texture array` est un bon choix pour un moteur voxel.

Le probleme n'est pas ce choix, mais le fait qu'il soit recree plusieurs fois.

## Diagnostic synthese

Le moteur a une bonne base architecturale.

Le rendu actuel est bien en `vertex pulling`, ce qui est une bonne direction.

Le principal frein de performance n'est plus la quantite de geometrie brute autant qu'avant, car le `greedy meshing` a deja fortement reduit ce cout.

Les points qui restent les plus plausibles sont maintenant:

- travail CPU de streaming et priorisation de chunks
- gestion GPU si le nombre de chunks augmente fortement
- culling plus agressif
- futures features comme la lumiere, l'occlusion ou le rendu GPU-driven

## Plan d'action recommande

### Etape 1

- fait: mutualiser `BlockTextureManager`
- fait: supprimer les allocations et synchronisations evitables les plus evidentes par frame
- fait en bonne partie: ne recalculer certaines structures que quand necessaire

### Etape 2

- fait: implementer un greedy meshing
- fait: adapter le format d'encodage GPU pour supporter des quads de taille variable

### Etape 3

- optimiser la gestion des ressources GPU
- ajouter du culling plus avance
- optimiser le scheduler de streaming
- ajouter plus d'instrumentation sur le temps CPU de meshing et de rendu
- etudier `multi-draw indirect` ou culling GPU si besoin reel

## Conclusion finale

Oui, le rendu actuel est en `vertex pulling`.

Les optimisations initialement prioritaires suivantes sont maintenant deja faites:

1. `greedy meshing`
2. mutualisation des textures GPU
3. reduction d'une bonne partie du travail CPU et des allocations par frame

Les prochaines optimisations les plus pertinentes a faire sont maintenant:

1. optimiser le scheduler de streaming
2. ajouter du culling plus agressif
3. mieux gerer les ressources GPU si le nombre de chunks continue d'augmenter
4. instrumenter plus finement les temps de meshing et de rendu pour guider la suite

Le projet a deja une bonne architecture pour evoluer proprement. Le plus gros potentiel de gain n'est pas de changer de technique de rendu GPU, mais de reduire la quantite de geometrie generee et de mieux factoriser les ressources.
