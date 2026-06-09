package keiyoushi.gradle.extensions

import org.gradle.api.Transformer
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.plugin.use.PluginDependency
import java.util.function.BiFunction

// ---------------------------------------------------------------------------
// Provider wrapper — eagerly resolves, full Provider<out T> contract
// ---------------------------------------------------------------------------
class EagerProvider<out T>(private val value: T) : Provider<T> {
    override fun get(): T = value
    override fun getOrNull(): T = value
    override fun getOrElse(default: @UnsafeVariance T): T = value
    override fun isPresent(): Boolean = true
    override fun orElse(provider: Provider<out T>): Provider<T> = this
    override fun <S : Any> map(transformer: Transformer<out S?, in @UnsafeVariance T>): Provider<S?> =
        EagerProvider(transformer.transform(value))
    override fun <S : Any> flatMap(transformer: Transformer<out Provider<out S>?, in @UnsafeVariance T>): Provider<S?> {
        @Suppress("UNCHECKED_CAST")
        return (transformer.transform(value) as Provider<S>?) ?: EagerProvider<S?>(null)
    }
    override fun filter(spec: Spec<in @UnsafeVariance T>): Provider<T> =
        if (spec.isSatisfiedBy(value)) this else EagerProvider(value)
    override fun forUseAtConfigurationTime(): Provider<T> = this
    override fun <U : Any, R : Any> zip(right: Provider<U>, combiner: BiFunction<in @UnsafeVariance T, in U, out R?>): Provider<R> =
        EagerProvider(combiner.apply(value, right.get()))
}

// ---------------------------------------------------------------------------
// Version wrapper
// ---------------------------------------------------------------------------
class CatalogVersion(private val raw: String) {
    val requiredVersion: String get() = raw
    fun toInt(): Int = raw.toInt()
    override fun toString(): String = raw
}

// ===========================================================================
// LibrariesForKei  (gradle/kei.versions.toml)
// ===========================================================================
class LibrariesForKei(private val c: VersionCatalog) {

    val versions = KeiVersions(c)
    val plugins = KeiPlugins(c)

    class KeiVersions(private val c: VersionCatalog) {
        val android = object {
            val sdk = object {
                val compile: Provider<CatalogVersion> get() = v("android-sdk-compile")
                val min: Provider<CatalogVersion> get() = v("android-sdk-min")
                val target: Provider<CatalogVersion> get() = v("android-sdk-target")
            }
        }
        val java: Provider<CatalogVersion> get() = v("java")
        private fun v(key: String) = EagerProvider(CatalogVersion(c.findVersion(key).get().requiredVersion))
    }

    class KeiPlugins(private val c: VersionCatalog) {
        val android = object {
            val base: Provider<PluginDependency> get() = p("android-base")
        }
        val extension = object {
            val legacy: Provider<PluginDependency> get() = p("extension-legacy")
        }
        val library: Provider<PluginDependency> get() = p("library")
        val multisrc: Provider<PluginDependency> get() = p("multisrc")
        val spotless: Provider<PluginDependency> get() = p("spotless")
        private fun p(key: String): Provider<PluginDependency> = EagerProvider(c.findPlugin(key).get().get())
    }
}

// ===========================================================================
// LibrariesForLibs  (gradle/libs.versions.toml)
// ===========================================================================
class LibrariesForLibs(private val c: VersionCatalog) {

    val plugins = LibsPlugins(c)
    val bundles = LibsBundles(c)
    val versions = LibsVersions(c)

    // Direct libraries — findLibrary() already returns Provider, use directly
    val junit: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("junit").get()
    val jsoup: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("jsoup").get()
    val okhttp: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("okhttp").get()
    val rxjava: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("rxjava").get()
    val quickjs: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("quickjs").get()

    val android = object {
        val gradle: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("android-gradle").get()
    }
    val kotlin = object {
        val gradle: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("kotlin-gradle").get()
        val json: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("kotlin-json").get()
        val protobuf: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("kotlin-protobuf").get()
        val jsonOkio: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("kotlin-json-okio").get()
    }
    val ktlint = object {
        val bom: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("ktlint-bom").get()
    }
    val spotless = object {
        val gradle: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("spotless-gradle").get()
    }
    val tapmoc = object {
        val gradle: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("tapmoc-gradle").get()
    }
    val coroutines = object {
        val android: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("coroutines-android").get()
        val core: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("coroutines-core").get()
    }
    val injekt = object {
        val core: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("injekt-core").get()
    }
    val aniyomi = object {
        val lib: Provider<MinimalExternalModuleDependency> get() = c.findLibrary("aniyomi-lib").get()
    }

    class LibsPlugins(private val c: VersionCatalog) {
        val kotlin = object {
            val jvm: Provider<PluginDependency> get() = p("kotlin-jvm")
            val samWithReceiver: Provider<PluginDependency> get() = p("kotlin-samWithReceiver")
            val serialization: Provider<PluginDependency> get() = p("kotlin-serialization")
        }
        val android = object {
            val application: Provider<PluginDependency> get() = p("android-application")
            val library: Provider<PluginDependency> get() = p("android-library")
        }
        val spotless: Provider<PluginDependency> get() = p("spotless")
        private fun p(key: String): Provider<PluginDependency> = EagerProvider(c.findPlugin(key).get().get())
    }

    class LibsBundles(private val c: VersionCatalog) {
        val common: Provider<ExternalModuleDependencyBundle> get() = EagerProvider(c.findBundle("common").get())
    }

    class LibsVersions(private val c: VersionCatalog) {
        val coroutines: Provider<CatalogVersion> get() = v("coroutines")
        val junit: Provider<CatalogVersion> get() = v("junit")
        val ktlint: Provider<CatalogVersion> get() = v("ktlint")
        val spotless: Provider<CatalogVersion> get() = v("spotless")
        val tapmoc: Provider<CatalogVersion> get() = v("tapmoc")
        val serialization: Provider<CatalogVersion> get() = v("serialization")
        val android = object {
            val gradle: Provider<CatalogVersion> get() = v("android-gradle")
        }
        val kotlin = object {
            val gradle: Provider<CatalogVersion> get() = v("kotlin-gradle")
        }
        private fun v(key: String) = EagerProvider(CatalogVersion(c.findVersion(key).get().requiredVersion))
    }
}
