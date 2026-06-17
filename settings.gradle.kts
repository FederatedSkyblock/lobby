rootProject.name = "lobby"

// Shared code lives in the FederatedSkyblock/server repo, pulled in as the `server` git submodule.
// Run `git submodule update --init` after cloning.
includeBuild("server/common")
