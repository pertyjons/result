plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "result"

include("result")
include("result-assertj")
include("result-http")
include("result-http-jackson")
