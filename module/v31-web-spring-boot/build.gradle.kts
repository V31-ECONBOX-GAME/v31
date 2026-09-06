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

description = "V31 Web auto-configuration"

dependencies {
    // The envelope every endpoint answers with, so this module is what makes the
    // contract in v31-core the one a failure is reported through too.
    api(project(":library:v31-core"))

    api("org.springframework.boot:spring-boot")
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-webmvc")

    // Provided by the container at runtime, as it is for spring-webmvc itself. The
    // filter and the auto-configuration that registers it are both guarded on a
    // servlet application, so it is never reached without one.
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // Bean Validation is what turns a rejected field into a `FieldViolation`. Not
    // exported: a service that has no use for it should not be made to carry a
    // validator, and the handler that reads violations is conditional on it.
    optional("org.springframework.boot:spring-boot-starter-validation")

    // `org.springframework.dao` lives here. Optional for the same reason: a
    // service backed by something other than a database never raises these.
    optional("org.springframework:spring-tx")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
