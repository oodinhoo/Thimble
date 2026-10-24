# Thimble

Plugin pour serveur Minecraft permettant de créer et de jouer au mini jeu du "dé à coudre" (thimble)

Chaque joueur saute à tour de rôle d'un plongeoir vers une piscine. Atterrir dans l'eau pose un bloc
de sa couleur à l'endroit de l'impact — la piscine rétrécit à chaque saut. Rater coûte une vie.
Le dernier joueur en vie gagne.

## Compilation

Requiert **JDK 25** (Minecraft 26.1+ l'impose).

```powershell
$env:JAVA_HOME = "<chemin vers un JDK 25>"
.\gradlew.bat build      # -> build/libs/Thimble-0.2.0.jar
```

Compilé contre `io.papermc.paper:paper-api:26.2.build.112-stable` (Gradle 9.7.1, aucun
avertissement). `.\gradlew.bat test` lance les tests. Déposer le jar dans `plugins/` d'un serveur
**Paper 26.2**.

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

Chaque point posé par la baguette reste modifiable à la commande, et les paramètres de partie —
joueurs, vies, chrono, palette — se règlent au même endroit : voir [Commandes](#commandes).
La **surface de jeu** est le Y le plus haut des deux coins.

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

Une **pancarte** peut servir de bouton de jonction : `[Thimble]` en première ligne, le nom de
l'arène en seconde (laissée vide = l'arène libre la plus remplie). Un clic droit dessus rejoint,
sans taper de commande. C'est une bascule : re-cliquer alors qu'on est déjà en partie fait quitter,
comme `/th leave`.

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

Une déconnexion en pleine partie garde la place 60 secondes ; l'inventaire est rendu
immédiatement. Si le serveur tombe pendant une partie, l'inventaire et la position d'origine sont
rendus à la connexion suivante — ils sont écrits sur disque dès l'entrée en arène.

Pendant la partie : le tableau de score donne les joueurs avec leur couleur et leurs vies en cœurs,
la barre d'action rappelle la consigne du moment, la barre de boss passe au rouge sous cinq
secondes, et la fin de partie affiche le classement complet. La couleur attribuée à chaque joueur
teinte aussi son point sur la barre de localisation (le « radar ») et son nom au-dessus de la tête,
et la traînée de particules de sa chute. Un joueur **en attente de son tour** est retenu au bord de
la piscine, pour ne pas y tomber et fausser une détection.

Titres, sons, barre d'action, barre de boss, tableau de score, particules, panneau de classement
(`leaderboard`) et couleur radar (`radar`) se désactivent un par un dans la section `ui` de
`config.yml`.

## Classement

Un **panneau flottant** affiche le classement persistant du serveur — victoires et dés à coudre,
TOP réglable (`leaderboard.size`) — et le résultat de la dernière partie. Place-toi à l'endroit
voulu, regarde dans la bonne direction, puis `/th admin setboard` ; `/th admin clearboard` le
retire, et le reposer ailleurs déplace l'ancien.

Il se met à jour à la fin de chaque partie **multijoueur terminée normalement**. Le solo (c'est de
l'entraînement), les parties interrompues par `forcestop` et celles gagnées parce que tous les
adversaires sont partis ne comptent pas. Les stats vivent dans
`stats.yml` et survivent aux redémarrages ; `ui.leaderboard: false` masque le panneau sans cesser
de compter.

## Commandes

`/thimble`, avec les alias `/th`, `/deacoudre` et `/dac`. Toutes les commandes se complètent à la
touche tab, et `/th help` n'affiche les lignes d'administration qu'aux joueurs qui y ont droit.

### Joueur — permission `thimble.play`

| Commande | Rôle |
|---|---|
| `/th join [arène]` | Rejoint une partie. Sans argument, choisit l'arène libre **la plus remplie**, pour regrouper les joueurs plutôt que les éparpiller. Si la partie est déjà lancée, place en spectateur. |
| `/th solo [arène]` | Partie d'entraînement, seul. Démarre immédiatement, sans décompte. Demande une arène **libre et vide**, donc échoue si quelqu'un attend déjà. |
| `/th spectate <arène>` | Observe une partie. L'arène est obligatoire. Nécessite `thimble.spectate`. |
| `/th leave` | Quitte la partie ou l'observation, et rend inventaire, mode de jeu et position d'origine. |
| `/th list` | Liste les arènes avec leur état et leur nombre de joueurs. |
| `/th help` | Aide, adaptée aux permissions. |

### Administration — permission `thimble.admin`

**Créer une arène**

| Commande | Rôle |
|---|---|
| `/th admin wand <arène>` | Donne la baguette et guide les quatre clics. **C'est la voie normale** : crée l'arène si besoin et l'active à la fin. |
| `/th admin create\|delete <arène>` | Crée une arène vide, ou la supprime. Le nom n'accepte que lettres, chiffres, tiret et underscore. |

**Placer les points** — chaque commande enregistre *ta position actuelle*, orientation comprise.

| Commande | Rôle |
|---|---|
| `/th admin setjump <arène>` | Le plongeoir. Le joueur y est téléporté à son tour, tourné comme tu l'étais. |
| `/th admin setwait <arène>` | Le lobby d'attente, avant le début de la partie. |
| `/th admin setspectator <arène>` | Le point de retour après chaque saut. La baguette le confond avec le lobby ; ces deux commandes permettent de les séparer. |
| `/th admin pos1\|pos2 <arène>` | Les deux coins opposés de la piscine. **Place-toi dans l'eau** : c'est le bloc sous tes pieds qui est retenu. |
| `/th admin setsurface <arène> [y]` | Force la surface de jeu. Sans argument, prend ton altitude actuelle. Utile si les deux coins ne sont pas au même niveau. |

**Régler**

| Commande | Rôle |
|---|---|
| `/th admin set <arène> min <n>` | Joueurs nécessaires pour lancer le décompte. Plancher : 2. |
| `/th admin set <arène> max <n>` | Places disponibles. Au-delà de la taille de la palette, deux joueurs partageraient une couleur. |
| `/th admin set <arène> lives <n>` | Vies par joueur. Plancher : 1. |
| `/th admin set <arène> timeout <s>` | Secondes accordées pour résoudre un saut, chute comprise. Plancher : 3. |
| `/th admin palette <arène> <add\|remove\|clear\|list> [bloc]` | Couleurs attribuées aux joueurs, dans l'ordre. Vide, l'arène reprend `default-palette` de `config.yml`. N'importe quel bloc teinté fonctionne : laine, béton, terre cuite, verre. |

**Exploiter**

| Commande | Rôle |
|---|---|
| `/th admin enable <arène>` | Rend l'arène jouable. Refuse tant qu'il manque quelque chose, et dit quoi. |
| `/th admin disable <arène>` | Interrompt la partie en cours et retire l'arène du jeu. |
| `/th admin info <arène>` | État, réglages, cases d'eau détectées, palette, et ce qui manque. **Le premier réflexe quand une arène ne se comporte pas comme prévu.** |
| `/th admin restore <arène>` | Remet la piscine en eau. Fonctionne même après un redémarrage, où le plugin a perdu la trace des blocs posés. |
| `/th admin forcestart <arène>` | Démarre sans attendre : court-circuite le décompte en cours, ou lance la partie sans avoir atteint `min` joueurs. Refuse si l'arène est vide ou déjà en partie. |
| `/th admin forcestop <arène>` | Interrompt la partie et rend chacun à son point de départ. |
| `/th admin reload` | Recharge `config.yml`, `messages.yml` et `arenas.yml`. **Arrête les parties en cours** : à éviter quand le serveur est peuplé. |

**Classement** — ces deux commandes ne prennent pas d'arène : le panneau est unique pour le serveur.

| Commande | Rôle |
|---|---|
| `/th admin setboard` | Pose le panneau de classement flottant à ta position, orientation comprise. Le déplace s'il existait ailleurs. |
| `/th admin clearboard` | Retire le panneau. |

### Permissions

| Permission | Par défaut | Donne accès à |
|---|---|---|
| `thimble.play` | tout le monde | `join`, `solo`, `leave`, `list`, `help` |
| `thimble.spectate` | tout le monde | `spectate` |
| `thimble.admin` | opérateurs | `/th admin …` |

## Mettre à jour

Remplacer le jar dans `plugins/` puis **redémarrer** le serveur. Ni `config.yml` ni `messages.yml`
ni `arenas.yml` ne sont écrasés, et il n'y a rien à supprimer : les réglages et les textes absents
d'un fichier existant retombent sur ceux du jar. Pour voir les nouvelles options commentées, il
suffit de supprimer le fichier concerné, qui sera régénéré complet.

Une exception depuis cette version : l'écran d'attente affiche désormais `joueurs/max` et non plus
`joueurs/min`. Si tu as un `messages.yml` personnalisé, remplaces-y `<min>` par `<max>` dans
`scoreboard.waiting` et `actionbar.waiting` — sinon ces lignes afficheraient un `<min>` littéral.

## Documentation

- [Spécifications du jeu et du plugin](docs/SPECIFICATIONS.md) — règles, cible technique,
  architecture, détection des sauts, commandes, fichiers, affichage, et ce qui n'est
  volontairement pas fait.
