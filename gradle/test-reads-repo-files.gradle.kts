// Declares the repo files a module's tests read as inputs of that module's test tasks.
//
// Why: a test that reads a file outside its own module's sources (a card, an ADR, a
// registry, a build script) is "up to date" for Gradle when only that file changed, so a
// docs edit can break the test while the build still says nothing has to run.
//
// Applied once from the root build (subprojects { apply(from = ...) }). That runs BEFORE a
// module's own build script, so nothing is read here at apply time: the lists are read in
// afterEvaluate, when the module script has already set them. A module that sets nothing
// gets no new inputs.
//
// In a module build script (paths relative to the REPOSITORY ROOT):
//
//     extra["repoFileInputs"] = listOf("decisions/ADR-010-retention.md")
//     extra["repoFolderInputs"] = listOf("android/modules/phrases")   // a whole folder tree
//
// A folder tree leaves out build/ and .gradle/. A declared path that does not exist is not
// an error for Gradle; tests/contract/test_gradle_inputs_contract.py fails on it.

val repoRoot = rootProject.layout.projectDirectory.asFile

afterEvaluate {
    val files = (extra.properties["repoFileInputs"] as? List<*>).orEmpty().map { repoRoot.resolve(it as String) }
    val folders = (extra.properties["repoFolderInputs"] as? List<*>).orEmpty().map { repoRoot.resolve(it as String) }
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        if (files.isNotEmpty()) inputs.files(files)
        for (dir in folders) {
            inputs.files(fileTree(dir) { exclude("**/build/**", "**/.gradle/**") })
        }
    }
}
