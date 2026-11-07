// Pro+ autoplay: AI vs AI games without a GUI, with a summary per game (riepilogo.json/.md)
// and a comparison of several games (riepilogo-generale.md). Used by
// .github/workflows/proplus-autoplay.yml; see CLAUDE.md "Autoplay".
plugins {
    id("triplea-java-library")
    id("application")
}

application {
    mainClass.set("org.triplea.proplus.autoplay.AutoplayRunner")
    applicationDefaultJvmArgs = listOf("-Xss1250K", "-Xmx4g")
}

dependencies {
    implementation(project(":domain-data"))
    implementation(project(":game-core"))
    implementation(project(":game-headless"))
    implementation(project(":java-extras"))
    implementation(libs.gson)
    implementation(libs.sonatype.goodies.prefs)
}

tasks.named<JavaExec>("run") {
    // relative paths in --args are read from the repository root
    workingDir = rootProject.layout.projectDirectory.asFile
}
