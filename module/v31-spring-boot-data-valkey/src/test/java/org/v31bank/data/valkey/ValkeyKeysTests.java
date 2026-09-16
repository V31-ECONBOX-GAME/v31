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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link ValkeyKeys}.
 *
 * @author Xander Wang
 */
class ValkeyKeysTests {

	private final ValkeyKeys keys = new ValkeyKeys("v31");

	@Test
	void putsEverythingUnderThePrefix() {
		assertThat(this.keys.of("customer", "0199-7a")).isEqualTo("v31:customer:0199-7a");
		assertThat(this.keys.of("session")).isEqualTo("v31:session");
	}

	@Test
	void refusesASegmentThatWouldEscapeItsNamespace() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> this.keys.of("session", "7:session:admin"))
			.withMessageContaining("must not contain ':'");
	}

	@Test
	void refusesAnEmptySegment() {
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> this.keys.of("customer", ""));
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> this.keys.of("customer", "  "));
		assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> this.keys.of("customer", null));
	}

	@Test
	void refusesAKeyWithNothingBelowThePrefix() {
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(this.keys::of);
	}

	@Test
	void refusesAPrefixThatIsNotOne() {
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new ValkeyKeys(""));
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new ValkeyKeys("v31:cache"));
		assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new ValkeyKeys(null));
	}

}
