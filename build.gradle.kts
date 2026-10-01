import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
    id("com.gradleup.shadow") version "9.6.1"
}

version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    implementation("com.squareup.leakcanary:shark:2.14")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

// ponytail: sem toolchain; compila bytecode 21 com o JDK que estiver instalado (>= 21)
java { targetCompatibility = JavaVersion.VERSION_21 }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }
tasks.withType<JavaCompile> { options.release = 21 }

application { mainClass = "hprof.MainKt" }

// version.properties is the only templated resource: report.html / echarts contain "$"
tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("version.properties") { expand("version" to v) }
}

// keep a stable jar name (build/libs/hprof-analyzer-all.jar) used by the docs
tasks.shadowJar { archiveVersion = "" }

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}
