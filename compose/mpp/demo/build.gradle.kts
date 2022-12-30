/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import androidx.build.AndroidXComposePlugin
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("native.cocoapods")
    id("AndroidXPlugin")
    id("AndroidXComposePlugin")
    id("kotlin-multiplatform")
    id("application")
}
project.setBuildDir("build")

AndroidXComposePlugin.applyAndConfigureKotlinPlugin(project)

val resourcesDir = "$buildDir/resources"
val skikoWasm by configurations.creating

dependencies {
    skikoWasm(libs.skikoWasm)
}

val unzipTask = tasks.register("unzipWasm", Copy::class) {
    destinationDir = file(resourcesDir)
    from(skikoWasm.map { zipTree(it) })
}

repositories {
    mavenLocal()
}

kotlin {
    jvm("desktop")
    js(IR) {
        browser()
        binaries.executable()
    }
    macosX64() {
        binaries {
            executable() {
                entryPoint = "androidx.compose.mpp.demo.main"
                freeCompilerArgs += listOf(
                    "-linker-option", "-framework", "-linker-option", "Metal"
                )
                // TODO: the current release binary surprises LLVM, so disable checks for now.
                freeCompilerArgs += "-Xdisable-phases=VerifyBitcode"
            }
        }
    }
    macosArm64() {
        binaries {
            executable() {
                entryPoint = "androidx.compose.mpp.demo.main"
                freeCompilerArgs += listOf(
                    "-linker-option", "-framework", "-linker-option", "Metal"
                )
                // TODO: the current release binary surprises LLVM, so disable checks for now.
                freeCompilerArgs += "-Xdisable-phases=VerifyBitcode"
            }
        }
    }
    ios("uikit")
    iosSimulatorArm64("uikitSimulatorArm64")
    cocoapods {
        version = "0.0.0"
        summary = "Shared code for the UIKit demo"
        homepage = "https://github.com/JetBrains/compose-jb"
        ios.deploymentTarget = "14.1"
        podfile = project.file("../uikitApp/Podfile")
        framework {
            baseName = "demo"
            isStatic = true
        }
    }
    sourceSets {
        val commonMain by getting {
             dependencies {
                implementation(project(":compose:foundation:foundation"))
                implementation(project(":compose:foundation:foundation-layout"))
                implementation(project(":compose:material:material"))
                implementation(project(":compose:mpp"))
                implementation(project(":compose:runtime:runtime"))
                implementation(project(":compose:ui:ui"))
                implementation(project(":compose:ui:ui-graphics"))
                implementation(project(":compose:ui:ui-text"))
                implementation(libs.kotlinCoroutinesCore)
            }
        }

        val skikoMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.skikoCommon)
            }
        }

        val jsMain by getting {
            dependsOn(skikoMain)
            resources.setSrcDirs(resources.srcDirs)
            resources.srcDirs(unzipTask.map { it.destinationDir })
        }

        val nativeMain by creating { dependsOn(skikoMain) }
        val darwinMain by creating { dependsOn(nativeMain) }
        val macosMain by creating { dependsOn(darwinMain) }
        val macosX64Main by getting { dependsOn(macosMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val uikitMain by getting { dependsOn(darwinMain) }
        val uikitSimulatorArm64Main by getting {
            dependsOn(uikitMain)
        }
    }
}
