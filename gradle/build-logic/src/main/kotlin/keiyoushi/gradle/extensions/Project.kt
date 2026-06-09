package keiyoushi.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.PluginManager
import org.gradle.kotlin.dsl.getByType

internal val Project.libs get() = LibrariesForLibs(extensions.getByType<VersionCatalogsExtension>().named("libs"))
internal val Project.kei get() = LibrariesForKei(extensions.getByType<VersionCatalogsExtension>().named("kei"))

internal fun Project.plugins(block: PluginManager.() -> Unit) {
    pluginManager.apply(block)
}

fun Project.spotlessTaskName() = if (providers.environmentVariable("CI").orNull != "true") "spotlessApply" else "spotlessCheck"