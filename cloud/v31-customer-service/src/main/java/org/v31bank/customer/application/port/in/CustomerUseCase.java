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

package org.v31bank.customer.application.port.in;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.v31bank.core.HttpResponse;
import org.v31bank.customer.application.dto.CustomerPageQuery;
import org.v31bank.customer.domain.constant.CustomerStatus;
import org.v31bank.customer.domain.model.Customer;

/**
 * Customer use cases.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public interface CustomerUseCase {

	Customer create(String email, String fullName);

	Optional<Customer> get(UUID id);

	HttpResponse<List<Customer>> page(CustomerPageQuery query);

	Optional<Customer> update(UUID id, String email, String fullName, CustomerStatus status);

	boolean delete(UUID id);

}
