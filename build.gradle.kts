// Shared configuration for every module that applies `java-library`: Java 25 toolchain, strict
// compiler lint and JUnit Platform. Group and version come from gradle.properties. Each module
// declares the plugin itself in its plugins {} block; the withType<JavaLibraryPlugin> guard runs
// this block once the plugin has been applied.
//
// Modules that also apply com.vanniktech.maven.publish get the shared POM metadata and Maven
// Central settings below; that plugin also adds the sources and javadoc jars. The module's own
// `description` becomes the POM description.

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import net.ltgt.gradle.errorprone.errorprone

plugins {
    // Put the plugin on the root classpath so the subprojects {} block can refer to its types.
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.errorprone) apply false
}

// Inside subprojects {} the implicit receiver is the subproject, where the generated `libs`
// accessor is not available — so resolve the catalog once here, against the root project.
val catalog = libs

subprojects {
    apply(plugin = "net.ltgt.errorprone")
    plugins.withId("com.vanniktech.maven.publish") {
        // The plugin only builds the javadoc jar when publishing. Build it on every `build` too, so
        // broken Javadoc fails CI instead of the release.
        tasks.named("assemble") {
            dependsOn("plainJavadocJar")
        }

        configure<MavenPublishBaseExtension> {
            // Central Portal (central.sonatype.com). Credentials and the signing key are read from
            // mavenCentralUsername, mavenCentralPassword, signingInMemoryKey and
            // signingInMemoryKeyPassword Gradle properties (e.g. ORG_GRADLE_PROJECT_* env vars).
            publishToMavenCentral()

            // Maven Central requires signed artifacts. Signing is skipped when no key is configured,
            // so publishToMavenLocal works on a developer machine without one.
            if (providers.gradleProperty("signingInMemoryKey").isPresent ||
                providers.gradleProperty("signing.keyId").isPresent
            ) {
                signAllPublications()
            }

            pom {
                name = project.name
                description = provider { project.description }
                url = "https://github.com/pertyjons/result"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "pertyjons"
                        name = "Per Jonsson"
                        url = "https://github.com/pertyjons"
                    }
                }
                scm {
                    url = "https://github.com/pertyjons/result"
                    connection = "scm:git:https://github.com/pertyjons/result.git"
                    developerConnection = "scm:git:ssh://git@github.com/pertyjons/result.git"
                }
                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/pertyjons/result/issues"
                }
            }
        }
    }

    plugins.withType<JavaLibraryPlugin>().configureEach {
        repositories {
            mavenCentral()
        }

        configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }

        dependencies {
            "testImplementation"(catalog.junit.jupiter)
            "testRuntimeOnly"(catalog.junit.platform.launcher)
            "errorprone"(catalog.errorprone.core)
            "errorprone"(catalog.nullaway)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
            options.errorprone {
                disableAllChecks = true
                // Tests intentionally pass null to verify runtime validation. Production
                // packages are @NullMarked and checked, including their generic contracts.
                if (name == "compileJava") {
                    error("NullAway")
                    option("NullAway:OnlyNullMarked", "true")
                    option("NullAway:JSpecifyMode", "true")
                }
            }
        }

        // A stable JPMS name for module-path users, derived from the base package:
        // result-http-jackson -> io.github.pertyjons.result.http.jackson.
        val automaticModuleName = "io.github.pertyjons." + name.replace('-', '.')
        tasks.named<Jar>("jar") {
            manifest {
                attributes("Automatic-Module-Name" to automaticModuleName)
            }
        }

        // Ship the license text inside the binary and sources jars, as the Apache License asks of
        // redistributions.
        tasks.withType<Jar>().configureEach {
            from(rootProject.layout.projectDirectory.file("LICENSE")) {
                into("META-INF")
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()

            // One summary line per module once its root suite finishes, e.g.
            //   :result:test Result: SUCCESS (177 tests, 177 passed, 0 failed, 0 skipped)
            // addTestListener replaces the Closure-based afterSuite, which Gradle 9 deprecates.
            // The listener only captures the task path, so it is configuration-cache safe.
            val taskPath = path
            addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) {}

                override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                    if (suite.parent == null) {
                        println(
                            "$taskPath  Result: ${result.resultType} " +
                                "(${result.testCount} tests, ${result.successfulTestCount} passed, " +
                                "${result.failedTestCount} failed, ${result.skippedTestCount} skipped)"
                        )
                    }
                }

                override fun beforeTest(testDescriptor: TestDescriptor) {}

                override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
            })
        }
    }
}
