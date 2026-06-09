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
class PluginMultiSrc : Plugin<Project> {
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
            namespace = "eu.kanade.tachiyomi.multisrc.${project.name}"

            sourceSets {
                named("main") {
                    manifest.srcFile("AndroidManifest.xml")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }
        }

        dependencies {
            compileOnly(libs.findBundle("common").get())
            implementation(project(":core"))
        }
    }
}
