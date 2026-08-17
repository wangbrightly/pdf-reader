pluginManagement {
    repositories {
        // 阿里云镜像放前面：部分依赖（如 Robolectric 的 nativeruntime-dist-compat，
        // 约 159MB）走本机代理走境外线路只有约 10KB/s，实测走阿里云镜像直连约 9MB/s。
        // ~/.gradle/gradle.properties 里已把 *.aliyun.com 排除在代理之外。
        // 镜像找不到时会自动回落到下面的 google()/mavenCentral()。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "pdf-reader"
include(":app")
