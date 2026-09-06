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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.v31bank.core.HttpResponse;

/**
 * Reports a refused write as a conflict ({@code 409}).
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 200)
public class DataAccessExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(DataAccessExceptionHandler.class);

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<HttpResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		logger.warn("Write refused by the database", ex);
		return conflict(HttpStatus.CONFLICT.getReasonPhrase());
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<HttpResponse<Void>> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
		logger.debug("Concurrent modification", ex);
		return conflict("The record changed while this request was in flight");
	}

	private static ResponseEntity<HttpResponse<Void>> conflict(String message) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(HttpResponse.error(HttpStatus.CONFLICT.value(), message));
	}

}
