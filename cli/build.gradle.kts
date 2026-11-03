import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.abhinesh.tvremote.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
