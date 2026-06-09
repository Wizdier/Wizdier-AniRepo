import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DefaultConfig
import com.android.build.api.dsl.LibraryDefaultConfig
import keiyoushi.gradle.configurations.configureKotlin
import keiyoushi.gradle.extensions.keiCatalog
import keiyoushi.gradle.extensions.spotlessTaskName
import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("UNUSED", "UNCHECKED_CAST")
class PluginAndroidBase : Plugin<Project> {

    private fun DefaultConfig.applyDefaults(project: Project, kei: org.gradle.api.artifacts.VersionCatalog) {
        minSdk = kei.findVersion("android-sdk-min").get().requiredVersion.toInt()
        if (this is ApplicationDefaultConfig) {
            targetSdk = kei.findVersion("android-sdk-target").get().requiredVersion.toInt()
        }
        val proguardFile = project.file("proguard-rules.pro")
        if (proguardFile.exists()) {
            when (this) {
                is ApplicationDefaultConfig -> proguardFile(proguardFile)
                is LibraryDefaultConfig -> consumerProguardFiles(proguardFile)
            }
        }
    }

    override fun apply(target: Project) {
        val project = target
        with(project) {
            configureKotlin()

            val kei = keiCatalog

            @Suppress("USELESS_CAST")
            val common = extensions.getByName("android") as CommonExtension
            common.compileSdk = kei.findVersion("android-sdk-compile").get().requiredVersion.toInt()
            common.defaultConfig.apply { applyDefaults(project, kei) }

            tasks.getByName("preBuild").dependsOn(spotlessTaskName())
        }
    }
}