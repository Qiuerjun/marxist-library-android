buildscript {
    // These minimum versions fix advisories in the Gradle plugin classpath.
    val versions = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    val patched = mapOf(
        "com.google.guava:guava" to "build-guava",
        "org.apache.commons:commons-lang3" to "build-commons-lang",
        "org.bitbucket.b_c:jose4j" to "build-jose4j",
        "org.jdom:jdom2" to "build-jdom",
        "org.bouncycastle:bcprov-jdk18on" to "build-bouncycastle",
        "org.bouncycastle:bcpkix-jdk18on" to "build-bouncycastle",
        "org.bouncycastle:bcutil-jdk18on" to "build-bouncycastle"
    )
    dependencies {
        constraints {
            patched.forEach { (module, version) ->
                add("classpath", "$module:${versions.findVersion(version).get().requiredVersion}")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

