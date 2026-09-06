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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for V31 jOOQ.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@ConfigurationProperties("v31.jooq")
public class V31JooqProperties {

	private final Auditing auditing = new Auditing();

	private final Identifiers identifiers = new Identifiers();

	public Auditing getAuditing() {
		return this.auditing;
	}

	public Identifiers getIdentifiers() {
		return this.identifiers;
	}

	public static class Auditing {

		/**
		 * Whether to record who wrote each row and when.
		 */
		private boolean enabled = true;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

	public static class Identifiers {

		/**
		 * Whether to give a record without a primary key a time-ordered UUIDv7.
		 */
		private boolean enabled = true;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

}
