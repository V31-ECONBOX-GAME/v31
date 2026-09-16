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

package org.v31bank.data.jpa;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.v31bank.core.HttpResponse;
import org.v31bank.core.PageQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JpaPages}.
 *
 * @author Xander Wang
 */
class JpaPagesTests {

	@Test
	void countsFromOneWhileSpringDataCountsFromZero() {
		assertThat(pageable(1, 10).getPageNumber()).isZero();
		assertThat(pageable(3, 10).getPageNumber()).isEqualTo(2);
	}

	@Test
	void normalisesWhatTheCallerAskedFor() {
		assertThat(pageable(0, 10).getPageNumber()).isZero();
		assertThat(pageable(1, PageQuery.MAX_PAGE_SIZE + 1).getPageSize()).isEqualTo(PageQuery.MAX_PAGE_SIZE);
		assertThat(pageable(1, 0).getPageSize()).isEqualTo(1);
	}

	@Test
	void carriesTheSortThrough() {
		Sort sort = Sort.by(Sort.Direction.DESC, "createdDate");
		assertThat(JpaPages.toPageable(new PageQuery(), sort).getSort()).isEqualTo(sort);
		assertThat(JpaPages.toPageable(new PageQuery()).getSort()).isEqualTo(Sort.unsorted());
	}

	@Test
	void carriesThePageAndTheTotal() {
		HttpResponse<List<String>> page = JpaPages
			.from(new PageImpl<>(List.of("a", "b", "c", "d", "e"), PageRequest.of(2, 10), 25));
		assertThat(page.succeeded()).isTrue();
		assertThat(page.total()).isEqualTo(25);
		assertThat(page.data()).containsExactly("a", "b", "c", "d", "e");
	}

	private static Pageable pageable(int pageNumber, int pageSize) {
		PageQuery query = new PageQuery();
		query.setPageNumber(pageNumber);
		query.setPageSize(pageSize);
		return JpaPages.toPageable(query);
	}

}
