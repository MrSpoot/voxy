# Roadmap des prochaines fonctionnalités

Ce document regroupe les prochaines évolutions importantes de Voxy. Les fonctionnalités sont classées par dépendances techniques : les fondations de sauvegarde et d'interface doivent être prêtes avant d'ajouter des systèmes persistants comme le temps, les entités et les fluides.

## Légende

- `[ ]` À faire
- `[~]` En cours
- `[x]` Terminé
- **P0** : fondation bloquante
- **P1** : fonctionnalité prioritaire
- **P2** : amélioration après la première version jouable

## 0. Fondations : profils, mondes et sauvegardes — P0

### Objectif

Permettre à chaque monde de posséder sa propre configuration et de survivre à la fermeture du client ou du serveur. Cette étape est nécessaire pour l'écran solo, le temps, les entités et la simulation de l'eau.

### Tâches

- [x] Définir un répertoire de données utilisateur distinct des fichiers du dépôt.
- [x] Créer un format versionné de métadonnées de monde contenant au minimum :
  - [x] identifiant interne unique ;
  - [x] nom affiché ;
  - [x] seed ;
  - [x] date de création et dernière ouverture ;
  - [x] version du format de sauvegarde ;
  - [x] configuration de génération et hauteur du monde ;
  - [x] distances de simulation et de rendu par défaut.
- [x] Sauvegarder les modifications de chunks au lieu de conserver uniquement les éditions de la session courante.
- [x] Sauvegarder la position et l'état local du joueur.
- [x] Prévoir dans le format les sections futures pour l'heure du monde, les entités et les fluides.
- [x] Effectuer les écritures de façon atomique afin qu'une interruption ne corrompe pas le monde.
- [x] Ajouter une sauvegarde automatique configurable et une sauvegarde propre à la fermeture.
- [x] Détecter les mondes incompatibles ou corrompus et afficher une erreur sans les écraser.
- [x] Prévoir une stratégie de migration entre les versions du format.

### Critères de fin

- [x] Un monde retrouve sa seed, ses blocs modifiés et la position du joueur après redémarrage.
- [x] Deux mondes possédant des réglages différents ne partagent aucun état.
- [x] Une sauvegarde interrompue conserve la dernière version valide.
- [x] Le serveur dédié peut charger le même format sans dépendance graphique.

## 1. Interface complète du jeu — P0/P1

### 1.1 Navigation et écran titre

- [x] Introduire un état d'application séparant clairement les menus, le chargement et la partie.
- [x] Créer un système de navigation entre écrans avec retour et annulation cohérents.
- [x] Créer l'écran titre avec les actions :
  - [x] **Solo** ;
  - [x] **Multijoueur** ;
  - [x] **Héberger un serveur** ;
  - [x] **Paramètres** ;
  - [x] **Quitter**.
- [x] Gérer correctement le clavier, la souris, le focus, la résolution et le redimensionnement.
- [x] Afficher un écran de chargement avec progression, étape courante, annulation et message d'erreur.

### 1.2 Gestion des mondes solo

- [x] Créer l'écran listant les mondes sauvegardés avec nom, date, seed et version.
- [x] Ajouter les actions jouer, créer, renommer et supprimer.
- [x] Demander une confirmation avant toute suppression et utiliser une opération récupérable si possible.
- [x] Créer l'écran de nouveau monde avec :
  - [x] nom validé et unique ;
  - [x] seed saisie ou générée aléatoirement ;
  - [x] configuration de génération ;
  - [x] hauteur minimale et maximale ;
  - [x] distance de simulation ;
  - [x] distance de rendu initiale ;
  - [x] récapitulatif avant création.
- [x] Lancer le solo via le serveur local existant afin de conserver les mêmes règles qu'en multijoueur.

### 1.3 Multijoueur et hébergement

- [x] Créer l'écran de connexion directe avec nom du joueur, adresse IP ou nom d'hôte, port et distance de vue demandée.
- [x] Valider les adresses IPv4, IPv6 et les noms d'hôte sans bloquer l'interface.
- [x] Afficher les états résolution, connexion, authentification protocolaire, chargement et échec.
- [x] Permettre d'annuler une connexion en cours et de revenir au menu sans laisser de transport ouvert.
- [x] Ajouter une petite liste locale des serveurs récemment utilisés.
- [x] Créer l'écran d'hébergement avec monde existant ou nouveau monde, port, nombre maximal de joueurs et visibilité LAN.
- [x] Conserver les options en ligne de commande pour les tests, les benchmarks et les serveurs dédiés.

### 1.4 Paramètres et menu pause

- [x] Créer des catégories Graphismes, Contrôles, Gameplay et Réseau.
- [x] Séparer les paramètres globaux de la machine des paramètres propres à un monde.
- [x] Sauvegarder les paramètres globaux dans un fichier versionné.
- [x] Ajouter appliquer, annuler et restaurer les valeurs par défaut.
- [x] Permettre de reconfigurer les touches et détecter les conflits.
- [x] Créer le menu pause avec reprendre, paramètres, sauvegarder et quitter vers le titre.
- [x] En solo, suspendre la simulation seulement si aucun client distant n'est connecté au serveur local.

### Critères de fin

- [x] Le jeu démarre sur l'écran titre lorsqu'aucun mode CLI explicite n'est demandé.
- [x] Toutes les fonctions actuelles de lancement solo, hôte et connexion IP sont accessibles sans ligne de commande.
- [x] Une erreur réseau ou de chargement ramène vers un écran utilisable sans fermer le jeu.
- [x] Le serveur dédié reste entièrement headless.

## 2. Temps du monde et cycle jour/nuit — P1

### Objectif

Ajouter une horloge de monde persistante et autoritaire produisant un cycle visuel continu, identique pour tous les joueurs.

### Tâches

- [ ] Créer une horloge serveur indépendante du framerate et basée sur les ticks de simulation.
- [ ] Utiliser une journée de 20 minutes réelles par défaut, avec durée configurable par monde.
- [ ] Sauvegarder l'heure courante et la restaurer au chargement.
- [ ] Synchroniser périodiquement l'heure avec les clients et interpoler visuellement entre deux mises à jour.
- [ ] Ajouter des outils de développement pour régler, accélérer, ralentir et figer l'heure.
- [ ] Faire évoluer la direction et la couleur du soleil au cours de la journée.
- [ ] Ajouter la lune et définir sa trajectoire initiale.
- [ ] Faire varier progressivement :
  - [ ] couleurs du ciel et de l'horizon ;
  - [ ] lumière ambiante et exposition ;
  - [ ] brouillard ;
  - [ ] nuages ;
  - [ ] intensité apparente du skylight.
- [ ] Appliquer un multiplicateur global de lumière solaire au rendu plutôt que recalculer tous les voxels à chaque tick.
- [ ] Gérer des transitions sans saut lors d'une correction de temps reçue du serveur.

### Critères de fin

- [ ] Deux clients voient la même phase de journée avec une dérive imperceptible.
- [ ] Un cycle complet traverse continûment matin, jour, soir et nuit.
- [ ] L'heure reprend correctement après sauvegarde et redémarrage.
- [ ] Le cycle ne déclenche pas de remesh global ni de recalcul complet de l'éclairage voxel.

## 3. Première entité vivante : le mouton — P1

### 3.1 Fondation générique des entités

- [ ] Créer un identifiant stable d'entité attribué par le serveur.
- [ ] Définir les composants minimums : type, position, rotation, vélocité, dimensions, collision et état vivant.
- [ ] Ajouter un gestionnaire serveur pour apparition, mise à jour, désactivation et disparition.
- [ ] Limiter la simulation aux zones actives et gérer les chunks non chargés.
- [ ] Sauvegarder et restaurer les entités avec leur monde.
- [ ] Étendre le protocole avec apparition, snapshot, événement et disparition d'entité.
- [ ] Ajouter interpolation et correction côté client sans téléportations visuelles inutiles.
- [ ] Créer un pipeline de rendu d'entités distinct du rendu des chunks.

### 3.2 Mouton

- [ ] Ajouter un modèle temporaire puis un modèle final de mouton.
- [ ] Ajouter les animations repos, marche et rotation.
- [ ] Définir une boîte de collision cohérente avec le modèle.
- [ ] Ajouter les comportements passifs : attendre, regarder autour de soi et choisir une destination proche.
- [ ] Empêcher le mouton de traverser les blocs, tomber volontairement d'une falaise ou entrer dans une zone non chargée.
- [ ] Faire éviter l'eau profonde dans la première version.
- [ ] Ajouter des règles simples d'apparition sur terrain solide éclairé et de disparition à grande distance.

### 3.3 Pathfinding

- [ ] Construire une représentation navigable à partir des voxels solides et de l'espace libre au-dessus.
- [ ] Implémenter un A* borné pour la marche terrestre avec montée d'une marche et chute limitée.
- [ ] Répartir les recherches sur plusieurs ticks avec un budget serveur strict.
- [ ] Mettre en cache les résultats locaux et les invalider lors d'une modification pertinente du terrain.
- [ ] Recalculer un trajet bloqué sans figer le tick serveur.
- [ ] Prévoir un mode de visualisation de développement pour les nœuds et chemins.

### Critères de fin

- [ ] Un mouton apparaît, marche, s'arrête et contourne un obstacle simple.
- [ ] Son déplacement est autoritaire serveur et fluide sur plusieurs clients.
- [ ] Il ne traverse pas les blocs et ne tente pas de chemin dans un chunk absent.
- [ ] Plusieurs moutons respectent le budget de simulation sans dégrader sensiblement les 30 ticks par seconde.
- [ ] Leur état survit à une sauvegarde et un rechargement.

## 4. Eau dynamique, courants et nage — P1/P2

### 4.1 État et propagation du fluide

- [ ] Séparer le type de fluide de son niveau afin de représenter une source et plusieurs hauteurs d'écoulement.
- [ ] Définir une source stable et des niveaux d'écoulement décroissants.
- [ ] Propager l'eau vers le bas en priorité, puis horizontalement.
- [ ] Recalculer l'écoulement lorsqu'une source ou un obstacle est ajouté ou retiré.
- [ ] Utiliser une file de mises à jour avec un nombre maximal de cellules traité par tick.
- [ ] Gérer la propagation à travers les frontières de chunks et reprendre le calcul lorsqu'un voisin est chargé.
- [ ] Éviter de charger ou générer des chunks uniquement à cause d'une propagation lointaine.
- [ ] Sauvegarder les niveaux de fluide et les mises à jour différées.
- [ ] Garder le serveur autoritaire et répliquer uniquement les deltas nécessaires aux clients.
- [ ] Déduire un vecteur de courant local à partir des différences de niveau et de la direction d'écoulement.

### 4.2 Joueur et entités dans l'eau

- [ ] Détecter séparément les pieds, le corps et la tête immergés.
- [ ] Appliquer une traînée horizontale et verticale dans l'eau.
- [ ] Ajouter une flottabilité progressive selon le volume immergé.
- [ ] Permettre de nager dans la direction regardée et de remonter avec la commande de saut.
- [ ] Gérer proprement l'entrée dans l'eau, la sortie et le passage à la surface.
- [ ] Appliquer les courants au joueur sans rendre les contrôles imprévisibles.
- [ ] Réutiliser les mêmes principes pour les entités capables de flotter.
- [ ] Ajouter plus tard la respiration et la noyade après validation de la nage de base.

### 4.3 Rendu de l'eau

- [ ] Générer une hauteur de surface correspondant au niveau réel du fluide.
- [ ] Créer des surfaces inclinées entre niveaux voisins sans fissures entre chunks.
- [ ] Orienter l'animation et les normales selon le courant local.
- [ ] Conserver le tri transparent, les vagues et les réglages graphiques existants.
- [ ] Mettre à jour uniquement les meshes touchés par un changement de fluide.

### Critères de fin

- [ ] Une source descend, s'étale, se stabilise et se retire correctement quand elle disparaît.
- [ ] La propagation traverse une frontière de chunk sans duplication ni perte d'eau.
- [ ] Le joueur peut entrer, flotter, nager, remonter et sortir de l'eau de façon prévisible.
- [ ] Les clients observent le même état de fluide que le serveur.
- [ ] Une grande propagation reste bornée par le budget de tick et ne bloque pas le rendu.

## 5. Qualité, tests et observabilité — transversal

- [ ] Ajouter des tests unitaires pour chaque format de données et règle de simulation.
- [ ] Ajouter des tests d'intégration solo, serveur local et serveur TCP.
- [ ] Tester les sauvegardes interrompues, anciennes et corrompues.
- [ ] Tester la synchronisation du temps, des entités et des fluides avec latence simulée.
- [ ] Ajouter des compteurs de profiling : entités actives, recherches de chemin, cellules de fluide en attente et temps CPU par système.
- [ ] Définir des budgets configurables pour l'IA, le pathfinding et les fluides.
- [ ] Documenter toute évolution du protocole et incrémenter sa version lorsque nécessaire.
- [ ] Maintenir la compatibilité des modes CLI et du serveur dédié à chaque étape.

## Hors périmètre des premières versions

- Matchmaking centralisé et découverte de serveurs Internet.
- Traversée automatique de NAT.
- Comptes et authentification en ligne.
- IA hostile complexe, combat et reproduction des animaux.
- Simulation de fluide physiquement réaliste ou pression volumétrique complète.
- Météo complète ; elle pourra être construite plus tard sur l'horloge du monde.
