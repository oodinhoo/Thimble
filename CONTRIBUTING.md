# Contributing

Thanks for helping. This page says how to get a change into Thimble.

## Before you start

- Bugs and ideas go through the [issue forms](https://github.com/oodinhoo/Thimble/issues/new/choose).
  For a sizeable change, open an issue first so we agree on the approach before you write code.
- Read [What is not done](docs/SPECIFICATIONS.md#10-what-is-not-done) in the specification: some
  ideas were already weighed and set aside, with the reason.
- Security problems never go in a public issue: see [SECURITY.md](SECURITY.md).

## Build and test

Requires **JDK 25**.

```powershell
.\gradlew.bat build      # Windows
./gradlew build          # Linux, macOS
```

The build must pass with no compiler warning; it also runs the tests. Drop the jar from
`build/libs/` into a **Paper 26.2** server to try your change.

Only the logic that touches neither the world nor players is unit-tested (see
[Tests](docs/SPECIFICATIONS.md#9-tests)): for anything else, say in the pull request how you
checked it in game.

## Code

- Match the surrounding code: Java 25, one flat package `fr.thimble`, same naming and comment style.
- Every player-facing text lives in `messages.yml` (MiniMessage), never hard-coded. Durations in
  `config.yml` are in seconds.
- A deliberate shortcut with a known limit gets a `ponytail:` comment that names the limit and the
  way out.
- When behavior changes, update the [README](README.md) and the
  [specification](docs/SPECIFICATIONS.md) in the same pull request.

## Commits and pull requests

- Branch from `main`, one topic per pull request.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`,
  `fix:`, `refactor:`, `perf:`, `test:`, `docs:`, `build:`, `chore:`, then a short summary in the
  imperative, in English.
- The CI must pass. Pull requests are squash-merged: the title becomes the commit on `main`.

By contributing, you agree that your contribution is licensed under the [MIT License](LICENSE) and
that you follow the [Code of Conduct](CODE_OF_CONDUCT.md).
