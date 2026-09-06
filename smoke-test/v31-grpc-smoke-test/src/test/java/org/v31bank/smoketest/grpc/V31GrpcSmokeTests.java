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

package org.v31bank.smoketest.grpc;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import org.v31bank.grpc.HeaderPropagationClientInterceptor;
import org.v31bank.grpc.HeaderPropagationServerInterceptor;
import org.v31bank.grpc.RefusalGrpcExceptionHandler;
import org.v31bank.grpc.UnexpectedExceptionGrpcExceptionHandler;
import org.v31bank.grpc.autoconfigure.V31GrpcProperties;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class V31GrpcSmokeTests {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private V31GrpcProperties properties;

	@Test
	void theStarterAloneContributesTheServerBeans() {
		assertThat(this.context.getBeansOfType(RefusalGrpcExceptionHandler.class)).hasSize(1);
		assertThat(this.context.getBeansOfType(UnexpectedExceptionGrpcExceptionHandler.class)).hasSize(1);
		assertThat(this.context.getBeansOfType(HeaderPropagationServerInterceptor.class)).hasSize(1);
	}

	@Test
	void theStarterAloneContributesTheClientBeans() {
		assertThat(this.context.getBeansOfType(HeaderPropagationClientInterceptor.class)).hasSize(1);
	}

	@Test
	void applicationPropertiesAreBound() {
		assertThat(this.properties.getClient().getDeadline().getDuration()).isEqualTo(Duration.ofSeconds(3));
	}

}
