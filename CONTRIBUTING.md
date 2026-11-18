# Contributing to OrderSMP Clips

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an issue with:

- your Minecraft, Fabric Loader and Clips versions;
- your graphics card and operating system;
- what you did, what you expected and what happened;
- the lines from `logs/latest.log` that mention `OrderSMP Clips`.

Turn on **Show Recording Stats** (Options > Controls > Key Binds > OrderSMP Clips) and include
what it shows if the problem is about performance or recording.

## Pull requests

1. Fork the repository and create a branch, for example `fix/save-on-linux`.
2. Keep the change small and focused on one thing.
3. Build with `./gradlew build`; every compiler warning fails the build.
4. Try it in the game with `./gradlew runClient`.
5. Open a pull request that says what changed, why, and what you tested.

## Code style

- Check errors first and return early; keep the main path unindented.
- `final` classes and records where possible, two-space indentation.
- A short Javadoc on each class and non-obvious method explaining what it does and why; no
  inline comments.
- Nothing may block or allocate on the render thread every frame.
- Use Minecraft's render API, never raw OpenGL or Vulkan.

By contributing you agree that your work is released under the [MIT License](LICENSE).
