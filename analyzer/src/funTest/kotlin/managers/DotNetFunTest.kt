/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.analyzer.managers

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.containExactlyInAnyOrder
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe

import org.ossreviewtoolkit.analyzer.managers.utils.NuGetDependency
import org.ossreviewtoolkit.analyzer.managers.utils.NuGetSupport.Companion.OPTION_DIRECT_DEPENDENCIES_ONLY
import org.ossreviewtoolkit.utils.test.getAssetFile
import org.ossreviewtoolkit.utils.test.patchActualResult
import org.ossreviewtoolkit.utils.test.patchExpectedResult
import org.ossreviewtoolkit.utils.test.toYaml

class DotNetFunTest : StringSpec({
    "Definition file is correctly read" {
        val definitionFile = getAssetFile("projects/synthetic/dotnet/subProjectTest/test.csproj")
        val reader = DotNetPackageFileReader()
        val result = reader.getDependencies(definitionFile)

        result should containExactlyInAnyOrder(
            NuGetDependency(name = "System.Globalization", version = "4.3.0", targetFramework = "netcoreapp3.1"),
            NuGetDependency(name = "System.Threading", version = "4.0.11", targetFramework = "netcoreapp3.1"),
            NuGetDependency(
                name = "System.Threading.Tasks.Extensions",
                version = "4.5.4",
                targetFramework = "net45"
            ),
            NuGetDependency(
                name = "WebGrease",
                version = "1.5.2",
                targetFramework = "netcoreapp3.1",
                developmentDependency = true
            ),
            NuGetDependency(name = "foobar", version = "1.2.3", targetFramework = "netcoreapp3.1")
        )
    }

    "Project dependencies are detected correctly" {
        val definitionFile = getAssetFile("projects/synthetic/dotnet/subProjectTest/test.csproj")
        val expectedResultFile = getAssetFile("projects/synthetic/dotnet-expected-output.yml")

        val result = create("DotNet").resolveSingleProject(definitionFile)

        patchActualResult(result.toYaml()) shouldBe patchExpectedResult(expectedResultFile, definitionFile)
    }

    "Direct project dependencies are detected correctly" {
        val definitionFile = getAssetFile("projects/synthetic/dotnet/subProjectTest/test.csproj")
        val expectedResultFile = getAssetFile(
            "projects/synthetic/dotnet-direct-dependencies-only-expected-output.yml"
        )

        val result = create("DotNet", OPTION_DIRECT_DEPENDENCIES_ONLY to "true").resolveSingleProject(definitionFile)

        patchActualResult(result.toYaml()) shouldBe patchExpectedResult(expectedResultFile, definitionFile)
    }

    "Project metadata is correctly extracted from a .nuspec file" {
        val definitionFile = getAssetFile("projects/synthetic/dotnet/subProjectTestWithNuspec/test.csproj")
        val expectedResultFile = getAssetFile("projects/synthetic/dotnet-expected-output-with-nuspec.yml")

        val result = create("DotNet").resolveSingleProject(definitionFile)

        patchActualResult(result.toYaml()) shouldBe patchExpectedResult(expectedResultFile, definitionFile)
    }
})
