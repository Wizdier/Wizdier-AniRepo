package keiyoushi.gradle.extensions

import org.gradle.api.plugins.PluginManager
import org.gradle.plugin.use.PluginDependency

/** Apply the plugin whose id is carried by [dep]. */
fun PluginManager.alias(dep: PluginDependency) {
    apply(dep.pluginId)
}
