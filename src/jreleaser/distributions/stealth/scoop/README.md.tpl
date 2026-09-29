# {{projectName}} Scoop bucket

Scoop manifests for [{{projectName}}]({{projectLinkHomepage}}), updated automatically on every release. Don't edit the manifests here: they are generated from the {{projectName}} repo.

## Install

1. Install [Scoop](https://scoop.sh).
2. Install Java 21, if you don't have it already:

   ```powershell
   scoop bucket add java
   scoop install java/temurin21-jre
   ```

3. Add this bucket and install {{projectName}}:

   ```powershell
   scoop bucket add {{projectName}} {{scoopRepositoryCloneUrl}}
   scoop install {{projectName}}
   ```

Upgrade with `scoop update {{projectName}}`.
