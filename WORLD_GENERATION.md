# Génération procédurale du monde

## 1. Objectif général

Le but du système de génération n’est pas simplement de produire un terrain à partir de bruit procédural.

L’objectif est de générer une géographie cohérente à grande échelle, capable de produire naturellement :

- de vastes continents ;
- de grands océans ;
- des plaines étendues ;
- des plateaux ;
- de longues chaînes de montagnes ;
- des sommets pouvant atteindre plusieurs milliers de blocs d’altitude ;
- des vallées ;
- des bassins ;
- des lacs ;
- des ruisseaux ;
- des rivières ;
- de grands fleuves ;
- des réseaux hydrographiques cohérents ;
- des climats régionaux ;
- des biomes dépendant réellement de la géographie ;
- des transitions progressives entre environnements ;
- une géologie locale variée ;
- un terrain visible à très grande distance grâce à un système de LOD.

Le monde doit donner l’impression d’avoir été façonné par des processus naturels, même si les simulations utilisées restent simplifiées.

Le principe fondamental est donc :

```text
Le bruit procédural ne génère pas le monde.

Le bruit procédural ajoute du détail à un monde déjà structuré.
```

L’architecture doit fonctionner sur plusieurs échelles.

```text
GÉOGRAPHIE MONDIALE
        ↓
TOPOGRAPHIE
        ↓
HYDROLOGIE
        ↓
CLIMAT
        ↓
ÉCOSYSTÈMES
        ↓
GÉOLOGIE / SOL
        ↓
DÉTAIL LOCAL
        ↓
VOXELS
```

---

# 2. Limites du système actuel

Le système actuellement utilisé repose principalement sur du bruit de type Perlin avec plusieurs octaves.

Exemple simplifié :

```java
height =
    noise(x, z)
    + noise(x * 2, z * 2) * 0.5
    + noise(x * 4, z * 4) * 0.25;
```

Cette technique fonctionne correctement pour produire :

- des collines ;
- des variations locales ;
- un terrain irrégulier.

Elle fonctionne beaucoup moins bien pour produire une géographie crédible.

Les principaux problèmes sont :

- aucune structure continentale réelle ;
- montagnes distribuées arbitrairement ;
- absence de chaînes montagneuses cohérentes ;
- rivières indépendantes du relief ;
- absence de véritables bassins hydrographiques ;
- biomes liés artificiellement au bruit ;
- difficulté à créer de très grandes structures ;
- impression de terrain bruité plutôt que naturel.

Le nouveau système doit donc séparer les grandes structures géographiques du détail local.

---

# 3. Architecture générale

La génération est organisée autour de plusieurs niveaux de résolution.

```text
WORLD SCALE
    ↓
MACRO SCALE
    ↓
REGIONAL SCALE
    ↓
LOCAL SCALE
    ↓
VOXEL SCALE
```

Chaque niveau contient uniquement les informations nécessaires à son échelle.

---

# 4. Génération multi-résolution

## 4.1 Niveau macro

Le niveau macro décrit la géographie générale.

Une cellule macro peut représenter par exemple :

```text
128 × 128 blocs
```

ou :

```text
256 × 256 blocs
```

Une cellule macro peut contenir :

```java
class MacroCell {

    float baseElevation;

    float continentalness;

    float mountainStrength;

    float temperature;

    float humidity;

    float rainfall;

    float waterFlow;

    float erosion;

    float slope;

    TerrainType terrainType;

    ClimateType climate;

    GeologyType geology;
}
```

Les cellules macro sont utilisées pour :

- les continents ;
- les océans ;
- les chaînes montagneuses ;
- l’altitude générale ;
- les bassins ;
- les rivières ;
- le climat ;
- les biomes ;
- le terrain lointain.

À ce niveau, aucun voxel n’est généré.

---

# 5. Macro-régions

Le monde est découpé en grandes régions logiques.

Exemple :

```text
MacroRegion = 8192 × 8192 blocs
```

ou :

```text
MacroRegion = 16384 × 16384 blocs
```

Une macro-région contient une grille beaucoup plus petite.

Par exemple :

```text
MacroRegion : 16384 blocs
Résolution : 256 blocs

16384 / 256 = 64
```

Donc seulement :

```text
64 × 64 = 4096 cellules
```

sont nécessaires pour représenter la structure générale de cette région.

Cela permet de réaliser des traitements relativement complexes pour un coût CPU très faible.

---

# 6. Continentalness

La première couche définit la séparation entre océans et continents.

On génère un champ continu :

```text
continentalness ∈ [-1 ; 1]
```

Exemple :

```text
-1.0     océan profond
-0.6     océan
-0.2     plateau continental
 0.0     côte
 0.3     plaine
 0.7     intérieur continental
 1.0     cœur continental
```

Le bruit utilisé ici doit être extrêmement basse fréquence.

On cherche des structures mesurant plusieurs dizaines de kilomètres.

Le bruit peut être combiné avec :

- Simplex Noise ;
- OpenSimplex ;
- FBM ;
- Voronoi ;
- domain warping.

Exemple :

```java
double continentalness =
    continentalNoise.sample(
        x * CONTINENT_SCALE,
        z * CONTINENT_SCALE
    );
```

Cette couche détermine notamment :

```text
océan
↓
plateau continental
↓
côte
↓
plaines intérieures
```

---

# 7. Plaques tectoniques simplifiées

Pour éviter que les montagnes apparaissent arbitrairement, le monde peut contenir des plaques tectoniques procédurales.

Il ne s’agit pas d’effectuer une simulation géologique scientifique complète.

L’objectif est uniquement de produire une structure convaincante.

Une plaque peut contenir :

```java
class TectonicPlate {

    int id;

    Vec2 movement;

    boolean continental;

    float elevationBias;
}
```

Les plaques peuvent être générées à partir d’un Voronoi.

```text
          PLAQUE A
      ┌─────────────┐
      │             │
──────┼─────────────┼────
      │             │
      │  PLAQUE B   │
      └─────────────┘
```

Chaque plaque possède un vecteur de mouvement.

---

# 8. Frontières tectoniques

Les interactions entre plaques influencent le relief.

## Convergence

```text
→ → → | ← ← ←
      ^^^^^^^
```

Création probable :

- chaînes montagneuses ;
- hauts plateaux ;
- relief accidenté.

## Divergence

```text
← ← ← | → → →
      \_____/
```

Création probable :

- vallées ;
- rifts ;
- bassins.

## Cisaillement

```text
↑ ↑ ↑ | ↓ ↓ ↓
```

Création possible :

- relief fracturé ;
- vallées étroites ;
- ruptures géologiques.

Encore une fois, aucune simulation physique complexe n’est nécessaire.

On cherche seulement à obtenir une logique géographique cohérente.

---

# 9. Chaînes montagneuses

Les montagnes ne doivent pas provenir directement d’un bruit haute amplitude.

Éviter :

```java
height = noise(x, z) * 8000;
```

Cela produirait simplement d’énormes bosses irrégulières.

Une chaîne montagneuse doit posséder une structure.

Elle peut être représentée par :

- une frontière tectonique ;
- une spline ;
- un ensemble de segments ;
- un champ de distance.

Exemple :

```java
double distance =
    distanceToMountainAxis(x, z);

double mountainFactor =
    1.0 - clamp(distance / mountainWidth, 0, 1);
```

Puis :

```java
mountainHeight =
    mountainFactor
    * mountainFactor
    * maxMountainElevation;
```

On ajoute ensuite du bruit pour produire les crêtes.

```java
mountainHeight +=
    ridgeNoise(x, z)
    * mountainFactor
    * detailStrength;
```

On obtient ainsi :

```text
                 /\
          /\    /  \        /\
     /\  /  \__/    \  /\  /  \
____/  \/             \/  \/    \____
```

plutôt que :

```text
_/\/\_/\/\/\_/\/\_/\/\/\/\_
```

---

# 10. Échelles verticales

Le moteur ne doit pas être limité aux proportions traditionnelles de Minecraft.

Exemple de distribution possible :

```text
océan profond         -1000 → -200
fonds côtiers          -200 → 0
plaines                  20 → 300
collines                300 → 800
plateaux                500 → 1500
montagnes              1000 → 3500
hautes montagnes       3000 → 6000
pics exceptionnels     6000 → 9000+
```

Ces valeurs ne sont que des exemples.

Elles doivent rester configurables.

---

# 11. Heightmap macro

Toutes les couches précédentes sont combinées dans une carte d’altitude macro.

Exemple :

```java
double macroHeight =
      continentalHeight
    + tectonicHeight
    + mountainHeight
    + plateauHeight
    + basinHeight;
```

Cette carte ne contient encore aucun détail local.

Elle représente la géographie générale.

---

# 12. Hydrologie

Les rivières ne doivent pas être générées avec un bruit procédural indépendant.

Mauvaise approche :

```java
if (riverNoise > 0.9) {
    createRiver();
}
```

Une rivière doit être la conséquence du relief.

---

# 13. Direction d’écoulement

Pour chaque cellule macro, on détermine vers quelle cellule adjacente l’eau descend.

Exemple :

```text
9 8 7
8 6 5
7 4 2
    ↓
```

Chaque cellule obtient :

```java
int flowDirection;
```

ou directement une référence vers la cellule aval.

---

# 14. Accumulation de flux

Ensuite, on calcule combien de cellules contribuent à chaque point.

```text
 \   /
  \ /
   |
 \ |
  \|
   |
   |
```

Chaque branche augmente le débit en aval.

Cela produit un réseau naturellement hiérarchisé :

```text
ruisseau
   ↓
rivière
   ↓
rivière principale
   ↓
fleuve
```

Une cellule peut contenir :

```java
float flowAccumulation;
```

Plus cette valeur est élevée, plus le cours d’eau est important.

---

# 15. Génération des rivières

Une rivière apparaît lorsque :

```java
flowAccumulation > riverThreshold
```

La largeur peut dépendre du débit :

```java
riverWidth =
    BASE_WIDTH
    + Math.log(flowAccumulation)
    * WIDTH_FACTOR;
```

La profondeur également :

```java
riverDepth =
    Math.log(flowAccumulation)
    * DEPTH_FACTOR;
```

Ainsi :

```text
petit cours d'eau

 \_/


rivière

 \____/


grand fleuve

 \____________/
```

---

# 16. Lacs et dépressions

Une difficulté importante est la présence de dépressions.

Exemple :

```text
     \       /
      \_____/
```

L’eau ne peut plus descendre.

Deux solutions sont possibles :

- transformer cette zone en lac ;
- remplir virtuellement le bassin jusqu’au point de débordement.

Un algorithme de type Priority Flood peut être utilisé.

On obtient :

```text
      ~~~~~~~
     /       \
____/         \____
                \
                 \ rivière
```

Les lacs sont donc eux aussi dérivés naturellement du relief.

---

# 17. Érosion fluviale

Les cours d’eau peuvent modifier légèrement la topographie.

Exemple :

```java
height -= riverInfluence
        * erosionStrength;
```

Cela permet de creuser :

- lits de rivières ;
- vallées ;
- canyons ;
- plaines fluviales.

Les grandes rivières peuvent influencer une zone plus large.

---

# 18. Érosion générale

Une simulation hydraulique complète serait très coûteuse.

Elle n’est pas nécessaire.

On peut utiliser plusieurs approximations.

## Érosion basée sur le drainage

Les zones avec beaucoup de flux sont davantage creusées.

```java
erosion =
    log(flowAccumulation)
    * erosionFactor;
```

## Érosion thermique

Si une pente dépasse un angle maximal :

```java
if (slope > TALUS_THRESHOLD) {
    moveMaterialDownhill();
}
```

Cela limite les pics artificiellement abrupts.

Quelques passes suffisent généralement.

---

# 19. Climat

Une fois le relief et l’hydrologie calculés, on peut générer le climat.

Le climat dépend principalement de :

- latitude ;
- altitude ;
- proximité de l’océan ;
- humidité ;
- vents dominants ;
- précipitations ;
- montagnes.

---

# 20. Température

La température peut être calculée à partir d’une température régionale puis corrigée avec l’altitude.

Exemple :

```java
temperature =
    latitudeTemperature
    - elevation * ALTITUDE_COOLING;
```

Exemple visuel :

```text
0 m       28°C
1000 m    21°C
2000 m    14°C
3000 m     7°C
4000 m     0°C
5000 m    -7°C
```

Une seule montagne peut donc traverser plusieurs zones climatiques.

---

# 21. Humidité et océans

Les océans produisent une source d’humidité.

Une approximation simple peut propager cette humidité sur le continent.

```text
OCÉAN

~~~~~~~ → → → → continent
```

Plus une région est éloignée de la mer, plus l’humidité peut diminuer.

---

# 22. Vents dominants

Le monde peut posséder une direction générale des vents.

Par exemple :

```text
Ouest → Est
```

L’humidité se propage donc principalement dans cette direction.

---

# 23. Ombre pluviométrique

Les montagnes peuvent bloquer une partie de l’humidité.

```text
océan
~~~~~~~

→ → → air humide

          /\
         /  \
        /    \
     pluie    \ air sec
     ↓↓↓↓      \
```

Le côté exposé au vent reçoit davantage de pluie.

Le côté opposé devient plus sec.

On peut obtenir naturellement :

```text
océan
↓
forêt humide
↓
montagne
↓
forêt clairsemée
↓
steppe
↓
désert
```

Une approximation suffit :

```java
if (terrainRises) {

    float rain =
        humidity
        * upliftFactor;

    rainfall += rain;

    humidity -= rain;
}
```

---

# 24. Biomes

Un biome ne doit pas générer le relief.

Le biome doit être une conséquence du relief et du climat.

Principe :

```text
géographie
    ↓
climat
    ↓
écosystème
```

---

# 25. Séparation terrain / climat / écosystème

Il est préférable de ne pas stocker toutes les combinaisons dans une seule enum.

Éviter :

```java
TEMPERATE_MOUNTAIN_FOREST
COLD_MOUNTAIN_FOREST
WARM_MOUNTAIN_FOREST
TEMPERATE_HILLY_FOREST
```

À la place :

```java
Environment {

    TerrainType terrain;

    ClimateType climate;

    MoistureType moisture;

    EcologyType ecology;

    GeologyType geology;
}
```

Exemple :

```text
Terrain   : MOUNTAIN
Climate   : COLD
Moisture  : HUMID
Ecology   : CONIFEROUS_FOREST
Geology   : GRANITE
```

---

# 26. Classification climatique

Une première classification peut utiliser uniquement :

```text
température
+
humidité
```

Exemple :

```text
                    HUMIDITÉ

                 faible   moyenne   forte

chaud            désert   savane    forêt tropicale

tempéré          steppe   prairie   forêt tempérée

froid            toundra  taïga     forêt froide
```

---

# 27. Influence de l’altitude

L’altitude peut modifier l’écosystème local.

Une montagne peut donc devenir :

```text
           neige
            /\
           /  \
          /alpin\
         /      \
        /conifère\
       /          \
      / forêt      \
_____/______________\_____
```

Il n’est donc pas nécessaire d’avoir un biome unique couvrant toute la montagne.

---

# 28. Influence des rivières

Les rivières peuvent augmenter l’humidité locale.

```java
humidity +=
    riverInfluence(distanceToRiver);
```

Une zone sèche peut alors avoir :

```text
.............
.....🌳......
....🌳│🌳.....
...🌳 │ 🌳....
....🌳│🌳.....
.............
```

Cela crée naturellement des couloirs de végétation.

---

# 29. Transitions de biome

Les frontières de biome ne doivent pas être brutales.

Éviter :

```text
FOREST FOREST FOREST | DESERT DESERT DESERT
```

Utiliser plutôt des valeurs continues.

Exemple :

```text
humidity

0.75
0.68
0.60
0.51
0.44
0.32
0.21
```

On obtient :

```text
forêt dense
↓
forêt
↓
forêt claire
↓
prairie
↓
steppe
↓
zone sèche
```

---

# 30. Poids de biome

Au lieu de choisir immédiatement un biome unique :

```java
Biome biome;
```

on peut utiliser plusieurs poids :

```java
BiomeWeights {
    float forest;
    float plains;
    float steppe;
}
```

Exemple :

```text
forest = 0.70
plains = 0.30
```

Cela permet d’ajuster progressivement :

- densité d’arbres ;
- quantité d’herbe ;
- couleur du sol ;
- arbustes ;
- plantes.

---

# 31. Géologie

La géologie est indépendante du biome.

Une région peut être :

```text
granite
limestone
basalt
slate
sandstone
```

Une montagne granitique et une montagne calcaire doivent avoir des apparences différentes même si leur forme générale est similaire.

Exemple :

```text
MONTAGNE A

géologie : granite
humidité : forte
température : froide

→ granite
→ mousse
→ terre sombre
→ pins
```

contre :

```text
MONTAGNE B

géologie : limestone
humidité : faible
température : chaude

→ calcaire
→ sol sec
→ herbes
→ arbustes
```

---

# 32. Détail régional

La carte macro donne uniquement la structure.

On ajoute ensuite du détail à moyenne échelle.

Exemple :

```text
macro :
1 valeur / 256 blocs

regional :
1 valeur / 16 blocs
```

Cette couche ajoute :

- collines secondaires ;
- petites vallées ;
- irrégularités de pente ;
- falaises locales ;
- variations de terrain.

---

# 33. Domain warping

Le domain warping est utile pour casser les formes trop régulières.

Exemple :

```java
double warpX =
    noise(x * 0.001, z * 0.001)
    * 500;

double warpZ =
    noise(x * 0.001 + 1000, z * 0.001 + 1000)
    * 500;

double value =
    noise(
        x + warpX,
        z + warpZ
    );
```

Cela permet de rendre plus organiques :

- côtes ;
- vallées ;
- chaînes montagneuses ;
- transitions.

---

# 34. Génération locale

Une fois proche du joueur, les informations macro sont interpolées.

Exemple :

```java
double macroHeight =
    macroMap.sampleInterpolated(x, z);

double regionalDetail =
    regionalNoise.sample(x, z)
    * regionalStrength;

double localDetail =
    localNoise.sample(x, z)
    * localStrength;

double finalHeight =
      macroHeight
    + regionalDetail
    + localDetail;
```

---

# 35. Génération voxel

Seulement à courte distance du joueur, le moteur génère réellement :

- blocs ;
- caves ;
- matériaux ;
- arbres ;
- végétation ;
- structures ;
- eau ;
- ressources.

Le générateur de chunks ne doit pas recréer la géographie.

Il consulte la géographie existante.

```text
MacroWorld
    ↓
ChunkGenerator
    ↓
voxelisation
```

---

# 36. Génération des grottes

Les grottes peuvent rester indépendantes du système de surface dans une première version.

Elles peuvent utiliser :

- bruit 3D ;
- cellular noise ;
- worm tunnels ;
- density fields.

Mais certains paramètres peuvent dépendre de la géologie.

Exemple :

```text
calcaire
→ cavernes plus grandes

granite
→ réseaux plus fracturés

zones volcaniques
→ tunnels de lave
```

---

# 37. LOD et terrain distant

Le moteur doit permettre d’observer le monde à très grande distance.

Il ne faut pas générer les vrais voxels pour cela.

Le terrain lointain utilise directement les données macro.

Architecture :

```text
                    WORLD DATA
                       |
             +---------+---------+
             |                   |
        LOD Renderer        Chunk Generator
             |                   |
       Macro Height          Macro Height
                                  +
                            Regional Detail
                                  +
                              Voxels
```

---

# 38. Niveaux de détail possibles

Exemple :

```text
0 → 512 blocs
voxels complets

512 → 2048 blocs
1 sommet / 4 blocs

2 → 8 km
1 sommet / 16 blocs

8 → 32 km
1 sommet / 64 blocs

32 → 100 km
1 sommet / 256 blocs
```

Les valeurs seront adaptées selon les performances.

---

# 39. Cohérence entre LOD et terrain réel

Le terrain distant et le terrain voxel doivent partager la même source géographique.

Sinon :

```text
LOD

      /\


terrain chargé

     /  \__
```

la montagne changerait de forme lorsqu’on s’en approche.

La bonne architecture :

```text
MacroHeight
     |
   +-+-+
   |   |
  LOD Chunk
```

Le terrain réel ajoute simplement davantage de détail.

---

# 40. Génération progressive

Le monde ne doit jamais être généré entièrement au lancement.

Il est généré progressivement autour du joueur.

Exemple :

```text
         4 4 4 4 4

       4 3 3 3 4

     4 3 2 2 3 4

     4 3 2 P 2 3 4

     4 3 2 2 3 4

       4 3 3 3 4

         4 4 4 4
```

Avec :

```text
P = joueur

1 = chunks indispensables

2 = chunks proches

3 = terrain intermédiaire

4 = LOD faible priorité
```

---

# 41. Priorités de génération

Une queue de tâches doit gérer les priorités.

```java
enum GenerationPriority {

    IMMEDIATE,

    NEAR,

    MEDIUM,

    DISTANT,

    BACKGROUND
}
```

Exemple :

```text
IMMEDIATE
collision + chunks visibles

NEAR
chunks autour du joueur

MEDIUM
LOD intermédiaire

DISTANT
heightmaps lointaines

BACKGROUND
pré-calcul macro
```

---

# 42. Multithreading

La génération doit être exécutée hors du thread principal.

Architecture possible :

```text
Main Thread
    |
    +-- Render
    +-- Physics
    +-- Gameplay

Worker Pool
    |
    +-- Macro generation
    +-- Chunk generation
    +-- Meshing
    +-- Vegetation
    +-- LOD generation
```

Utiliser idéalement un pool de tâches plutôt que réserver un thread par système.

Exemple :

```java
ExecutorService workers =
    Executors.newFixedThreadPool(
        Math.max(
            2,
            Runtime.getRuntime()
                   .availableProcessors() - 2
        )
    );
```

---

# 43. Cache

Une région macro ne doit être générée qu’une fois.

```text
World Seed
    ↓
Macro Region
    ↓
Generate
    ↓
Cache
```

Les accès suivants lisent directement les données calculées.

---

# 44. Persistance

Les informations macro peuvent être sauvegardées sur disque.

Exemple :

```text
world/
├── macro/
│   ├── region_0_0.dat
│   ├── region_0_1.dat
│   └── region_1_0.dat
│
├── chunks/
│
└── metadata.dat
```

Une MacroRegion sauvegardée peut contenir :

```text
height
temperature
humidity
rainfall
flow
geology
terrain type
```

---

# 45. Taille mémoire

Les cartes macro peuvent être très compactes.

Exemple :

```java
class PackedMacroCell {

    short height;

    byte temperature;

    byte humidity;

    byte rainfall;

    byte terrain;

    byte geology;

    int flow;
}
```

Même plusieurs centaines de milliers de cellules restent relativement légères.

---

# 46. Continuité entre macro-régions

Certaines informations nécessitent de connaître les régions voisines.

C’est particulièrement important pour :

- rivières ;
- chaînes montagneuses ;
- climat ;
- drainage.

Une solution consiste à générer une marge supplémentaire.

Exemple :

```text
+-------------------------+
|                         |
|       zone marge        |
|    +-------------+      |
|    |             |      |
|    | région utile|      |
|    |             |      |
|    +-------------+      |
|                         |
+-------------------------+
```

Une région de :

```text
8192 × 8192
```

peut être calculée dans une zone temporaire de :

```text
12288 × 12288
```

ou davantage.

Puis seul le centre est conservé.

---

# 47. Super-régions

Une autre possibilité consiste à calculer certains systèmes sur des zones encore plus grandes.

Exemple :

```text
SuperRegion
    |
    +-- tectonic plates
    +-- river basins
    +-- mountain ranges
    +-- climate
```

Puis les MacroRegions récupèrent une partie de ces données.

---

# 48. Déterminisme

Toute génération doit être déterministe.

À partir de :

```text
WorldSeed + coordonnées
```

le résultat doit toujours être identique.

Exemple :

```java
long regionSeed =
    hash(
        worldSeed,
        regionX,
        regionZ
    );
```

Il est important d’utiliser des seeds indépendantes pour chaque système.

Exemple :

```java
continentalSeed
mountainSeed
geologySeed
vegetationSeed
caveSeed
```

Cela permet de modifier un système sans forcément modifier tous les autres.

---

# 49. Structure proposée du code

```text
world/
│
├── generation/
│   │
│   ├── macro/
│   │   ├── ContinentalGenerator
│   │   ├── PlateGenerator
│   │   ├── MountainGenerator
│   │   ├── ElevationGenerator
│   │   ├── HydrologyGenerator
│   │   ├── ErosionGenerator
│   │   ├── ClimateGenerator
│   │   ├── EcologyGenerator
│   │   └── GeologyGenerator
│   │
│   ├── regional/
│   │   ├── RegionalTerrainGenerator
│   │   └── TerrainDetailGenerator
│   │
│   ├── voxel/
│   │   ├── ChunkGenerator
│   │   ├── CaveGenerator
│   │   ├── SurfaceGenerator
│   │   └── VegetationGenerator
│   │
│   └── lod/
│       ├── LodGenerator
│       └── LodMeshBuilder
│
├── biome/
│
├── geology/
│
├── terrain/
│
├── hydrology/
│
└── storage/
```

---

# 50. Pipeline complet

Le pipeline final peut être résumé ainsi :

```text
WORLD SEED
    ↓
PLATES
    ↓
CONTINENTS
    ↓
TECTONIC BOUNDARIES
    ↓
MOUNTAIN RANGES
    ↓
BASE ELEVATION
    ↓
BASINS
    ↓
HYDROLOGY
    ├── drainage
    ├── rivers
    └── lakes
    ↓
EROSION
    ↓
FINAL MACRO HEIGHT
    ↓
CLIMATE
    ├── temperature
    ├── moisture
    └── rainfall
    ↓
GEOLOGY
    ↓
ECOLOGY
    ↓
REGIONAL DETAIL
    ↓
LOCAL DETAIL
    ↓
VOXEL TERRAIN
    ↓
CAVES
    ↓
SURFACE BLOCKS
    ↓
VEGETATION
```

---

# 51. Ce qui est calculé à chaque échelle

## Monde

```text
plaques
continents
grandes chaînes
océans
```

## Macro

```text
altitude
hydrologie
climat
géologie
écosystème
```

## Régional

```text
collines
vallées secondaires
falaises
petits reliefs
```

## Local

```text
surface
arbres
rochers
végétation
```

## Voxel

```text
blocs
grottes
minerais
structures
```

---

# 52. Principe de performance

La règle fondamentale est :

> Plus une structure est grande, plus elle est calculée à basse résolution.

Une montagne de 8000 blocs de haut n’a pas besoin d’être représentée avec des millions de voxels lorsqu’elle se trouve à 50 kilomètres du joueur.

À cette distance, quelques valeurs d’altitude suffisent.

---

# 53. Ce qu’il faut éviter

Ne pas calculer :

```text
tectonique
climat
rivières
biomes
```

pour chaque voxel.

Ne pas recalculer les cartes macro à chaque frame.

Ne pas générer les chunks éloignés uniquement pour afficher leur silhouette.

Ne pas utiliser un bruit haute fréquence pour générer les grandes structures.

Ne pas générer les rivières indépendamment du relief.

Ne pas laisser les biomes générer directement la topographie.

---

# 54. Coûts CPU attendus

Les opérations macro sont relativement faibles.

Exemple :

```text
256 × 256 cellules
=
65 536 cellules
```

Quelques passes :

```text
elevation
drainage
flow
temperature
humidity
biome
```

restent très raisonnables pour un CPU moderne.

Les véritables coûts devraient surtout provenir de :

- génération des chunks voxel ;
- caves 3D ;
- meshing ;
- végétation ;
- éclairage ;
- upload GPU ;
- rendu ;
- physique.

---

# 55. Système de debug recommandé

Un outil de visualisation 2D serait extrêmement utile.

Pouvoir afficher :

```text
continentalness
elevation
tectonic plates
mountain strength
flow direction
flow accumulation
rivers
temperature
humidity
rainfall
terrain
geology
biome
```

sous forme de cartes.

Exemple :

```text
Seed : 84562341

[ Elevation ]

[ Rivers ]

[ Temperature ]

[ Humidity ]

[ Biomes ]
```

Cela permet de développer la génération du monde sans devoir constamment lancer le jeu et parcourir les chunks.

---

# 56. Roadmap d’implémentation

Il ne faut pas essayer d’implémenter tout le système immédiatement.

## Phase 1 — Terrain macro

Créer :

```text
continentalness
+
altitude macro
+
chaînes montagneuses
```

Objectif :

obtenir de vrais continents, plaines et chaînes montagneuses.

---

## Phase 2 — Hydrologie

Ajouter :

```text
flow direction
flow accumulation
rivières
lacs
```

Objectif :

avoir un réseau hydrographique cohérent.

---

## Phase 3 — Terrain local

Ajouter :

```text
regional noise
local noise
domain warping
```

Objectif :

éviter que le terrain macro soit trop lisse.

---

## Phase 4 — LOD

Utiliser directement la heightmap macro pour afficher le terrain lointain.

Objectif :

voir immédiatement :

- montagnes ;
- vallées ;
- océans ;
- grandes formes du monde.

---

## Phase 5 — Climat

Ajouter :

```text
température
humidité
pluie
ombre pluviométrique
```

---

## Phase 6 — Écosystèmes

Ajouter :

```text
forêts
prairies
steppes
toundra
déserts
```

avec transitions progressives.

---

## Phase 7 — Géologie

Ajouter plusieurs familles de roche et sols.

Exemple :

```text
granite
basalte
calcaire
ardoise
grès
```

---

## Phase 8 — Érosion

Ajouter progressivement :

```text
river incision
thermal erosion
slope relaxation
```

---

# 57. Première version réaliste

Une première implémentation réellement utile pourrait être beaucoup plus simple que le système final.

```text
1. Continentalness

2. Mountain ranges

3. Macro heightmap

4. Flow direction

5. Flow accumulation

6. Rivers

7. Regional noise

8. Chunk voxelisation

9. LOD
```

Cette version serait déjà radicalement supérieure à un générateur basé uniquement sur du Perlin.

---

# 58. Vision finale

L’objectif est que le joueur puisse apparaître dans une forêt et voir à plusieurs dizaines de kilomètres une chaîne montagneuse.

Il peut ensuite :

```text
traverser la forêt
↓
suivre un ruisseau
↓
voir celui-ci rejoindre une rivière
↓
descendre dans une vallée
↓
atteindre un grand fleuve
↓
observer les montagnes qui alimentent ce bassin
↓
continuer jusqu’à la côte
↓
arriver sur un océan
```

Tous ces éléments doivent être reliés entre eux par la génération procédurale.

Le monde ne doit pas sembler être une succession de biomes générés indépendamment.

Il doit sembler être un territoire.

---

# 59. Résumé architectural

```text
                         WORLD SEED
                             |
                             v
                    CONTINENT GENERATOR
                             |
                             v
                     TECTONIC SYSTEM
                             |
                             v
                     MACRO ELEVATION
                             |
                             v
                       HYDROLOGY
                    /              \
                rivers             lakes
                    \              /
                             |
                             v
                         EROSION
                             |
                             v
                          CLIMATE
                    /        |        \
             temperature  humidity  rainfall
                    \        |        /
                             |
                             v
                         ECOLOGY
                             |
                             v
                         GEOLOGY
                             |
                             v
                    REGIONAL TERRAIN
                             |
                             v
                    +----------------+
                    |                |
                    v                v
                  LOD             CHUNKS
                                     |
                                     v
                                   CAVES
                                     |
                                     v
                                SURFACE BLOCKS
                                     |
                                     v
                                VEGETATION
```

Le principe central reste :

```text
Structure globale d’abord.
Détail local ensuite.
```

C’est ce qui doit permettre au monde d’avoir simultanément :

- une très grande échelle ;
- une topographie crédible ;
- une bonne distance de vue ;
- des performances raisonnables ;
- des rivières cohérentes ;
- des biomes naturels ;
- une grande variété visuelle ;
- et surtout une véritable sensation de géographie.