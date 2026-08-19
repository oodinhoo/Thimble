# Thimble — Spécifications du mini-jeu « Dé à coudre »

> Plugin serveur Minecraft **Java Edition 26.2** (PaperMC), permettant de créer des arènes et
> de jouer au mini-jeu du dé à coudre.
>
> Statut : spécification fonctionnelle et technique — version 1.0 (à valider avant implémentation).

---

## 1. Le jeu

### 1.1 Origine

Le « dé à coudre » est un sport Minecraft inventé par deux joueurs du serveur français Minefield
(Tenshinryu et Skayloss) et popularisé sous ce nom par Bob Lennon. Il est connu à l'international
sous le nom **Thimble**.

### 1.2 Principe en une phrase

Chaque joueur saute à tour de rôle du haut d'un plongeoir vers une piscine ; s'il atterrit dans
l'eau, il pose un bloc de sa couleur à l'endroit exact de son impact — la piscine rétrécit donc à
chaque saut, jusqu'à ce qu'il ne reste qu'un ou deux blocs d'eau. Rater la piscine = mourir de
chute = perdre une vie. **Le dernier joueur en vie gagne.**

### 1.3 Terrain

| Élément | Valeur recommandée | Contrainte |
|---|---|---|
| Hauteur du plongeoir | 20 à 30 blocs au-dessus de la surface de l'eau | doit être létale en cas de chute hors piscine |
| Surface de la piscine | 5×5 à 11×11 (7×7 par défaut) | zone rectangulaire, définie par 2 coins |
| Profondeur de la piscine | 2 à 3 blocs | 1 seul niveau de « surface » est jouable |
| Plateforme de saut | 1×1 à 3×3, barrières/vitres autour | le joueur y est téléporté à son tour |
| Zone de spectateur | libre | hors zone de jeu |

La **surface de jeu** est le plan horizontal `y = poolSurfaceY` : c'est le seul niveau où des blocs
sont posés et où les cases sont comptées. Les niveaux inférieurs restent en eau pour amortir la
chute.

### 1.4 Déroulement d'une partie

1. **Attente** — les joueurs rejoignent l'arène (`/th join`), attendent dans le lobby d'attente.
2. **Démarrage** — dès que `minPlayers` est atteint, décompte (30 s par défaut, réduit à 5 s si
   `maxPlayers` est atteint). Chaque joueur reçoit une **couleur unique** (laine/béton) et un
   nombre de vies (`lives`, 3 par défaut).
3. **Manches** — la partie est une succession de manches ; une manche = un tour de jeu pour chaque
   joueur encore en vie, dans un ordre fixé au départ (aléatoire, puis rotatif).
4. **Tour d'un joueur** :
   - téléportation sur le plongeoir, immobilisation courte (`freezeTicks`, 20 ticks) ;
   - libération, le joueur saute ; un chrono (`turnTimeout`, 15 s par défaut) court ;
   - **résolution** à l'atterrissage :
     - *atterrissage dans l'eau de la piscine* → succès : le bloc d'eau touché est remplacé par
       le bloc de la couleur du joueur, le joueur est téléporté hors de la piscine ;
     - *atterrissage sur un bloc / hors de la région* → échec : le joueur perd une vie ;
     - *chrono écoulé sans résolution* → échec (perte d'une vie) ;
   - passage au joueur suivant.
5. **Élimination** — un joueur à 0 vie est éliminé et passe en mode spectateur.
6. **Fin** — la partie s'arrête quand il ne reste qu'un joueur en vie (vainqueur), ou 0 joueur
   (égalité si double élimination en mode simultané), ou si la piscine est entièrement remplie
   (voir 1.7). Récompenses, message de victoire, retour au lobby, **restauration de l'arène**.

### 1.5 Le « dé à coudre » (coup parfait)

On dit qu'un joueur *fait un dé à coudre* lorsqu'il atterrit dans un **trou de 1×1**, c'est-à-dire
une case d'eau dont les 4 voisines orthogonales (option : les 8 voisines) au niveau de la surface
sont déjà remplies. Ce coup est mis en avant (titre, son, particules, statistique dédiée) et
rapporte un **bonus configurable** :

| Bonus | Clé de config | Effet |
|---|---|---|
| Case supplémentaire (défaut) | `extra_block` | le joueur choisit une 2ᵉ case d'eau à remplir |
| Vie supplémentaire | `extra_life` | +1 vie (plafonnée à `maxLives`) |
| Saut sauté | `skip_turn` | le joueur passe son prochain tour sans risque |
| Aucun | `none` | bonus purement cosmétique (points de stats) |

### 1.6 Modes de jeu

- **`TURN` (tour par tour)** — mode historique, un seul sauteur à la fois. Ordre de passage
  affiché. Recommandé par défaut.
- **`CONCURRENT` (simultané)** — tous les joueurs sautent en même temps depuis un plongeoir
  élargi ; l'ordre de résolution est l'ordre d'atterrissage. Deux joueurs ne peuvent pas remplir
  la même case (le second est traité comme un échec ou repoussé, selon `concurrent.collision`).

### 1.7 Cas limites de règles

| Situation | Résolution |
|---|---|
| Plus assez de cases d'eau pour finir la manche | la piscine est réinitialisée (« refill ») et une nouvelle manche démarre, ou la partie s'arrête sur égalité — clé `pool.whenFull` : `refill` \| `draw` \| `suddenDeath` |
| Joueur qui se déconnecte pendant son tour | tour compté comme échec ; s'il revient avant `rejoinGrace` (60 s) il reprend sa place, sinon il est éliminé |
| Joueur qui atterrit sur le bord (hors région mais sur un bloc) | échec |
| Joueur qui atterrit dans l'eau **hors** de la région définie | échec (l'eau hors arène ne compte pas) |
| Deux derniers joueurs qui échouent au même tour (mode simultané) | égalité, aucun vainqueur |
| Joueur qui triche (elytra, perle, bloc posé, vol) | tour annulé + échec, action loguée |

---

## 2. Cible technique

| Élément | Choix | Vérifié |
|---|---|---|
| Serveur | **PaperMC 26.2** (`io.papermc.paper:paper-api:26.2.build.+`, dépôt `https://repo.papermc.io/repository/maven-public/`) | résolu en `26.2.build.112-stable` |
| Java | **JDK 25** (obligatoire depuis Minecraft 26.1) | compilé avec Corretto 25.0.4 |
| Build | Gradle (Kotlin DSL) + wrapper ; `com.gradleup.shadow` seulement quand des libs devront être embarquées (lot 3) | Gradle 9.7.1, `BUILD SUCCESSFUL`, 0 avertissement |
| Manifeste | `plugin.yml` avec `api-version: '26.2'` — le chargement Bukkit historique reste supporté en 26.2 ; migration `paper-plugin.yml` possible plus tard | jar vérifié |
| Texte | Adventure / **MiniMessage** (fourni par Paper) — aucune couleur `§` en dur | compile |
| Commandes | `CommandExecutor` + `TabCompleter` déclarés dans `plugin.yml` ; Brigadier (`LifecycleEvents.COMMANDS`) au lot 2 si besoin de vrais types d'arguments | compile |
| Dépendances optionnelles | WorldEdit (sélection de région), PlaceholderAPI (placeholders), Vault (récompenses économiques) | lots 3-4 |
| Libs embarquées | HikariCP + driver SQLite/MySQL (uniquement si stockage SQL activé) | lot 3 |

> Compatibilité confirmée par la compilation : `paper-api:26.2.build.112-stable` sous JDK 25,
> aucune API dépréciée utilisée. Le jar produit (`build/libs/Thimble-0.1.0.jar`) embarque
> `plugin.yml` (`api-version: '26.2'`), `config.yml` et `messages.yml`.

### 2.1 Arborescence du projet

Structure réelle du lot 1 — un package plat, cinq classes. Le découpage en sous-packages
(`arena/`, `game/`, `storage/`, `ui/`…) ne sera introduit que quand les lots 2 et 3 le rendront
nécessaire.

```
Thimble/
├── build.gradle.kts · settings.gradle.kts · gradlew(.bat)
├── docs/SPECIFICATIONS.md
└── src/main/
    ├── java/fr/thimble/
    │   ├── ThimblePlugin.java     # cycle de vie, arènes YAML, config, messages, boucle de tick
    │   ├── Arena.java             # config d'arène + partie : états, tours, sauts, piscine
    │   ├── ThimbleCommand.java    # /th et /th admin, avec complétion
    │   ├── GameListener.java      # atterrissage, protections, déconnexion
    │   └── PlayerSnapshot.java    # sauvegarde/restauration de l'état du joueur
    └── resources/
        ├── plugin.yml
        ├── config.yml
        └── messages.yml
```

---

## 3. Modèle de données

```
Arena
  id: String (unique, [a-z0-9_-])
  world: UUID
  enabled: boolean
  state: WAITING | STARTING | PLAYING | ENDING | RESETTING | DISABLED
  waitLobby: Location          # lobby d'attente de l'arène
  jumpSpot: Location           # plongeoir
  spectatorSpot: Location      # point d'observation / retour après saut
  pool: PoolRegion             # min/max (BlockVector3), surfaceY
  minPlayers / maxPlayers: int (2..16, défaut 2..8)
  lives: int (défaut 3)
  mode: TURN | CONCURRENT
  turnTimeoutSeconds: int (défaut 15)
  palette: List<Material>      # blocs de couleur attribuables

Game (runtime, 1 par arène active)
  players: LinkedHashMap<UUID, ThimblePlayer>   # ordre de passage
  round: int, turnIndex: int
  snapshot: PoolSnapshot                        # blocs modifiés pour restauration
  startedAt: Instant

ThimblePlayer
  uuid, name, block: Material, lives: int, alive: boolean
  jumps: int, hits: int, thimbles: int, bestStreak: int
  previous: PlayerSnapshot   # inventaire, xp, gamemode, vie, faim, effets, location

PlayerStats (persisté)
  uuid, gamesPlayed, wins, jumps, successfulJumps, thimbles, bestStreak, playtimeSeconds
```

---

## 4. Machine à états

```
DISABLED ──enable──▶ WAITING
WAITING ──(≥ minPlayers)──▶ STARTING ──(countdown=0)──▶ PLAYING
STARTING ──(< minPlayers)──▶ WAITING
PLAYING  ──(1 survivant | 0 survivant | /th admin forcestop)──▶ ENDING
ENDING   ──(délai fin, téléport, récompenses)──▶ RESETTING
RESETTING ──(piscine restaurée)──▶ WAITING
```

Sous-machine d'un tour (état `PLAYING`, mode `TURN`) :

```
TURN_PREPARE  → téléportation plongeoir, freeze, titre « À toi de jouer »
TURN_ACTIVE   → joueur libéré, chrono lancé, suivi de la chute
TURN_RESOLVE  → succès (pose du bloc / bonus) ou échec (perte de vie)
TURN_NEXT     → joueur suivant, ou fin de manche
```

---

## 5. Règles techniques de détection

### 5.1 Atterrissage réussi

Écoute de `PlayerMoveEvent` (filtrée sur le seul sauteur actif, comparaison de blocs et non de
positions pour éviter le coût) :

- le saut ne commence qu'une fois le joueur descendu sous le plongeoir (marge d'un bloc) ;
- si la colonne des pieds est dans `pool` et que le bloc `(x, pool.surfaceY, z)` est
  `Material.WATER` → **succès** ;
- sinon, atterrissage sur un bloc → **échec**. Le test est fait **côté serveur** (bloc solide
  sous les pieds + joueur pile à la limite du bloc) : `Player#isOnGround()` est déprécié car
  renseigné par le client, donc falsifiable ;
- chute dans le vide (sous le fond de la piscine, ou sous la hauteur minimale du monde) → **échec** ;
- tous les `EntityDamageEvent` des joueurs en partie sont **annulés** (le plugin gère lui-même
  les vies) ; le joueur n'est jamais réellement tué.

### 5.2 Pose du bloc

Le bloc d'eau touché (`x`, `surfaceY`, `z`) est remplacé par `player.block`, avec enregistrement du
`BlockState` original dans `PoolSnapshot`. Le bloc n'est jamais posé par le joueur lui-même (tout
`BlockPlaceEvent` en arène est annulé).

### 5.3 Détection du dé à coudre

Après validation de la case `(x, z)`, on inspecte les voisins orthogonaux au niveau `surfaceY`
(`neighbourMode: ORTHOGONAL | DIAGONAL`) : si aucun n'est de l'eau *appartenant à la piscine*,
c'est un dé à coudre.

### 5.4 Restauration de l'arène

`PoolSnapshot` contient uniquement les blocs modifiés. Restauration **asynchrone par lots**
(`restore.blocksPerTick`, 200 par défaut) via `Block#setBlockData(data, false)` pour éviter les
mises à jour physiques en cascade. Un snapshot est aussi persisté sur disque
(`arenas/<id>.snapshot`) afin de pouvoir restaurer une arène après un crash serveur.

### 5.5 Protections en arène

Annulés pour les joueurs en partie : `BlockPlaceEvent`, `BlockBreakEvent`, `PlayerDropItemEvent`,
`InventoryClickEvent`, `EntityDamageByEntityEvent` (PvP), `FoodLevelChangeEvent`,
`PlayerInteractEvent` (sauf items de menu), utilisation d'élytres / perles de l'Ender / chorus.
Les joueurs sont en `GameMode.ADVENTURE` (spectateurs en `SPECTATOR`), invulnérables hors saut.

---

## 6. Commandes

Préfixe : `/thimble`, alias `/th`.

### 6.1 Joueur

| Commande | Description | Permission |
|---|---|---|
| `/th join [arène]` | rejoint une arène (sans argument : l'arène libre la plus remplie) | `thimble.play` |
| `/th leave` | quitte la partie / la file | `thimble.play` |
| `/th list` | liste les arènes, leur état et le nombre de joueurs | `thimble.play` |
| `/th spectate <arène>` | observe une partie en cours | `thimble.spectate` |
| `/th stats [joueur]` | statistiques personnelles (ou d'un autre joueur) | `thimble.stats` / `thimble.stats.other` |
| `/th top [victoires\|dés\|sauts]` | classement (10 premiers) | `thimble.stats` |
| `/th help` | aide contextuelle | `thimble.play` |

### 6.2 Administration (`thimble.admin`)

| Commande | Description |
|---|---|
| `/th admin create <arène>` | crée une arène vide (désactivée) |
| `/th admin delete <arène>` | supprime une arène |
| `/th admin setwait <arène>` | définit le lobby d'attente à la position courante |
| `/th admin setjump <arène>` | définit le plongeoir |
| `/th admin setspectator <arène>` | définit le point de retour / d'observation |
| `/th admin setpool <arène>` | définit la piscine depuis la sélection WorldEdit (ou `pos1`/`pos2` internes) |
| `/th admin setsurface <arène> [y]` | définit le niveau de surface (par défaut : Y courant) |
| `/th admin set <arène> <min\|max\|lives\|mode\|timeout> <valeur>` | paramètres de partie |
| `/th admin palette <arène> <add\|remove\|list> [bloc]` | gère les blocs de couleur |
| `/th admin enable\|disable <arène>` | active / désactive l'arène (validation complète avant activation) |
| `/th admin forcestart\|forcestop <arène>` | force le démarrage / l'arrêt |
| `/th admin restore <arène>` | restaure manuellement la piscine |
| `/th admin setlobby` | définit le lobby global (retour après partie) |
| `/th admin reload` | recharge configuration et messages |

Toutes les commandes sont **auto-complétées** (Brigadier) et vérifiées : activer une arène
incomplète renvoie la liste précise des éléments manquants.

### 6.3 Permissions

```
thimble.play          (default: true)
thimble.stats         (default: true)
thimble.stats.other   (default: op)
thimble.spectate      (default: true)
thimble.admin         (default: op)   # implique toutes les sous-commandes admin
thimble.join.full     (default: op)   # rejoindre une partie pleine (kick d'un joueur inactif)
thimble.bypass.cooldown (default: op)
```

---

## 7. Configuration

### 7.1 `config.yml` (extrait)

```yaml
language: fr
storage:
  type: yaml            # yaml | sqlite | mysql
  mysql: { host: localhost, port: 3306, database: thimble, user: root, password: "", pool-size: 5 }
game:
  countdown-seconds: 30
  countdown-full-seconds: 5
  end-delay-seconds: 8
  freeze-ticks: 20
  turn-timeout-seconds: 15
  default-lives: 3
  max-lives: 5
  wait-as-spectator: true      # les retardataires observent au lieu d'être refusés
  rejoin-grace-seconds: 60
thimble:
  neighbour-mode: ORTHOGONAL   # ORTHOGONAL | DIAGONAL
  bonus: extra_block           # extra_block | extra_life | skip_turn | none
pool:
  when-full: refill            # refill | draw | sudden_death
  restore-blocks-per-tick: 200
rewards:
  win: { money: 100, commands: ["give %player% diamond 1"] }
  thimble: { money: 10, commands: [] }
ui:
  scoreboard: true
  bossbar: true
  titles: true
  sounds: true
```

### 7.2 `arenas/<id>.yml`

Sérialisation complète de l'objet `Arena` (monde, positions, région, paramètres, palette).
Fichier par arène pour faciliter l'édition et l'export.

### 7.3 `messages.yml`

Tous les textes en **MiniMessage**, avec placeholders `<player>`, `<arena>`, `<lives>`,
`<remaining>`, `<time>`. Fichiers `messages_fr.yml` / `messages_en.yml` fournis.

---

## 8. Interface joueur

- **Scoreboard** (pendant la partie) : nom de l'arène, manche, joueur en train de sauter, liste des
  joueurs avec couleur et vies restantes, cases d'eau restantes.
- **BossBar** : décompte avant démarrage, puis chrono du tour en cours.
- **Titres / sous-titres** : « À toi de jouer », « Dé à coudre ! », « Raté ! — 2 vies restantes »,
  « <joueur> remporte la partie ».
- **Sons** : `BLOCK_NOTE_BLOCK_PLING` (tour), `ENTITY_PLAYER_LEVELUP` (dé à coudre),
  `ENTITY_ITEM_BREAK` (échec), `UI_TOAST_CHALLENGE_COMPLETE` (victoire).
- **Particules** : traînée pendant la chute (couleur du joueur), explosion de particules sur un dé
  à coudre.
- **Tab list** : préfixe coloré selon la couleur attribuée.
- **Items de lobby** : boussole « choisir une arène », lit « quitter » (hotbar du lobby d'attente).

---

## 9. Statistiques et persistance

- Métriques suivies : parties jouées, victoires, sauts, sauts réussis (+ ratio), dés à coudre,
  meilleure série de réussites, temps de jeu.
- Backends : **YAML** (défaut, petits serveurs), **SQLite**, **MySQL** (réseaux multi-serveurs).
- Toutes les E/S sont **asynchrones** ; cache mémoire par joueur connecté, écriture différée
   (batch toutes les 60 s + à la déconnexion + à l'arrêt du plugin).
- Placeholders PlaceholderAPI : `%thimble_wins%`, `%thimble_games%`, `%thimble_thimbles%`,
  `%thimble_ratio%`, `%thimble_arena_<id>_state%`, `%thimble_arena_<id>_players%`.

---

## 10. Contraintes non fonctionnelles

| Contrainte | Exigence |
|---|---|
| Performance | aucune opération bloquante sur le thread principal ; restauration de piscine étalée sur plusieurs ticks ; `PlayerMoveEvent` filtré au bloc et au seul sauteur actif |
| Concurrence | plusieurs arènes simultanées (limite configurable, 20 par défaut) sans interférence |
| Robustesse | reprise après crash (snapshots persistés) ; `onDisable` remet tous les joueurs dans leur état d'origine et restaure les arènes |
| Sécurité des données joueur | inventaire, XP, effets, mode de jeu et position sauvegardés à l'entrée, restaurés à la sortie (y compris en cas de kick, de reload ou d'arrêt serveur) |
| Compatibilité | Paper 26.2+ ; dégradation propre si WorldEdit / PlaceholderAPI / Vault sont absents |
| i18n | tous les textes externalisés, aucun texte en dur dans le code |

---

## 11. Qualité et livraison

- Tests unitaires JUnit 5 sur la logique pure (ordre des tours, résolution d'un saut, détection du
  dé à coudre, machine à états) — la logique de jeu est isolée de l'API Bukkit pour être testable.
- Tests d'intégration avec MockBukkit sur les listeners et commandes.
- CI GitHub Actions : build Gradle sur JDK 25, tests, publication de l'artefact.
- Versionnage sémantique, `CHANGELOG.md`.

### Lots

| Lot | Contenu | Objectif |
|---|---|---|
| **L1 — MVP** | arènes (création/config/persistance), mode TURN, détection de saut, pose de bloc, vies, victoire, restauration, commandes joueur + admin, messages | jouable de bout en bout |
| **L2 — Confort** | scoreboard, bossbar, titres, sons, particules, spectateurs, dé à coudre + bonus, reconnexion | expérience serveur public |
| **L3 — Données** | statistiques, `top`, SQLite/MySQL, PlaceholderAPI, récompenses Vault | rétention et intégration réseau |
| **L4 — Avancé** | mode CONCURRENT, sudden death, sélection WorldEdit, import/export d'arène, API pour plugins tiers | complétude |

---

## 12. Points à valider

1. **Package / nom de domaine** : `fr.thimble` proposé — à confirmer.
2. **Mode par défaut** : `TURN` (tour par tour) proposé.
3. **Bonus du dé à coudre** : case supplémentaire au choix proposé.
4. **Stockage par défaut** : YAML (SQLite si multi-arène intensif).
5. **Périmètre du premier lot** : le L1 seul, ou L1 + L2 d'un bloc ?
6. **Lobby global** : le plugin gère-t-il un hub (téléportation à la connexion), ou reste-t-il
   strictement « arènes à la demande » ?

---

## Sources

- [Java Edition 26.2 — Minecraft Wiki](https://minecraft.wiki/w/Java_Edition_26.2)
- [Minecraft new version numbering system — Minecraft.net](https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system)
- [Communauté:Dé à coudre — Le Minecraft Wiki (fr)](https://fr.minecraft.wiki/w/Communaut%C3%A9:D%C3%A9_%C3%A0_coudre)
- [Thimble — SpigotMC](https://www.spigotmc.org/resources/thimble.88191/) et [syldium/Thimble — GitHub](https://github.com/syldium/Thimble)
- [Project setup — PaperMC Docs](https://docs.papermc.io/paper/dev/project-setup/) · [plugin.yml — PaperMC Docs](https://docs.papermc.io/paper/dev/plugin-yml/)
- [paper-api 26.2 javadoc](https://jd.papermc.io/paper/26.2/)

---

## 13. Decisions validees et ecarts du lot 1

Decisions (2026-08-19) : package `fr.thimble` · bonus du de a coudre = case supplementaire ·
stockage YAML · premiere version = **jeu jouable, retours dans le chat** · le plugin gere
**uniquement le mini-jeu** (aucun hub, le joueur revient exactement ou il etait).

Ecarts assumes par rapport aux sections ci-dessus, a lever aux lots suivants :

| Ecart | Raison | A faire |
|---|---|---|
| Commandes Bukkit classiques (`plugin.yml`) au lieu de Brigadier | chargement Bukkit toujours supporte en 26.2, API stable | migration Brigadier au lot 2 si besoin de vrais types d'arguments |
| Bonus du de a coudre : case **tiree au sort** au lieu de choisie | selectionner une case demande une UI fiable | selecteur au lot 2 (`ponytail:` dans `Arena#hit`) |
| Restauration de la piscine synchrone | une piscine fait au plus quelques centaines de blocs | lots par tick si des arenes geantes apparaissent |
| Pas de snapshot persiste sur disque | evite un format de fichier de plus | a ajouter si des crashs laissent des arenes sales |
| Pas de statistiques, ni scoreboard, ni spectateurs | lot 1 volontairement nu | lots 2 et 3 |
| Region definie par `pos1`/`pos2` (position du joueur) | pas de dependance WorldEdit | adaptateur WorldEdit au lot 4 |

### 13.1 Verification de compatibilite (2026-08-19)

`gradlew build` sous **JDK 25 (Corretto 25.0.4)** avec **Gradle 9.7.1** :
`io.papermc.paper:paper-api:26.2.build.+` resolu en **26.2.build.112-stable**, `BUILD SUCCESSFUL`,
aucun avertissement de compilation ni d'API depreciee. Artefact : `build/libs/Thimble-0.1.0.jar`.

Reste a valider **en jeu** sur un serveur Paper 26.2 : le comportement des sauts (detection
d'atterrissage), la restauration de l'inventaire a la deconnexion, et le nettoyage de la piscine
en fin de partie.
