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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link HttpResponseExceptionHandler}.
 *
 * @author Xander Wang
 */
class HttpResponseExceptionHandlerTests {

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
		.setControllerAdvice(new HttpResponseExceptionHandler())
		.setValidator(validator())
		.build();

	@Test
	void reportsARefusalWithTheStatusItCarried() throws Exception {
		this.mvc.perform(get("/refuse/404"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(404))
			.andExpect(jsonPath("$.message").value(HttpStatus.NOT_FOUND.getReasonPhrase()));
	}

	@Test
	void placesARefusalByTheCodesOwnStatus() throws Exception {
		this.mvc.perform(get("/refuse/429")).andExpect(status().isTooManyRequests());
		this.mvc.perform(get("/refuse/504")).andExpect(status().isGatewayTimeout());
	}

	@Test
	void answersARejectedBodyAsTheCallersFault() throws Exception {
		this.mvc.perform(post("/customers").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(HttpStatus.BAD_REQUEST.value()));
	}

	@Test
	void sendsNothingBeyondTheEnvelope() throws Exception {
		this.mvc.perform(get("/refuse/404"))
			.andExpect(jsonPath("$.succeeded").doesNotExist())
			.andExpect(jsonPath("$.violations").doesNotExist())
			.andExpect(jsonPath("$.timestamp").doesNotExist())
			.andExpect(jsonPath("$.traceId").doesNotExist())
			.andExpect(jsonPath("$.code").exists())
			.andExpect(jsonPath("$.message").exists());
	}

	@Test
	void keepsTheRejectedValueOutOfTheResponse() throws Exception {
		this.mvc
			.perform(post("/customers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"ada@v31bank.org\",\"fullName\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$..*[?(@ == 'ada@v31bank.org')]").doesNotExist());
	}

	@Test
	void keepsTheRejectedValueOutOfTheLogToo() throws Exception {
		ListAppender<ILoggingEvent> appender = attachAppender();
		try {
			this.mvc.perform(post("/customers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"ada@v31bank.org\",\"fullName\":\"\"}"));
			assertThat(appender.list).isNotEmpty()
				.noneMatch((event) -> event.getFormattedMessage().contains("ada@v31bank.org"));
		}
		finally {
			detachAppender(appender);
		}
	}

	@Test
	void reportsAMalformedEmailRatherThanStoringIt() throws Exception {
		this.mvc
			.perform(post("/customers").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\",\"fullName\":\"Ada\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(HttpStatus.BAD_REQUEST.value()));
	}

	@Test
	void saysNothingAboutAnUnrecognisedFailure() throws Exception {
		this.mvc.perform(get("/explode"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value(HttpStatus.INTERNAL_SERVER_ERROR.value()))
			.andExpect(jsonPath("$.message").value(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
				.not(org.hamcrest.Matchers.containsString("jdbc:postgresql://ledger-primary.internal:5432"))));
	}

	@Test
	void stillAnswersWithTheEnvelopeForAStatusItCannotName() throws Exception {
		this.mvc.perform(get("/nonstandard"))
			.andExpect(status().is(499))
			.andExpect(jsonPath("$.code").value(499))
			.andExpect(jsonPath("$.message").exists());
	}

	@Test
	void answersAnUnreadableBodyAsTheCallersFault() throws Exception {
		this.mvc.perform(post("/customers").contentType(MediaType.APPLICATION_JSON).content("not json at all"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(HttpStatus.BAD_REQUEST.value()));
	}

	@Test
	void answersAPathVariableThatWillNotParseAsTheCallersFault() throws Exception {
		this.mvc.perform(get("/customers/not-a-number")).andExpect(status().isBadRequest());
	}

	@Test
	void leavesASucceedingRequestAlone() throws Exception {
		this.mvc.perform(get("/ok")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
	}

	private static ListAppender<ILoggingEvent> attachAppender() {
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(HttpResponseExceptionHandler.class);
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		return appender;
	}

	private static void detachAppender(ListAppender<ILoggingEvent> appender) {
		((Logger) org.slf4j.LoggerFactory.getLogger(HttpResponseExceptionHandler.class)).detachAppender(appender);
	}

	private static LocalValidatorFactoryBean validator() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		return validator;
	}

	@RestController
	static class TestController {

		@GetMapping("/refuse/{code}")
		String refuse(@PathVariable("code") String code) {
			HttpStatus status = HttpStatus.valueOf(Integer.parseInt(code));
			throw new ResponseStatusException(status,
					(status == HttpStatus.NOT_FOUND) ? "No account exists with id 7" : status.getReasonPhrase());
		}

		@GetMapping("/nonstandard")
		String nonstandard() {
			throw new ResponseStatusException(HttpStatusCode.valueOf(499), "the client went away");
		}

		@GetMapping("/explode")
		String explode() {
			throw new IllegalStateException(
					"could not connect to jdbc:postgresql://ledger-primary.internal:5432/ledger");
		}

		@GetMapping("/ok")
		org.v31bank.core.HttpResponse<String> ok() {
			return org.v31bank.core.HttpResponse.ok("fine");
		}

		@GetMapping("/customers/{id}")
		String byId(@PathVariable("id") long id) {
			return String.valueOf(id);
		}

		@PostMapping("/customers")
		String create(@Valid @RequestBody CustomerBody body) {
			return body.email();
		}

	}

	record CustomerBody(@NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(max = 100) String fullName) {

	}

}
