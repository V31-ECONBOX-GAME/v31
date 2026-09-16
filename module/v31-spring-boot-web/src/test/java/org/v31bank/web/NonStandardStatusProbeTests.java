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

package org.v31bank.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class NonStandardStatusProbeTests {

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
		.setControllerAdvice(new HttpResponseExceptionHandler())
		.build();

	@Test
	void nonStandardStatus() throws Exception {
		try {
			System.out.println("[PROBE-WEB] result="
					+ this.mvc.perform(get("/nonstandard")).andReturn().getResponse().getContentAsString() + " status="
					+ this.mvc.perform(get("/nonstandard")).andReturn().getResponse().getStatus());
		}
		catch (Exception ex) {
			System.out.println("[PROBE-WEB] threw " + ex.getClass().getName() + ": " + ex.getMessage());
			Throwable root = ex;
			while (root.getCause() != null) {
				root = root.getCause();
			}
			System.out.println("[PROBE-WEB] root " + root.getClass().getName() + ": " + root.getMessage());
		}
	}

	@RestController
	static class Probe {

		@GetMapping("/nonstandard")
		String nonstandard() {
			throw new ResponseStatusException(HttpStatusCode.valueOf(499), "client closed request");
		}

	}

}
