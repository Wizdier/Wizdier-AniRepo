import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DefaultConfig
import com.android.build.api.dsl.LibraryDefaultConfig
import keiyoushi.gradle.configurations.configureKotlin
import keiyoushi.gradle.extensions.kei
import keiyoushi.gradle.extensions.spotlessTaskName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

@Suppress("UNUSED")
class PluginAndroidBase : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        configureKotlin()

        extensions.configure<CommonExtension<*, *, *, *, *, *>> {
            compileSdk = kei.versions.android.sdk.compile.get().requiredVersion.toInt()

            defaultConfig {
                minSdk = kei.versions.android.sdk.min.get().requiredVersion.toInt()
                if (this is ApplicationDefaultConfig) {
                    targetSdk = kei.versions.android.sdk.target.get().requiredVersion.toInt()
                }

                val proguardFile = file("proguard-rules.pro")
                if (proguardFile.exists()) {
                    when (this) {
                        is ApplicationDefaultConfig -> proguardFile(proguardFile)
                        is LibraryDefaultConfig -> consumerProguardFiles(proguardFile)
                    }
                }
            }
        }

        tasks.getByName("preBuild").dependsOn(spotlessTaskName())
    }
}
