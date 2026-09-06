/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    java
    id("org.v31bank.integration-test")
}

description = "Resolves V31 from a repository the way a consumer does"

val testRepository = configurations.create("testRepository")

dependencies {
    for (path in listOf(
        ":platform:v31-dependencies",
        ":apis:v31-compliance-api",
        ":apis:v31-customer-api",
        ":apis:v31-ledger-api",
        ":apis:v31-risk-api",
        ":apis:v31-transfer-api",
        ":library:v31-core",
        ":module:v31-data-jpa-spring-boot",
        ":module:v31-data-valkey-spring-boot",
        ":module:v31-grpc-spring-boot",
        ":module:v31-jooq-spring-boot",
        ":module:v31-web-spring-boot",
        ":starter:v31-data-jpa-spring-boot-starter",
        ":starter:v31-data-valkey-spring-boot-starter",
        ":starter:v31-grpc-spring-boot-starter",
        ":starter:v31-jooq-spring-boot-starter",
        ":starter:v31-web-spring-boot-starter",
    )) {
        testRepository(project(mapOf("path" to path, "configuration" to "mavenRepository")))
    }

    intTestImplementation("org.junit.jupiter:junit-jupiter")
    intTestImplementation("org.assertj:assertj-core")
    intTestImplementation(gradleTestKit())
}

val syncTestRepository = tasks.register<Sync>("syncTestRepository") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Gathers the published V31 artifacts into one repository."
    from(testRepository)
    into(layout.buildDirectory.dir("test-repository"))
}

tasks.named<Test>("intTest") {
    inputs.files(syncTestRepository)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("testRepository")
    systemProperty("testRepository", layout.buildDirectory.dir("test-repository").get().asFile.absolutePath)
    systemProperty("v31Version", project.version.toString())
}
