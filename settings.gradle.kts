rootProject.name = "CloudstreamPlugins"

// This file sets what projects are included.
// All new projects should get automatically included unless specified in the "disabled" variable.

val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    // Updated to check for either build.gradle.kts OR build.gradle
    if (!disabled.contains(dir.name) && (File(dir, "build.gradle.kts").exists() || File(dir, "build.gradle").exists())) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
