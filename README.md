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
/th solo [arène]       # partie d'entraînement, seul
/th spectate <arène>   # observer une partie en cours
/th leave
/th list
```

**Mode solo** : démarre immédiatement, sans décompte ni adversaire, sur une arène libre et vide.
Le but est de remplir toute la piscine sans épuiser ses vies. La partie s'arrête à court de vies —
le bilan donne le nombre de cases remplies et de dés à coudre — ou en victoire si la dernière case
d'eau est bouchée. Contrairement au multijoueur, la piscine n'est jamais remise à neuf en cours de
partie : c'est justement ce qui fait le score.

La partie démarre seule dès `min` joueurs (15 s de décompte, 5 s si l'arène est pleine). Rejoindre
une partie déjà lancée place le joueur en spectateur.

Après un saut réussi, le joueur reste 2 secondes dans l'eau avant de repartir aux gradins ; un
saut manqué renvoie immédiatement.

Après un **dé à coudre** (atterrissage dans un trou de 1×1), le joueur est placé en vol au-dessus
de la piscine, regard plongeant et immobile — seule la vue bouge. Il a 8 secondes pour faire un
clic droit, au choix, sur une **case d'eau** (elle se remplit) ou sur un **bloc déjà posé** (elle
se rouvre). Seuls les blocs posés pendant la partie peuvent être rouverts, jamais le fond ni les
bords de la piscine.

À la fin d'une partie, les joueurs **restent dans l'arène** : un nouveau décompte démarre tout
seul, sans avoir à retaper `/th join`. `/th leave` pour sortir. Une partie solo, elle, rend la
main à la fin.

Une déconnexion en pleine partie garde la place 60 secondes ; l'inventaire est rendu immédiatement.

Pendant la partie : le tableau de score donne les joueurs avec leur couleur et leurs vies en cœurs,
la barre d'action rappelle la consigne du moment, la barre de boss passe au rouge sous cinq
secondes, et la fin de partie affiche le classement complet.

Titres, sons, barre d'action, barre de boss, tableau de score et particules se désactivent un par
un dans la section `ui` de `config.yml`.

## Mettre à jour

Remplacer le jar dans `plugins/` puis **redémarrer** le serveur. Ni `config.yml` ni `messages.yml`
ni `arenas.yml` ne sont écrasés, et il n'y a rien à supprimer : les réglages et les textes absents
d'un fichier existant retombent sur ceux du jar. Pour voir les nouvelles options commentées, il
suffit de supprimer le fichier concerné, qui sera régénéré complet.

## Documentation

- [Spécifications du jeu et du plugin](docs/SPECIFICATIONS.md) — règles, cible technique,
  architecture, commandes, configuration, découpage en lots et écarts assumés du lot 1.
