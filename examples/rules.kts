/*
 * Copyright (C) 2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

/*******************************************************
 * Example OSS Review Toolkit (ORT) rules.kts file     *
 *                                                     *
 * Note this file only contains example how to write   *
 * rules. It's recommended you consult your own legal  *
 * when writing your own rules.                        *
 *******************************************************/

/**
 * Import license configuration from licenses.yml.
 */

fun getLicenseSet(setId: String) = licenseConfiguration.getLicensesForSet(setId).map { it.id }.toSet()

val permissiveLicenses = getLicenseSet("permissive")

val copyleftLicenses = getLicenseSet("copyleft")

val copyleftLimitedLicenses = getLicenseSet("copyleft-limited")

val publicDomainLicenses = getLicenseSet("public-domain")

// The complete set of licenses covered by policy rules.
val handledLicenses = listOf(
    permissiveLicenses,
    publicDomainLicenses,
    copyleftLicenses,
    copyleftLimitedLicenses
).flatten().let {
    it.groupBy { it }.filter { it.value.size > 1 }.let {
        require(it.isEmpty()) {
            "The classifications for the following licenses overlap: ${it.keys.joinToString()}"
        }
    }
    it.toSet()
}

/**
 * Function to return Markdown-formatted text to aid users with resolving violations.
 */

fun PackageRule.howToFixDefault() = """
        A text written in MarkDown to help users resolve policy violations
        which may link to additional resources.
    """.trimIndent()

/**
 * Set of matchers to help keep policy rules easy to understand
 */

fun PackageRule.LicenseRule.isHandled() =
    object : RuleMatcher {
        override val description = "isHandled($license)"

        override fun matches() =
            license in handledLicenses
                    && !(license.toString().contains("-exception")
                    && !license.toString().contains(" WITH "))
    }

fun PackageRule.LicenseRule.isCopyleft() =
    object : RuleMatcher {
        override val description = "isCopyleft($license)"

        override fun matches() = license in copyleftLicenses
    }

fun PackageRule.LicenseRule.isCopyleftLimited() =
    object : RuleMatcher {
        override val description = "isCopyleftLimited($license)"

        override fun matches() = license in copyleftLimitedLicenses
    }

/**
 * Example policy rules
 */

// Define the set of policy rules.
val ruleSet = ruleSet(ortResult, packageConfigurationProvider) {
    // Define a rule that is executed for each package.
    packageRule("UNHANDLED_LICENSE") {
        // Do not trigger this rule on packages that have been excluded in the .ort.yml.
        require {
            -isExcluded()
        }

        // Define a rule that is executed for each license of the package.
        licenseRule("UNHANDLED_LICENSE", LicenseView.CONCLUDED_OR_REST) {
            require {
                -isExcluded()
                -isHandled()
            }

            // Throw an error message including guidance how to fix the issue.
            error(
                "The license $license is currently not covered by policy rules. " +
                        "The license was ${licenseSource.name.toLowerCase()} in package " +
                        "${pkg.id.toCoordinates()}",
                howToFixDefault()
            )
        }
    }

    packageRule("UNMAPPED_DECLARED_LICENSE") {
        require {
            -isExcluded()
        }

        pkg.declaredLicensesProcessed.unmapped.forEach { unmappedLicense ->
            warning(
                "The declared license '$unmappedLicense' could not be mapped to a valid license or parsed as an SPDX " +
                        "expression. The license was found in package ${pkg.id.toCoordinates()}.",
                howToFixDefault()
            )
        }
    }

    packageRule("COPYLEFT_IN_SOURCE") {
        require {
            -isExcluded()
        }

        licenseRule("COPYLEFT_IN_SOURCE", LicenseView.CONCLUDED_OR_REST) {
            require {
                -isExcluded()
                +isCopyleft()
            }

            val message = if (licenseSource == LicenseSource.DETECTED) {
                "The ScanCode copyleft categorized license $license was ${licenseSource.name.toLowerCase()} " +
                        "in package ${pkg.id.toCoordinates()}."
            } else {
                "The package ${pkg.id.toCoordinates()} has the ${licenseSource.name.toLowerCase()} " +
                        " ScanCode copyleft catalogized license $license."
            }

            error(message, howToFixDefault())
        }

        licenseRule("COPYLEFT_LIMITED_IN_SOURCE", LicenseView.CONCLUDED_OR_DECLARED_OR_DETECTED) {
            require {
                -isExcluded()
                +isCopyleftLimited()
            }

            val message = if (licenseSource == LicenseSource.DETECTED) {
                if (pkg.id.type == "Unmanaged") {
                    "The ScanCode copyleft-limited categorized license $license was ${licenseSource.name.toLowerCase()} " +
                            "in package ${pkg.id.toCoordinates()}."
                } else {
                    "The ScanCode copyleft-limited categorized license $license was ${licenseSource.name.toLowerCase()} " +
                            "in package ${pkg.id.toCoordinates()}."
                }
            } else {
                "The package ${pkg.id.toCoordinates()} has the ${licenseSource.name.toLowerCase()} " +
                        " ScanCode copyleft-limited categorized license $license."
            }

            error(message, howToFixDefault())
        }
    }

    // Define a rule that is executed for each dependency of a project.
    dependencyRule("COPYLEFT_IN_DEPENDENCY") {
        licenseRule("COPYLEFT_IN_DEPENDENCY", LicenseView.CONCLUDED_OR_DECLARED_OR_DETECTED) {
            require {
                +isCopyleft()
            }

            issue(
                Severity.ERROR,
                "The project ${project.id.toCoordinates()} has a dependency licensed under the ScanCode " +
                        "copyleft categorized license $license.",
                howToFixDefault()
            )
        }
    }

    dependencyRule("COPYLEFT_LIMITED_STATIC_LINK_IN_DIRECT_DEPENDENCY") {
        require {
            +isAtTreeLevel(1)
            +isStaticallyLinked()
        }

        licenseRule("LINKED_WEAK_COPYLEFT", LicenseView.CONCLUDED_OR_DECLARED_OR_DETECTED) {
            require {
                +isCopyleftLimited()
            }

            // Use issue() instead of error() if you want to set the severity.
            issue(
                Severity.WARNING,
                "The project ${project.id.toCoordinates()} has a statically linked direct dependency licensed " +
                        "under the ScanCode copyleft-left categorized license $license.",
                howToFixDefault()
            )
        }
    }
}

// Populate the list of policy rule violations to return.
ruleViolations += ruleSet.violations
