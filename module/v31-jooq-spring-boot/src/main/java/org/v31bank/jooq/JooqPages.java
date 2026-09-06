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

package org.v31bank.jooq;

import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SelectLimitStep;

import org.v31bank.core.HttpResponse;
import org.v31bank.core.PageQuery;

/**
 * Runs a jOOQ query one page at a time.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public final class JooqPages {

	private JooqPages() {
	}

	public static <R extends Record> HttpResponse<List<R>> fetch(DSLContext dsl, SelectLimitStep<R> query,
			PageQuery page) {
		long total = dsl.fetchCountLarge(query);
		if (total == 0) {
			return HttpResponse.page(List.of(), 0);
		}
		int size = page.normalizedPageSize();
		Result<R> records = query.offset((page.normalizedPageNumber() - 1) * (long) size).limit(size).fetch();
		return HttpResponse.page(records, total);
	}

}
