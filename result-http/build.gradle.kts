plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

description = "A fluent, Result-returning adapter over the JDK java.net.http.HttpClient."

dependencies {
    // Result and HttpError appear in the public API; the JDK HttpClient needs no dependency.
    api(project(":result"))

    testImplementation(project(":result-assertj"))
}
