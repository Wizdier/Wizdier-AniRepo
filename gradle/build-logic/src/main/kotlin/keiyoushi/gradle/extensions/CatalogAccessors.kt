package keiyoushi.gradle.extensions

import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.provider.Provider
import org.gradle.plugin.use.PluginDependency

// ---------------------------------------------------------------------------
// Simple eager Provider wrapper
// ---------------------------------------------------------------------------
class ValProvider<T>(private val value: T) : Provider<T> {
    override fun get(): T = value
    override fun getOrNull(): T = value
    override fun getOrElse(default: T): T = value
    override fun isPresent(): Boolean = true
    override fun orElse(provider: Provider<out T>): Provider<T> = this
}

// ---------------------------------------------------------------------------
// Version wrapper — same API as Gradle's generated Version class
// ---------------------------------------------------------------------------
class CatalogVersion(private val raw: String) {
    val requiredVersion: String get() = raw
    val strictVersion: String? get() = null
    val preferredVersion: String? get() = null
    fun toInt(): Int = raw.toInt()
    override fun toString(): String = raw
}

// ---------------------------------------------------------------------------
// LibrariesForKei  (gradle/kei.versions.toml)
// ---------------------------------------------------------------------------
class LibrariesForKei(private val c: VersionCatalog) {

    val versions = object {
        val android = object {
            val sdk = object {
                val compile: Provider<CatalogVersion> get() = v("android-sdk-compile")
                val min: Provider<CatalogVersion> get() = v("android-sdk-min")
                val target: Provider<CatalogVersion> get() = v("android-sdk-target")
            }
        }
        val java: Provider<CatalogVersion> get() = v("java")
    }

    val plugins = object {
        val android = object {
            val base: Provider<PluginDependency> get() = p("android-base")
        }
        val extension = object {
            val legacy: Provider<PluginDependency> get() = p("extension-legacy")
        }
        val library: Provider<PluginDependency> get() = p("library")
        val multisrc: Provider<PluginDependency> get() = p("multisrc")
        val spotless: Provider<PluginDependency> get() = p("spotless")
    }

    private fun v(key: String) = ValProvider(CatalogVersion(c.findVersion(key).get().requiredVersion))
    private fun p(key: String) = ValProvider(c.findPlugin(key).get().get())
}

// ---------------------------------------------------------------------------
// LibrariesForLibs  (gradle/libs.versions.toml)
// ---------------------------------------------------------------------------
class LibrariesForLibs(private val c: VersionCatalog) {

    // -- plugins --
    val plugins = object {
        val kotlin = object {
            val jvm: Provider<PluginDependency> get() = pp("kotlin-jvm")
            val samWithReceiver: Provider<PluginDependency> get() = pp("kotlin-samWithReceiver")
            val serialization: Provider<PluginDependency> get() = pp("kotlin-serialization")
        }
        val android = object {
            val application: Provider<PluginDependency> get() = pp("android-application")
            val library: Provider<PluginDependency> get() = pp("android-library")
        }
        val spotless: Provider<PluginDependency> get() = pp("spotless")
    }

    // -- libraries --
    val android = object {
        val gradle: Provider<MinimalExternalModuleDependency> get() = ll("android-gradle")
    }
    val kotlin = object {
        val gradle: Provider<MinimalExternalModuleDependency> get() = ll("kotlin-gradle")
    }
    val ktlint = object {
        val bom: Provider<MinimalExternalModuleDependency> get() = ll("ktlint-bom")
    }
    val spotless = object {
        val gradle: Provider<MinimalExternalModuleDependency> get() = ll("spotless-gradle")
    }
    val tapmoc = object {
        val gradle: Provider<MinimalExternalModuleDependency> get() = ll("tapmoc-gradle")
    }
    val coroutines = object {
        val android: Provider<MinimalExternalModuleDependency> get() = ll("coroutines-android")
        val core: Provider<MinimalExternalModuleDependency> get() = ll("coroutines-core")
    }
    val injekt = object {
        val core: Provider<MinimalExternalModuleDependency> get() = ll("injekt-core")
    }
    val aniyomi = object {
        val lib: Provider<MinimalExternalModuleDependency> get() = ll("aniyomi-lib")
    }
    val junit: Provider<MinimalExternalModuleDependency> get() = ll("junit")
    val jsoup: Provider<MinimalExternalModuleDependency> get() = ll("jsoup")
    val okhttp: Provider<MinimalExternalModuleDependency> get() = ll("okhttp")
    val rxjava: Provider<MinimalExternalModuleDependency> get() = ll("rxjava")
    val quickjs: Provider<MinimalExternalModuleDependency> get() = ll("quickjs")
    val kotlinJson: Provider<MinimalExternalModuleDependency> get() = ll("kotlin-json")
    val kotlinProtobuf: Provider<MinimalExternalModuleDependency> get() = ll("kotlin-protobuf")
    val kotlinJsonOkio: Provider<MinimalExternalModuleDependency> get() = ll("kotlin-json-okio")

    // -- bundles --
    val bundles = object {
        val common: Provider<ExternalModuleDependencyBundle> get() =
            ValProvider(c.findBundle("common").get())
    }

    // -- versions --
    val versions = object {
        val coroutines: Provider<CatalogVersion> get() = vv("coroutines")
        val junit: Provider<CatalogVersion> get() = vv("junit")
        val ktlint: Provider<CatalogVersion> get() = vv("ktlint")
        val spotless: Provider<CatalogVersion> get() = vv("spotless")
        val tapmoc: Provider<CatalogVersion> get() = vv("tapmoc")
        val serialization: Provider<CatalogVersion> get() = vv("serialization")
        val android = object {
            val gradle: Provider<CatalogVersion> get() = vv("android-gradle")
        }
        val kotlin = object {
            val gradle: Provider<CatalogVersion> get() = vv("kotlin-gradle")
        }
    }

    private fun ll(key: String) = ValProvider(c.findLibrary(key).get())
    private fun pp(key: String) = ValProvider(c.findPlugin(key).get().get())
    private fun vv(key: String) = ValProvider(CatalogVersion(c.findVersion(key).get().requiredVersion))
}
