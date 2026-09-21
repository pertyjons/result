plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

description = "A Rust-inspired Result<T, E> sum type for Java with a fluent API for explicit, typed error handling."

dependencies {
    // JSpecify nullness annotations appear in the public API signatures.
    api(libs.jspecify)

    testImplementation(project(":result-assertj"))
}
