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

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import org.hibernate.Hibernate;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.v31bank.core.Uuids;

/**
 * Auditing for a model.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Audited {

	@Id
	private UUID id;

	@CreatedBy
	@Column(name = "created_by", length = 64, updatable = false)
	private String createdBy;

	@CreatedDate
	@Column(name = "created_date", updatable = false)
	private Instant createdDate;

	@LastModifiedBy
	@Column(name = "last_modified_by", length = 64)
	private String lastModifiedBy;

	@LastModifiedDate
	@Column(name = "last_modified_date")
	private Instant lastModifiedDate;

	@PrePersist
	void assignId() {
		if (this.id == null) {
			this.id = Uuids.timeOrdered();
		}
	}

	public UUID getId() {
		return this.id;
	}

	protected void setId(UUID id) {
		this.id = id;
	}

	public String getCreatedBy() {
		return this.createdBy;
	}

	public Instant getCreatedDate() {
		return this.createdDate;
	}

	public String getLastModifiedBy() {
		return this.lastModifiedBy;
	}

	public Instant getLastModifiedDate() {
		return this.lastModifiedDate;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || Hibernate.getClass(this) != Hibernate.getClass(obj)) {
			return false;
		}
		return this.id != null && this.id.equals(((Audited) obj).id);
	}

	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
