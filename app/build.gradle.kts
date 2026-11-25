import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.marxistlibrary.reader"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.marxistlibrary.reader"
        minSdk = 26
        targetSdk = 37
        versionCode = 14
        versionName = "1.1.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

// Every APK includes the same license text as the repository.
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val packageLicense = tasks.register<PackageProjectLicense>("package${variantName}ProjectLicense") {
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(packageLicense, PackageProjectLicense::outputDirectory)
    }
}

abstract class PackageProjectLicense : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyLicense() {
        val packagedLicense = outputDirectory.file("LICENSE").get().asFile
        licenseFile.get().asFile.copyTo(packagedLicense, overwrite = true)
    }
}

ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.ui)
    implementation(libs.core)
    implementation(libs.activity)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.runtime)
    implementation(libs.navigation.ui)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    constraints {
        add("ksp", "com.google.guava:guava:${libs.versions.build.guava.get()}") {
            because("Use the patched version in Room's annotation processor")
        }
    }
    implementation(libs.datastore)
    implementation(libs.work)
    implementation(libs.webkit)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.serialization)
    testImplementation(libs.junit)
}

tasks.register("releaseDependencyReport") {
    group = "verification"
    description = "Lists the resolved dependencies used to build and ship a release."
    val report = layout.buildDirectory.file("reports/security/release-dependencies.json")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        fun modules(configurations: List<Configuration>) = configurations.flatMap { configuration ->
            val resolution = configuration.incoming.resolutionResult
            val unresolved = resolution.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
            if (unresolved.isNotEmpty()) throw GradleException("Could not resolve ${configuration.name}", unresolved.first().failure)
            resolution.allComponents.mapNotNull {
                (it.id as? ModuleComponentIdentifier)?.let { component ->
                    mapOf("name" to "${component.group}:${component.module}", "version" to component.version)
                }
            }
        }.distinct().sortedBy { it.getValue("name") + ":" + it.getValue("version") }

        val graph = mapOf(
            "runtime" to modules(listOf(configurations.getByName("releaseRuntimeClasspath"))),
            "build" to modules(listOf(rootProject.buildscript.configurations.getByName("classpath"),
                buildscript.configurations.getByName("classpath"))),
            "processors" to modules(configurations.filter {
                it.isCanBeResolved && (it.name == "kotlinCompilerClasspath" ||
                    it.name.startsWith("ksp") && !it.name.contains("debug", ignoreCase = true) &&
                    !it.name.contains("test", ignoreCase = true))
            })
        )
        val target = report.get().asFile
        target.parentFile.mkdirs()
        target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(graph)) + "\n")
    }
}

