plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

description = "Jackson 3 JsonCodec for io.github.pertyjons:result-http, discovered through ServiceLoader."

dependencies {
    // JacksonCodec implements JsonCodec and accepts an ObjectMapper, so both are part of its API.
    api(project(":result-http"))
    api(libs.jackson.databind)

    testImplementation(project(":result-assertj"))
}
