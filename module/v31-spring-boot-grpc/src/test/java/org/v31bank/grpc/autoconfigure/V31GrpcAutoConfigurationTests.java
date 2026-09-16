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

package org.v31bank.grpc.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.grpc.client.interceptor.DefaultDeadlineSetupClientInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;

import org.v31bank.grpc.HeaderPropagationClientInterceptor;
import org.v31bank.grpc.HeaderPropagationFilter;
import org.v31bank.grpc.HeaderPropagationServerInterceptor;
import org.v31bank.grpc.RefusalGrpcExceptionHandler;
import org.v31bank.grpc.UnexpectedExceptionGrpcExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link V31GrpcServerAutoConfiguration},
 * {@link V31GrpcClientAutoConfiguration} and {@link V31GrpcWebAutoConfiguration}.
 *
 * @author Xander Wang
 */
class V31GrpcAutoConfigurationTests {

	private final ApplicationContextRunner server = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(V31GrpcServerAutoConfiguration.class));

	private final ApplicationContextRunner client = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(V31GrpcClientAutoConfiguration.class));

	private final WebApplicationContextRunner web = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(V31GrpcWebAutoConfiguration.class));

	@Test
	void contributesTheExceptionHandlersAndThePropagationInterceptor() {
		this.server.run((context) -> {
			assertThat(context).hasSingleBean(RefusalGrpcExceptionHandler.class);
			assertThat(context).hasSingleBean(UnexpectedExceptionGrpcExceptionHandler.class);
			assertThat(context).hasSingleBean(HeaderPropagationServerInterceptor.class);
			assertThat(context.getBeansOfType(GrpcExceptionHandler.class)).hasSize(2);
		});
	}

	@Test
	void leavesExceptionsAloneWhenAskedTo() {
		this.server.withPropertyValues("v31.grpc.server.exception-handling.enabled=false").run((context) -> {
			assertThat(context).doesNotHaveBean(GrpcExceptionHandler.class);
			assertThat(context).hasSingleBean(HeaderPropagationServerInterceptor.class);
		});
	}

	@Test
	void carriesNothingWhenPropagationIsTurnedOff() {
		this.server.withPropertyValues("v31.grpc.propagation.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(HeaderPropagationServerInterceptor.class));
		this.client.withPropertyValues("v31.grpc.propagation.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(HeaderPropagationClientInterceptor.class));
		this.web.withPropertyValues("v31.grpc.propagation.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(HeaderPropagationFilter.class));
	}

	@Test
	void givesEveryCallADeadlineAndCarriesTheContextOnward() {
		this.client.run((context) -> {
			assertThat(context).hasSingleBean(DefaultDeadlineSetupClientInterceptor.class);
			assertThat(context).hasSingleBean(HeaderPropagationClientInterceptor.class);
		});
	}

	@Test
	void readsTheContextOffTheIncomingHttpRequest() {
		this.web.run((context) -> assertThat(context).hasSingleBean(HeaderPropagationFilter.class));
	}

	@Test
	void addsNoHttpFilterToAServiceWithNoHttpEntryPoint() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(V31GrpcWebAutoConfiguration.class))
			.run((context) -> assertThat(context).doesNotHaveBean(HeaderPropagationFilter.class));
	}

	@Test
	void leavesCallsUnboundedOnlyWhenAskedExplicitly() {
		this.client.withPropertyValues("v31.grpc.client.deadline.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(DefaultDeadlineSetupClientInterceptor.class));
	}

	@Test
	void refusesToStartOnADeadlineThatWouldExpireEveryCall() {
		this.client.withPropertyValues("v31.grpc.client.deadline.duration=0s").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("v31.grpc.client.deadline.duration must be positive");
		});
	}

	@Test
	void appliesTheConfiguredDeadline() {
		this.client.withPropertyValues("v31.grpc.client.deadline.duration=250ms")
			.run((context) -> assertThat(context).hasSingleBean(DefaultDeadlineSetupClientInterceptor.class));
	}

}
