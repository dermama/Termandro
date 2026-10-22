plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        "../sources/termux-app/terminal-emulator/src/main/java",
        "../sources/termux-app/terminal-view/src/main/java",
    )
    sourceSets["main"].res.srcDirs(
        "src/main/res",
        "../sources/termux-app/terminal-view/src/main/res",
    )
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
