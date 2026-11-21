plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}

// Version properties used across submodules
val carAppVersion = "1.4.0"
val mediaVersion = "1.6.0"
val retrofitVersion = "2.11.0"
val okhttpVersion = "4.12.0"
val coroutinesVersion = "1.8.0"
val lifecycleVersion = "2.7.0"
val coreKtxVersion = "1.12.0"
val appcompatVersion = "1.6.1"
val activityKtxVersion = "1.8.2"
val kotlinVersion = "1.9.23"

allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
        }
    }
}
