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

package org.v31bank.customer.infra.persistence.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import org.v31bank.core.HttpResponse;
import org.v31bank.customer.application.dto.CustomerPageQuery;
import org.v31bank.customer.application.port.out.CustomerPort;
import org.v31bank.customer.domain.constant.CustomerStatus;
import org.v31bank.customer.domain.model.Customer;
import org.v31bank.customer.infra.persistence.jpa.JpaCustomerRepository;
import org.v31bank.data.jpa.JpaPages;

/**
 * {@link CustomerPort} over JPA.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@Repository
public class CustomerPersistenceAdapter implements CustomerPort {

	private final JpaCustomerRepository jpaRepository;

	public CustomerPersistenceAdapter(JpaCustomerRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public Customer save(Customer customer) {
		return this.jpaRepository.save(customer);
	}

	@Override
	public Optional<Customer> findById(UUID id) {
		return this.jpaRepository.findById(id);
	}

	@Override
	public HttpResponse<List<Customer>> findPage(CustomerPageQuery pageQuery) {
		String email = pageQuery.getEmail();
		CustomerStatus status = pageQuery.getStatus();
		Specification<Customer> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (StringUtils.hasText(email)) {
				predicates.add(cb.like(cb.lower(root.get("email")), "%" + email.toLowerCase() + "%"));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		Sort sort = Sort.by(Sort.Direction.DESC, "createdDate");
		return JpaPages.from(this.jpaRepository.findAll(spec, JpaPages.toPageable(pageQuery, sort)));
	}

	@Override
	public void delete(Customer customer) {
		this.jpaRepository.delete(customer);
	}

}
