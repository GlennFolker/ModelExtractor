import arc.files.*
import arc.util.*

buildscript{
    dependencies{
        val mindustryVersion: String by project
        classpath("com.github.Anuken.Arc:arc-core:$mindustryVersion")
    }

    repositories{
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://oss.sonatype.org/content/repositories/releases/")
        maven("https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository")
    }
}

plugins{
    `java-library`
}

val mindustryVersion: String by project
val entVersion: String by project

val androidSdkVersion: String by project
val androidBuildVersion: String by project
val androidMinVersion: String by project

sourceSets["main"].java.setSrcDirs(listOf("src"))

configurations.configureEach{
    resolutionStrategy.eachDependency{
        if(requested.group == "com.github.Anuken.Arc"){
            useVersion(mindustryVersion)
        }
    }
}

dependencies{
    annotationProcessor("com.github.GlennFolker.EntityAnno:downgrader:$entVersion")
    compileOnlyApi("com.github.Anuken.Mindustry:core:$mindustryVersion")
}

repositories{
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/releases/")

    maven("https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository")
    maven("https://jitpack.io")
}

tasks.withType<JavaCompile>().configureEach{
    sourceCompatibility = "17"
    options.apply{
        release = 8
        compilerArgs.add("-Xlint:-options")

        isIncremental = true
        encoding = "UTF-8"
    }
}

val jar = tasks.named<Jar>("jar"){
    inputs.files(configurations.runtimeClasspath)
    archiveFileName = "ModelExtractorDesktop.jar"

    from(
        files(sourceSets["main"].output.classesDirs),
        files(sourceSets["main"].output.resourcesDir),
        configurations.runtimeClasspath.map{conf -> conf.map{if(it.isDirectory) it else zipTree(it)}},

        files(layout.projectDirectory.dir("assets")),
        layout.projectDirectory.files("mod.json", "icon.png"),
    )

    metaInf.from(layout.projectDirectory.file("LICENSE"))
}

tasks.register<Jar>("dex"){
    inputs.files(jar)
    archiveFileName = "ModelExtractor.jar"

    val desktopJar = jar.flatMap{it.archiveFile}
    val dexJar = File(temporaryDir, "Dex.jar")

    val dex = providers.exec{
        val sdkRoot = File(
            OS.env("ANDROID_SDK_ROOT") ?: OS.env("ANDROID_HOME") ?:
            throw IllegalStateException("Neither `ANDROID_SDK_ROOT` nor `ANDROID_HOME` is set.")
        )

        val d8 = File(sdkRoot, "build-tools/$androidBuildVersion/${if(OS.isWindows) "d8.bat" else "d8"}")
        if(!d8.exists()) throw IllegalStateException("Android SDK `build-tools;$androidBuildVersion` isn't installed or is corrupted")

        val input = desktopJar.get().asFile
        val command = arrayListOf("$d8", "--release", "--min-api", androidMinVersion, "--output", "$dexJar", "$input")

        (configurations.compileClasspath.get().toList() + configurations.runtimeClasspath.get().toList()).forEach{
            if(it.exists()) command.addAll(arrayOf("--classpath", it.path))
        }

        val androidJar = File(sdkRoot, "platforms/android-$androidSdkVersion/android.jar")
        if(!androidJar.exists()) throw IllegalStateException("Android SDK `platforms;android-$androidSdkVersion` isn't installed or is corrupted")

        command.addAll(arrayOf("--lib", "$androidJar"))
        if(OS.isWindows) command.addAll(0, arrayOf("cmd", "/c").toList())

        logger.lifecycle("Running `d8`.")
        commandLine(command)
    }

    from(zipTree(desktopJar), zipTree(dexJar))
    doFirst{
        dex.result.get().rethrowFailure()
    }
}

tasks.register<DefaultTask>("install"){
    inputs.files(jar)

    val desktopJar = jar.flatMap{it.archiveFile}
    doLast{
        val input = desktopJar.get().asFile

        val folder = Fi.get(OS.getAppDataDirectoryString("Mindustry")).child("mods")
        folder.mkdirs()

        folder.child(input.name).delete()
        Fi(input).copyTo(folder)

        logger.lifecycle("Copied :deploy output to $folder.")
    }
}
