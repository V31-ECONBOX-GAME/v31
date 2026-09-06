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

package org.v31bank.build;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.v31bank.build.task.TaskDependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for {@link FlywayPlugin}.
 *
 * @author Xander Wang
 */
class FlywayPluginTests {

	private static final String TASK_NAME = "validateMigrationNames";

	private static final String MIGRATION = "V20260730093000__customer_category_create.sql";

	@TempDir
	private File directory;

	@Test
	void leavesAProjectThatIsNotASpringBootApplicationAlone() {
		givenAMigration();
		Project project = javaProject();
		assertThat(project.getTasks().findByName(TASK_NAME)).isNull();
		assertThat(runtimeOnly(project)).isEmpty();
	}

	@Test
	void registersTheCheckOnASpringBootApplication() {
		assertThat(springBootProject().getTasks().findByName(TASK_NAME)).isNotNull();
	}

	@Test
	void presentsTheCheckAsAVerificationTask() {
		Task check = springBootProject().getTasks().getByName(TASK_NAME);
		assertThat(check.getGroup()).isEqualTo(LifecycleBasePlugin.VERIFICATION_GROUP);
		assertThat(check.getDescription()).isEqualTo("Checks migration file names against db/migration/README.md.");
	}

	@Test
	void checksTheMigrationsTheProjectShips() {
		givenAMigration();
		assertThat(migrationsOf(springBootProject())).singleElement()
			.satisfies((file) -> assertThat(file).hasName(MIGRATION));
	}

	@Test
	void looksInsideTheYearDirectories() {
		givenAMigration();
		givenResource("src/main/resources/db/migration/2027/V20270102030405__account_create.sql");
		assertThat(migrationsOf(springBootProject())).hasSize(2);
	}

	@Test
	void ignoresWhateverElseSitsBesideTheMigrations() {
		givenAMigration();
		givenResource("src/main/resources/db/migration/README.md");
		assertThat(migrationsOf(springBootProject())).singleElement()
			.satisfies((file) -> assertThat(file).hasName(MIGRATION));
	}

	@Test
	void ignoresSqlThatIsNotAMigration() {
		givenResource("src/main/resources/sql/report.sql");
		assertThat(migrationsOf(springBootProject())).isEmpty();
	}

	@Test
	void followsTheResourcesWhereverTheProjectPutsThem() {
		Project project = springBootProject();
		mainSourceSet(project).getResources().srcDir("src/main/sql");
		givenResource("src/main/sql/db/migration/2026/" + MIGRATION);
		assertThat(migrationsOf(project)).singleElement().satisfies((file) -> assertThat(file).hasName(MIGRATION));
	}

	@Test
	void runsBeforeTheMigrationsAreCopiedIntoTheJar() {
		Task processResources = springBootProject().getTasks().getByName(JavaPlugin.PROCESS_RESOURCES_TASK_NAME);
		assertThat(TaskDependencies.namesOf(processResources.getDependsOn())).contains(TASK_NAME);
	}

	@Test
	void runsAsPartOfCheck() {
		Task check = springBootProject().getTasks().getByName(LifecycleBasePlugin.CHECK_TASK_NAME);
		assertThat(TaskDependencies.namesOf(check.getDependsOn())).contains(TASK_NAME);
	}

	@Test
	void writesItsReportIntoTheBuildDirectory() {
		Project project = springBootProject();
		ValidateMigrationNames check = (ValidateMigrationNames) project.getTasks().getByName(TASK_NAME);
		Path report = check.getReport().get().getAsFile().toPath();
		assertThat(project.getProjectDir().toPath().relativize(report))
			.isEqualTo(Path.of("build", "reports", "migration-names.txt"));
	}

	@Test
	void givesEveryServiceWhatItTakesToApplyMigrations() {
		assertThat(runtimeOnly(springBootProject())).extracting(Dependency::getGroup, Dependency::getName)
			.containsExactlyInAnyOrder(tuple("org.springframework.boot", "spring-boot-flyway"),
					tuple("org.flywaydb", "flyway-database-postgresql"), tuple("org.postgresql", "postgresql"));
	}

	private void givenAMigration() {
		givenResource("src/main/resources/db/migration/2026/" + MIGRATION);
	}

	private void givenResource(String path) {
		Path file = this.directory.toPath().resolve(path);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, "select 1;");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static List<File> migrationsOf(Project project) {
		ValidateMigrationNames check = (ValidateMigrationNames) project.getTasks().getByName(TASK_NAME);
		return List.copyOf(check.getMigrations().getFiles());
	}

	private static List<Dependency> runtimeOnly(Project project) {
		return List.copyOf(
				project.getConfigurations().getByName(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME).getDependencies());
	}

	private static SourceSet mainSourceSet(Project project) {
		return project.getExtensions()
			.getByType(JavaPluginExtension.class)
			.getSourceSets()
			.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
	}

	private Project javaProject() {
		Project project = ProjectBuilder.builder().withProjectDir(this.directory).build();
		new FlywayPlugin().apply(project);
		project.getPlugins().apply("java");
		return project;
	}

	private Project springBootProject() {
		Project project = javaProject();
		project.getPlugins().apply("org.springframework.boot");
		return project;
	}

}
