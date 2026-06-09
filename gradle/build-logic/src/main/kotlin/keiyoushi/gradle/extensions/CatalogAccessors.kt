package keiyoushi.gradle.extensions

import org.gradle.api.Transformer
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.plugin.use.PluginDependency
import java.util.function.BiFunction

<<<<<<< HEAD
// ============================================================================
//  Eager provider + version wrappers
// ============================================================================

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

=======
// ---------------------------------------------------------------------------
// Provider wrapper — eagerly resolves, full Provider<T> contract
// ---------------------------------------------------------------------------
class EagerProvider<T>(private val value: T) : Provider<T> {
    override fun get(): T = value
    override fun getOrNull(): T = value
    override fun getOrElse(default: T & Any): T = value
    override fun isPresent(): Boolean = true
    override fun orElse(value: T & Any): Provider<T & Any> = EagerProvider(value)
    override fun orElse(provider: Provider<out T>): Provider<T> = this
    override fun <S : Any> map(transformer: Transformer<out S?, in T>): Provider<S?> =
        EagerProvider(transformer.transform(value))
    override fun <S : Any> flatMap(transformer: Transformer<out Provider<out S>?, in T>): Provider<S> {
        @Suppress("UNCHECKED_CAST")
        return (transformer.transform(value) as Provider<S>?) ?: EagerProvider<S?>(null) as Provider<S>
    }
    override fun filter(spec: Spec<in T>): Provider<T> =
        if (spec.isSatisfiedBy(value)) this else EagerProvider(value)
    override fun forUseAtConfigurationTime(): Provider<T> = this
    override fun <U : Any, R : Any> zip(right: Provider<U>, combiner: BiFunction<in T, in U, out R?>): Provider<R> =
        EagerProvider(combiner.apply(value, right.get()))
}

// ---------------------------------------------------------------------------
// Version wrapper
// ---------------------------------------------------------------------------
>>>>>>> 25b228605e61f1f1a59fa533287cb3e988cc0b2b
class CatalogVersion(private val raw: String) {
    val requiredVersion: String get() = raw
    fun toInt(): Int = raw.toInt()
    override fun toString(): String = raw
}

<<<<<<< HEAD
// ============================================================================
//  LibrariesForKei  —  gradle/kei.versions.toml
// ============================================================================

class LibrariesForKei(private val c: VersionCatalog) {
    val versions get() = _Versions(c)
    val plugins get() = _Plugins(c)

    class _Versions(private val c: VersionCatalog) {
        val android get() = _Android(c)
        val java get() = v("java")
        private fun v(k: String) = EagerProvider(CatalogVersion(c.findVersion(k).get().requiredVersion))
    }
    class _Android(private val c: VersionCatalog) {
        val sdk get() = _Sdk(c)
    }
    class _Sdk(private val c: VersionCatalog) {
        val compile get() = v("android-sdk-compile")
        val min get() = v("android-sdk-min")
        val target get() = v("android-sdk-target")
        private fun v(k: String) = EagerProvider(CatalogVersion(c.findVersion(k).get().requiredVersion))
    }

    class _Plugins(private val c: VersionCatalog) {
        val android get() = _AndroidPlugins(c)
        val extension get() = _ExtensionPlugins(c)
        val library get() = p("library")
        val multisrc get() = p("multisrc")
        val spotless get() = p("spotless")
        private fun p(k: String) = EagerProvider(c.findPlugin(k).get().get())
    }
    class _AndroidPlugins(private val c: VersionCatalog) {
        val base get() = p("android-base")
        private fun p(k: String) = EagerProvider(c.findPlugin(k).get().get())
    }
    class _ExtensionPlugins(private val c: VersionCatalog) {
        val legacy get() = p("extension-legacy")
        private fun p(k: String) = EagerProvider(c.findPlugin(k).get().get())
    }
}

// ============================================================================
//  LibrariesForLibs  —  gradle/libs.versions.toml
// ============================================================================

class LibrariesForLibs(private val c: VersionCatalog) {

    // --- plugins ---
    val plugins get() = _Plugins(c)

    // --- bundles ---
    val bundles get() = _Bundles(c)

    // --- versions ---
    val versions get() = _Versions(c)

    // --- direct libraries ---
    val junit get() = c.findLibrary("junit").get()
    val jsoup get() = c.findLibrary("jsoup").get()
    val okhttp get() = c.findLibrary("okhttp").get()
    val rxjava get() = c.findLibrary("rxjava").get()
    val quickjs get() = c.findLibrary("quickjs").get()

    // --- libraries by group ---
    val android get() = _LibAndroid(c)
    val kotlin get() = _LibKotlin(c)
    val ktlint get() = _LibKtlint(c)
    val spotless get() = _LibSpotless(c)
    val tapmoc get() = _LibTapmoc(c)
    val coroutines get() = _LibCoroutines(c)
    val injekt get() = _LibInjekt(c)
    val aniyomi get() = _LibAniyomi(c)

    // ---- nested classes ----

    class _Plugins(private val c: VersionCatalog) {
        val kotlin get() = _KotlinPlugins(c)
        val android get() = _AndroidPlugins(c)
        val spotless get() = p("spotless")
        private fun p(k: String) = EagerProvider(c.findPlugin(k).get().get())
    }
    class _KotlinPlugins(private val c: VersionCatalog) {
        val jvm get() = p("kotlin-jvm")
        val samWithReceiver get() = p("kotlin-samWithReceiver")
        val serialization get() = p("kotlin-serialization")
        private fun p(k: String) = EagerProvider(c.findPlugin(k).get().get())
    }
    class _AndroidPlugins(private val c: VersionCatalog) {
        val application get() = p("android-application")
        val library get() = p("android-library")
        private fun p(k: String) = EagerProvider(c.findPlugin(k).get().get())
    }

    class _Bundles(private val c: VersionCatalog) {
        val common get() = EagerProvider(c.findBundle("common").get())
    }

    class _Versions(private val c: VersionCatalog) {
        val coroutines get() = v("coroutines")
        val junit get() = v("junit")
        val ktlint get() = v("ktlint")
        val spotless get() = v("spotless")
        val tapmoc get() = v("tapmoc")
        val serialization get() = v("serialization")
        val android get() = _VAndroid(c)
        val kotlin get() = _VKotlin(c)
        private fun v(k: String) = EagerProvider(CatalogVersion(c.findVersion(k).get().requiredVersion))
    }
    class _VAndroid(private val c: VersionCatalog) {
        val gradle get() = v("android-gradle")
        private fun v(k: String) = EagerProvider(CatalogVersion(c.findVersion(k).get().requiredVersion))
    }
    class _VKotlin(private val c: VersionCatalog) {
        val gradle get() = v("kotlin-gradle")
        private fun v(k: String) = EagerProvider(CatalogVersion(c.findVersion(k).get().requiredVersion))
    }

    class _LibAndroid(private val c: VersionCatalog) {
        val gradle get() = c.findLibrary("android-gradle").get()
    }
    class _LibKotlin(private val c: VersionCatalog) {
        val gradle get() = c.findLibrary("kotlin-gradle").get()
        val json get() = c.findLibrary("kotlin-json").get()
        val protobuf get() = c.findLibrary("kotlin-protobuf").get()
        val jsonOkio get() = c.findLibrary("kotlin-json-okio").get()
    }
    class _LibKtlint(private val c: VersionCatalog) {
        val bom get() = c.findLibrary("ktlint-bom").get()
    }
    class _LibSpotless(private val c: VersionCatalog) {
        val gradle get() = c.findLibrary("spotless-gradle").get()
    }
    class _LibTapmoc(private val c: VersionCatalog) {
        val gradle get() = c.findLibrary("tapmoc-gradle").get()
    }
    class _LibCoroutines(private val c: VersionCatalog) {
        val android get() = c.findLibrary("coroutines-android").get()
        val core get() = c.findLibrary("coroutines-core").get()
    }
    class _LibInjekt(private val c: VersionCatalog) {
        val core get() = c.findLibrary("injekt-core").get()
    }
    class _LibAniyomi(private val c: VersionCatalog) {
        val lib get() = c.findLibrary("aniyomi-lib").get()
    }
}
=======
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
        val common: Provider<ExternalModuleDependencyBundle> get() = c.findBundle("common").get()
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
>>>>>>> 25b228605e61f1f1a59fa533287cb3e988cc0b2b
