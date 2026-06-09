package keiyoushi.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.DependencyHandlerScope

fun DependencyHandlerScope.compileOnly(dep: MinimalExternalModuleDependency) {
    add("compileOnly", dep)
}

fun DependencyHandlerScope.compileOnly(bundle: ExternalModuleDependencyBundle) {
    add("compileOnly", bundle)
}

fun DependencyHandlerScope.implementation(dep: MinimalExternalModuleDependency) {
    add("implementation", dep)
}

fun DependencyHandlerScope.implementation(bundle: ExternalModuleDependencyBundle) {
    add("implementation", bundle)
}

fun DependencyHandlerScope.implementation(dep: Project) {
    add("implementation", dep)
}

fun DependencyHandlerScope.implementation(dep: ProjectDependency) {
    add("implementation", dep)
}
