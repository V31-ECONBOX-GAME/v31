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
    id("org.v31bank.optional-dependencies")
}

description = "V31 Web auto-configuration"

dependencies {
    api(project(":library:v31-core"))
    api("org.springframework.boot:spring-boot-webmvc")

    optional("org.springframework.boot:spring-boot-autoconfigure")
    optional("org.springframework:spring-tx")

    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.hamcrest:hamcrest")
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation("jakarta.validation:jakarta.validation-api")
    testImplementation("jakarta.servlet:jakarta.servlet-api")

    testRuntimeOnly("org.hibernate.validator:hibernate-validator")
    testRuntimeOnly("org.apache.tomcat.embed:tomcat-embed-el")
    testRuntimeOnly("com.jayway.jsonpath:json-path")
    testRuntimeOnly("tools.jackson.core:jackson-databind")
}
