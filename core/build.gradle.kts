// Plain JVM Kotlin with no Android SDK: the lyrics sources, blends, matching, parsers, models and
// the lyric clock. The Android app depends on it, and a desktop app can too.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_11
}

dependencies {
    api("com.google.code.gson:gson:2.14.0")
    api("com.squareup.okhttp3:okhttp:5.5.0")
    api("com.squareup.retrofit2:retrofit:3.0.0")
    api("com.squareup.retrofit2:converter-gson:3.0.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("javax.inject:javax.inject:1")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // Android ships the XmlPull API; a desktop app adds kxml2 at runtime.
    compileOnly("xmlpull:xmlpull:1.1.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
