plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; declaring KGP here pins the compiler version
    // (tdl-coroutines is built with Kotlin 2.4).
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
