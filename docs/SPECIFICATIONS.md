# Thimble — spécifications

Plugin **PaperMC 26.2** du mini-jeu du « dé à coudre ». Ce document décrit le plugin tel qu'il est,
et les décisions qui ne se lisent pas dans le code. L'historique des changements est dans git.

---

## 1. Le jeu

Le « dé à coudre » est un sport Minecraft inventé sur le serveur français Minefield par Tenshinryu
et Skayloss, popularisé sous ce nom par Bob Lennon, connu à l'international sous le nom *Thimble*.

Chaque joueur saute à tour de rôle d'un plongeoir vers une piscine. Atterrir dans l'eau pose un
bloc de sa couleur à l'endroit de l'impact : la piscine rétrécit à chaque saut. Rater coûte une
vie. **Le dernier joueur en vie gagne.**

### 1.1 Terrain

| Élément | Recommandé | Contrainte |
|---|---|---|
| Piscine | 7×7, 3 blocs de profondeur | zone rectangulaire définie par deux coins |
| Surface de jeu | le Y le plus haut des deux coins | seul niveau où des blocs se posent et se comptent |
| Plongeoir | 20 à 30 blocs au-dessus de l'eau | rien ne l'impose techniquement |
| Gradins | vue sur la piscine | lobby d'attente et point de retour après chaque saut |

### 1.2 Déroulement

1. **Attente** — `/th join`. Le décompte part dès `min` joueurs (15 s, ramené à 5 s si l'arène est pleine).
2. **Démarrage** — couleur unique par joueur, `lives` vies chacun (3 par défaut).
3. **Tour** — téléportation sur le plongeoir, immobilisation d'une seconde, chrono de 15 s.
   - atterrissage dans l'eau → le bloc est posé, 2 s sur place, retour aux gradins ;
   - atterrissage sur un bloc, chute dans le vide, ou chrono écoulé → une vie en moins, retour immédiat.
4. **Élimination** — à 0 vie, le joueur passe spectateur ; l'ordre d'élimination fait le classement.
5. **Fin** — dernier survivant, ou piscine pleine (remise à neuf ou fin selon `refill-when-full`).
   Podium, puis les joueurs **restent en place** et une nouvelle partie s'enchaîne.

### 1.3 Le dé à coudre

Atterrir dans un **trou de 1×1** — les quatre cases voisines déjà remplies. Le joueur est alors
placé en vol au-dessus de la piscine, regard plongeant, immobile : il a 8 secondes pour viser et
faire un clic droit, au choix sur **une case d'eau** (elle se remplit) ou sur **un bloc posé**
(elle se rouvre). Sans choix à temps, une case est tirée au sort.

Le sabotage n'accepte que les blocs posés pendant la partie : ils viennent du journal de
restauration, donc le fond et les bords de la piscine sont hors d'atteinte sans qu'aucune
vérification n'ait à l'énoncer.

### 1.4 Mode solo

`/th solo [arène]` démarre une partie à un joueur sur une arène libre et vide, sans décompte. Le
mode est **déduit** à l'ouverture (`order.size() == 1`), pas configuré : la même arène sert au solo
et au multijoueur. Trois règles changent.

| Règle | Multijoueur | Solo |
|---|---|---|
| Fin de partie | au dernier survivant | quand le joueur n'a plus de vie |
| Piscine pleine | remise à neuf | fin de partie, comptée comme une victoire |
| Bilan | vainqueur et podium | cases remplies et dés à coudre réussis |

Le sans-faute exige que la piscine soit réellement pleine, pas seulement que le joueur soit en
vie : une partie interrompue affiche le bilan simple.

---

## 2. Cible technique

| Élément | Choix |
|---|---|
| Serveur | **PaperMC 26.2**, `io.papermc.paper:paper-api:26.2.build.112-stable` (version épinglée) |
| Java | **JDK 25**, obligatoire depuis Minecraft 26.1 |
| Build | Gradle 9.7.1 + wrapper, JUnit 5 pour les tests |
| Manifeste | `plugin.yml`, `api-version: '26.2'` |
| Texte | Adventure / MiniMessage, aucune couleur `§` en dur |
| Commandes | `CommandExecutor` + `TabCompleter` |

**Bukkit n'est pas déprécié** : `paper-api` *est* l'API Bukkit augmentée, `org.bukkit.*` reste la
surface principale de Paper 26.2. Vérifié dans le bytecode du jar de l'API — ni `CommandExecutor`,
ni `PluginCommand`, ni `JavaPlugin#getCommand`, ni la section `commands:` du `plugin.yml` ne
portent l'attribut `Deprecated` ; seul `JavaPluginLoader`, que nul plugin n'appelle, est marqué.
Ce qui bouge est le *chargement* (`paper-plugin.yml` et Brigadier), sans calendrier de suppression
annoncé. `JavaPlugin#registerCommand` permettrait de vrais types d'arguments Brigadier sans migrer
le manifeste : un seul fichier à reprendre, le jour où ça vaut le coup.

### 2.1 Arborescence

Un package plat, sept classes. Le découpage en sous-packages n'apporterait rien tant que le volume
reste celui-là.

```text
Thimble/
├── build.gradle.kts · settings.gradle.kts · gradlew(.bat)
├── docs/SPECIFICATIONS.md
└── src/
    ├── main/java/fr/thimble/
    │   ├── ThimblePlugin.java   # cycle de vie, arènes, config, messages, tick, baguette, restaurations
    │   ├── Arena.java           # configuration d'arène + partie en cours
    │   ├── ThimbleCommand.java  # /th et /th admin, complétion
    │   ├── GameListener.java    # gel, protections, connexion/déconnexion, clic droit
    │   ├── Hud.java             # titres, sons, barre de boss, barre d'action, tableau de score, particules
    │   ├── Leaderboard.java     # classement persistant + dernière partie, affiché sur un TextDisplay
    │   └── PlayerSnapshot.java  # état du joueur, en mémoire et sur disque
    ├── main/resources/          # plugin.yml, config.yml, messages.yml
    └── test/java/fr/thimble/    # HudTest, ArenaConfigTest, LeaderboardTest
```

---

## 3. Machine à états

```text
DISABLED ──enable──▶ WAITING
WAITING  ──(≥ min joueurs)──▶ STARTING ──(décompte)──▶ PLAYING
STARTING ──(< min joueurs)──▶ WAITING
PLAYING  ──(1 survivant | 0 survivant | forcestop | piscine pleine en solo)──▶ ENDING
ENDING   ──(délai)──▶ WAITING          # les joueurs restent : la partie suivante s'enchaîne
ENDING   ──(solo ou arène vide)──▶ WAITING, joueurs rendus à leur point de départ
```

Un tour, dans `tickTurn()` : pause éventuelle → choix du bonus → gel sur le plongeoir → suivi de
la chute et résolution → chrono. Tout est piloté par le tick, jamais par une tâche différée : un
`forcestop` ou un rechargement ne peut donc pas laisser une action orpheline se déclencher dans la
partie suivante.

---

## 4. Détection

### 4.1 Résolution du saut

Évaluée **à chaque tick**, pas sur `PlayerMoveEvent` : un joueur qui atterrit sur un bloc et lâche
les touches n'émet plus aucun mouvement, son tour ne se terminait alors qu'à l'expiration du
chrono. `PlayerMoveEvent` ne sert plus qu'à l'immobilisation.

- le saut commence quand le joueur est descendu sous le plongeoir (marge d'un bloc) ;
- colonne des pieds dans la région et bloc `(x, surfaceY, z)` en eau → **réussite** ;
- bloc solide sous **l'un des quatre coins de la hitbox** → **échec**. Tester la seule colonne
  centrale laissait passer un joueur immobilisé sur le bord d'un bloc, dont le centre surplombe la
  case voisine : le tour restait en suspens et un pas vers l'eau transformait le raté en réussite ;
- chute sous le fond de la piscine ou sous la hauteur minimale du monde → **échec** ;
- `Player#isOnGround()` n'est pas une option : il est renseigné par le client, donc falsifiable
  (et déprécié pour cette raison).

La fenêtre verticale de détection fait au minimum 4 blocs, y compris quand les deux coins sont
posés au même niveau — sinon un joueur en chute rapide pouvait la traverser entre deux ticks.

### 4.2 Cases visées à la souris

L'eau n'est pas un bloc solide : **le client n'envoie jamais de clic dessus**. La cible est donc
calculée côté serveur par lancer de rayon avec `FluidCollisionMode.ALWAYS`, pour le choix du bonus
comme pour la baguette de configuration. `RIGHT_CLICK_AIR` et `RIGHT_CLICK_BLOCK` sont acceptés
tous les deux, le client envoyant l'un ou l'autre selon ce qui se trouve derrière l'eau — et le
listener filtre `EquipmentSlot.HAND`, faute de quoi chaque clic compterait double, l'événement
étant émis une fois par main.

### 4.3 Piscine

Les blocs posés sont journalisés avec leur état d'origine, ce qui sert à trois choses : la
restauration en fin de partie, le sabotage (seuls ces blocs sont rouvrables), et la remise à neuf
quand la piscine est pleine. Sans journal — après un redémarrage ou un crash — `restorePool()`
repeint la surface à partir de la géométrie, sinon l'arène resterait bouchée pour toujours.

### 4.4 Protections

En arène : pose, casse, drop, clic d'inventaire, faim et **tous** les dégâts sont annulés. Le
joueur n'est jamais réellement tué, les vies sont comptées par le plugin. Les blocs sont posés
sans émettre de `BlockPlaceEvent`, donc sans passer par WorldGuard — délibéré, mais à savoir. La
protection de la région contre les joueurs **hors** arène relève d'un autre plugin.

Un joueur **en attente de son tour** est repoussé au bord de la piscine (mur invisible sur
`PlayerMoveEvent`, mutualisé avec le gel du plongeoir) : sans cela il pouvait tomber dans l'eau et
fausser une détection. Le sauteur et le joueur qui vise sa case bonus en sont exemptés, de même que
les éliminés et les spectateurs.

---

## 5. Commandes et permissions

| Commande | Rôle | Permission |
|---|---|---|
| `/th join [arène]` | rejoindre ; sans argument, l'arène libre la plus remplie | `thimble.play` |
| `/th solo [arène]` | partie d'entraînement, démarrage immédiat | `thimble.play` |
| `/th spectate <arène>` | observer | `thimble.spectate` |
| `/th leave` · `/th list` · `/th help` | — | `thimble.play` |
| `/th admin wand <arène>` | configuration guidée en 4 clics | `thimble.admin` |
| `/th admin create\|delete <arène>` | — | `thimble.admin` |
| `/th admin setwait\|setjump\|setspectator\|pos1\|pos2 <arène>` | placement à la position courante | `thimble.admin` |
| `/th admin setsurface <arène> [y]` | forcer la surface de jeu | `thimble.admin` |
| `/th admin set <arène> <min\|max\|lives\|timeout> <valeur>` | — | `thimble.admin` |
| `/th admin palette <arène> <add\|remove\|clear\|list> [bloc]` | — | `thimble.admin` |
| `/th admin enable\|disable\|info\|restore <arène>` | — | `thimble.admin` |
| `/th admin forcestart\|forcestop <arène>` | — | `thimble.admin` |
| `/th admin setboard\|clearboard` | pose ou retire le panneau de classement (sans argument d'arène) | `thimble.admin` |
| `/th admin reload` | recharge config, messages et arènes | `thimble.admin` |

La **baguette** remplace la série de commandes de placement : quatre clics droits — coin, coin
opposé, plongeoir, gradins — l'arène étant créée si besoin et activée au dernier clic si tout est
cohérent. Les gradins fixent d'un geste le lobby d'attente et le point de retour, qui sont le même
endroit dans la quasi-totalité des arènes ; `setwait` et `setspectator` permettent de les séparer.

Un identifiant d'arène est validé (`[a-z0-9_-]{1,32}`) : Bukkit lit le point comme un séparateur
de chemin, et `piscine.1` imbriquerait la section au point de faire disparaître l'arène au
rechargement.

Une **pancarte de jonction** rejoint sans commande : première ligne `[Thimble]`, seconde ligne
l'arène visée (vide = l'arène libre la plus remplie). Le clic droit est intercepté avant l'éditeur
de pancarte. C'est une bascule : hors partie il rejoint (`/th join`), en partie il fait quitter
(`/th leave`) — un seul panneau, pas de pancarte « leave » séparée.

---

## 6. Fichiers

| Fichier | Contenu |
|---|---|
| `config.yml` | durées, `refill-when-full`, palette par défaut, section `ui` |
| `messages.yml` | tous les textes, en MiniMessage |
| `arenas.yml` | une section par arène : positions, coins, réglages, palette |
| `pending-restore.yml` | états de joueurs à rendre après une partie interrompue |
| `stats.yml` | stats cumulées par joueur, position du panneau de classement, podium de la dernière partie |

Tout est en secondes dans `config.yml`, la conversion en ticks se fait au point d'usage. Les
textes du jar servent de valeurs par défaut : une mise à jour qui ajoute des messages n'oblige pas
à supprimer le fichier existant, et les personnalisations sont conservées.

Une arène chargée passe par les mêmes bornes que la commande (`min ≥ 2`, `lives ≥ 1`,
`timeout ≥ 3`) et n'est activée que si sa configuration est complète : un `arenas.yml` édité à la
main ne peut plus produire un `timeout` nul ni une téléportation vers une position absente.

---

## 7. Affichage

| Canal | Contenu |
|---|---|
| Tableau de score | joueurs avec carré de couleur, vies en cœurs, curseur sur le sauteur, éliminés barrés ; manche, eau restante, jauge, nom de l'arène |
| Barre d'action | la consigne du moment : attente, ton tour, choix du bonus |
| Barre de boss | décompte avant départ, chrono du tour (rouge sous 5 s), choix du bonus |
| Titres | début de partie, ton tour, dé à coudre, raté (en cœurs), élimination, victoire |
| Chat | les faits seulement : poses, dés à coudre, ratés, éliminations, podium |
| Sons et particules | traînée pendant la chute, gerbe sur un dé à coudre |
| Panneau de classement | `TextDisplay` flottant posé par `/th admin setboard` : TOP du serveur (victoires, dés à coudre) et podium de la dernière partie, rafraîchi en fin de partie, jamais au tick |

Chaque canal se désactive séparément dans la section `ui` de `config.yml`.

Le panneau de classement est une entité du monde, pas un overlay client : il survit aux
redémarrages. Elle porte un tag (`thimble_board`) qui sert à retrouver et supprimer les doublons
au `reload` ou après un crash — le seul piège de ce canal est là, pas dans le rendu.

La couleur d'un joueur vient de `DyeColor`, reconnue au préfixe du matériau (`RED_WOOL`,
`BLUE_CONCRETE`…) : n'importe quelle palette teinte fonctionne sans table à maintenir.

Cette même couleur teinte le point du joueur sur la **barre de localisation** (le « radar ») et
son nom au-dessus de la tête. Paper 26.2 n'expose aucune API waypoint ; la couleur d'équipe du
scoreboard est le seul levier serveur, et le jeu la fait primer sur la couleur par défaut dérivée
de l'UUID. Elle est bornée aux seize couleurs nommées, d'où le passage par `NamedTextColor.nearestTo`.
Désactivable via `ui.radar`.

Deux limites du jeu, pas du plugin : une barre de boss n'a que six couleurs et l'or n'en fait pas
partie, le bonus utilise donc le violet avec un texte doré ; et une barre d'action s'efface au
bout de trois secondes, d'où le renvoi chaque seconde, greffé sur le rafraîchissement du tableau
de score.

---

## 8. Robustesse

**L'état du joueur est écrit sur disque dès l'entrée en arène.** Minecraft sauvegarde les joueurs
toutes les cinq minutes : un crash en pleine partie graverait sinon l'inventaire vide, sans
recours. Le fichier est relu à la connexion suivante, ce qui règle du même coup la position de
retour — une téléportation pendant `PlayerQuitEvent` n'étant pas fiable.

**Une arène qui lève une exception est isolée**, loguée et désactivée : sans cela elle arrêtait le
tick de toutes les suivantes, vingt fois par seconde.

**Une déconnexion en partie garde la place** `rejoin-grace-seconds` : l'inventaire est rendu tout
de suite, les tours du joueur absent sont sautés, et il reprend sa place s'il revient à temps.

**`arenaOf(joueur)` est indexé**, pas parcouru : il est appelé à chaque paquet de mouvement de
chaque joueur connecté, y compris ceux qui ne jouent pas.

---

## 9. Tests

Neuf tests, sur ce qui ne touche ni au monde ni aux joueurs : `paper-api` sur le classpath de test
suffit, aucun simulateur n'est nécessaire.

| Test | Couvre |
|---|---|
| `HudTest` | cœurs, jauge en dix crans, carré de couleur y compris pour un bloc non teinté |
| `ArenaConfigTest` | bornes des réglages, clé inconnue signalée, énumération de ce qui manque à une arène, validation des identifiants |
| `LeaderboardTest` | tri du classement : victoires d'abord, dés à coudre pour départager, taille du TOP bornée |

**Le reste ne se teste pas hors serveur, et ce n'est pas un choix.** `forceStart()` finit par
téléporter un joueur ; tout ce qui touche à la partie appelle `Bukkit`. Il faudrait un simulateur
de serveur : MockBukkit s'arrête à la 1.21, rien n'existe pour la ligne 26.x.

Extraire l'ordre des tours pour le rendre testable n'est pas la réponse : les deux vrais bugs de
rotation rencontrés — un tour enchaîné deux fois, un tour qui continuait après la fin de partie —
étaient dans les appelants, pas dans le calcul d'indice. Un test sur une rotation extraite ne les
aurait pas vus. Ce qui les a trouvés, c'est la relecture, et ce qui les empêche de revenir, c'est
d'avoir un seul endroit qui enchaîne un tour.

---

## 10. Ce qui n'est pas fait

| Sujet | Statut |
|---|---|
| Statistiques persistantes (victoires, parties, dés à coudre, cases) | fait — `stats.yml`, affichées sur le panneau de classement (`/th admin setboard`). Seules les parties multi terminées normalement comptent : le solo est de l'entraînement, le forcestop ne passe pas par `end()`, et une partie où il ne reste qu'un joueur classé (adversaires partis) ne compte pas, sans quoi un second compte donnerait des victoires gratuites |
| `/th top` en commande, records solo | non fait — le panneau suffit tant que personne ne demande le classement en chat |
| PlaceholderAPI, récompenses Vault, stockage SQL | non fait |
| Mode simultané (tous les joueurs sautent en même temps) | non fait, le seul ajout de mode qui vaudrait le coup |
| Mort subite | non fait |
| Sélection WorldEdit | abandonné : la baguette fait le travail, sans dépendance, et gère l'eau |
| Import/export d'arène | abandonné : `arenas.yml` est du texte, une section se copie |
| API pour plugins tiers | abandonné tant que personne ne demande à s'y greffer |
| Traînée de particules à la couleur du joueur | non fait |

### Propositions écartées, et pourquoi

**Compteur d'eau incrémental.** Un balayage coûte 49 lectures de blocs, une fois par seconde. Le
compteur devrait être maintenu à quatre endroits, dont la réouverture de case du sabotage : on
échangerait un coût nul contre une dérive silencieuse.

**Cache des accesseurs de configuration.** `getConfig().getInt()` est une lecture de map. Sans
mesure, c'est de l'optimisation à l'aveugle, et ça n'enlève aucune ligne.

**Découpage d'`Arena` en trois classes.** Le plan de coupe est juste — configuration, piscine,
partie — mais il ajoute un fichier et une couche de délégation sans supprimer une ligne. À faire
au moment où la persistance sera retouchée, pas en commit isolé.

**Balayage du segment de chute.** La vitesse terminale est de 3,92 blocs/tick et la fenêtre de
détection en fait 4 : elle ne peut pas être franchie, et l'eau freine ensuite sur plusieurs ticks.

---

## Sources

- [Java Edition 26.2 — Minecraft Wiki](https://minecraft.wiki/w/Java_Edition_26.2)
- [Nouveau système de numérotation — Minecraft.net](https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system)
- [Communauté:Dé à coudre — Minecraft Wiki FR](https://fr.minecraft.wiki/w/Communaut%C3%A9:D%C3%A9_%C3%A0_coudre)
- [plugin.yml](https://docs.papermc.io/paper/dev/plugin-yml/) · [Paper plugins](https://docs.papermc.io/paper/reference/paper-plugins/) — PaperMC Docs
