import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream

plugins {
    id("idea")
    // Gradle IntelliJ Plugin
    // https://github.com/JetBrains/gradle-intellij-plugin
    id("org.jetbrains.intellij") version "1.13.0"
    // Kotlin JVM plugin to add support for Kotlin
    // https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#kotlin-standard-library
    kotlin("jvm") version "1.6.21"
    kotlin("plugin.serialization") version "1.6.21"
}

group = "cms.rendner.intellij"
version = "0.11.0"

repositories {
    mavenCentral()
}

dependencies {
    // https://github.com/beryx/awt-color-factory
    implementation("org.beryx:awt-color-factory:1.0.2")
    // https://github.com/Kotlin/kotlinx.serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")

    // https://github.com/junit-team/junit5-samples/blob/r5.8.2/junit5-jupiter-starter-gradle-kotlin/build.gradle.kts
    testImplementation(platform("org.junit:junit-bom:5.8.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    testImplementation("org.assertj:assertj-core:3.23.1")
}

// See https://github.com/JetBrains/gradle-intellij-plugin/
// https://intellij-support.jetbrains.com/hc/en-us/community/posts/206590865-Creating-PyCharm-plugins
intellij {
    plugins.add("python-ce") // is required even if we specify a PyCharm IDE
    version.set("2021.3")
    type.set("PC")
    downloadSources.set(true)
    updateSinceUntilBuild.set(false)
}

val testDockeredSourceSet by sourceSets.register("test-dockered") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations {
    getByName(testDockeredSourceSet.implementationConfigurationName) { extendsFrom(testImplementation.get()) }
    getByName(testDockeredSourceSet.runtimeOnlyConfigurationName) { extendsFrom(testRuntimeOnly.get()) }
}

idea {
    module {
        testSourceDirs = testSourceDirs.plus(testDockeredSourceSet.allJava.srcDirs)
        testResourceDirs = testResourceDirs.plus(testDockeredSourceSet.resources.srcDirs)
    }
}

// https://github.com/nazmulidris/idea-plugin-example/blob/main/build.gradle.kts
tasks {

    val supportedPandasVersions = listOf("1.1", "1.2", "1.3", "1.4", "1.5", "2.0", "2.1")

    data class PythonDockerImage(
        val path: String,
        val pythonVersion: String,
        val pipenvEnvironments: List<String>,
        val envsAreLocalPythonProjects: Boolean,
        ) {
        private val workdir = "/usr/src/app"
        val dockerImageName = "sdfv-plugin-dockered-python_$pythonVersion"
        val contentPath = "$path/content/"
        fun getTransferDirMappings(hostTransferPath: String) = arrayOf(
            "$hostTransferPath/in:${workdir}/transfer/in:ro",
            "$hostTransferPath/out:${workdir}/transfer/out",
        )
        fun getEnvironmentDir(pipenvEnvironment: String) = "${workdir}/pipenv_environments/$pipenvEnvironment"
        fun getBuildArgs(): Array<String> {
            return if (envsAreLocalPythonProjects) emptyArray()
            else arrayOf("--build-arg", "pipenv_environment=${pipenvEnvironments.first()}")
        }
    }

    val pythonDockerBaseDir = "$projectDir/dockered-python"
    val pythonDockerImages = listOf(
        // order of python versions: latest to oldest
        // order of pipenv environments: latest to oldest
        PythonDockerImage(
            "$pythonDockerBaseDir/python_3.11",
            "3.11",
            listOf("python_3.11"),
            false,
        ),
        PythonDockerImage(
            "$pythonDockerBaseDir/python_3.10",
            "3.10",
            listOf("python_3.10"),
            false,
        ),
        PythonDockerImage(
            "$pythonDockerBaseDir/python_3.9",
            "3.9",
            listOf("pandas_2.1"),
            true,
        ),
        PythonDockerImage(
            "$pythonDockerBaseDir/python_3.8",
            "3.8",
            listOf("pandas_2.0", "pandas_1.5", "pandas_1.4"),
            true,
        ),
        PythonDockerImage(
            "$pythonDockerBaseDir/python_3.7",
            "3.7",
            listOf("pandas_1.3", "pandas_1.2", "pandas_1.1"),
            true,
        ),
    )

    val buildPythonDockerImagesTasks = mutableListOf<Task>()
    pythonDockerImages.forEach { entry ->

        register<DefaultTask>("buildPythonDockerImage_${entry.pythonVersion}") {
            description = "Builds the python ${entry.pythonVersion} docker image."
            group = "docker"

            if (buildPythonDockerImagesTasks.isNotEmpty()) {
                shouldRunAfter(buildPythonDockerImagesTasks.last())
            }
            buildPythonDockerImagesTasks.add(this)

            doLast {
                delete(entry.contentPath)

                if (entry.envsAreLocalPythonProjects) {
                    entry.pipenvEnvironments.forEach { pipEnvEnvironment ->
                        val pythonSourceProjectPath = "../projects/python_plugin_code/${pipEnvEnvironment}_code"

                        val pipFile = project.file("$pythonSourceProjectPath/Pipfile")
                        val pipFileLock = project.file("$pythonSourceProjectPath/Pipfile.lock")
                        if (pipFile.exists() && pipFileLock.exists()) {
                            copy {
                                from(pipFile, pipFileLock)
                                into("${entry.contentPath}/pipenv_environments/$pipEnvEnvironment/")
                            }
                        } else {
                            throw GradleException("Incomplete Pipfiles for environment: $pipEnvEnvironment")
                        }
                    }
                }

                exec {
                    workingDir = file(entry.path)
                    executable = "docker"
                    args("build", *entry.getBuildArgs(), ".", "-t", entry.dockerImageName)
                }
            }
        }
    }

    register<DefaultTask>("buildPythonDockerImages") {
        description = "Builds all python docker images."
        group = "docker"
        dependsOn(project.tasks.filter { it.name.startsWith("buildPythonDockerImage_") })
    }

    register<DefaultTask>("killAllPythonContainers") {
        description = "Kills all python docker containers (cleanup task)."
        group = "docker"

        doLast {
            pythonDockerImages.forEach { entry ->
                val containerIdsResult = ByteArrayOutputStream().use { outputStream ->
                    exec {
                        executable = "docker"
                        args("ps", "-aq", "--filter", "ancestor=${entry.dockerImageName}")
                        standardOutput = outputStream
                    }
                    outputStream.toString().trim()
                }

                if (containerIdsResult.isNotEmpty()) {
                    containerIdsResult.split(System.lineSeparator()).let { ids ->
                        logger.lifecycle("killing containers $ids for image ${entry.dockerImageName}")
                        exec {
                            executable = "docker"
                            args(mutableListOf("rm", "-f").apply { addAll(ids) })
                        }
                    }
                } else {
                    logger.lifecycle("no running containers found for image ${entry.dockerImageName}")
                }
            }
        }
    }

    val integrationTestTasks = mutableListOf<Task>()
    pythonDockerImages.forEach { entry ->
        entry.pipenvEnvironments.forEach { pipEnvEnvironment ->
            register<Test>("integrationTest_$pipEnvEnvironment") {
                description = "Runs integration test on pipenv environment: $pipEnvEnvironment."
                group = "verification"
                outputs.upToDateWhen { false }

                testClassesDirs = testDockeredSourceSet.output.classesDirs
                classpath = testDockeredSourceSet.runtimeClasspath

                systemProperty(
                    "cms.rendner.dataframe.viewer.docker.image",
                    entry.dockerImageName,
                )
                systemProperty(
                    "cms.rendner.dataframe.viewer.docker.workdir",
                    entry.getEnvironmentDir(pipEnvEnvironment),
                )
                systemProperty(
                    "cms.rendner.dataframe.viewer.docker.volumes",
                    listOf(
                        *entry.getTransferDirMappings("$projectDir/src/test-dockered/transfer"),
                    ).joinToString(";"),
                )
                useJUnitPlatform {
                    include("**/integration/**")
                }

                shouldRunAfter(test)
                if (integrationTestTasks.isNotEmpty()) {
                    shouldRunAfter(integrationTestTasks.last())
                }
                integrationTestTasks.add(this)

                afterTest(KotlinClosure2<TestDescriptor, TestResult, Any>({ descriptor, result ->
                    val clsName = descriptor.parent?.displayName ?: descriptor.className
                    println("\t$clsName > ${descriptor.name}: ${result.resultType}")
                }))
            }
        }
    }

    register<DefaultTask>("integrationTest_all") {
        description = "Runs all integrationTest tasks."
        group = "verification"
        outputs.upToDateWhen { false }
        dependsOn(project.tasks.filter { it.name.startsWith("integrationTest_") && it.name != this.name })
    }

    test {
        useJUnitPlatform()
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        version.set(project.version.toString())
    }

    runIde {
        // enable debug log for plugin
        // -> https://github.com/JetBrains/gradle-intellij-plugin/issues/708#issuecomment-870442081
        systemProperty(
            "idea.log.debug.categories",
            "#cms.rendner",
        )
        // environment["PYCHARM_DEBUG"] = "True"
        // environment["PYDEV_DEBUG"] = "True"
        environment["PYDEVD_USE_CYTHON"] = "NO"
        environment["idea.is.internal"] = true
        // ideDir.set(File("/snap/intellij-idea-community/current"))
    }

    processResources {
        // Note:
        // Copying the resources has to happen in "doFirst" instead of "doLast".
        //
        // Problem:
        // When using "doLast" and running the gradle-task "runIde", the ide is started with the old
        // resources. The resources are copied, but it seems the ide is started too early?
        //
        // Traceability:
        // - switch to "doLast"
        // - delete all "src/resources/pandas_x.y" folders
        // - run gradle-task "runIde"
        // - try to use the plugin code (by inspecting a DataFrame.style)
        // - plugin crashes because the resources are not available
        doFirst {
            logger.lifecycle("copy 'plugin_code' files")
            copy {
                from(project.file("../projects/python_plugin_code/sdfv_base/generated"))
                into(project.file("src/main/resources/sdfv_base"))
            }
            supportedPandasVersions.forEach { majorMinor ->
                val generatedCodeDir = project.file("../projects/python_plugin_code/pandas_${majorMinor}_code/generated")
                if (generatedCodeDir.exists()) {
                    copy {
                        from(generatedCodeDir)
                        into(project.file("src/main/resources/pandas_$majorMinor"))
                    }
                } else {
                    throw GradleException("Missing file 'plugin_code' for pandas version: $majorMinor")
                }
            }
        }
        outputs.upToDateWhen { false }
    }

    runPluginVerifier {
        // See https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html#tasks-runpluginverifier
        // See https://data.services.jetbrains.com/products?fields=code,name,releases.version,releases.build,releases.type&code=PC
        //ideVersions.addAll(listOf("PC-2021.3", "PC-2021.3.2", "PC-2022.1"))
    }

    listProductsReleases {
        sinceVersion.set("2021.3")
        untilVersion.set("2023.2.1")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs = listOf(
            // to allow experimental "Json.decodeFromString()"
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

tasks.jar {
    exclude {
        // Exclude unwanted files.
        // These files are created sometimes when using the python files from the plugin resource folder directly
        // in a PyCharm project.
        // info: https://www.jetbrains.com/help/pycharm/cleaning-pyc-files.html
        it.name == "__pycache__" || it.file.extension == ".pyc"
    }
}