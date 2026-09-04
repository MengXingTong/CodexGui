import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.7.2"
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "com.codexgui"
version = "0.5.1"

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
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

node {
    version.set("22.14.0")
    download.set(true)
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated-resources"))
    }
}

val frontendTypecheck by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run"))
    args.set(listOf("typecheck"))
    inputs.files(fileTree("src/main/ts"), fileTree("src/test/ts"), "tsconfig.json")
}

val frontendTest by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run"))
    args.set(listOf("test"))
    inputs.files(fileTree("src/main/ts"), fileTree("src/test/ts"), "package.json", "package-lock.json")
}

val frontendBundle by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run"))
    args.set(listOf("build"))
    inputs.files(fileTree("src/main/ts"), "package.json", "package-lock.json")
    outputs.file(layout.buildDirectory.file("generated-resources/web/app.js"))
}

intellijPlatform {
    pluginConfiguration {
        id = "com.codexgui.jetbrains"
        name = "CodeDeck"
        version = project.version.toString()
        description = """
            <p>CodeDeck is a native Codex and Claude Code CLI interface for JetBrains IDEs.</p>
            <p>面向 JetBrains IDE 的 Codex / Claude Code 图形界面。</p>
            <p>连接 Codex app-server 与 Claude Code CLI，不注入第三方隐藏提示词。</p>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }

        vendor {
            name = "CodeDeck"
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
    processResources {
        dependsOn(frontendBundle)
    }

    check {
        dependsOn(frontendTypecheck, frontendTest, frontendBundle)
    }

    test {
        useJUnitPlatform()
        classpath = classpath.filter { it.name != "testFramework.jar" }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
