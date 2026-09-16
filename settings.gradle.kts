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

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
	repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
	repositories {
		mavenCentral()
	}
}

rootProject.name = "v31"

@Suppress("UnstableApiUsage")
gradle.lifecycle.beforeProject {
	pluginManager.apply("org.v31bank.conventions")
}

include("apis")
include("cloud")
include("module")
include("starter")
include("library")
include("platform")
include("processor")
include("integration-test")
include("smoke-test")

include("library:v31-core")

include("apis:v31-customer-api")
include("apis:v31-transfer-api")
include("apis:v31-ledger-api")
include("apis:v31-risk-api")
include("apis:v31-compliance-api")

include("cloud:v31-customer-service")
include("cloud:v31-transfer-service")
include("cloud:v31-ledger-service")
include("cloud:v31-risk-service")
include("cloud:v31-compliance-service")
include("cloud:v31-cbs-service")

include("platform:v31-dependencies")
include("platform:v31-internal-dependencies")

include("starter:v31-spring-boot-starter")
include("starter:v31-spring-boot-starter-data-jpa")
include("module:v31-spring-boot-data-jpa")
include("starter:v31-spring-boot-starter-jooq")
include("module:v31-spring-boot-jooq")
include("starter:v31-spring-boot-starter-data-valkey")
include("module:v31-spring-boot-data-valkey")
include("starter:v31-spring-boot-starter-grpc")
include("module:v31-spring-boot-grpc")
include("starter:v31-spring-boot-starter-web")
include("module:v31-spring-boot-web")

include("smoke-test:v31-grpc-smoke-test")
include("smoke-test:v31-web-smoke-test")
include("smoke-test:v31-data-jpa-smoke-test")
include("smoke-test:v31-jooq-smoke-test")
include("smoke-test:v31-data-valkey-smoke-test")

include("integration-test:v31-bom-integration-test")
