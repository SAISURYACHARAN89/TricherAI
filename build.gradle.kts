buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://chaquo.com/maven")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.9.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
        classpath("com.chaquo.python:gradle:14.0.2")
    }
}



tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
