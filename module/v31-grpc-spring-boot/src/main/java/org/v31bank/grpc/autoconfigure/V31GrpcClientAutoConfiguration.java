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

import java.time.Duration;

import io.grpc.ClientInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.interceptor.DefaultDeadlineSetupClientInterceptor;

import org.v31bank.grpc.HeaderPropagationClientInterceptor;

/**
 * {@link AutoConfiguration Auto-configuration} for the calls a service makes.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@AutoConfiguration(before = GrpcClientAutoConfiguration.class)
@ConditionalOnClass({ ClientInterceptor.class, GrpcChannelFactory.class })
@EnableConfigurationProperties(V31GrpcProperties.class)
public class V31GrpcClientAutoConfiguration {

	@Bean
	@GlobalClientInterceptor
	@ConditionalOnMissingBean
	@ConditionalOnBooleanProperty(name = "v31.grpc.client.deadline.enabled", matchIfMissing = true)
	public DefaultDeadlineSetupClientInterceptor defaultDeadlineSetupClientInterceptor(V31GrpcProperties properties) {
		Duration duration = properties.getClient().getDeadline().getDuration();
		if (duration == null || !duration.isPositive()) {
			throw new IllegalStateException("v31.grpc.client.deadline.duration must be positive, but was " + duration
					+ "; set v31.grpc.client.deadline.enabled to false to leave calls without a deadline");
		}
		return new DefaultDeadlineSetupClientInterceptor(duration);
	}

	@Bean
	@GlobalClientInterceptor
	@ConditionalOnMissingBean
	@ConditionalOnBooleanProperty(name = "v31.grpc.propagation.enabled", matchIfMissing = true)
	public HeaderPropagationClientInterceptor headerPropagationClientInterceptor() {
		return new HeaderPropagationClientInterceptor();
	}

}
