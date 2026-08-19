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

Construire une tour de saut (~20-30 blocs au-dessus de l'eau) et une piscine de 2 à 3 blocs de
profondeur, puis, en jeu :

```
/th admin create piscine1
/th admin setwait piscine1          # lobby d'attente (à ta position)
/th admin setjump piscine1          # plongeoir
/th admin setspectator piscine1     # où les joueurs sont renvoyés après leur saut
/th admin pos1 piscine1             # un coin de la piscine, debout dans l'eau de surface
/th admin pos2 piscine1             # le coin opposé
/th admin enable piscine1
```

`/th admin info piscine1` liste ce qui manque encore. La surface de jeu est le Y le plus haut des
deux coins ; `/th admin setsurface piscine1 [y]` permet de le forcer.

Réglages : `/th admin set <arène> <min|max|lives|timeout> <valeur>` et
`/th admin palette <arène> <add|remove|clear|list> [bloc]`.

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
