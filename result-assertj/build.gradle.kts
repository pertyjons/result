plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

description = "AssertJ assertions for io.github.pertyjons:result."

dependencies {
    // ResultAssert extends AssertJ types and asserts on Result, so both are part of its API.
    api(project(":result"))
    api(libs.assertj.core)
}
