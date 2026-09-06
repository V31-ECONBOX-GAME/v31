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

package org.v31bank.data.valkey.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for V31 Data Valkey.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@ConfigurationProperties("v31.data.valkey")
public class V31ValkeyProperties {

	private final Cache cache = new Cache();

	private String keyPrefix = "v31";

	public Cache getCache() {
		return this.cache;
	}

	public String getKeyPrefix() {
		return this.keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public static class Cache {

		private boolean enabled = true;

		private Duration defaultTtl = Duration.ofMinutes(10);

		private Map<String, Duration> ttls = new LinkedHashMap<>();

		private boolean allowNullValues = true;

		private Duration nullTtl = Duration.ofMinutes(1);

		private double ttlJitter = 0.1;

		private int clearBatchSize = 500;

		private boolean failFast;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public Duration getDefaultTtl() {
			return this.defaultTtl;
		}

		public void setDefaultTtl(Duration defaultTtl) {
			this.defaultTtl = defaultTtl;
		}

		public Map<String, Duration> getTtls() {
			return this.ttls;
		}

		public void setTtls(Map<String, Duration> ttls) {
			this.ttls = ttls;
		}

		public boolean isAllowNullValues() {
			return this.allowNullValues;
		}

		public void setAllowNullValues(boolean allowNullValues) {
			this.allowNullValues = allowNullValues;
		}

		public Duration getNullTtl() {
			return this.nullTtl;
		}

		public void setNullTtl(Duration nullTtl) {
			this.nullTtl = nullTtl;
		}

		public double getTtlJitter() {
			return this.ttlJitter;
		}

		public void setTtlJitter(double ttlJitter) {
			this.ttlJitter = ttlJitter;
		}

		public int getClearBatchSize() {
			return this.clearBatchSize;
		}

		public void setClearBatchSize(int clearBatchSize) {
			this.clearBatchSize = clearBatchSize;
		}

		public boolean isFailFast() {
			return this.failFast;
		}

		public void setFailFast(boolean failFast) {
			this.failFast = failFast;
		}

	}

}
