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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
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

	private static final Log logger = LogFactory.getLog(DataAccessExceptionHandler.class);

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<HttpResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		logger.warn("Write refused by the database", ex);
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(HttpResponse.error(HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase()));
	}

}
