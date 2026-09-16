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
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for {@link ValkeyCacheErrorHandler}.
 *
 * @author Xander Wang
 */
class ValkeyCacheErrorHandlerTests {

	private static final Cache CACHE = new ConcurrentMapCache("customers");

	private final ValkeyCacheErrorHandler handler = new ValkeyCacheErrorHandler(false);

	private final ValkeyCacheErrorHandler failFast = new ValkeyCacheErrorHandler(true);

	@Test
	void letsAFailedLookupFallThroughToTheMethod() {
		assertThatNoException().isThrownBy(() -> this.handler.handleCacheGetError(unreachable(), CACHE, "7"));
	}

	@Test
	void letsAFailedWriteGoUnstored() {
		assertThatNoException().isThrownBy(() -> this.handler.handleCachePutError(unreachable(), CACHE, "7", "value"));
	}

	@Test
	void refusesToSwallowAFailedEviction() {
		QueryTimeoutException failure = unreachable();
		assertThatExceptionOfType(QueryTimeoutException.class)
			.isThrownBy(() -> this.handler.handleCacheEvictError(failure, CACHE, "7"))
			.as("an eviction that did not happen leaves a value the application knows is wrong")
			.isSameAs(failure);
	}

	@Test
	void refusesToSwallowAFailedClear() {
		QueryTimeoutException failure = unreachable();
		assertThatExceptionOfType(QueryTimeoutException.class)
			.isThrownBy(() -> this.handler.handleCacheClearError(failure, CACHE))
			.isSameAs(failure);
	}

	@Test
	void letsEverythingOutWhenAskedToFailFast() {
		assertThatExceptionOfType(QueryTimeoutException.class)
			.isThrownBy(() -> this.failFast.handleCacheGetError(unreachable(), CACHE, "7"));
		assertThatExceptionOfType(QueryTimeoutException.class)
			.isThrownBy(() -> this.failFast.handleCachePutError(unreachable(), CACHE, "7", "value"));
		assertThatExceptionOfType(QueryTimeoutException.class)
			.isThrownBy(() -> this.failFast.handleCacheEvictError(unreachable(), CACHE, "7"));
	}

	private static QueryTimeoutException unreachable() {
		return new QueryTimeoutException("Valkey command timed out");
	}

}
