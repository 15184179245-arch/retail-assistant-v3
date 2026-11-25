plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.50" apply false
    // 2026-09-17 效率改造：KSP 取代 kapt。KSP 版本号必须与 Kotlin 版本严格对齐
    // （1.9.22-1.0.17 = Kotlin 1.9.22 + KSP 1.0.17）
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
