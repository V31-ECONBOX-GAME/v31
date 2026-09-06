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

package org.v31bank.grpc;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.http.HttpStatus;

/**
 * Answers anything the transport did not recognise.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@Order
public class UnexpectedExceptionGrpcExceptionHandler implements GrpcExceptionHandler {

	private static final Log logger = LogFactory.getLog(UnexpectedExceptionGrpcExceptionHandler.class);

	@Override
	public StatusException handleException(Throwable exception) {
		if (exception instanceof StatusException statusException) {
			return statusException;
		}
		if (exception instanceof StatusRuntimeException statusRuntimeException) {
			return new StatusException(statusRuntimeException.getStatus(), statusRuntimeException.getTrailers());
		}
		logger.error("Unhandled failure while serving a gRPC call", exception);
		return Status.INTERNAL.withDescription(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()).asException();
	}

}
