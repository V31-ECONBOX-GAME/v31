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

package org.v31bank.customer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;

import org.v31bank.customer.infra.persistence.jpa.JpaCustomerRepository;
import org.v31bank.customer.presentation.controller.v1.CustomerController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CustomerController}.
 *
 * @author Xander Wang
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfiguration.class)
class CustomerApiIntegrationTests {

	private static final String PATH = "/api/v1/customers";

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
	};

	private static final UUID ABSENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000000");

	@LocalServerPort
	private int port;

	@Autowired
	private JpaCustomerRepository customers;

	private RestTestClient client;

	@BeforeEach
	void setUp() {
		this.client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
		this.customers.deleteAll();
	}

	@Test
	void createsACustomerAndSaysWhereItWent() {
		Map<String, Object> body = this.client.post()
			.uri(PATH)
			.body(Map.of("email", "ada@v31bank.org", "fullName", "Ada Lovelace"))
			.exchange()
			.expectStatus()
			.isCreated()
			.expectBody(JSON_OBJECT)
			.returnResult()
			.getResponseBody();
		assertThat(body).containsEntry("code", 200);
		assertThat(data(body)).containsEntry("email", "ada@v31bank.org")
			.containsEntry("fullName", "Ada Lovelace")
			.containsEntry("status", "ACTIVE");
	}

	@Test
	void issuesATimeOrderedIdentifierAndStampsTheAuditFields() {
		Map<String, Object> created = create("grace@v31bank.org", "Grace Hopper");
		assertThat(UUID.fromString((String) created.get("id")).version()).isEqualTo(7);
		assertThat(created.get("createdDate")).asString().endsWith("Z");
		assertThat(created).containsEntry("createdDate", created.get("lastModifiedDate"));
	}

	@Test
	void findsACustomerItJustCreated() {
		String id = (String) create("alan@v31bank.org", "Alan Turing").get("id");
		Map<String, Object> body = get(PATH + "/" + id);
		assertThat(data(body)).containsEntry("email", "alan@v31bank.org");
	}

	@Test
	void reportsAnAbsentCustomerAsNotFound() {
		Map<String, Object> body = this.client.get()
			.uri(PATH + "/" + ABSENT_ID)
			.exchange()
			.expectStatus()
			.isNotFound()
			.expectBody(JSON_OBJECT)
			.returnResult()
			.getResponseBody();
		assertThat(body).containsEntry("code", 404);
	}

	@Test
	void pagesNewestFirstAndCountsThemAll() {
		createMany(25);
		Map<String, Object> page = get(PATH + "?pageNumber=1&pageSize=10");
		assertThat(page).containsEntry("total", 25);
		assertThat(records(page)).hasSize(10);
		assertThat(records(page).get(0)).containsEntry("email", "customer25@v31bank.org");
	}

	@Test
	void reportsTheLastPageAsTheLastOne() {
		createMany(25);
		Map<String, Object> page = get(PATH + "?pageNumber=3&pageSize=10");
		assertThat(records(page)).hasSize(5);
	}

	@Test
	void doesNotRepeatOrDropARecordAcrossPages() {
		createMany(25);
		Set<Object> seen = new HashSet<>();
		for (int page = 1; page <= 3; page++) {
			records(get(PATH + "?pageNumber=" + page + "&pageSize=10")).forEach((record) -> seen.add(record.get("id")));
		}
		assertThat(seen).hasSize(25);
	}

	@Test
	void filtersByEmailFragment() {
		create("ada@v31bank.org", "Ada");
		create("alan@v31bank.org", "Alan");
		assertThat(get(PATH + "?email=ada")).containsEntry("total", 1);
	}

	@Test
	void updatesACustomer() {
		String id = (String) create("ada@v31bank.org", "Ada").get("id");
		this.client.put()
			.uri(PATH + "/" + id)
			.body(Map.of("email", "ada.lovelace@v31bank.org", "fullName", "Ada Lovelace", "status", "FROZEN"))
			.exchange()
			.expectStatus()
			.isOk();
		assertThat(data(get(PATH + "/" + id))).containsEntry("email", "ada.lovelace@v31bank.org")
			.containsEntry("status", "FROZEN");
	}

	@Test
	void reportsAnUpdateToAnAbsentCustomerAsNotFound() {
		this.client.put()
			.uri(PATH + "/" + ABSENT_ID)
			.body(Map.of("email", "x@v31bank.org", "fullName", "X"))
			.exchange()
			.expectStatus()
			.isNotFound();
	}

	@Test
	void deletesACustomer() {
		String id = (String) create("ada@v31bank.org", "Ada").get("id");
		this.client.delete().uri(PATH + "/" + id).exchange().expectStatus().isOk();
		this.client.get().uri(PATH + "/" + id).exchange().expectStatus().isNotFound();
		assertThat(this.customers.count()).isZero();
	}

	@Test
	void reportsADeleteOfAnAbsentCustomerAsNotFound() {
		this.client.delete().uri(PATH + "/" + ABSENT_ID).exchange().expectStatus().isNotFound();
	}

	@Test
	void refusesAnIdentifierThatIsNotOne() {
		this.client.get().uri(PATH + "/not-a-uuid").exchange().expectStatus().isBadRequest();
	}

	private Map<String, Object> create(String email, String fullName) {
		return data(this.client.post()
			.uri(PATH)
			.body(Map.of("email", email, "fullName", fullName))
			.exchange()
			.expectStatus()
			.isCreated()
			.expectBody(JSON_OBJECT)
			.returnResult()
			.getResponseBody());
	}

	private void createMany(int count) {
		for (int i = 1; i <= count; i++) {
			create("customer%d@v31bank.org".formatted(i), "Customer " + i);
		}
	}

	private Map<String, Object> get(String uri) {
		return this.client.get()
			.uri(uri)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(JSON_OBJECT)
			.returnResult()
			.getResponseBody();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> data(Map<String, ?> envelope) {
		return (Map<String, Object>) envelope.get("data");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> records(Map<String, ?> envelope) {
		return (List<Map<String, Object>>) envelope.get("data");
	}

	@Test
	void rejectsAnEmptyBody() {
		Map<String, Object> body = this.client.post()
			.uri(PATH)
			.body(Map.of())
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody(JSON_OBJECT)
			.returnResult()
			.getResponseBody();
		assertThat(body).containsEntry("code", 400);
	}

	@Test
	void rejectsAValueLongerThanTheColumnHolds() {
		Map<String, Object> body = this.client.post()
			.uri(PATH)
			.body(Map.of("email", "ada@v31bank.org", "fullName", TOO_LONG))
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody(JSON_OBJECT)
			.returnResult()
			.getResponseBody();
		assertThat(body).containsEntry("code", 400);
	}

	@Test
	void reportsAFrameworkRejectionInTheSameEnvelope() {
		Map<String, Object> body = this.client.post()
			.uri(PATH)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.body("not json at all")
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody(JSON_OBJECT)
			.returnResult()
			.getResponseBody();
		assertThat(body).containsEntry("code", 400)
			.doesNotContainKeys("succeeded", "violations", "timestamp", "traceId");
	}

	private static final String TOO_LONG = "x".repeat(100 + 1);

}
