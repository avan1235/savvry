import buildsrc.convention.Env_gradle.Env.SavvryVersion
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile

plugins {
    id("buildsrc.convention.env")
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        wasmJsMain.dependencies {
            implementation(projects.app.shared)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.browser)
        }
    }
}

tasks.withType<KotlinJsCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xwasm-kclass-fqn")
}

fun File.fillServiceWorkerEnvVariables(cacheVersion: String, apiUrl: String) {
    if (!exists()) return

    val content = readText()
    val updatedContent = content
        .replace("{{CACHE_VERSION}}", cacheVersion)
        .replace("{{API_URL}}", apiUrl)
    writeText(updatedContent)
}

tasks.named("wasmJsProcessResources") {
    val cacheVersion = SavvryVersion
    val apiUrl = "${env.CLIENT_HTTP_PROTOCOL.value}://${env.CLIENT_HOST.value}:${env.CLIENT_PORT.value}"

    inputs.property("cacheVersion", cacheVersion)
    inputs.property("apiUrl", apiUrl)

    doLast {
        val serviceWorker = layout.buildDirectory.file("processedResources/wasmJs/main/service-worker.js").get().asFile
        serviceWorker.fillServiceWorkerEnvVariables(cacheVersion, apiUrl)
    }
}
