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

package org.v31bank.jooq.autoconfigure;

import java.util.Arrays;
import java.util.Optional;

import org.jooq.RecordListener;
import org.jooq.RecordListenerProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.v31bank.core.AuditorSupplier;
import org.v31bank.jooq.AuditRecordListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link V31JooqAutoConfiguration}.
 *
 * @author Xander Wang
 */
class V31JooqAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(V31JooqAutoConfiguration.class));

	@Test
	void registersTheListenerWithoutAnAuditor() {
		this.runner.run((context) -> {
			assertThat(context).hasSingleBean(AuditRecordListener.class);
			assertThat(context).doesNotHaveBean(AuditorSupplier.class);
		});
	}

	@Test
	void takesTheAuditorTheApplicationSupplies() {
		this.runner.withUserConfiguration(CustomAuditorConfiguration.class).run((context) -> {
			assertThat(context).hasSingleBean(AuditorSupplier.class);
			assertThat(context.getBean(AuditorSupplier.class).currentAuditor()).contains("xander");
		});
	}

	@Test
	void attachesTheListenerToTheJooqConfiguration() {
		this.runner.run((context) -> {
			DefaultConfiguration configuration = new DefaultConfiguration();
			context.getBean(DefaultConfigurationCustomizer.class).customize(configuration);
			assertThat(providedListeners(configuration)).containsExactly(context.getBean(AuditRecordListener.class));
		});
	}

	@Test
	void keepsListenersRegisteredByTheApplication() {
		RecordListener existing = RecordListener.onInsertStart((ctx) -> {
		});
		this.runner.run((context) -> {
			DefaultConfiguration configuration = new DefaultConfiguration();
			configuration.set(existing);
			context.getBean(DefaultConfigurationCustomizer.class).customize(configuration);
			assertThat(providedListeners(configuration)).containsExactly(existing,
					context.getBean(AuditRecordListener.class));
		});
	}

	private static Object[] providedListeners(DefaultConfiguration configuration) {
		return Arrays.stream(configuration.recordListenerProviders()).map(RecordListenerProvider::provide).toArray();
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomAuditorConfiguration {

		@Bean
		AuditorSupplier auditorSupplier() {
			return () -> Optional.of("xander");
		}

	}

}
