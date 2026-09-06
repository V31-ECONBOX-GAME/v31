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

package org.v31bank.bom;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolves V31 as a consumer does.
 *
 * @author Xander Wang
 */
class BomIntegrationTests {

	private static final List<String> ARTIFACTS = List.of("v31-compliance-api", "v31-customer-api", "v31-ledger-api",
			"v31-risk-api", "v31-transfer-api", "v31-core", "v31-data-jpa-spring-boot", "v31-data-valkey-spring-boot",
			"v31-grpc-spring-boot", "v31-jooq-spring-boot", "v31-web-spring-boot", "v31-data-jpa-spring-boot-starter",
			"v31-data-valkey-spring-boot-starter", "v31-grpc-spring-boot-starter", "v31-jooq-spring-boot-starter",
			"v31-web-spring-boot-starter");

	@TempDir
	private Path consumer;

	@Test
	void resolvesEveryArtifactTheBomNamesWithoutAVersion() throws IOException {
		BuildResult result = new ConsumerBuild(this.consumer).resolve(ARTIFACTS);
		assertThat(result.getOutput()).contains("RESOLVED");
		for (String artifact : ARTIFACTS) {
			assertThat(result.getOutput()).as(artifact).contains(artifact + "-" + ConsumerBuild.VERSION + ".jar");
		}
	}

	@Test
	void resolvesTheThirdPartyLibrariesTheArtifactsNeed() throws IOException {
		BuildResult result = new ConsumerBuild(this.consumer).resolve(List.of("v31-data-jpa-spring-boot"));
		assertThat(result.getOutput()).contains("spring-boot-", "spring-data-jpa-", "hibernate-core-");
	}

	@Test
	void needsNothingThatIsNotPublished() throws IOException {
		BuildResult result = new ConsumerBuild(this.consumer).resolve(ARTIFACTS);
		assertThat(result.getOutput()).doesNotContain("v31-internal-dependencies");
	}

}
