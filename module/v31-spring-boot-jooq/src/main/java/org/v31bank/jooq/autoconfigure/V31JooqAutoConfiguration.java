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

import java.time.Clock;
import java.util.Optional;

import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer;
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration;
import org.springframework.context.annotation.Bean;

import org.v31bank.core.AuditorSupplier;
import org.v31bank.jooq.AuditRecordListener;

/**
 * {@link AutoConfiguration Auto-configuration} for V31 jOOQ.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@AutoConfiguration(before = JooqAutoConfiguration.class)
@ConditionalOnClass({ DSLContext.class, DefaultConfigurationCustomizer.class })
public class V31JooqAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AuditRecordListener auditRecordListener(ObjectProvider<AuditorSupplier> auditor) {
		return new AuditRecordListener(auditor.getIfAvailable(() -> Optional::empty), Clock.systemUTC());
	}

	@Bean
	public DefaultConfigurationCustomizer v31JooqAuditConfigurationCustomizer(AuditRecordListener listener) {
		return (configuration) -> configuration.setAppending(listener);
	}

}
