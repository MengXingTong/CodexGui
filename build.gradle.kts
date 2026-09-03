import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "com.codexgui"
version = "0.4.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("javazoom:jlayer:1.0.1")

    intellijPlatform {
        intellijIdeaCommunity("2024.3.7")
        pluginVerifier()
        zipSigner()
    }

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.codexgui.jetbrains"
        name = "Codex GUI"
        version = project.version.toString()
        description = """
            <p>Codex GUI is a native Codex and Claude Code CLI interface for JetBrains IDEs.</p>
            <p>面向 JetBrains IDE 的 Codex / Claude Code 图形界面。</p>
            <p>连接 Codex app-server 与 Claude Code CLI，不注入第三方隐藏提示词。</p>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }

        vendor {
            name = "Codex GUI"
        }
    }

    buildSearchableOptions = false

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7")
        }
    }

    publishing {
        token = providers.environmentVariable("INTELLIJ_PLATFORM_PUBLISH_TOKEN")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
