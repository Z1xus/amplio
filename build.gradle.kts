plugins {
    java
    id("net.fabricmc.fabric-loom-remap") version "1.17.19"
    id("ploceus") version "1.17.6"
}

group = "us.z1x"
version = property("mod_version")!!

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    maven("https://maven.taumc.org/releases")
    maven("https://maven.cloverclient.com/releases")
    mavenCentral()
}

configurations.configureEach {
    exclude(group = "org.lwjgl.lwjgl")
}

ploceus {
    setIntermediaryGeneration(2)
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(ploceus.layeredMappings {
        mappings("net.ornithemc:feather-gen2:${property("minecraft_version")}+build.${property("feather_build")}:v2") {
            containsUnpick()
        }
    })
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_version")}")

    modCompileOnly("dev.rdh:argentum:${property("argentum_version")}")
    compileOnly("org.embeddedt.celeritas:celeritas-common:${property("celeritas_version")}")
    modCompileOnly("pl.tomgirl:pylon:${property("pylon_version")}")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

// local builds bundle the linux natives from native/build, release builds take every platform from ci artifacts
val nativesDir = layout.buildDirectory.dir("natives")
val bundleNatives by tasks.registering(Sync::class) {
    from("native/build") { include("libamplio.so") }
    from("native/external/DLSS/lib/Linux_x86_64/rel") { include("libnvidia-ngx-dlss.so.*") }
    into(nativesDir.map { it.dir("natives/linux-x86_64") })
    doLast {
        val dir = destinationDir
        File(dir, "index").writeText(dir.list()!!.filter { it != "index" }.sorted().joinToString("\n"))
    }
}

sourceSets.main {
    resources.srcDir(bundleNatives.map { nativesDir.get() })
}
