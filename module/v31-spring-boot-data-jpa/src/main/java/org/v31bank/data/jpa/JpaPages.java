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
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.v31bank.core.HttpResponse;
import org.v31bank.core.PageQuery;

/**
 * Spring Data paging.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public final class JpaPages {

	private JpaPages() {
	}

	public static Pageable toPageable(PageQuery query) {
		return toPageable(query, Sort.unsorted());
	}

	public static Pageable toPageable(PageQuery query, Sort sort) {
		Objects.requireNonNull(query, "query must not be null");
		return PageRequest.of(query.normalizedPageNumber() - 1, query.normalizedPageSize(), sort);
	}

	public static <T> HttpResponse<List<T>> from(Page<T> page) {
		Objects.requireNonNull(page, "page must not be null");
		return HttpResponse.page(page.getContent(), page.getTotalElements());
	}

}
