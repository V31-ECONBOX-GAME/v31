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

import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.v31bank.bom")
    id("org.v31bank.deployed")
}

description = "V31 Dependencies"

bom {
    imports(SpringBootPlugin.BOM_COORDINATES)
}

dependencies {
    constraints {
        api("com.google.guava:guava:33.6.0-jre")

        api(project(":apis:v31-compliance-api"))
        api(project(":apis:v31-customer-api"))
        api(project(":apis:v31-ledger-api"))
        api(project(":apis:v31-risk-api"))
        api(project(":apis:v31-transfer-api"))

        api(project(":library:v31-core"))

        api(project(":module:v31-data-jpa-spring-boot"))
        api(project(":module:v31-data-valkey-spring-boot"))
        api(project(":module:v31-grpc-spring-boot"))
        api(project(":module:v31-jooq-spring-boot"))
        api(project(":module:v31-web-spring-boot"))

        api(project(":starter:v31-data-jpa-spring-boot-starter"))
        api(project(":starter:v31-data-valkey-spring-boot-starter"))
        api(project(":starter:v31-grpc-spring-boot-starter"))
        api(project(":starter:v31-jooq-spring-boot-starter"))
        api(project(":starter:v31-web-spring-boot-starter"))
    }
}
