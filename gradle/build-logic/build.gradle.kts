// build-logic's own build — uses literal plugin versions since the
// `plugins {}` block cannot resolve VersionCatalog lookups dynamically.

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.sam.with.receiver") version "2.3.21"
    id("com.diffplug.spotless") version "8.5.0"
    `java-gradle-plugin`
}

val ktlintVersion = "1.8.0"
val editorConfigFile = rootProject.file("../../.editorconfig")
spotless {
    kotlin {
        target("src/**/*.kt", "*.kts")
        ktlint(ktlintVersion)
            .setEditorConfigPath(editorConfigFile)
            .editorConfigOverride(
                mapOf(
                    "max_line_length" to 2147483647,
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencies {
    compileOnly(gradleKotlinDsl())
    compileOnly("com.android.tools.build:gradle:9.2.1")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.5.0")
    implementation("com.gradleup.tapmoc:tapmoc-gradle-plugin:0.4.2")
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        register("android-base") {
            id = "kei.plugins.android.base"
            implementationClass = "PluginAndroidBase"
        }
        register("extension") {
            id = "kei.plugins.extension.legacy"
            implementationClass = "PluginExtensionLegacy"
        }
        register("library") {
            id = "kei.plugins.library"
            implementationClass = "PluginLibrary"
        }
        register("multisrc") {
            id = "kei.plugins.multisrc"
            implementationClass = "PluginMultiSrc"
        }
        register("spotless") {
            id = "kei.plugins.spotless"
            implementationClass = "PluginSpotless"
        }
    }
}
