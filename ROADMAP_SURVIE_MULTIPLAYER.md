# Roadmap Survie Multijoueur Post-Apocalyptique

## Vision cible

L'objectif est de faire evoluer Voxy d'un moteur voxel jouable vers un jeu de survie post-apocalyptique multijoueur avec monde persistant, base-building, zombies, loot, exploration et vocal de proximite.

La direction technique recommandee est de structurer le projet autour de trois blocs:

```text
Client
- rendu
- input
- audio
- UI
- interpolation reseau

Simulation / Serveur
- monde source de verite
- joueurs
- entites
- inventaires
- zombies / animaux
- loot
- construction
- sauvegarde
- regles de survie

Moteur monde
- chunks
- generation procedurale
- edition limitee
- streaming
- persistence
```

Meme en solo, le jeu devrait progressivement fonctionner comme si un serveur local simulait la partie. Cela preparera le multijoueur sans devoir reecrire toute la logique gameplay.

## Phase 0 - Stabilisation technique

Objectif: securiser le moteur actuel avant d'ajouter du gameplay lourd.

### Technique

- Clarifier la separation entre `Game`, `World`, `GameplaySession` et `Renderer`.
- Creer une vraie boucle de simulation independante du rendu.
- Introduire un `GameServer` local, meme pour le mode solo.
- Garder le client responsable du rendu, de l'input, de l'audio et de l'UI.
- Reduire progressivement les dependances globales comme `Blocks` et `BlockRegistry`.
- Ajouter une couche de configuration pour blocs, items et entites.
- Conserver et enrichir le profiling existant.
- Ajouter des tests sur les composants critiques.

### Tests prioritaires

- Chunks modifies.
- Streaming.
- Remesh.
- Generation deterministe.
- Contrats de lecture/ecriture de blocs.
- Future sauvegarde de monde.

### A eviter dans cette phase

- Zombies complexes.
- Multijoueur public.
- Villes procedurales geantes.
- Vehicules.
- Vocal de proximite.

## Phase 1 - Socle simulation

Objectif: rendre possible un monde vivant avec des objets, creatures et joueurs simules proprement.

### Gameplay

- Joueur comme entite.
- Zombie comme entite.
- Animal comme entite.
- Objet au sol.
- Coffre.
- Projectile plus tard.

### Technique

- Ajouter un `EntityManager`.
- Ajouter des IDs uniques d'entites.
- Ajouter spawn/despawn.
- Ajouter collision entite/monde.
- Ajouter une premiere spatial partition pour eviter de parcourir toutes les entites.
- Separarer etat simule et etat rendu interpole.
- Mettre en place un tick simulation fixe, par exemple 20 ou 30 ticks/seconde.

### Composants de base

- Position.
- Vitesse.
- Sante.
- Collision.
- Interaction.
- Inventaire.

## Phase 2 - Persistence du monde

Objectif: pouvoir casser, construire, looter et quitter la partie sans perdre l'etat du monde.

### Gameplay

- Sauvegarde des blocs modifies.
- Sauvegarde des entites importantes.
- Sauvegarde du joueur.
- Sauvegarde des coffres.
- Chargement et rechargement d'une partie.

### Technique

- Separarer le monde genere du monde modifie.
- Stocker uniquement les deltas de chunks.
- Utiliser un format simple au depart.
- Prevoir des fichiers par region.
- Compresser les chunks modifies.
- Garder JSON uniquement pour debug si besoin.
- Migrer vers un format binaire quand la structure se stabilise.

Cette phase est prioritaire avant de pousser le base-building. Sans persistence solide, la construction et le loot resteront fragiles.

## Phase 3 - Inventaire, items et loot

Objectif: passer d'un moteur voxel a une vraie boucle de jeu.

### Gameplay

- Inventaire joueur.
- Hotbar.
- Items stackables.
- Items non stackables.
- Coffres lootables.
- Tables de loot.
- Rareté des objets.
- Ressources de base.
- Outils.
- Premiers blocs de construction.

### Ressources initiales

- Bois.
- Pierre.
- Metal.
- Tissu.
- Nourriture.
- Eau.
- Medicaments.

### Outils initiaux

- Hache.
- Pioche.
- Marteau.

### Construction initiale

- Mur en bois.
- Sol.
- Porte.
- Barricade.
- Coffre.

### Technique

- Ajouter `ItemDefinition`.
- Ajouter `ItemStack`.
- Ajouter `Inventory`.
- Ajouter `LootTable`.
- Ajouter une interaction generique: regarder, utiliser, ramasser, ouvrir, construire.

## Phase 4 - Survie hardcore

Objectif: creer une pression constante sur le joueur.

### Gameplay

- Sante.
- Faim.
- Soif.
- Endurance.
- Saignement.
- Infection.
- Maladie simple.
- Medicaments.
- Eau potable et non potable.
- Mort du joueur.
- Respawn ou permadeath selon le mode.

### Plus tard

- Nourriture perissable.
- Temperature.
- Blessures localisees.
- Fatigue.

### Technique

- Systeme de stats joueur.
- Effets de statut.
- Tick de survie separe du rendu.
- Sauvegarde des stats.
- UI minimale et lisible.

## Phase 5 - Zombies MVP

Objectif: obtenir une menace jouable avant de viser les hordes massives.

### Gameplay

- Un type de zombie basique.
- Detection par distance.
- Detection par bruit simple.
- Deplacement vers le joueur.
- Attaque au contact.
- Degats joueur.
- Mort zombie.
- Loot zombie optionnel.
- Spawn autour des zones urbaines.

### Technique

- IA par etats: idle, wander, investigate, chase, attack, dead.
- Pathfinding simple au debut.
- Evitement basique.
- Budget IA par tick.
- Activation uniquement autour des joueurs.
- Despawn controle loin des joueurs.

Ne pas commencer directement par les hordes. Une horde est surtout un probleme de performance, pathfinding, animation et reseau.

## Phase 6 - Generation de structures

Objectif: transformer le monde naturel en monde post-apocalyptique explorable.

### Gameplay

- Routes procedurales.
- Petits batiments.
- Maisons abandonnees.
- Stations-service.
- Entrepots.
- Coffres generes.
- Decoration detruite.
- Points d'interet.

### Destruction visuelle

- Trous dans les murs.
- Vitres cassees.
- Gravats.
- Murs abimes.
- Toits partiellement detruits.

### Zones

- Foret.
- Campagne.
- Petite ville.
- Zone industrielle.
- Ville plus dense plus tard.

### Technique

- Generateur par couches: terrain, routes, parcelles, batiments, destruction, loot, spawns.
- Structures deterministes selon seed.
- Templates de batiments.
- Connexion routes/batiments.
- Placement coherent du loot.
- Spawns lies aux points d'interet.

Il faut commencer par un village propre, puis une petite ville, puis seulement ensuite des villes geantes.

## Phase 7 - Construction de base

Objectif: faire du base-building le coeur du gameplay.

### Gameplay

- Placement de pieces de construction.
- Snap/grid simple.
- Amelioration des pieces.
- Reparation.
- Demolition controlee.
- Portes.
- Coffres.
- Etabli.
- Feu de camp.
- Defenses simples.

### Materiaux

- Bois.
- Pierre.
- Metal.

### Plus tard

- Serrures.
- Ownership.
- Permissions de groupe.
- Stabilite structurelle.
- Pieges.
- Electricite simple.

### Technique

- Differencier blocs naturels et blocs construits.
- Ajouter couts en materiaux.
- Synchroniser construction et persistence.
- Preparer ownership et permissions pour le multijoueur.

## Phase 8 - Armes et combat

Objectif: rendre l'exploration et le loot gratifiants.

### Gameplay

- Arme de melee.
- Arme a feu simple.
- Munitions.
- Rechargement.
- Recul simple.
- Bruit attirant les zombies.
- Durabilite.
- Degats sur zombies et joueurs.

### Plus tard

- Degats localises.
- Accessoires d'armes.
- Armures.
- Differents types de munitions.

### Technique

- Raycast pour armes a feu.
- Projectiles seulement si necessaire.
- Systeme de degats generique.
- Sons 3D.
- Tables de loot adaptees.
- Validation serveur des tirs en multijoueur.

## Phase 9 - Multijoueur

Objectif: passer d'un serveur local a un vrai serveur jouable.

### Gameplay

- Connexion client/serveur.
- Plusieurs joueurs.
- Positions synchronisees.
- Modifications de blocs synchronisees.
- Inventaires serveur.
- Coffres serveur.
- Zombies serveur.
- Chat texte.
- Permissions/admin minimal.

### Technique

- Serveur autoritaire.
- Protocole reseau.
- Snapshots d'etat.
- Delta compression.
- Interpolation client.
- Reconciliation client pour le joueur local.
- Validation serveur des actions.
- Anti-triche basique.

### Validations serveur prioritaires

- Distance d'interaction.
- Vitesse de deplacement.
- Inventaire.
- Degats.
- Placement/destruction de blocs.
- Ouverture des coffres.

Cette phase sera beaucoup plus simple si les phases precedentes ont deja separe client et simulation.

## Phase 10 - Vocal de proximite

Objectif: ajouter l'immersion multijoueur apres le reseau gameplay.

### Gameplay

- Push-to-talk.
- Volume selon distance.
- Spatialisation gauche/droite.
- Mute joueur.
- Parametres micro.
- Indicateur discret de parole.

### Technique

- Capture micro.
- Encodage Opus.
- Transport UDP ou WebRTC.
- Jitter buffer.
- Decodage audio.
- Spatialisation OpenAL.
- Integration avec la position joueur.

Le vocal doit arriver apres le multijoueur de gameplay, pas avant.

## Phase 11 - Hordes, animaux et vehicules

Objectif: enrichir le monde une fois le socle stable.

### Hordes

- Spawner de hordes.
- Migration vers bruit, lumiere ou base.
- Attaque de structures.
- Budget IA strict.
- LOD IA selon distance.
- Zombies simplifiés loin du joueur.

### Animaux

- Spawn par biome.
- Fuite.
- Chasse.
- Loot viande/peau.
- Sons.
- Comportements simples de groupe plus tard.

### Vehicules

- Vehicule simple d'abord.
- Entrer/sortir.
- Carburant.
- Inventaire vehicule.
- Degats.
- Synchronisation reseau.
- Physique simplifiee.

Les vehicules sont a garder tard dans la roadmap. Ils deviennent couteux en multijoueur a cause de la physique, de la prediction et de la synchronisation.

## Ordre recommande

1. Architecture client/simulation/serveur local.
2. Entites.
3. Persistence.
4. Inventaire, items et loot.
5. Survie.
6. Zombie basique.
7. Structures simples.
8. Construction de base.
9. Combat.
10. Multijoueur.
11. Vocal de proximite.
12. Hordes, villes geantes, animaux et vehicules.

## MVP jouable recommande

Le premier objectif concret devrait etre:

```text
Solo local
Monde voxel sauvegarde
Inventaire
Loot dans coffres
Faim / soif / sante
Arbres cassables
Construction de base simple
Un type de zombie
Petits batiments generes
```

Quand ce MVP est fun, stable et sauvegarde, le projet peut passer au multijoueur avec beaucoup moins de risque.

## Risques principaux

- Commencer le multijoueur trop tard sans separation client/simulation.
- Ajouter beaucoup de gameplay avant la persistence.
- Faire des villes geantes avant des petites structures fiables.
- Faire des hordes avant une IA simple performante.
- Ajouter les vehicules avant que le reseau soit stable.
- Faire du vocal avant que les positions joueurs soient correctement synchronisees.

## Principe directeur

Avancer par vertical slices jouables:

```text
Une feature simple
+ persistence
+ tests
+ profiling
+ integration gameplay
```

Chaque systeme doit etre petit au debut, mais pose sur une architecture qui ne bloque pas le multijoueur plus tard.
