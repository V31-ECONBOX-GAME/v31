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

/**
 * Audit column names.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public final class AuditColumns {

	/**
	 * Primary key.
	 */
	public static final String ID = "id";

	/**
	 * Who inserted.
	 */
	public static final String CREATED_BY = "created_by";

	/**
	 * When inserted.
	 */
	public static final String CREATED_DATE = "created_date";

	/**
	 * Who last changed.
	 */
	public static final String LAST_MODIFIED_BY = "last_modified_by";

	/**
	 * When last changed.
	 */
	public static final String LAST_MODIFIED_DATE = "last_modified_date";

	private AuditColumns() {
	}

}
