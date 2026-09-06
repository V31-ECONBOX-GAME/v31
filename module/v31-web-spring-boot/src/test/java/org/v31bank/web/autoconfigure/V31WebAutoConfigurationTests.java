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

package org.v31bank.web.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.v31bank.web.DataAccessExceptionHandler;
import org.v31bank.web.HttpResponseExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link V31WebAutoConfiguration}.
 *
 * @author Xander Wang
 */
class V31WebAutoConfigurationTests {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(V31WebAutoConfiguration.class));

	@Test
	void registersBothHandlers() {
		this.runner.run((context) -> {
			assertThat(context).hasSingleBean(HttpResponseExceptionHandler.class);
			assertThat(context).hasSingleBean(DataAccessExceptionHandler.class);
		});
	}

	@Test
	void proxiesTheNestedConfiguration() {
		this.runner.run((context) -> assertThat(
				context.getBean(V31WebAutoConfiguration.DataAccessExceptionHandlerConfiguration.class))
			.as("dropping @Configuration leaves the class registered but unproxied, which nothing else here notices")
			.isNotExactlyInstanceOf(V31WebAutoConfiguration.DataAccessExceptionHandlerConfiguration.class));
	}

	@Test
	void backsOffFromAnApplicationSuppliedHandler() {
		this.runner.withUserConfiguration(CustomHandlerConfiguration.class).run((context) -> {
			assertThat(context).hasSingleBean(HttpResponseExceptionHandler.class);
			assertThat(context.getBean(HttpResponseExceptionHandler.class)).isInstanceOf(CustomHandler.class);
		});
	}

	@Test
	void addsNothingOutsideAWebApplication() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(V31WebAutoConfiguration.class))
			.run((context) -> assertThat(context).doesNotHaveBean(HttpResponseExceptionHandler.class));
	}

	@Configuration
	static class CustomHandlerConfiguration {

		@Bean
		HttpResponseExceptionHandler apiResponseExceptionHandler() {
			return new CustomHandler();
		}

	}

	static class CustomHandler extends HttpResponseExceptionHandler {

	}

}
