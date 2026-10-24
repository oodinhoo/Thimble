# Thimble — specification

**PaperMC 26.2** plugin for the "thimble" mini-game (*dé à coudre*). This document describes the
plugin as it is, and the decisions that cannot be read in the code. The change history is in git.

---

## 1. The game

The *dé à coudre* is a Minecraft sport invented on the French server Minefield by Tenshinryu and
Skayloss, popularized under that name by Bob Lennon, and known internationally as *Thimble*.

Players take turns jumping from a diving board into a pool. Landing in the water places a block of
their color where they hit: the pool shrinks with every jump. Missing costs a life. **The last
player alive wins.**

### 1.1 Arena

| Element | Recommended | Constraint |
|---|---|---|
| Pool | 7×7, 3 blocks deep | rectangular area defined by two corners |
| Playing surface | the highest Y of the two corners | the only level where blocks are placed and counted |
| Diving board | 20 to 30 blocks above the water | nothing enforces it technically |
| Stands | view of the pool | waiting lobby and return point after each jump |

### 1.2 Game flow

1. **Waiting** — `/th join`. The countdown starts at `min` players (15 s, cut to 5 s when the arena is full).
2. **Start** — a unique color per player, `lives` lives each (3 by default).
3. **Turn** — teleport to the diving board, one second frozen, 15 s timer.
   - landing in the water → the block is placed, 2 s in place, back to the stands;
   - landing on a block, falling into the void, or timer out → one life lost, immediate return.
4. **Elimination** — at 0 lives, the player becomes a spectator; the elimination order makes the ranking.
5. **End** — last survivor, or full pool (refill or end depending on `refill-when-full`). Podium,
   then players **stay in place** and a new game follows.

### 1.3 The thimble

Landing in a **1×1 hole** — the four neighbouring blocks already filled. The player is then put in
flight above the pool, looking down, frozen: they have 8 seconds to aim and right-click either **a
water block** (it gets filled) or **a placed block** (it gets reopened). Without a choice in time, a
block is picked at random.

Sabotage only accepts blocks placed during the game: they come from the restoration log, so the
floor and the edges of the pool are out of reach without any check having to say so.

### 1.4 Solo mode

`/th solo [arena]` starts a one-player game on a free and empty arena, without countdown. The mode
is **inferred** at start (`order.size() == 1`), not configured: the same arena serves solo and
multiplayer. Three rules change.

| Rule | Multiplayer | Solo |
|---|---|---|
| End of game | at the last survivor | when the player has no lives left |
| Full pool | refill | end of game, counted as a win |
| Summary | winner and podium | blocks filled and thimbles landed |

A perfect run requires the pool to be truly full, not just the player to be alive: an interrupted
game shows the plain summary.

---

## 2. Technical target

| Element | Choice |
|---|---|
| Server | **PaperMC 26.2**, `io.papermc.paper:paper-api:26.2.build.112-stable` (pinned version) |
| Java | **JDK 25**, mandatory since Minecraft 26.1 |
| Build | Gradle 9.7.1 + wrapper, JUnit 5 for tests |
| Manifest | `plugin.yml`, `api-version: '26.2'` |
| Text | Adventure / MiniMessage, no hard-coded `§` colors |
| Commands | `CommandExecutor` + `TabCompleter` |

**Bukkit is not deprecated**: `paper-api` *is* the extended Bukkit API, `org.bukkit.*` remains the
main surface of Paper 26.2. Checked in the API jar's bytecode — neither `CommandExecutor`, nor
`PluginCommand`, nor `JavaPlugin#getCommand`, nor the `commands:` section of `plugin.yml` carry the
`Deprecated` attribute; only `JavaPluginLoader`, which no plugin calls, is marked. What is moving is
*loading* (`paper-plugin.yml` and Brigadier), with no announced removal date.
`JavaPlugin#registerCommand` would allow real Brigadier argument types without migrating the
manifest: a single file to rework, the day it is worth it.

### 2.1 File tree

A flat package, seven classes. Splitting into sub-packages would bring nothing at this size.

```text
Thimble/
├── build.gradle.kts · settings.gradle.kts · gradlew(.bat)
├── docs/SPECIFICATIONS.md
└── src/
    ├── main/java/fr/thimble/
    │   ├── ThimblePlugin.java   # lifecycle, arenas, config, messages, tick, wand, restorations
    │   ├── Arena.java           # arena configuration + running game
    │   ├── ThimbleCommand.java  # /th and /th admin, tab completion
    │   ├── GameListener.java    # freeze, protections, join/quit, right-click
    │   ├── Hud.java             # titles, sounds, boss bar, action bar, scoreboard, particles
    │   ├── Leaderboard.java     # persistent leaderboard + last game, shown on a TextDisplay
    │   └── PlayerSnapshot.java  # player state, in memory and on disk
    ├── main/resources/          # plugin.yml, config.yml, messages.yml
    └── test/java/fr/thimble/    # HudTest, ArenaConfigTest, LeaderboardTest
```

---

## 3. State machine

```text
DISABLED ──enable──▶ WAITING
WAITING  ──(≥ min players)──▶ STARTING ──(countdown)──▶ PLAYING
STARTING ──(< min players)──▶ WAITING
PLAYING  ──(1 survivor | 0 survivors | forcestop | full pool in solo)──▶ ENDING
ENDING   ──(delay)──▶ WAITING          # players stay: the next game follows
ENDING   ──(solo or empty arena)──▶ WAITING, players sent back to where they started
```

A turn, in `tickTurn()`: optional pause → bonus choice → freeze on the diving board → fall tracking
and resolution → timer. Everything is driven by the tick, never by a delayed task: a `forcestop` or
a reload therefore cannot leave an orphan action firing in the next game.

---

## 4. Detection

### 4.1 Jump resolution

Evaluated **on every tick**, not on `PlayerMoveEvent`: a player who lands on a block and lets go of
the keys emits no more movement, so their turn only ended when the timer ran out. `PlayerMoveEvent`
is now only used for freezing.

- the jump starts when the player has dropped below the diving board (one-block margin);
- feet column inside the region and block `(x, surfaceY, z)` is water → **success**;
- solid block under **one of the four corners of the hitbox** → **miss**. Testing the center column
  alone let through a player stopped on the edge of a block, whose center overhangs the
  neighbouring column: the turn stayed pending and a step toward the water turned the miss into a
  success;
- falling below the pool floor or below the world's minimum height → **miss**;
- `Player#isOnGround()` is not an option: it is supplied by the client, therefore spoofable (and
  deprecated for that reason).

The vertical detection window is at least 4 blocks, even when both corners are placed at the same
level — otherwise a fast-falling player could pass through it between two ticks.

### 4.2 Blocks aimed with the mouse

Water is not a solid block: **the client never sends a click on it**. The target is therefore
computed server-side by a ray trace with `FluidCollisionMode.ALWAYS`, both for the bonus choice and
for the setup wand. `RIGHT_CLICK_AIR` and `RIGHT_CLICK_BLOCK` are both accepted, since the client
sends one or the other depending on what lies behind the water — and the listener filters
`EquipmentSlot.HAND`, otherwise every click would count twice, the event being fired once per hand.

### 4.3 Pool

Placed blocks are logged with their original state, which serves three purposes: restoration at the
end of the game, sabotage (only these blocks can be reopened), and refilling when the pool is full.
Without a log — after a restart or a crash — `restorePool()` repaints the surface from the geometry,
otherwise the arena would stay filled forever.

### 4.4 Protections

In an arena: placing, breaking, dropping, inventory clicks, hunger and **all** damage are cancelled.
The player is never actually killed, lives are counted by the plugin. Blocks are placed without
firing `BlockPlaceEvent`, so without going through WorldGuard — deliberate, but worth knowing.
Protecting the region against players **outside** the arena is another plugin's job.

A player **waiting for their turn** is pushed back at the edge of the pool (invisible wall on
`PlayerMoveEvent`, shared with the diving board freeze): without it they could fall into the water
and disrupt detection. The jumper and the player aiming at their bonus block are exempt, as are
eliminated players and spectators.

---

## 5. Commands and permissions

| Command | Role | Permission |
|---|---|---|
| `/th join [arena]` | join; without argument, the fullest free arena | `thimble.play` |
| `/th solo [arena]` | practice game, immediate start | `thimble.play` |
| `/th spectate <arena>` | watch | `thimble.spectate` |
| `/th leave` · `/th list` · `/th help` | — | `thimble.play` |
| `/th admin wand <arena>` | guided setup in 4 clicks | `thimble.admin` |
| `/th admin create\|delete <arena>` | — | `thimble.admin` |
| `/th admin setwait\|setjump\|setspectator\|pos1\|pos2 <arena>` | placement at the current position | `thimble.admin` |
| `/th admin setsurface <arena> [y]` | force the playing surface | `thimble.admin` |
| `/th admin set <arena> <min\|max\|lives\|timeout> <value>` | — | `thimble.admin` |
| `/th admin palette <arena> <add\|remove\|clear\|list> [block]` | — | `thimble.admin` |
| `/th admin enable\|disable\|info\|restore <arena>` | — | `thimble.admin` |
| `/th admin forcestart\|forcestop <arena>` | — | `thimble.admin` |
| `/th admin setboard\|clearboard` | place or remove the leaderboard panel (no arena argument) | `thimble.admin` |
| `/th admin reload` | reload config, messages and arenas | `thimble.admin` |

The **wand** replaces the series of placement commands: four right-clicks — corner, opposite
corner, diving board, stands — the arena being created if needed and enabled on the last click if
everything is consistent. The stands set the waiting lobby and the return point in one go, since
they are the same spot in nearly every arena; `setwait` and `setspectator` let you separate them.

An arena identifier is validated (`[a-z0-9_-]{1,32}`): Bukkit reads the dot as a path separator,
and `pool.1` would nest the section to the point of making the arena disappear on reload.

A **join sign** joins without a command: first line `[Thimble]`, second line the target arena
(empty = the fullest free arena). The right-click is intercepted before the sign editor opens. It
is a toggle: outside a game it joins (`/th join`), in a game it leaves (`/th leave`) — a single
sign, no separate "leave" sign. It requires the same `thimble.play` permission as `/th join`.

---

## 6. Files

| File | Content |
|---|---|
| `config.yml` | durations, `refill-when-full`, default palette, `ui` section |
| `messages.yml` | every text, in MiniMessage |
| `arenas.yml` | one section per arena: positions, corners, settings, palette |
| `pending-restore.yml` | player states to return after an interrupted game |
| `stats.yml` | cumulative stats per player, leaderboard panel position, last game's podium |

Everything in `config.yml` is in seconds, the conversion to ticks happens where it is used. The
jar's texts serve as defaults: an update that adds messages does not require deleting the existing
file, and customizations are kept.

A loaded arena goes through the same bounds as the command (`min ≥ 2`, `lives ≥ 1`, `timeout ≥ 3`)
and is only enabled if its configuration is complete: a hand-edited `arenas.yml` can no longer
produce a zero `timeout` or a teleport to a missing position.

---

## 7. Display

| Channel | Content |
|---|---|
| Scoreboard | players with a color square, lives as hearts, cursor on the jumper, eliminated players struck through; round, water left, gauge, arena name |
| Action bar | the current instruction: waiting, your turn, bonus choice |
| Boss bar | countdown before start, turn timer (red under 5 s), bonus choice |
| Titles | game start, your turn, thimble, miss (in hearts), elimination, win |
| Chat | facts only: placements, thimbles, misses, eliminations, podium |
| Sounds and particles | trail in the player's color during the fall, burst on a thimble |
| Leaderboard panel | floating `TextDisplay` placed with `/th admin setboard`: the server's TOP (wins, thimbles) and the last game's podium, refreshed at the end of a game, never on the tick |

Each channel can be turned off separately in the `ui` section of `config.yml`. When
`ui.leaderboard` is off, the panel is removed from the world but stats keep being counted.

The leaderboard panel is a world entity, not a client overlay: it survives restarts. It carries a
tag (`thimble_board`) used to find and remove duplicates on `reload` or after a crash — that is the
only pitfall of this channel, not the rendering.

A player's color comes from `DyeColor`, recognized by the material prefix (`RED_WOOL`,
`BLUE_CONCRETE`…): any dyed palette works without a table to maintain.

That same color tints the player's dot on the **locator bar** (the "radar") and their name tag.
Paper 26.2 exposes no waypoint API; the scoreboard team color is the only server-side lever, and the
game gives it priority over the default color derived from the UUID. It is limited to the sixteen
named colors, hence the use of `NamedTextColor.nearestTo`. Can be turned off with `ui.radar`.

Two limits of the game, not of the plugin: a boss bar only has six colors and gold is not one of
them, so the bonus uses purple with gold text; and an action bar fades after three seconds, hence
resending it every second, piggybacked on the scoreboard refresh.

---

## 8. Robustness

**The player's state is written to disk as soon as they enter the arena.** Minecraft saves players
every five minutes: a crash mid-game would otherwise persist the empty inventory, with no way back.
The file is read again on the next login, which also settles the return position — a teleport
during `PlayerQuitEvent` not being reliable.

**An arena that throws an exception is isolated**, logged and disabled: without that it stopped the
tick of every following arena, twenty times per second.

**A disconnection mid-game keeps the seat** for `rejoin-grace-seconds`: the inventory is returned
right away, the absent player's turns are skipped, and they get their seat back if they return in
time.

**`arenaOf(player)` is indexed**, not scanned: it is called on every movement packet of every
connected player, including those not playing.

---

## 9. Tests

Ten tests, on what touches neither the world nor players: `paper-api` on the test classpath is
enough, no simulator is needed.

| Test | Covers |
|---|---|
| `HudTest` | hearts, ten-step gauge, color square including for an undyed block |
| `ArenaConfigTest` | setting bounds, unknown key reported, list of what an arena is missing, identifier validation |
| `LeaderboardTest` | leaderboard sorting: wins first, thimbles as tie-breaker, bounded TOP size |

**The rest cannot be tested outside a server, and that is not a choice.** `forceStart()` ends up
teleporting a player; everything about the game calls `Bukkit`. A server simulator would be needed:
MockBukkit stops at 1.21, nothing exists for the 26.x line.

Extracting the turn order to make it testable is not the answer: the two real rotation bugs found —
a turn advanced twice, a turn that kept going after the end of the game — were in the callers, not
in the index computation. A test on an extracted rotation would not have caught them. What found
them was review, and what keeps them from coming back is having a single place that advances a
turn.

---

## 10. What is not done

| Topic | Status |
|---|---|
| Persistent stats (wins, games, thimbles, blocks) | done — `stats.yml`, shown on the leaderboard panel (`/th admin setboard`). Only multiplayer games that end normally count: solo is practice, forcestop never goes through `end()`, and a game with a single ranked player left (opponents gone) does not count, otherwise a second account would give free wins |
| `/th top` command, solo records | not done — the panel is enough as long as nobody asks for the leaderboard in chat |
| PlaceholderAPI, Vault rewards, SQL storage | not done |
| Simultaneous mode (all players jump at the same time) | not done, the only extra mode worth adding |
| Sudden death | not done |
| WorldEdit selection | dropped: the wand does the job, without a dependency, and handles water |
| Arena import/export | dropped: `arenas.yml` is text, a section can be copied |
| API for third-party plugins | dropped until someone asks to hook into it |
| Particle trail in the player's color | done |

### Rejected proposals, and why

**Incremental water counter.** A scan costs 49 block reads, once per second. The counter would have
to be maintained in four places, including the block reopening of sabotage: a zero cost would be
traded for a silent drift.

**Caching config accessors.** `getConfig().getInt()` is a map read. Without a measurement, it is
blind optimization, and it removes no line.

**Splitting `Arena` into three classes.** The cut is right — configuration, pool, game — but it adds
a file and a delegation layer without removing a line. To be done when persistence is reworked, not
as a standalone commit.

**Scanning the fall segment.** Terminal velocity is 3.92 blocks/tick and the detection window is 4:
it cannot be skipped, and water then slows the player down over several ticks.

---

## Sources

- [Java Edition 26.2 — Minecraft Wiki](https://minecraft.wiki/w/Java_Edition_26.2)
- [New version numbering system — Minecraft.net](https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system)
- [Community:Dé à coudre — French Minecraft Wiki](https://fr.minecraft.wiki/w/Communaut%C3%A9:D%C3%A9_%C3%A0_coudre)
- [plugin.yml](https://docs.papermc.io/paper/dev/plugin-yml/) · [Paper plugins](https://docs.papermc.io/paper/reference/paper-plugins/) — PaperMC Docs
