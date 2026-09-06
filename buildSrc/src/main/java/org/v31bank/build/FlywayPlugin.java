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

import java.util.List;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.springframework.boot.gradle.plugin.SpringBootPlugin;

import org.v31bank.build.constant.Coordinates;
import org.v31bank.build.constant.Locations;
import org.v31bank.build.constant.Tasks;
import org.v31bank.build.util.SourceSets;

/**
 * Checks a service's Flyway migration names.
 *
 * @author Xander Wang
 */
public class FlywayPlugin implements Plugin<Project> {

	private static final String MIGRATIONS = Locations.MIGRATIONS_DIRECTORY + "/**/*.sql";

	private static final List<String> RUNTIME = List.of(Coordinates.FLYWAY, Coordinates.FLYWAY_POSTGRESQL,
			Coordinates.POSTGRESQL);

	@Override
	public void apply(Project project) {
		project.getPluginManager().apply(JavaPlugin.class);
		project.getPlugins().withType(SpringBootPlugin.class, (_) -> {
			addRuntimeDependencies(project);
			runAsPartOfCheck(project, registerValidateMigrationNames(project));
		});
	}

	private void addRuntimeDependencies(Project project) {
		RUNTIME.forEach(
				(coordinate) -> project.getDependencies().add(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME, coordinate));
	}

	private TaskProvider<ValidateMigrationNames> registerValidateMigrationNames(Project project) {
		return project.getTasks().register(Tasks.VALIDATE_MIGRATION_NAMES, ValidateMigrationNames.class, (task) -> {
			task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
			task.setDescription("Checks migration file names against db/migration/README.md.");
			task.getMigrations().from(migrations(project));
			task.getReport().set(project.getLayout().getBuildDirectory().file("reports/migration-names.txt"));
		});
	}

	private FileTree migrations(Project project) {
		return SourceSets.of(project).main().resources().unwrap().matching((sql) -> sql.include(MIGRATIONS));
	}

	private void runAsPartOfCheck(Project project, TaskProvider<ValidateMigrationNames> check) {
		project.getTasks()
			.named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME)
			.configure((processResources) -> processResources.dependsOn(check));
		project.getTasks()
			.named(LifecycleBasePlugin.CHECK_TASK_NAME)
			.configure((lifecycle) -> lifecycle.dependsOn(check));
	}

}
