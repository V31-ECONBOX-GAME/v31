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

package org.v31bank.customer.presentation.controller.v1;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.v31bank.core.HttpResponse;
import org.v31bank.customer.application.dto.CustomerPageQuery;
import org.v31bank.customer.application.port.in.CustomerUseCase;
import org.v31bank.customer.domain.model.Customer;
import org.v31bank.customer.presentation.dto.CustomerRequest;
import org.v31bank.customer.presentation.dto.CustomerResponse;

/**
 * Customer endpoints.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@RestController
@RequestMapping(CustomerController.PATH)
public class CustomerController {

	static final String PATH = "/api/v1/customers";

	private final CustomerUseCase customerInputPort;

	public CustomerController(CustomerUseCase customerInputPort) {
		this.customerInputPort = customerInputPort;
	}

	@PostMapping
	public ResponseEntity<HttpResponse<CustomerResponse>> create(@Valid @RequestBody CustomerRequest request) {
		Customer customer = this.customerInputPort.create(request.email(), request.fullName());
		return ResponseEntity.created(URI.create(PATH + "/" + customer.getId()))
			.body(HttpResponse.ok(CustomerResponse.from(customer)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<HttpResponse<CustomerResponse>> get(@PathVariable UUID id) {
		return this.customerInputPort.get(id)
			.map((customer) -> ResponseEntity.ok(HttpResponse.ok(CustomerResponse.from(customer))))
			.orElseGet(() -> notFound(id));
	}

	@GetMapping
	public HttpResponse<List<CustomerResponse>> page(CustomerPageQuery query) {
		return this.customerInputPort.page(query)
			.map((records) -> records.stream().map(CustomerResponse::from).toList());
	}

	@PutMapping("/{id}")
	public ResponseEntity<HttpResponse<CustomerResponse>> update(@PathVariable UUID id,
			@Valid @RequestBody CustomerRequest request) {
		return this.customerInputPort.update(id, request.email(), request.fullName(), request.status())
			.map((customer) -> ResponseEntity.ok(HttpResponse.ok(CustomerResponse.from(customer))))
			.orElseGet(() -> notFound(id));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<HttpResponse<Void>> delete(@PathVariable UUID id) {
		return this.customerInputPort.delete(id) ? ResponseEntity.ok(HttpResponse.ok()) : notFound(id);
	}

	private static <T> ResponseEntity<HttpResponse<T>> notFound(UUID id) {
		return error(HttpStatus.NOT_FOUND.value(), "No customer exists with id " + id);
	}

	private static <T> ResponseEntity<HttpResponse<T>> error(int code, String message) {
		return ResponseEntity.status(code).body(HttpResponse.error(code, message));
	}

}
