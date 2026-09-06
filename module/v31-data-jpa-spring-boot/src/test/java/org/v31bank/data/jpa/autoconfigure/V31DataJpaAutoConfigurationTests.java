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

package org.v31bank.data.jpa.autoconfigure;

import java.util.Optional;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.v31bank.core.AuditorSupplier;
import org.v31bank.data.jpa.Audited;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link V31DataJpaAutoConfiguration}.
 *
 * @author Xander Wang
 */
@Testcontainers
class V31DataJpaAutoConfigurationTests {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
				V31DataJpaAutoConfiguration.class))
		.withUserConfiguration(PersistenceConfiguration.class)
		.withPropertyValues("spring.datasource.url=" + POSTGRES.getJdbcUrl(),
				"spring.datasource.username=" + POSTGRES.getUsername(),
				"spring.datasource.password=" + POSTGRES.getPassword(), "spring.jpa.hibernate.ddl-auto=create-drop");

	@Test
	void registersAnAuditorThatAnswersNothingUntilOneIsSupplied() {
		this.runner.run((context) -> {
			assertThat(context).hasSingleBean(AuditorAware.class);
			assertThat(auditor(context.getBean(AuditorAware.class))).isEmpty();
		});
	}

	@Test
	void stampsARowWithoutTheApplicationSettingAnything() {
		this.runner.run((context) -> {
			AuditedRecord saved = save(context.getBean(PlatformTransactionManager.class),
					context.getBean(EntityManagerFactory.class));
			assertThat(saved.getCreatedBy()).isNull();
			assertThat(saved.getLastModifiedBy()).isNull();
			assertThat(saved.getCreatedDate()).isNotNull();
			assertThat(saved.getLastModifiedDate()).isNotNull();
			assertThat(saved.getId()).isNotNull();
		});
	}

	@Test
	void takesTheAuditorSuppliedByTheApplication() {
		this.runner.withUserConfiguration(SuppliedAuditorConfiguration.class)
			.run((context) -> assertThat(auditor(context.getBean(AuditorAware.class))).contains("grace"));
	}

	@Test
	void backsOffFromAnApplicationSuppliedAuditor() {
		this.runner.withUserConfiguration(CustomAuditorConfiguration.class).run((context) -> {
			assertThat(context).hasSingleBean(AuditorAware.class);
			assertThat(auditor(context.getBean(AuditorAware.class))).contains("ada");
		});
	}

	private static AuditedRecord save(PlatformTransactionManager transactionManager,
			EntityManagerFactory entityManagerFactory) {
		EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
		return new TransactionTemplate(transactionManager).execute((_) -> {
			AuditedRecord record = new AuditedRecord();
			entityManager.persist(record);
			entityManager.flush();
			return record;
		});
	}

	@SuppressWarnings("unchecked")
	private static Optional<String> auditor(AuditorAware<?> auditorAware) {
		return ((AuditorAware<String>) auditorAware).getCurrentAuditor();
	}

	@Configuration
	@EntityScan(basePackageClasses = V31DataJpaAutoConfigurationTests.class)
	static class PersistenceConfiguration {

	}

	@Configuration
	static class SuppliedAuditorConfiguration {

		@Bean
		AuditorSupplier auditorSupplier() {
			return () -> Optional.of("grace");
		}

	}

	@Configuration
	static class CustomAuditorConfiguration {

		@Bean
		AuditorAware<String> auditorAware() {
			return () -> Optional.of("ada");
		}

	}

	@Entity
	@Table(name = "audited_record")
	static class AuditedRecord extends Audited {

	}

}
