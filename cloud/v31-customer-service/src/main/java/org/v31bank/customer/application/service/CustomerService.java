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

package org.v31bank.customer.application.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.v31bank.core.HttpResponse;
import org.v31bank.customer.application.dto.CustomerPageQuery;
import org.v31bank.customer.application.port.in.CustomerUseCase;
import org.v31bank.customer.application.port.out.CustomerPort;
import org.v31bank.customer.domain.constant.CustomerStatus;
import org.v31bank.customer.domain.model.Customer;

/**
 * Default {@link CustomerUseCase}.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@Service
@Transactional
public class CustomerService implements CustomerUseCase {

	private final CustomerPort customerRepository;

	public CustomerService(CustomerPort customerRepository) {
		this.customerRepository = customerRepository;
	}

	@Override
	public Customer create(String email, String fullName) {
		Customer customer = new Customer();
		customer.setEmail(email);
		customer.setFullName(fullName);
		return this.customerRepository.save(customer);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Customer> get(UUID id) {
		return this.customerRepository.findById(id);
	}

	@Override
	@Transactional(readOnly = true)
	public HttpResponse<List<Customer>> page(CustomerPageQuery query) {
		return this.customerRepository.findPage(query);
	}

	@Override
	public Optional<Customer> update(UUID id, String email, String fullName, CustomerStatus status) {
		return this.customerRepository.findById(id).map((customer) -> {
			customer.setEmail(email);
			customer.setFullName(fullName);
			customer.setStatus(status);
			return this.customerRepository.save(customer);
		});
	}

	@Override
	public boolean delete(UUID id) {
		return this.customerRepository.findById(id).map((customer) -> {
			this.customerRepository.delete(customer);
			return true;
		}).orElse(false);
	}

}
