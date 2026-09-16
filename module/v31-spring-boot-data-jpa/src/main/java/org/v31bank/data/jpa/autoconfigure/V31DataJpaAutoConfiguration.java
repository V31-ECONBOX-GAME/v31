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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import org.v31bank.core.AuditorSupplier;

/**
 * {@link AutoConfiguration Auto-configuration} for V31 Data JPA.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@AutoConfiguration
@ConditionalOnClass(AuditingEntityListener.class)
@EnableJpaAuditing
public class V31DataJpaAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AuditorAware<String> auditorAware(ObjectProvider<AuditorSupplier> auditor) {
		return () -> auditor.getIfAvailable(() -> Optional::empty).currentAuditor();
	}

}
