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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NullValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for {@link ValkeyCacheTtl}.
 *
 * @author Xander Wang
 */
class ValkeyCacheTtlTests {

	@Test
	void keepsAValueForTheConfiguredTime() {
		ValkeyCacheTtl ttl = new ValkeyCacheTtl(Duration.ofMinutes(10), Duration.ofMinutes(1), 0);
		assertThat(ttl.getTimeToLive("7", "Ada")).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	void keepsAMissForLessTimeThanAValue() {
		ValkeyCacheTtl ttl = new ValkeyCacheTtl(Duration.ofMinutes(10), Duration.ofMinutes(1), 0);
		assertThat(ttl.getTimeToLive("7", null)).isEqualTo(Duration.ofMinutes(1));
		assertThat(ttl.getTimeToLive("7", NullValue.INSTANCE)).isEqualTo(Duration.ofMinutes(1));
	}

	@Test
	void neverKeepsAMissLongerThanAValue() {
		ValkeyCacheTtl ttl = new ValkeyCacheTtl(Duration.ofSeconds(30), Duration.ofMinutes(1), 0);
		assertThat(ttl.getTimeToLive("7", null)).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void spreadsExpiryForwardWithinTheJitter() {
		ValkeyCacheTtl ttl = new ValkeyCacheTtl(Duration.ofMinutes(10), Duration.ofMinutes(1), 0.1);
		Set<Duration> expiries = IntStream.range(0, 200)
			.mapToObj((index) -> ttl.getTimeToLive("7", "Ada"))
			.collect(Collectors.toSet());
		assertThat(expiries).hasSizeGreaterThan(50)
			.allSatisfy((expiry) -> assertThat(expiry).isBetween(Duration.ofMinutes(10), Duration.ofMinutes(11)));
	}

	@Test
	void readsZeroAsNeverExpiring() {
		ValkeyCacheTtl ttl = new ValkeyCacheTtl(Duration.ZERO, Duration.ofMinutes(1), 0.1);
		assertThat(ttl.getTimeToLive("7", "Ada")).isEqualTo(Duration.ZERO);
		assertThat(ttl.getTimeToLive("7", null)).isBetween(Duration.ofMinutes(1), Duration.ofSeconds(66));
	}

	@Test
	void refusesAnExpiryOrAJitterThatRunsBackwards() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new ValkeyCacheTtl(Duration.ofMinutes(-1), Duration.ofMinutes(1), 0));
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new ValkeyCacheTtl(Duration.ofMinutes(10), Duration.ofMinutes(-1), 0));
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new ValkeyCacheTtl(Duration.ofMinutes(10), Duration.ofMinutes(1), -0.1));
		assertThatNoException().isThrownBy(() -> new ValkeyCacheTtl(Duration.ZERO, Duration.ZERO, 0));
	}

}
