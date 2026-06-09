@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package keiyoushi.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.PluginManager
import org.gradle.api.provider.Provider
import org.gradle.plugin.use.PluginDependency
import org.gradle.kotlin.dsl.getByType

// ============================================================================
// Project extensions — replacements for `the<LibrariesForLibs>()` etc.
// ============================================================================

internal val Project.libs: LibsAccessor
    get() {
        val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
        return LibsAccessor(catalog)
    }

internal val Project.kei: KeiAccessor
    get() {
        val catalog = extensions.getByType<VersionCatalogsExtension>().named("kei")
        return KeiAccessor(catalog)
    }

internal fun Project.plugins(block: PluginManager.() -> Unit) {
    pluginManager.apply(block)
}

fun Project.spotlessTaskName(): String =
    if (providers.environmentVariable("CI").orNull != "true") "spotlessApply" else "spotlessCheck"

// ============================================================================
// Kei catalog accessor  (gradle/kei.versions.toml)
// ============================================================================

open class KeiAccessor(private val c: VersionCatalog) {
    val versions = KeiVersions(c)
    val plugins = KeiPlugins(c)
}

open class KeiVersions(private val c: VersionCatalog) {
    val android = KeiAndroid(c)
    val java: Provider<org.gradle.api.artifacts.Version> get() = v("java")
    private fun v(key: String) = c.findVersion(key).get()
}

open class KeiAndroid(private val c: VersionCatalog) {
    val sdk = KeiSdk(c)
}

open class KeiSdk(private val c: VersionCatalog) {
    val min: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("android-sdk-min").get()
    val compile: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("android-sdk-compile").get()
    val target: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("android-sdk-target").get()
}

open class KeiPlugins(private val c: VersionCatalog) {
    val android = KeiAndroidPlugins(c)
    val extension = KeiExtensionPlugins(c)
    val library: Provider<PluginDependency> get() = c.findPlugin("library").get().get()
    val multisrc: Provider<PluginDependency> get() = c.findPlugin("multisrc").get().get()
    val spotless: Provider<PluginDependency> get() = c.findPlugin("spotless").get().get()
}

open class KeiAndroidPlugins(private val c: VersionCatalog) {
    val base: Provider<PluginDependency> get() = c.findPlugin("android-base").get().get()
}

open class KeiExtensionPlugins(private val c: VersionCatalog) {
    val legacy: Provider<PluginDependency> get() = c.findPlugin("extension-legacy").get().get()
}

// ============================================================================
// Libs catalog accessor  (gradle/libs.versions.toml)
// ============================================================================

open class LibsAccessor(private val c: VersionCatalog) {
    val versions = LibsVersions(c)
    val libraries = LibsLibraries(c)
    val bundles = LibsBundles(c)
    val plugins = LibsPlugins(c)

    // Direct library access for single-segment names (no dots)
    // e.g. libs.junit, libs.jsoup, libs.okhttp, libs.rxjava, libs.quickjs
    val junit: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("junit").get()
    val jsoup: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("jsoup").get()
    val okhttp: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("okhttp").get()
    val rxjava: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("rxjava").get()
    val quickjs: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("quickjs").get()

    // Nested library access
    val android = LibsAndroid(c)
    val kotlin = LibsKotlin(c)
    val ktlint = LibsKtlint(c)
    val spotless = LibsSpotless(c)
    val tapmoc = LibsTapmoc(c)
    val coroutines = LibsCoroutines(c)
    val injekt = LibsInjekt(c)
    val aniyomi = LibsAniyomi(c)
}

open class LibsVersions(private val c: VersionCatalog) {
    val android = LibsAndroidVersions(c)
    val coroutines: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("coroutines").get()
    val junit: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("junit").get()
    val kotlin = LibsKotlinVersions(c)
    val ktlint: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("ktlint").get()
    val spotless: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("spotless").get()
    val tapmoc: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("tapmoc").get()
    val serialization: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("serialization").get()
}

open class LibsAndroidVersions(private val c: VersionCatalog) {
    val gradle: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("android-gradle").get()
}

open class LibsKotlinVersions(private val c: VersionCatalog) {
    val gradle: Provider<org.gradle.api.artifacts.Version> get() = c.findVersion("kotlin-gradle").get()
}

// --- Libraries ---

open class LibsLibraries(private val c: VersionCatalog) {
    val android = LibsAndroid(c)
    val kotlin = LibsKotlin(c)
    val ktlint = LibsKtlint(c)
    val spotless = LibsSpotless(c)
    val tapmoc = LibsTapmoc(c)
    val coroutines = LibsCoroutines(c)
    val injekt = LibsInjekt(c)
    val aniyomi = LibsAniyomi(c)
}

open class LibsAndroid(private val c: VersionCatalog) {
    val gradle: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("android-gradle").get()
}

open class LibsKotlin(private val c: VersionCatalog) {
    val gradle: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("kotlin-gradle").get()
    val json: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("kotlin-json").get()
    val protobuf: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("kotlin-protobuf").get()
    val jsonOkio: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("kotlin-json-okio").get()
}

open class LibsKtlint(private val c: VersionCatalog) {
    val bom: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("ktlint-bom").get()
}

open class LibsSpotless(private val c: VersionCatalog) {
    val gradle: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("spotless-gradle").get()
}

open class LibsTapmoc(private val c: VersionCatalog) {
    val gradle: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("tapmoc-gradle").get()
}

open class LibsCoroutines(private val c: VersionCatalog) {
    val android: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("coroutines-android").get()
    val core: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("coroutines-core").get()
}

open class LibsInjekt(private val c: VersionCatalog) {
    val core: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("injekt-core").get()
}

open class LibsAniyomi(private val c: VersionCatalog) {
    val lib: Provider<org.gradle.api.artifacts.MinimalExternalModuleDependency> get() = c.findLibrary("aniyomi-lib").get()
}

// --- Bundles ---

open class LibsBundles(private val c: VersionCatalog) {
    val common: Provider<org.gradle.api.artifacts.ExternalModuleDependencyBundle> get() = c.findBundle("common").get()
}

// --- Plugins ---

open class LibsPlugins(private val c: VersionCatalog) {
    val android = LibsAndroidPlugins(c)
    val kotlin = LibsKotlinPlugins(c)
    val spotless: Provider<PluginDependency> get() = c.findPlugin("spotless").get().get()
}

open class LibsAndroidPlugins(private val c: VersionCatalog) {
    val application: Provider<PluginDependency> get() = c.findPlugin("android-application").get().get()
    val library: Provider<PluginDependency> get() = c.findPlugin("android-library").get().get()
}

open class LibsKotlinPlugins(private val c: VersionCatalog) {
    val jvm: Provider<PluginDependency> get() = c.findPlugin("kotlin-jvm").get().get()
    val samWithReceiver: Provider<PluginDependency> get() = c.findPlugin("kotlin-samWithReceiver").get().get()
    val serialization: Provider<PluginDependency> get() = c.findPlugin("kotlin-serialization").get().get()
}
