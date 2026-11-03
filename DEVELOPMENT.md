# Studio development

Studio work is preserved on `codex/studio-dev-update`, based on `dev` plus
the existing shop/inventory commits. This branch is a development snapshot;
automated build results do not imply full editor client acceptance.

New issue fixes should use a `codex/fix-<issue>` branch based on updated
`dev` and a pull request targeting `dev`. Use a separate checkout/worktree
when Studio has uncommitted changes. For fixes requiring unmerged Studio
code, base the branch on `codex/studio-dev-update` and state that dependency.
Issue #45 is excluded from this migration's issue-fix scope. The #47/#48
fixes are included so development does not reintroduce those failures.

Use JDK 17 and run:

```text
gradlew.bat spotlessCheck test check build
```

On other systems use `./gradlew`. The `check` task includes a real headless
Forge test server with a fresh directory under `build/native-item-tests`.
Historical opt-in probe init scripts require local fixtures; they are not
required by this command. Keep local fixtures, `.local-artifacts`, game
saves, logs, caches and generated jars out of commits.

The optional `editorExperimentalTest` suite additionally needs Windows
fonts under `C:/Windows/Fonts`, including CJK and matching upright/italic
faces. Run that suite on a Windows host with those fonts; do not treat it
as a portable Linux/macOS validation command.

When changing BlockOffensive too, push this repository's commit first,
then update and push BlockOffensive's FPSMatch submodule pin. Build CI
includes `dev` pull requests/pushes and Studio branch pushes; release
publication is a separate action.
