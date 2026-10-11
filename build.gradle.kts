// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
// SMP-Core build: one jar per plugin, from a clean clone, with dependencies from public repositories.
//
//   ./gradlew build    compiles every plugin; jars land in build/dist/
//
// Java 21 is the target. Plugins keep their existing layout: Java sources under <plugin>/src, and every resource file
// (plugin.yml, config.yml, ...) anywhere in the plugin directory, placed at the jar root.

val paperApi = "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT"
val hikari = "com.zaxxer:HikariCP:5.1.0"
val mariadb = "org.mariadb.jdbc:mariadb-java-client:3.5.4"

// Extra compile-only APIs per plugin, beyond the Paper API and HikariCP. Runtime copies come from the server
// (plugins and plugin.yml `libraries`), except where a plugin bundles them (see `bundled`).
val apis = mapOf(
    "PizzaNetworkCore" to listOf(
        "net.dmulloy2:ProtocolLib:5.4.0",
        "me.clip:placeholderapi:2.12.3",
        "com.github.MilkBowl:VaultAPI:1.7.1",
    ),
    "PizzaPunishment" to listOf("de.maxhenkel.voicechat:voicechat-api:2.5.27"),
)

// PizzaProxyGuard runs on Velocity, which has no library loader, so it compiles against the Velocity API and
// bundles the MariaDB driver (LGPL-2.1, unmodified).
val velocityPlugins = setOf("PizzaProxyGuard")
val velocityApi = "com.velocitypowered:velocity-api:3.5.1"

// Plugins that compile the shared PizzaCommon sources into their own jar.
val usesCommon = setOf(
    "PizzaAdminTools", "PizzaChatGuard", "PizzaEnderchest", "PizzaLimbo", "PizzaNetworkCore",
    "PizzaPunishment", "PizzaRuleGuard", "PizzaSpawnRules", "PizzaTune",
)

// One suite version: every plugin descriptor must carry the same version.
val descriptorVersion = Regex("""(?m)^version:\s*['"]?([^'"\s]+)|"version":\s*"([^"]+)"""")
val suiteVersion: String = run {
    val versions = subprojects.associate { p ->
        val descriptor = p.projectDir.walkTopDown()
            .filter { it.isFile && (it.name == "plugin.yml" || it.name == "velocity-plugin.json") }
            .firstOrNull() ?: throw GradleException("${p.name}: no plugin.yml or velocity-plugin.json")
        val match = descriptorVersion.find(descriptor.readText())
            ?: throw GradleException("${p.name}: no version in ${descriptor.name}")
        p.name to (match.groupValues[1].ifEmpty { match.groupValues[2] })
    }
    val distinct = versions.values.toSet()
    if (distinct.size != 1) throw GradleException("plugin versions differ: $versions")
    distinct.single()
}

val buildCommit: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

val dist by tasks.registering(Copy::class) {
    description = "Collects every plugin jar in build/dist."
    into(layout.buildDirectory.dir("dist"))
}

allprojects {
    group = "dev.pizzasmp"
    version = suiteVersion
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.extendedclip.com/releases/")
        maven("https://maven.maxhenkel.de/repository/public")
        maven("https://jitpack.io") { content { includeGroup("com.github.MilkBowl") } }
    }

    val bundled = configurations.create("bundled")

    dependencies {
        if (name in velocityPlugins) {
            "compileOnly"(velocityApi)
            "bundled"(mariadb)
        } else {
            "compileOnly"(paperApi)
            "compileOnly"(hikari)
            apis[name].orEmpty().forEach { api ->
                "compileOnly"(api) { isTransitive = false }
            }
        }
    }

    extensions.configure<JavaPluginExtension> {
        sourceSets.named("main") {
            java.setSrcDirs(listOf("src") + if (project.name in usesCommon) listOf("../PizzaCommon/src") else emptyList())
            resources.setSrcDirs(emptyList<String>())
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-proc:none"))
        options.debugOptions.debugLevel = "source,lines"
    }

    tasks.named<ProcessResources>("processResources") {
        // Resource files anywhere in the plugin directory, flattened to the jar root. Branding stays private.
        from(fileTree(projectDir) {
            include("**/*.yml", "**/*.json", "**/*.sql", "**/*.tsv", "**/*.txt")
            exclude("build/**", "**/branding.yml")
        })
        eachFile { path = name }
        includeEmptyDirs = false
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }

    tasks.named<Jar>("jar") {
        archiveFileName.set("${project.name}.jar")
        from(rootProject.files("LICENSE", "NOTICE")) { into("META-INF") }
        if (project.name in velocityPlugins) {
            from(rootProject.file("licenses/mariadb-connector-j-LICENSE")) { into("META-INF") }
        }
        manifest {
            attributes("Implementation-Version" to suiteVersion, "Build-Commit" to buildCommit)
        }
        from(bundled.map { zipTree(it) }) {
            exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF", "module-info.class",
                "META-INF/versions/*/module-info.class")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    rootProject.tasks.named<Copy>("dist") { from(tasks.named("jar")) }
}

tasks.register("build") {
    description = "Builds every plugin and collects the jars in build/dist."
    dependsOn(subprojects.map { it.tasks.named("build") }, dist)
}
