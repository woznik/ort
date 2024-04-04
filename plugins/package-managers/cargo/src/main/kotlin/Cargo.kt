/*
 * Copyright (C) 2019 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

@file:Suppress("TooManyFunctions")

package org.ossreviewtoolkit.plugins.packagemanagers.cargo

import java.io.File

import net.peanuuutz.tomlkt.decodeFromNativeReader

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.parseAuthorString
import org.ossreviewtoolkit.downloader.VcsHost
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.orEmpty
import org.ossreviewtoolkit.utils.common.CommandLineTool
import org.ossreviewtoolkit.utils.common.unquote
import org.ossreviewtoolkit.utils.common.withoutPrefix
import org.ossreviewtoolkit.utils.ort.DeclaredLicenseProcessor
import org.ossreviewtoolkit.utils.spdx.SpdxConstants
import org.ossreviewtoolkit.utils.spdx.SpdxOperator

private const val DEFAULT_KIND_NAME = "normal"
private const val DEV_KIND_NAME = "dev"
private const val BUILD_KIND_NAME = "build"

/**
 * The [Cargo](https://doc.rust-lang.org/cargo/) package manager for Rust.
 */
class Cargo(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig), CommandLineTool {
    class Factory : AbstractPackageManagerFactory<Cargo>("Cargo") {
        override val globsForDefinitionFiles = listOf("Cargo.toml")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Cargo(type, analysisRoot, analyzerConfig, repoConfig)
    }

    override fun command(workingDir: File?) = "cargo"

    override fun transformVersion(output: String) =
        // The version string can be something like:
        // cargo 1.35.0 (6f3e9c367 2019-04-04)
        output.removePrefix("cargo ").substringBefore(' ')

    /**
     * Cargo.lock is located next to Cargo.toml or in one of the parent directories. The latter is the case when the
     * project is part of a workspace. Cargo.lock is then located next to the Cargo.toml file defining the workspace.
     */
    private fun resolveLockfile(metadata: CargoMetadata): File {
        val workingDir = File(metadata.workspaceRoot)
        val lockfile = workingDir.resolve("Cargo.lock")

        requireLockfile(workingDir) { lockfile.isFile }

        return lockfile
    }

    private fun readHashes(lockfile: File): Map<String, String> {
        if (!lockfile.isFile) {
            logger.debug { "Cannot determine the hashes of remote artifacts because the Cargo lockfile is missing." }
            return emptyMap()
        }

        val contents = lockfile.reader().use { toml.decodeFromNativeReader<CargoLockfile>(it) }
        return when (contents.version) {
            3 -> {
                contents.packages.mapNotNull { pkg ->
                    pkg.checksum?.let { checksum ->
                        val key = "${pkg.name} ${pkg.version} (${pkg.source})"
                        key to checksum
                    }
                }
            }

            else -> {
                contents.metadata.mapNotNull { (k, v) ->
                    k.unquote().withoutPrefix("checksum ")?.let { it to v }
                }
            }
        }.toMap()
    }

    /**
     * Check if a package is a project. All path dependencies inside the analyzer root are treated as project
     * dependencies.
     */
    private fun isProjectDependency(id: String) =
        PATH_DEPENDENCY_REGEX.matchEntire(id)?.groups?.get(1)?.let { match ->
            val packageDir = File(match.value)
            packageDir.startsWith(analysisRoot)
        } == true

    override fun resolveDependencies(definitionFile: File, labels: Map<String, String>): List<ProjectAnalyzerResult> {
        val workingDir = definitionFile.parentFile
        val metadataProcess = run(workingDir, "metadata", "--format-version=1")
        val metadata = json.decodeFromString<CargoMetadata>(metadataProcess.stdout)

        val projectId = requireNotNull(metadata.resolve.root) {
            "Virtual workspaces are not supported."
        }

        val projectNode = metadata.resolve.nodes.single { it.id == projectId }
        val depNodesByKind = mutableMapOf<String, MutableList<CargoMetadata.Node>>()
        projectNode.deps.forEach { dep ->
            val depNode = metadata.resolve.nodes.single { it.id == dep.pkg }

            dep.depKinds.forEach { depKind ->
                depNodesByKind.getOrPut(depKind.kind ?: DEFAULT_KIND_NAME) { mutableListOf() } += depNode
            }
        }

        val hashes = readHashes(resolveLockfile(metadata))
        val packages = metadata.packages.associateBy(
            { it.id },
            { parsePackage(it, hashes) }
        )

        fun Collection<CargoMetadata.Node>.toPackageReferences(): Set<PackageReference> =
            mapNotNullTo(mutableSetOf()) { node ->
                // TODO: Handle renamed dependencies here, see:
                //       https://doc.rust-lang.org/cargo/reference/specifying-dependencies.html#renaming-dependencies-in-cargotoml
                val dependencyNodes = node.deps.filter { dep ->
                    // Only normal dependencies are transitive.
                    dep.depKinds.any { it.kind == null }
                }.map { dep ->
                    metadata.resolve.nodes.single { it.id == dep.pkg }
                }

                val linkage = if (isProjectDependency(node.id)) PackageLinkage.PROJECT_STATIC else PackageLinkage.STATIC
                packages[node.id]?.toReference(
                    linkage = linkage,
                    dependencies = dependencyNodes.toPackageReferences()
                )
            }

        val scopes = setOfNotNull(
            depNodesByKind[DEFAULT_KIND_NAME]?.let { Scope("dependencies", it.toPackageReferences()) },
            depNodesByKind[DEV_KIND_NAME]?.let { Scope("dev-dependencies", it.toPackageReferences()) },
            depNodesByKind[BUILD_KIND_NAME]?.let { Scope("build-dependencies", it.toPackageReferences()) }
        )

        val projectPkg = packages.getValue(projectId).let { it.copy(id = it.id.copy(type = managerName)) }

        val project = Project(
            id = projectPkg.id,
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
            authors = projectPkg.authors,
            declaredLicenses = projectPkg.declaredLicenses,
            declaredLicensesProcessed = projectPkg.declaredLicensesProcessed,
            vcs = projectPkg.vcs,
            vcsProcessed = processProjectVcs(workingDir, projectPkg.vcs, projectPkg.homepageUrl),
            homepageUrl = projectPkg.homepageUrl,
            scopeDependencies = scopes
        )

        val nonProjectPackages = packages
            .filterNot { isProjectDependency(it.key) }
            .mapTo(mutableSetOf()) { it.value }

        return listOf(ProjectAnalyzerResult(project, nonProjectPackages))
    }
}

private val PATH_DEPENDENCY_REGEX = Regex("""^.*\(path\+file://(.*)\)$""")

private fun parseDeclaredLicenses(pkg: CargoMetadata.Package): Set<String> {
    val declaredLicenses = pkg.license.orEmpty().split('/')
        .map { it.trim() }
        .filterTo(mutableSetOf()) { it.isNotEmpty() }

    // Cargo allows declaring non-SPDX licenses only by referencing a license file. If a license file is specified, add
    // an unknown declared license to indicate that there is a declared license, but we cannot know which it is at this
    // point.
    // See: https://doc.rust-lang.org/cargo/reference/manifest.html#the-license-and-license-file-fields
    if (pkg.licenseFile.orEmpty().isNotBlank()) {
        declaredLicenses += SpdxConstants.NOASSERTION
    }

    return declaredLicenses
}

private fun parsePackage(pkg: CargoMetadata.Package, hashes: Map<String, String>): Package {
    val declaredLicenses = parseDeclaredLicenses(pkg)

    // While the previously used "/" was not explicit about the intended license operator, the community consensus
    // seems to be that an existing "/" should be interpreted as "OR", see e.g. the discussions at
    // https://github.com/rust-lang/cargo/issues/2039
    // https://github.com/rust-lang/cargo/pull/4920
    val declaredLicensesProcessed = DeclaredLicenseProcessor.process(declaredLicenses, operator = SpdxOperator.OR)

    return Package(
        id = Identifier(
            type = "Crate",
            // Note that Rust / Cargo do not support package namespaces, see:
            // https://samsieber.tech/posts/2020/09/registry-structure-influence/
            namespace = "",
            name = pkg.name,
            version = pkg.version
        ),
        authors = pkg.authors.mapNotNullTo(mutableSetOf()) { parseAuthorString(it) },
        declaredLicenses = declaredLicenses,
        declaredLicensesProcessed = declaredLicensesProcessed,
        description = pkg.description.orEmpty(),
        binaryArtifact = RemoteArtifact.EMPTY,
        sourceArtifact = parseSourceArtifact(pkg, hashes).orEmpty(),
        homepageUrl = pkg.homepage.orEmpty(),
        vcs = VcsHost.parseUrl(pkg.repository.orEmpty())
    )
}

// Match source dependencies that directly reference git repositories. The specified tag or branch
// name is ignored (i.e. not captured) in favor of the actual commit hash that they currently refer
// to.
// See https://doc.rust-lang.org/cargo/reference/specifying-dependencies.html#specifying-dependencies-from-git-repositories
// for the specification for this kind of dependency.
private val GIT_DEPENDENCY_REGEX = Regex("git\\+(https://.*)\\?(?:rev|tag|branch)=.+#([0-9a-zA-Z]+)")

private fun parseSourceArtifact(pkg: CargoMetadata.Package, hashes: Map<String, String>): RemoteArtifact? {
    val source = pkg.source ?: return null

    if (source == "registry+https://github.com/rust-lang/crates.io-index") {
        val url = "https://crates.io/api/v1/crates/${pkg.name}/${pkg.version}/download"
        val hash = Hash.create(hashes[pkg.id].orEmpty())
        return RemoteArtifact(url, hash)
    }

    val match = GIT_DEPENDENCY_REGEX.matchEntire(source) ?: return null
    val (url, hash) = match.destructured
    return RemoteArtifact(url, Hash.create(hash))
}
