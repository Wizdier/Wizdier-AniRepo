plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.samWithReceiver)
    alias(libs.plugins.spotless)
    `java-gradle-plugin`
}

val ktlintVersion = libs.ktlint.bom.get().version
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
    compileOnly(libs.android.gradle)
    compileOnly(libs.kotlin.gradle)
    implementation(libs.spotless.gradle)
    implementation(libs.tapmoc.gradle)
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
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