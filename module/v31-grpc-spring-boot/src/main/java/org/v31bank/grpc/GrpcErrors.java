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

import java.util.function.Supplier;

import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reports a failed gRPC call as a server error, telling the caller nothing more.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public final class GrpcErrors {

	private GrpcErrors() {
	}

	public static <T> T call(Supplier<T> call) {
		try {
			return call.get();
		}
		catch (StatusRuntimeException ex) {
			throw asResponseStatusException(ex);
		}
	}

	public static ResponseStatusException asResponseStatusException(StatusRuntimeException exception) {
		return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
				HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), exception);
	}

}
