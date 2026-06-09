import com.android.build.api.dsl.LibraryExtension
import keiyoushi.gradle.extensions.alias
import keiyoushi.gradle.extensions.compileOnly
import keiyoushi.gradle.extensions.implementation
import keiyoushi.gradle.extensions.keiCatalog
import keiyoushi.gradle.extensions.libsCatalog
import keiyoushi.gradle.extensions.plugins
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

@Suppress("UNUSED")
class PluginLibrary : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val libs = libsCatalog
        val kei = keiCatalog

        plugins {
            alias(libs.findPlugin("android-library").get().get())
            alias(libs.findPlugin("kotlin-serialization").get().get())
            alias(kei.findPlugin("android-base").get().get())
            alias(kei.findPlugin("spotless").get().get())
        }

        extensions.configure<LibraryExtension> {
            namespace = "aniyomi.lib.${project.name}"

            sourceSets {
                named("main") {
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }

            androidResources.enable = false
        }

        dependencies {
            compileOnly(libs.findBundle("common").get())
            implementation(project(":core"))
        }
    }
}
