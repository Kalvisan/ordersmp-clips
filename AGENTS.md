# Agent instructions: OrderSMP Clips

A public, MIT-licensed Fabric client mod. Everything here is read by strangers, so it must be
correct, clean and free of private information.

## Start every task

1. Read this file and `README.md`.
2. Requirements live in the OrderSMP server repository's `docs/PLAN.md`, block R339, and the
   design in its `research/clips-mod-lightweight-recording-in-the-minecraft-client.md`
   (sections 1-9). Follow them; if a task conflicts with them, stop and say so.
3. Search the code for what already exists before writing anything. Extend existing classes;
   never add a second implementation of something that exists.

## Code rules

- Java 25, Fabric, Mojang names. `./gradlew build` must pass; every compiler warning is an error.
- Error first: check failures and bad input at the top and return early. The happy path stays
  unindented.
- `public final class` by default, records for values, private constructors on utility classes,
  two-space indentation.
- No inline comments. A Javadoc at the start of each class and non-obvious method says what it
  does and why. Never quote prompts or chat in code or docs.
- No test files. Verify by building and by running the mod in Minecraft (`./gradlew runClient`),
  and say exactly what was run and seen.
- Nothing on the render thread may block or allocate per frame. Use Minecraft's backend-neutral
  render API (`com.mojang.renderpearl`), never raw OpenGL or Vulkan calls.
- Player-facing text goes through `assets/ordersmp-clips/lang/en_us.json`: plain, short English.
- Copy code only from MIT or Apache-2.0 projects, with their notice. Never copy from ReplayMod,
  Flashback, WaterMedia or Simple Voice Chat.

## Privacy

- No personal names, email addresses, local paths, machine names, server addresses or tokens in
  code, docs, commits or logs.
- Commit with a GitHub noreply address.

## Git

- Short-lived branches `<type>/<topic>` (`feat`, `fix`, `docs`, `refactor`, `build`, `ci`), one
  task per pull request, squash-merged after CI passes. Never push to `main`.
- Commit messages: `type(scope): imperative lower-case summary`, at most 72 characters, no
  period. No AI attribution lines or co-author trailers for tools.

## Done means

1. `./gradlew build` passes locally and in CI.
2. The change was tried in a running game, or the pull request says plainly that it was not.
3. `README.md` matches what the mod does.
