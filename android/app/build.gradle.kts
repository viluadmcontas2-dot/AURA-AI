plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "ai.aura.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.aura.app"
        minSdk = 26
        targetSdk = 35
        // Versão vem do script de release (-PauraVersion=N). Atualizar por cima exige N maior.
        val auraVersion = (project.findProperty("auraVersion") as String?)?.toInt() ?: 1
        versionCode = auraVersion
        versionName = "v$auraVersion"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // Robolectric precisa dos recursos do app para clicar nas telas na JVM, sem emulador.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
// TEMPORÁRIO (Task 16): os testes de offline da Task 1 chegam antes do código dela e quebram a compilação
// de todos os testes. Enquanto EngineLink.kt não existir, ficam fora; ao entrar a Task 1 isto se desliga
// sozinho (e pode ser apagado no merge).
if (!file("src/main/java/ai/aura/app/offline/EngineLink.kt").exists()) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { it.name == "compileDebugUnitTestKotlin" }
        .configureEach { exclude("**/offline/**") }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Testes de tela e de camada offline rodam na JVM (AGENTS.md, regra 4).
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
