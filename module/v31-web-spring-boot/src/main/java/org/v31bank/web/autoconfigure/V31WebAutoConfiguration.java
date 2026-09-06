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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.web.servlet.DispatcherServlet;

import org.v31bank.web.DataAccessExceptionHandler;
import org.v31bank.web.HttpResponseExceptionHandler;

/**
 * {@link AutoConfiguration Auto-configuration} for V31 web support.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
public class V31WebAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public HttpResponseExceptionHandler apiResponseExceptionHandler() {
		return new HttpResponseExceptionHandler();
	}

	@Configuration
	@ConditionalOnClass(DataAccessException.class)
	public static class DataAccessExceptionHandlerConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public DataAccessExceptionHandler dataAccessExceptionHandler() {
			return new DataAccessExceptionHandler();
		}

	}

}
