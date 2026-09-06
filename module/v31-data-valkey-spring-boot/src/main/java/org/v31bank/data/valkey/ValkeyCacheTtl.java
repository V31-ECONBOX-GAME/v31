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

package org.v31bank.data.valkey;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;

/**
 * How long an entry lives: shorter for a miss, spread forward, zero for never.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public class ValkeyCacheTtl implements TtlFunction {

	private final Duration ttl;

	private final Duration missTtl;

	private final double jitter;

	public ValkeyCacheTtl(Duration ttl, Duration missTtl, double jitter) {
		if (ttl.isNegative() || missTtl.isNegative() || jitter < 0) {
			throw new IllegalArgumentException(
					"A ttl and a jitter must not be negative, but were " + ttl + ", " + missTtl + " and " + jitter);
		}
		this.ttl = ttl;
		this.missTtl = (ttl.isZero() || missTtl.compareTo(ttl) < 0) ? missTtl : ttl;
		this.jitter = jitter;
	}

	@Override
	public Duration getTimeToLive(Object key, Object value) {
		Duration ttl = (value == null || value instanceof NullValue) ? this.missTtl : this.ttl;
		long spread = (long) (ttl.toMillis() * this.jitter);
		return (spread > 0) ? ttl.plusMillis(ThreadLocalRandom.current().nextLong(spread + 1)) : ttl;
	}

}
