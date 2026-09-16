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

import io.grpc.ServerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;

import org.v31bank.grpc.HeaderPropagationServerInterceptor;
import org.v31bank.grpc.RefusalGrpcExceptionHandler;
import org.v31bank.grpc.UnexpectedExceptionGrpcExceptionHandler;

/**
 * {@link AutoConfiguration Auto-configuration} for the calls a service serves.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@AutoConfiguration(before = GrpcServerAutoConfiguration.class)
@ConditionalOnClass({ ServerInterceptor.class, GrpcExceptionHandler.class })
public class V31GrpcServerAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBooleanProperty(name = "v31.grpc.server.exception-handling.enabled", matchIfMissing = true)
	public RefusalGrpcExceptionHandler refusalGrpcExceptionHandler() {
		return new RefusalGrpcExceptionHandler();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBooleanProperty(name = "v31.grpc.server.exception-handling.enabled", matchIfMissing = true)
	public UnexpectedExceptionGrpcExceptionHandler unexpectedExceptionGrpcExceptionHandler() {
		return new UnexpectedExceptionGrpcExceptionHandler();
	}

	@Bean
	@GlobalServerInterceptor
	@ConditionalOnMissingBean
	@ConditionalOnBooleanProperty(name = "v31.grpc.propagation.enabled", matchIfMissing = true)
	public HeaderPropagationServerInterceptor headerPropagationServerInterceptor() {
		return new HeaderPropagationServerInterceptor();
	}

}
