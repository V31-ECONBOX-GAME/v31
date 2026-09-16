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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import org.v31bank.core.AuditorSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AuditRecordListener}.
 *
 * @author Xander Wang
 */
class AuditRecordListenerTests {

	private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

	private static final Field<UUID> ID = DSL.field(DSL.name(AuditColumns.ID), UUID.class);

	private static final Field<String> CREATED_BY = DSL.field(DSL.name(AuditColumns.CREATED_BY), String.class);

	private static final Field<Instant> CREATED_DATE = DSL.field(DSL.name(AuditColumns.CREATED_DATE), Instant.class);

	private static final Field<String> LAST_MODIFIED_BY = DSL.field(DSL.name(AuditColumns.LAST_MODIFIED_BY),
			String.class);

	private static final Field<Instant> LAST_MODIFIED_DATE = DSL.field(DSL.name(AuditColumns.LAST_MODIFIED_DATE),
			Instant.class);

	private static final Field<String> EMAIL = DSL.field(DSL.name("email"), String.class);

	private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

	private final AuditRecordListener listener = listener(() -> Optional.of("xander"));

	@Test
	void stampsEveryAuditColumnOnCreation() {
		Record record = auditedRecord();
		this.listener.stampCreation(record);
		assertThat(record.get(CREATED_BY)).isEqualTo("xander");
		assertThat(record.get(CREATED_DATE)).isEqualTo(NOW);
		assertThat(record.get(LAST_MODIFIED_BY)).isEqualTo("xander");
		assertThat(record.get(LAST_MODIFIED_DATE)).isEqualTo(NOW);
	}

	@Test
	void issuesATimeOrderedIdentifierOnCreation() {
		Record record = auditedRecord();
		this.listener.stampCreation(record);
		UUID id = record.get(ID);
		assertThat(id).isNotNull();
		assertThat(id.version()).isEqualTo(7);
		assertThat(Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16)).isBefore(Instant.now().plusSeconds(1));
	}

	@Test
	void keepsAnIdentifierTheCallerAlreadyChose() {
		Record record = auditedRecord();
		UUID chosen = UUID.fromString("019fb995-685c-77eb-8f95-c62642e1c17e");
		record.set(ID, chosen);
		this.listener.stampCreation(record);
		assertThat(record.get(ID)).isEqualTo(chosen);
	}

	@Test
	void touchesOnlyTheModificationColumnsOnUpdate() {
		Record record = auditedRecord();
		this.listener.stampModification(record);
		assertThat(record.get(CREATED_BY)).isNull();
		assertThat(record.get(CREATED_DATE)).isNull();
		assertThat(record.get(LAST_MODIFIED_BY)).isEqualTo("xander");
		assertThat(record.get(LAST_MODIFIED_DATE)).isEqualTo(NOW);
	}

	@Test
	void doesNotEraseWhoActedWhenNobodyIsIdentified() {
		Record record = auditedRecord();
		record.set(LAST_MODIFIED_BY, "xander");
		listener(Optional::empty).stampModification(record);
		assertThat(record.get(LAST_MODIFIED_BY)).isEqualTo("xander");
		assertThat(record.get(LAST_MODIFIED_DATE)).isEqualTo(NOW);
	}

	@Test
	void leavesATableWithoutAuditColumnsAlone() {
		Record record = this.dsl.newRecord(EMAIL);
		record.set(EMAIL, "xander.wang@v31bank.org");
		this.listener.stampCreation(record);
		this.listener.stampModification(record);
		assertThat(record.get(EMAIL)).isEqualTo("xander.wang@v31bank.org");
		assertThat(record.size()).isEqualTo(1);
	}

	@Test
	void stampsTheColumnsATableDoesCarry() {
		Record record = this.dsl.newRecord(EMAIL, LAST_MODIFIED_DATE);
		this.listener.stampModification(record);
		assertThat(record.get(LAST_MODIFIED_DATE)).isEqualTo(NOW);
	}

	private Record auditedRecord() {
		return this.dsl.newRecord(ID, CREATED_BY, CREATED_DATE, LAST_MODIFIED_BY, LAST_MODIFIED_DATE, EMAIL);
	}

	private AuditRecordListener listener(AuditorSupplier auditorSupplier) {
		return new AuditRecordListener(auditorSupplier, Clock.fixed(NOW, ZoneOffset.UTC));
	}

}
