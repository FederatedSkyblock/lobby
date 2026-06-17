rootProject.name = "lobby"

// Shared code lives in the FederatedSkyblock/common repo, pulled in as the `common` git submodule.
// Run `git submodule update --init` after cloning.
includeBuild("common")
