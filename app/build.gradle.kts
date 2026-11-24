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
        versionCode = 11
        versionName = "1.1.7"
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
    implementation(libs.datastore)
    implementation(libs.work)
    implementation(libs.webkit)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.serialization)
    testImplementation(libs.junit)
}

