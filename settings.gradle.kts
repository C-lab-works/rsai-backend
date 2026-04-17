rootProject.name = "tatsu-gate"

include("gate-mapping")
include("gate-core")
include("app")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
