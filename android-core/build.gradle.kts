plugins {
    kotlin("jvm") version "1.9.22"
}
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
