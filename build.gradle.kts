import java.util.concurrent.Callable

plugins {
    `java-library`
    `maven-publish`
    id("uk.jamierocks.propatcher") version "1.3.2"
    id("org.cadixdev.licenser") version "0.5.0"
    id("com.github.johnrengelman.shadow") version "6.1.0"
}

val og_group: String by project

val artifactId = name.toLowerCase()
base.archivesBaseName = artifactId

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

val build = "release #${System.getenv("GITHUB_RUN_NUMBER") ?: "custom"}"

version = project.findProperty("base_version") as String + "." + (System.getenv("GITHUB_RUN_NUMBER") ?: "9999")

logger.lifecycle(":building mercury v${version}")

fun Short.absoluteValue(): Short =
    if (this < 0) (-this).toShort() else this

configurations {
    register("jdtSources") {
        isTransitive = false
    }
    register("jdt")
}
configurations["api"].extendsFrom(configurations["jdt"])

configurations.all {
    resolutionStrategy {
        failOnNonReproducibleResolution()
    }
}

repositories {
    mavenCentral()
}

val jdtVersion = "org.eclipse.jdt:org.eclipse.jdt.core:3.31.0"
dependencies {
    // JDT pulls all of these deps in, however they do not specify the exact version to use so they can get updated without us knowing.
    // Depend specifically on these versions to prevent them from being updated under our feet.
    // The POM is also patched later on to as this strict versioning does not make it through.
    "jdt" (jdtVersion)
    "jdt" ("org.eclipse.platform:org.eclipse.compare.core:[3.7.100]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.commands:[3.10.200]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.contenttype:[3.8.200]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.expressions:[3.8.200]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.filesystem:[1.9.500]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.jobs:[3.13.100]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.resources:[3.18.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.runtime:[3.26.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.equinox.app:[1.6.200]")
    "jdt" ("org.eclipse.platform:org.eclipse.equinox.common:[3.16.200]")
    "jdt" ("org.eclipse.platform:org.eclipse.equinox.preferences:[3.10.100]")
    "jdt" ("org.eclipse.platform:org.eclipse.equinox.registry:[3.11.200]")
    "jdt" ("org.eclipse.platform:org.eclipse.osgi:[3.18.100]")
    "jdt" ("org.eclipse.platform:org.eclipse.team.core:[3.9.500]")
    "jdt" ("org.eclipse.platform:org.eclipse.text:[3.12.200]")
    "jdt" ("org.osgi:org.osgi.service.prefs:[1.1.2]")

    // TODO: Split in separate modules
    api("org.cadixdev:at:0.1.0-rc1")
    api("org.cadixdev:lorenz:0.5.7")

    "jdtSources"("$jdtVersion:sources")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.cadixdev:lorenz-io-jam:0.5.7")
}

tasks.withType<Javadoc> {
    exclude("$og_group.$artifactId.jdt.".replace('.', '/'))
}

// Patched ImportRewrite from JDT
patches {
    patches = file("patches")
    root = file("build/jdt/original")
    target = file("build/jdt/patched")
}
val jdtSrcDir = file("jdt")

val extract = task<Copy>("extractJdt") {
    dependsOn(configurations["jdtSources"])
    from(Callable { zipTree(configurations["jdtSources"].singleFile) })
    destinationDir = patches.root

    include("org/eclipse/jdt/core/dom/rewrite/ImportRewrite.java")
    include("org/eclipse/jdt/internal/core/dom/rewrite/imports/*.java")
}
tasks["applyPatches"].inputs.files(extract)

val renames = listOf(
        "org.eclipse.jdt.core.dom.rewrite" to "$og_group.$artifactId.jdt.rewrite.imports",
        "org.eclipse.jdt.internal.core.dom.rewrite.imports" to "$og_group.$artifactId.jdt.internal.rewrite.imports"
)

fun createRenameTask(prefix: String, inputDir: File, outputDir: File, renames: List<Pair<String, String>>): Task
        = task<Copy>("${prefix}renameJdt") {
    destinationDir = file(outputDir)

    renames.forEach { (old, new) ->
        from("$inputDir/${old.replace('.', '/')}") {
            into("${new.replace('.', '/')}/")
        }
    }

    filter { renames.fold(it) { s, (from, to) -> s.replace(from, to) } }
}

val renameTask = createRenameTask("", patches.target, jdtSrcDir, renames)
renameTask.inputs.files(tasks["applyPatches"])

tasks["makePatches"].inputs.files(createRenameTask("un", jdtSrcDir, patches.target, renames.map { (a,b) -> b to a }))
sourceSets["main"].java.srcDirs(renameTask)

tasks.jar.configure {
    manifest.attributes(mapOf("Automatic-Module-Name" to "$og_group.$artifactId"))
    archiveClassifier.set("raw")
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.release.set(11)
}

tasks.shadowJar.configure {
    configurations = listOf(project.configurations["jdt"])
    archiveClassifier.set(null as String?)
}

tasks.build.configure { dependsOn(tasks.shadowJar) }

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

val sourceJar = task<Jar>("sourceJar") {
    classifier = "sources"
    from(sourceSets["main"].allSource)
}

val javadocJar = task<Jar>("javadocJar") {
    classifier = "javadoc"
    from(tasks["javadoc"])
}

artifacts {
    add("archives", sourceJar)
    add("archives", javadocJar)
    add("archives", tasks.shadowJar)
}

license {
    header = file("HEADER")
    exclude("$og_group.$artifactId.jdt.".replace('.', '/'))
}

val isSnapshot = true

listOf(configurations.apiElements, configurations.runtimeElements).forEach { config ->
    (components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(config.get()) {
        configurationVariant.artifacts.removeIf { artifact ->
            artifact.file == tasks.jar.get().archiveFile.get().asFile
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
           from(components["java"])
            artifactId = base.archivesBaseName

            artifact(sourceJar)
            artifact(javadocJar)

            pom {
                val name: String by project
                val description: String by project
                val url: String by project
                name(name)
                description(description)
                url(url)

                scm {
                    url(url)
                    connection("scm:git:$url.git")
                    developerConnection.set(connection)
                }

                issueManagement {
                    system("GitHub Issues")
                    url("$url/issues")
                }

                licenses {
                    license {
                        name("Eclipse Public License, Version 2.0")
                        url("https://www.eclipse.org/legal/epl-2.0/")
                        distribution("repo")
                    }
                }

                developers {
                    developer {
                        id("jamierocks")
                        name("Jamie Mansfield")
                        email("jmansfield@cadixdev.org")
                        url("https://www.jamiemansfield.me/")
                        timezone("Europe/London")
                    }
                }

                withXml {
                    // I pray that im being stupid that this isn't what you have to put up with when using kotlin
                    (((asNode().get("dependencies") as groovy.util.NodeList).first() as groovy.util.Node).value() as groovy.util.NodeList)
                            .removeIf { node ->
                                val group = ((((node as groovy.util.Node).get("groupId") as groovy.util.NodeList).first() as groovy.util.Node).value() as groovy.util.NodeList).first() as String;
                                group.startsWith("org.eclipse.")
                            }
                }
            }
        }
    }

    repositories {
        if (System.getenv("MAVEN_PASS") != null) {
            maven("https://deploy.shedaniel.me/") {
                credentials {
                    username = "shedaniel"
                    password = System.getenv("MAVEN_PASS")
                }
            }
        }
    }
}

operator fun Property<String>.invoke(v: String) = set(v)
