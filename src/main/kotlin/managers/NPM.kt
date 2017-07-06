package com.here.provenanceanalyzer.managers

import com.github.salomonbrys.kotson.contains
import com.github.salomonbrys.kotson.forEach
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.string

import com.google.gson.Gson
import com.google.gson.JsonObject

import com.here.provenanceanalyzer.model.Dependency
import com.here.provenanceanalyzer.Main
import com.here.provenanceanalyzer.OS
import com.here.provenanceanalyzer.PackageManager
import com.here.provenanceanalyzer.ProcessCapture
import com.here.provenanceanalyzer.log
import com.here.provenanceanalyzer.parseJsonProcessOutput

import java.io.File
import java.io.IOException

import kotlin.system.measureTimeMillis

object NPM : PackageManager(
        "https://www.npmjs.com/",
        "JavaScript",
        listOf("package.json")
) {
    val npm: String
    val yarn: String

    init {
        if (OS.isWindows) {
            npm = "npm.cmd"
            yarn = "yarn.cmd"
        } else {
            npm = "npm"
            yarn = "yarn"
        }
    }

    override fun resolveDependencies(definitionFiles: List<File>): Map<File, Dependency> {
        val result = mutableMapOf<File, Dependency>()

        definitionFiles.forEach { definitionFile ->
            val parent = definitionFile.parentFile

            println("Resolving ${javaClass.simpleName} dependencies in '${parent.name}'...")

            val modulesDir = File(parent, "node_modules")
            if (modulesDir.isDirectory) {
                throw IllegalArgumentException("'$modulesDir' directory already exists.")
            }

            val elapsed = measureTimeMillis {
                // Actually installing the dependencies is the easiest way to get the meta-data of all transitive
                // dependencies (i.e. their respective "package.json" files). As npm (and yarn) use a global cache,
                // the same dependency is only ever downloaded once.
                val isYarn = File(parent, "yarn.lock").isFile
                result[definitionFile] = installDependencies(parent, if (isYarn) yarn else npm)
            }

            println("Resolving ${javaClass.simpleName} dependencies in '${parent.name}' took ${elapsed / 1000}s.")
        }

        return result
    }

    /**
     * Install dependencies using the given package manager command. Parse dependencies afterwards to return a
     * dependency tree.
     */
    fun installDependencies(workingDir: File, managerCommand: String): Dependency {
        if (Main.debug) {
            log.debug("Using '$managerCommand' to install ${javaClass.simpleName} dependencies.")
        }

        // Install all NPM dependencies to enable NPM to list dependencies.
        val install = ProcessCapture(workingDir, managerCommand, "install")
        if (install.exitValue() != 0) {
            throw IOException(
                    "'${install.commandLine}' failed with exit code ${install.exitValue()}:\n${install.stderr()}")
        }

        return parseInstalledDependencies(workingDir)
    }

    private fun parseInstalledDependencies(workingDir: File): Dependency {
        val modulesDir = File(workingDir, "node_modules")

        // List only production dependencies.
        if (Main.debug) {
            log.debug("Using '$npm' to list production dependencies.")
        }

        val prodJson = parseJsonProcessOutput(workingDir, npm, "list", "--json", "--only=prod") as JsonObject
        val prodDependencies = if (prodJson.contains("dependencies")) {
            parseNodeModules(modulesDir, prodJson["dependencies"].obj, "production")
        } else {
            listOf()
        }

        // List only dev dependencies.
        if (Main.debug) {
            log.debug("Using '$npm' to list development dependencies.")
        }

        val devJson = parseJsonProcessOutput(workingDir, npm, "list", "--json", "--only=dev") as JsonObject
        val devDependencies = if (devJson.contains("dependencies")) {
            parseNodeModules(modulesDir, devJson["dependencies"].obj, "development")
        } else {
            listOf()
        }

        // Delete node_modules folder to not pollute the scan.
        modulesDir.deleteRecursively()

        val artifact = prodJson["name"].string
        val version = prodJson["version"].string

        return Dependency(artifact = artifact, version = version, dependencies = prodDependencies + devDependencies,
                scope = "production")
    }

    private fun parseNodeModules(modulesDir: File, json: JsonObject, scope: String): List<Dependency> {
        val result = mutableListOf<Dependency>()
        json.forEach { key, jsonElement ->
            val version = jsonElement["version"].string
            val dependencies = if (jsonElement.obj.contains("dependencies")) {
                parseNodeModules(modulesDir, jsonElement["dependencies"].obj, scope)
            } else {
                listOf()
            }

            var scm: String? = null
            val packageFile = File(modulesDir, "$key/package.json")
            if (packageFile.isFile) {
                val packageJson = Gson().fromJson<JsonObject>(packageFile.readText())
                if (packageJson.contains("repository")) {
                    val repository = packageJson["repository"]

                    // For some packages "yarn install" generates a non-conforming shortcut repository entry like this:
                    // "repository": "git://..."
                    // For details about the correct format see: https://docs.npmjs.com/files/package.json#repository
                    scm = if (repository.isJsonObject) repository["url"].string else repository.string
                }
            }

            val dependency = Dependency(artifact = key, version = version, dependencies = dependencies, scope = scope,
                    scm = scm)
            result.add(dependency)
        }
        return result
    }
}
