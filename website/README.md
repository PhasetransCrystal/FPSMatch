# FPSMatch Wiki

The static documentation site is built with Docusaurus. The Markdown files in
the repository root and `docs/` remain the canonical documentation sources.

## Local development

```bash
pnpm install --frozen-lockfile
pnpm start
```

## Verification

```bash
pnpm typecheck
$env:DOCUSAURUS_BASE_URL = '/FPSMatch/master/'
pnpm build
```

The workflow builds the site after documentation changes and uploads the
generated directory as an artifact. Pushes to `master` also publish the build
to the `master/` directory of the `gh-pages` branch.

## Developer documentation

The developer guide assumes Java and basic Forge knowledge. The continuous
elimination tutorial is backed by `../examples/elimination/chapters/01` through
`08`. Topic pages explain one concrete development task, with prerequisites,
code placement, API behavior, and links to related topics. Keep method fragments
clearly distinguished from complete classes. Do not add per-page verification
procedures or test checklists to the developer guide.

Writing and organization references:

- [Fabric developer guides](https://docs.fabricmc.net/develop/)
- [Fabric: creating a first item](https://docs.fabricmc.net/develop/items/first-item)
- [NeoForge: items and object concepts](https://docs.neoforged.net/docs/1.21.1/items/)

These references guide explanation and organization; FPSMatch examples continue
to use the repository's Forge 1.20.1 API. Compile the eight checkpoints with
`gradlew -p examples/elimination compileChapters` from the FPSMatch root when
changing their Java sources. Keep the corresponding documentation snippets in
sync with those sources.
