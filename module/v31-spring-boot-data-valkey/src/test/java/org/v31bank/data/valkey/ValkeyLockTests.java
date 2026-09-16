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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link ValkeyLock}.
 *
 * @author Xander Wang
 */
@SuppressWarnings({ "unchecked" })
class ValkeyLockTests {

	private static final Duration LEASE = Duration.ofSeconds(30);

	private StringRedisTemplate template;

	private ValueOperations<String, String> values;

	private ValkeyLock lock;

	@BeforeEach
	void setUp() {
		this.template = mock(StringRedisTemplate.class);
		this.values = mock(ValueOperations.class);
		given(this.template.opsForValue()).willReturn(this.values);
		this.lock = new ValkeyLock(this.template);
	}

	@Test
	void takesTheLockWhenItIsFree() {
		given(this.values.setIfAbsent(eq("v31:batch"), any(), eq(LEASE))).willReturn(true);
		Optional<String> token = this.lock.acquire("v31:batch", LEASE);
		assertThat(token).isPresent();
		assertThat(UUID.fromString(token.get()).version()).as("the token should be a time-ordered identifier")
			.isEqualTo(7);
	}

	@Test
	void doesNotTakeALockSomebodyElseHolds() {
		given(this.values.setIfAbsent(any(), any(), any(Duration.class))).willReturn(false);
		assertThat(this.lock.acquire("v31:batch", LEASE)).isEmpty();
	}

	@Test
	void givesEachHolderADifferentToken() {
		given(this.values.setIfAbsent(any(), any(), any(Duration.class))).willReturn(true);
		assertThat(this.lock.acquire("v31:batch", LEASE)).isNotEqualTo(this.lock.acquire("v31:batch", LEASE));
	}

	@Test
	void refusesALeaseThatWouldExpireImmediately() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> this.lock.acquire("v31:batch", Duration.ZERO));
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> this.lock.acquire("v31:batch", Duration.ofSeconds(-1)));
	}

	@Test
	void releasesWithTheTokenItWasGiven() {
		given(this.template.execute(any(RedisScript.class), anyList(), any())).willReturn(1L);
		assertThat(this.lock.release("v31:batch", "token-a")).isTrue();
		ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
		then(this.template).should().execute(any(RedisScript.class), eq(List.of("v31:batch")), args.capture());
		assertThat(args.getValue()).isEqualTo("token-a");
	}

	@Test
	void reportsAReleaseThatChangedNothing() {
		given(this.template.execute(any(RedisScript.class), anyList(), any())).willReturn(0L);
		assertThat(this.lock.release("v31:batch", "token-a"))
			.as("a lease that already expired is not this caller's to release")
			.isFalse();
	}

	@Test
	void extendsALeaseItStillHolds() {
		given(this.template.execute(any(RedisScript.class), anyList(), any(), any())).willReturn(1L);
		assertThat(this.lock.extend("v31:batch", "token-a", LEASE)).isTrue();
		then(this.template).should()
			.execute(any(RedisScript.class), eq(List.of("v31:batch")), eq("token-a"),
					eq(Long.toString(LEASE.toMillis())));
	}

	@Test
	void reportsALeaseThatIsNoLongerItsToExtend() {
		given(this.template.execute(any(RedisScript.class), anyList(), any(), any())).willReturn(0L);
		assertThat(this.lock.extend("v31:batch", "token-a", LEASE))
			.as("a holder that lost its lease has to stop, not carry on")
			.isFalse();
	}

	@Test
	void refusesToExtendByNothing() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> this.lock.extend("v31:batch", "token-a", Duration.ZERO));
	}

	@Test
	void runsTheActionWhileHoldingTheLockAndGivesItBack() {
		given(this.values.setIfAbsent(any(), any(), any(Duration.class))).willReturn(true);
		given(this.template.execute(any(RedisScript.class), anyList(), any())).willReturn(1L);
		Optional<String> result = this.lock.runExclusively("v31:batch", LEASE, () -> "done");
		assertThat(result).hasValue("done");
		then(this.template).should().execute(any(RedisScript.class), anyList(), any());
	}

	@Test
	void givesTheLockBackWhenTheActionFails() {
		given(this.values.setIfAbsent(any(), any(), any(Duration.class))).willReturn(true);
		given(this.template.execute(any(RedisScript.class), anyList(), any())).willReturn(1L);
		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> this.lock.runExclusively("v31:batch", LEASE, () -> {
				throw new IllegalStateException("boom");
			}));
		then(this.template).should().execute(any(RedisScript.class), anyList(), any());
	}

	@Test
	void doesNotRunTheActionWhenTheLockIsHeld() {
		given(this.values.setIfAbsent(any(), any(), any(Duration.class))).willReturn(false);
		AtomicBoolean ran = new AtomicBoolean();
		Optional<String> result = this.lock.runExclusively("v31:batch", LEASE, () -> {
			ran.set(true);
			return "done";
		});
		assertThat(result).isEmpty();
		assertThat(ran.get()).isFalse();
		then(this.template).should(never()).execute(any(RedisScript.class), anyList(), any());
	}

}
