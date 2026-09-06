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

package org.v31bank.customer.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.v31bank.customer.domain.constant.CustomerStatus;
import org.v31bank.data.jpa.Audited;

/**
 * A customer.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@Entity
@Table(name = "customer", uniqueConstraints = @UniqueConstraint(name = "uk_customer_email", columnNames = "email"))
public class Customer extends Audited {

	@Column(name = "email", length = 320, nullable = false)
	private String email;

	@Column(name = "full_name", length = 100, nullable = false)
	private String fullName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private CustomerStatus status = CustomerStatus.ACTIVE;

	public String getEmail() {
		return this.email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getFullName() {
		return this.fullName;
	}

	public void setFullName(String fullName) {
		this.fullName = fullName;
	}

	public CustomerStatus getStatus() {
		return this.status;
	}

	public void setStatus(CustomerStatus status) {
		this.status = status;
	}

}
