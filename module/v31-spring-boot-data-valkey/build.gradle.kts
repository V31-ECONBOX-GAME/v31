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
    `java-library`
    id("org.v31bank.auto-configuration")
    id("org.v31bank.configuration-properties")
    id("org.v31bank.optional-dependencies")
}

description = "V31 Data Valkey auto-configuration"

dependencies {
    api(project(":library:v31-core"))
    api("org.springframework.boot:spring-boot-data-redis")
    api("org.springframework.boot:spring-boot-cache")

    optional("org.springframework.boot:spring-boot-autoconfigure")

    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")

    testRuntimeOnly("ch.qos.logback:logback-classic")
}
