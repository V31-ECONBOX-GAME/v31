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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import org.v31bank.core.HttpResponse;

/**
 * Answers a failure with the platform's response envelope.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class HttpResponseExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Log logger = LogFactory.getLog(HttpResponseExceptionHandler.class);

	@ExceptionHandler(Exception.class)
	public ResponseEntity<HttpResponse<Void>> handleUnexpectedException(Exception ex) {
		logger.error("Unhandled failure", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(HttpResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(),
					HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()));
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		if (logger.isDebugEnabled()) {
			logger.debug("Rejected with " + statusCode + " (" + ex.getClass().getSimpleName() + ")");
		}
		return super.handleExceptionInternal(ex, HttpResponse.error(statusCode.value(), reasonPhraseOf(statusCode)),
				headers, statusCode, request);
	}

	private static String reasonPhraseOf(HttpStatusCode statusCode) {
		HttpStatus resolved = HttpStatus.resolve(statusCode.value());
		return (resolved != null) ? resolved.getReasonPhrase() : "The request could not be completed";
	}

}
