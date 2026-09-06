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

package org.v31bank.transfer.domain.valueobject;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount and its currency.
 *
 * @param amount how much, never negative
 * @param currency what the amount is denominated in
 * @author Xander Wang
 * @since 0.2.0
 */
public record Money(BigDecimal amount, Currency currency) {

	public Money {
		Objects.requireNonNull(amount, "Amount must not be null.");
		Objects.requireNonNull(currency, "Currency must not be null.");

		if (amount.signum() < 0) {
			throw new IllegalArgumentException("Amount cannot be negative.");
		}
	}

	public Money add(Money other) {
		requireSameCurrency(other);
		return new Money(this.amount.add(other.amount), this.currency);
	}

	public Money subtract(Money other) {
		requireSameCurrency(other);
		return new Money(this.amount.subtract(other.amount), this.currency);
	}

	public boolean isZero() {
		return this.amount.signum() == 0;
	}

	public boolean isPositive() {
		return this.amount.signum() > 0;
	}

	public boolean isNegative() {
		return this.amount.signum() < 0;
	}

	public boolean isGreaterThan(Money other) {
		requireSameCurrency(other);
		return this.amount.compareTo(other.amount) > 0;
	}

	public boolean isLessThan(Money other) {
		requireSameCurrency(other);
		return this.amount.compareTo(other.amount) < 0;
	}

	private void requireSameCurrency(Money other) {
		Objects.requireNonNull(other, "Money must not be null.");

		if (!this.currency.equals(other.currency)) {
			throw new IllegalArgumentException("Currency mismatch: %s vs %s".formatted(this.currency, other.currency));
		}
	}
}
