package keiyoushi.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.PluginManager
import org.gradle.kotlin.dsl.getByType

/**
 * Extension accessors backed by raw [VersionCatalog].
 *
 * In the parent repo the Gradle-generated type-safe classes
 * (LibrariesForLibs / LibrariesForKei) are available at compile
 * time.  In an included build those classes are not resolvable,
 * so we fall back to string-keyed catalog lookups.
 */

internal val Project.libsCatalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal val Project.keiCatalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("kei")

internal fun Project.plugins(block: PluginManager.() -> Unit) {
    pluginManager.apply(block)
}

fun Project.spotlessTaskName(): String =
    if (providers.environmentVariable("CI").orNull != "true") "spotlessApply" else "spotlessCheck"