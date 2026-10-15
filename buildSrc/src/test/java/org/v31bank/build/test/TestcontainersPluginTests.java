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

package org.v31bank.build.test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import org.v31bank.build.constant.Endpoints;
import org.v31bank.build.constant.Environments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TestcontainersPlugin}.
 *
 * @author Xander Wang
 */
class TestcontainersPluginTests {

	@TempDir
	private File directory;

	@TempDir
	private File home;

	private String userHome;

	@BeforeEach
	void useTemporaryHome() {
		this.userHome = System.getProperty("user.home");
		System.setProperty("user.home", this.home.getPath());
	}

	@AfterEach
	void restoreHome() {
		System.setProperty("user.home", this.userHome);
	}

	@org.junit.jupiter.api.Test
	void pointsTestsAtTheLimaMachineWhenItsSocketIsPresent() throws IOException {
		File socket = new File(this.home, Endpoints.LIMA_DOCKER_SOCKET);
		assertThat(socket.getParentFile().mkdirs()).isTrue();
		assertThat(socket.createNewFile()).isTrue();
		assertThat(testTask(javaProject()).getEnvironment()).containsEntry(Environments.DOCKER_HOST, "unix://" + socket)
			.containsEntry(Environments.TESTCONTAINERS_HOST_OVERRIDE, Endpoints.LIMA_DOCKER_ADDRESS)
			.containsEntry(Environments.TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE, Endpoints.DOCKER_SOCKET);
	}

	@org.junit.jupiter.api.Test
	void leavesTestsAloneWithoutTheLimaSocket() {
		Map<String, Object> environment = testTask(javaProject()).getEnvironment();
		for (String variable : List.of(Environments.DOCKER_HOST, Environments.TESTCONTAINERS_HOST_OVERRIDE,
				Environments.TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE)) {
			assertThat(environment.get(variable)).isEqualTo(System.getenv(variable));
		}
	}

	private static Test testTask(Project project) {
		return (Test) project.getTasks().getByName(JavaPlugin.TEST_TASK_NAME);
	}

	private Project javaProject() {
		Project project = ProjectBuilder.builder().withProjectDir(this.directory).build();
		project.getPlugins().apply(TestcontainersPlugin.class);
		project.getPlugins().apply("java");
		return project;
	}

}
