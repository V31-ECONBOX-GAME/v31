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

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.jooq.Field;
import org.jooq.Record;
import org.jooq.RecordContext;
import org.jooq.RecordListener;

import org.v31bank.core.AuditorSupplier;
import org.v31bank.core.Uuids;

/**
 * Stamps the identifier and audit columns as records are written.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public class AuditRecordListener implements RecordListener {

	private final AuditorSupplier auditorSupplier;

	private final Clock clock;

	private final boolean assignIdentifiers;

	public AuditRecordListener(AuditorSupplier auditorSupplier, Clock clock, boolean assignIdentifiers) {
		this.auditorSupplier = auditorSupplier;
		this.clock = clock;
		this.assignIdentifiers = assignIdentifiers;
	}

	@Override
	public void insertStart(RecordContext ctx) {
		stampCreation(ctx.record());
	}

	@Override
	public void updateStart(RecordContext ctx) {
		stampModification(ctx.record());
	}

	void stampCreation(Record record) {
		Instant now = Instant.now(this.clock);
		String auditor = currentAuditor();
		if (this.assignIdentifiers) {
			Field<UUID> id = record.field(AuditColumns.ID, UUID.class);
			if (id != null && record.get(id) == null) {
				record.set(id, Uuids.timeOrdered());
			}
		}
		stamp(record, AuditColumns.CREATED_BY, String.class, auditor);
		stamp(record, AuditColumns.CREATED_DATE, Instant.class, now);
		stamp(record, AuditColumns.LAST_MODIFIED_BY, String.class, auditor);
		stamp(record, AuditColumns.LAST_MODIFIED_DATE, Instant.class, now);
	}

	void stampModification(Record record) {
		stamp(record, AuditColumns.LAST_MODIFIED_BY, String.class, currentAuditor());
		stamp(record, AuditColumns.LAST_MODIFIED_DATE, Instant.class, Instant.now(this.clock));
	}

	private String currentAuditor() {
		return this.auditorSupplier.currentAuditor().orElse(null);
	}

	private static <T> void stamp(Record record, String column, Class<T> type, T value) {
		if (value == null) {
			return;
		}
		Field<T> field = record.field(column, type);
		if (field != null) {
			record.set(field, value);
		}
	}

}
