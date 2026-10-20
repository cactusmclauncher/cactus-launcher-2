plugins {
    `java-library`
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val checkBuildStore by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.cactuslauncher.game.BuildStoreCheck")
}

tasks.check { dependsOn(checkBuildStore) }

// No JUnit tests here (see checkBuildStore), and Gradle 9 fails a test task that finds none.
tasks.test { enabled = false }
