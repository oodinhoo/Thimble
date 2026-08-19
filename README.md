# Thimble

Plugin pour serveur Minecraft permettant de créer et de jouer au mini jeu du "dé à coudre" (thimble)

Chaque joueur saute à tour de rôle d'un plongeoir vers une piscine. Atterrir dans l'eau pose un bloc
de sa couleur à l'endroit de l'impact — la piscine rétrécit à chaque saut. Rater coûte une vie.
Le dernier joueur en vie gagne.

## Compilation

Requiert **JDK 25** (Minecraft 26.1+ l'impose). Un Corretto 25 est installé dans
`C:\Users\AlexandreODIN\tools\jdk25.0.4_8`.

```powershell
$env:JAVA_HOME = "C:\Users\AlexandreODIN\tools\jdk25.0.4_8"
.\gradlew.bat build      # -> build/libs/Thimble-0.2.0.jar
```

Compilé et vérifié contre `io.papermc.paper:paper-api:26.2.build.112-stable` (Gradle 9.7.1,
aucun avertissement). Déposer le jar dans `plugins/` d'un serveur **Paper 26.2**.

## Créer une arène

### 1. Construire la zone de jeu

| Élément | Recommandé |
|---|---|
| Piscine | 7×7 blocs, **3 blocs de profondeur**, remplie d'eau source jusqu'au ras du bord |
| Bord | blocs solides tout autour, rien qui dépasse au-dessus de la surface |
| Plongeoir | plateforme 1×1 à 3×3, **20 à 30 blocs** au-dessus de l'eau, centrée sur la piscine |
| Lobby d'attente | à côté, hors de la piscine |
| Point spectateur | avec **vue sur la piscine** (c'est de là qu'on vise la case bonus) |

Seule la couche de surface compte : c'est là que les blocs se posent. Les couches du dessous
restent en eau pour amortir la chute.

### 2. Déclarer l'arène — la baguette

```
/th admin wand piscine1
```

Tu reçois une **baguette** (bâton de blaze) et quatre clics droits suffisent, dans l'ordre :

| Clic | Sur quoi viser |
|---|---|
| 1/4 | **l'eau**, à un coin de la piscine |
| 2/4 | **l'eau**, au coin opposé |
| 3/4 | le bloc du **plongeoir** (tu y seras téléporté à ton tour) |
| 4/4 | le bloc des **gradins** (attente avant la partie et retour après chaque saut) |

Pas besoin de te déplacer : la case visée est calculée par lancer de rayon côté serveur, l'eau
comprise — le client, lui, ne permet jamais de cliquer sur de l'eau. L'arène est créée si elle
n'existe pas, puis **activée automatiquement** au dernier clic si tout est cohérent ; sinon le
plugin dit ce qui manque.

### 3. Réglage fin (facultatif)

Chaque point reste modifiable à la commande, en te plaçant à l'endroit voulu :

```
/th admin setwait|setjump|setspectator piscine1
/th admin pos1|pos2 piscine1
/th admin setsurface piscine1 [y]
/th admin set piscine1 <min|max|lives|timeout> <valeur>
/th admin palette piscine1 <add|remove|clear|list> [bloc]
```

La baguette pose un seul point pour l'attente et le retour ; ces commandes permettent de les
séparer. La **surface de jeu** est le Y le plus haut des deux coins.

### 4. Vérifier

`/th admin info piscine1` affiche l'état, les réglages, le nombre de cases d'eau détectées et ce
qui manque encore. Une 7×7 correctement posée affiche `eau=49`. Si `eau=0`, la surface est mal
placée.

### Pièges courants

- **`eau=0`** : la surface tombe à côté de l'eau — repose `pos1` en étant *dans* l'eau du bord.
- **Eau courante** au lieu d'eau source : les blocs d'eau qui coulent ne comptent pas.
- **Un bloc au-dessus de la piscine** (surplomb, plafond) : le joueur atterrit dessus, c'est un raté.
- **Point spectateur sans vue** sur la piscine : le bonus du dé à coudre devient invisible à viser.
- Il faut **2 joueurs minimum** pour lancer une partie : le principe même du jeu est le dernier en
  vie, `min` ne descend pas sous 2.

## Jouer

```
/th join [arène]       # sans argument : l'arène libre la plus remplie
/th spectate <arène>   # observer une partie en cours
/th leave
/th list
```

La partie démarre seule dès `min` joueurs (30 s de décompte, 5 s si l'arène est pleine). Rejoindre
une partie déjà lancée place le joueur en spectateur.

Après un **dé à coudre** (atterrissage dans un trou de 1×1), le joueur choisit une case d'eau
supplémentaire à remplir : il la vise et fait un clic droit dans les 8 secondes.

Une déconnexion en pleine partie garde la place 60 secondes ; l'inventaire est rendu immédiatement.

Titres, sons, barre de boss, tableau de score et particules se désactivent un par un dans la
section `ui` de `config.yml`.

## Documentation

- [Spécifications du jeu et du plugin](docs/SPECIFICATIONS.md) — règles, cible technique,
  architecture, commandes, configuration, découpage en lots et écarts assumés du lot 1.
