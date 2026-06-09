@file:Suppress("unused")

package keiyoushi.gradle.extensions

import org.gradle.api.Transformer
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.plugin.use.PluginDependency
import java.util.function.BiFunction

// ============================================================================
//  Eager provider — satisfies all 11 Provider<T> methods for Gradle 8.x
//  INVARIANT T so that orElse(T) is legal; @UnsafeVariance handles the rest.
// ============================================================================

class EagerProvider<T>(private val value: T) : Provider<T> {
    override fun get(): T = value
    override fun getOrNull(): T = value
    override fun getOrElse(default: T): T = value
    override fun isPresent(): Boolean = true
    override fun orElse(provider: Provider<out T>): Provider<T> = this
    override fun orElse(value: @UnsafeVariance T): Provider<T> = this
    override fun <S : Any> map(transformer: Transformer<out S?, in T>): Provider<S?> =
        EagerProvider(transformer.transform(value))
    override fun <S : Any> flatMap(transformer: Transformer<out Provider<out S>?, in T>): Provider<S?> {
        @Suppress("UNCHECKED_CAST")
        val r = transformer.transform(value)
        return if (r != null) {
            @Suppress("UNCHECKED_CAST")
            r as Provider<S?>
        } else {
            NullProvider()
        }
    }
    override fun filter(spec: Spec<in T>): Provider<T> =
        if (spec.isSatisfiedBy(value)) this else EagerProvider(value)
    override fun forUseAtConfigurationTime(): Provider<T> = this
    override fun <U : Any, R : Any> zip(
        right: Provider<U>,
        combiner: BiFunction<in T, in U, out R?>,
    ): Provider<R> = EagerProvider(combiner.apply(value, right.get()))
}

private class NullProvider<T> : Provider<T> {
    override fun get(): T? = null
    override fun getOrNull(): T? = null
    override fun getOrElse(default: T): T = default
    override fun isPresent(): Boolean = false
    override fun orElse(provider: Provider<out T>): Provider<T> = EagerProvider(provider.get())
    override fun orElse(value: @UnsafeVariance T): Provider<T> = EagerProvider(value)

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> map(transformer: Transformer<out S?, in T>): Provider<S?> =
        unused() as Provider<S?>

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> flatMap(transformer: Transformer<out Provider<out S>?, in T>): Provider<S?> =
        unused() as Provider<S?>

    @Suppress("UNCHECKED_CAST")
    override fun filter(spec: Spec<in T>): Provider<T> = unused() as Provider<T>

    override fun forUseAtConfigurationTime(): Provider<T> = this

    @Suppress("UNCHECKED_CAST")
    override fun <U : Any, R : Any> zip(
        right: Provider<U>,
        combiner: BiFunction<in T, in U, out R?>,
    ): Provider<R> = unused() as Provider<R>

    private fun unused(): Nothing = throw UnsupportedOperationException("NullProvider transform")
}

class CatalogVersion(private val raw: String) {
    val requiredVersion: String get() = raw
    fun toInt(): Int = raw.toInt()
}

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

    val plugins get() = _Plugins(c)
    val bundles get() = _Bundles(c)
    val versions get() = _Versions(c)

    // Direct libraries
    val junit get() = c.findLibrary("junit").get()
    val jsoup get() = c.findLibrary("jsoup").get()
    val okhttp get() = c.findLibrary("okhttp").get()
    val rxjava get() = c.findLibrary("rxjava").get()
    val quickjs get() = c.findLibrary("quickjs").get()

    // Library groups
    val android get() = _LibAndroid(c)
    val kotlin get() = _LibKotlin(c)
    val ktlint get() = _LibKtlint(c)
    val spotless get() = _LibSpotless(c)
    val tapmoc get() = _LibTapmoc(c)
    val coroutines get() = _LibCoroutines(c)
    val injekt get() = _LibInjekt(c)
    val aniyomi get() = _LibAniyomi(c)

    // ——— nested ———

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
        // findBundle().get() → Provider<Bundle> — use directly, no wrap
        val common: Provider<ExternalModuleDependencyBundle> get() = c.findBundle("common").get()
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