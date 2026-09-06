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

package org.v31bank.customer.application.dto;

import org.jspecify.annotations.Nullable;

import org.v31bank.core.PageQuery;
import org.v31bank.customer.domain.constant.CustomerStatus;

/**
 * Customer page query.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public class CustomerPageQuery extends PageQuery {

	private @Nullable String email;

	private @Nullable CustomerStatus status;

	public @Nullable String getEmail() {
		return this.email;
	}

	public void setEmail(@Nullable String email) {
		this.email = email;
	}

	public @Nullable CustomerStatus getStatus() {
		return this.status;
	}

	public void setStatus(@Nullable CustomerStatus status) {
		this.status = status;
	}

}
