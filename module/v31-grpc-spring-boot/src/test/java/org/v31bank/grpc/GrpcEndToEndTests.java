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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.grpc.client.interceptor.DefaultDeadlineSetupClientInterceptor;
import org.springframework.grpc.server.exception.CompositeGrpcExceptionHandler;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tests for {@link HeaderPropagationClientInterceptor},
 * {@link HeaderPropagationServerInterceptor}, {@link RefusalGrpcExceptionHandler} and
 * {@link UnexpectedExceptionGrpcExceptionHandler}, over a real server and channel.
 *
 * @author Xander Wang
 */
class GrpcEndToEndTests {

	private static final String ECHO_DEADLINE = "echo-deadline";

	private static final String ECHO_TENANT = "echo-tenant";

	private static final String ECHO_OTHER = "echo-other";

	private static final String TENANT_HEADER = "x-tenant-id";

	private static final String OTHER_HEADER = "x-not-configured";

	private static final String FAIL_BUSINESS = "fail-business";

	private static final String FAIL_UNEXPECTED = "fail-unexpected";

	private static final MethodDescriptor.Marshaller<String> STRINGS = new MethodDescriptor.Marshaller<>() {

		@Override
		public InputStream stream(String value) {
			return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public String parse(InputStream stream) {
			try {
				return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

	};

	private static final MethodDescriptor<String, String> METHOD = MethodDescriptor.<String, String>newBuilder()
		.setType(MethodDescriptor.MethodType.UNARY)
		.setFullMethodName("v31.Probe/Call")
		.setRequestMarshaller(STRINGS)
		.setResponseMarshaller(STRINGS)
		.build();

	private Server server;

	private ManagedChannel channel;

	@AfterEach
	void tearDown() throws InterruptedException {
		if (this.channel != null) {
			this.channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
		if (this.server != null) {
			this.server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void everyFailedCallArrivesAsAServerError() throws IOException {
		Channel client = start(Duration.ZERO);
		for (String scenario : new String[] { FAIL_BUSINESS, FAIL_UNEXPECTED }) {
			ResponseStatusException thrown = catchThrowableOfType(ResponseStatusException.class,
					() -> GrpcErrors.call(() -> call(client, scenario)));
			assertThat(thrown.getStatusCode()).as(scenario).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(thrown.getReason()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
			assertThat(thrown.getCause()).as("the original stays reachable for the logs").isNotNull();
		}
	}

	@Test
	void aRefusalStillReachesAGrpcClientAsInternal() throws IOException {
		Channel client = start(Duration.ZERO);
		StatusRuntimeException thrown = catchThrowableOfType(StatusRuntimeException.class,
				() -> call(client, FAIL_BUSINESS));
		assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
		assertThat(thrown.getStatus().getDescription()).isEqualTo("Customer category 7 still has children");
	}

	@Test
	void anUnexpectedFailureTellsTheCallerNothingAboutTheServer() throws IOException {
		Channel client = start(Duration.ZERO);
		StatusRuntimeException thrown = catchThrowableOfType(StatusRuntimeException.class,
				() -> call(client, FAIL_UNEXPECTED));
		assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
		assertThat(String.valueOf(thrown.getStatus().getDescription()))
			.as("the driver's message must not reach the caller")
			.doesNotContain("customer_category");
	}

	@Test
	void noDescriptionEverReachesTheCaller() {
		for (Status status : new Status[] { Status.UNAVAILABLE.withDescription("io exception"),
				Status.UNIMPLEMENTED.withDescription("Method not found: v31.ledger.v1.LedgerAccountService/Get"),
				Status.RESOURCE_EXHAUSTED.withDescription("gRPC message exceeds maximum size 4194304: 9000000") }) {
			ResponseStatusException translated = GrpcErrors.asResponseStatusException(status.asRuntimeException());
			assertThat(translated.getReason()).as(String.valueOf(status.getCode()))
				.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
		}
	}

	@Test
	void aConfiguredHeaderCrossesTheHopToo() throws IOException {
		Channel client = start(Duration.ZERO);
		try (RequestContext.Scope scope = RequestContext.attach(java.util.Map.of(TENANT_HEADER, "acme-bank"))) {
			assertThat(call(client, ECHO_TENANT))
				.as("a header listed for propagation should reach the far side unchanged")
				.isEqualTo("acme-bank");
		}
	}

	@Test
	void everyHeaderTravels() throws IOException {
		Channel client = start(Duration.ZERO);
		try (RequestContext.Scope scope = RequestContext.attach(java.util.Map.of(OTHER_HEADER, "carried"))) {
			assertThat(call(client, ECHO_OTHER)).as("nothing is filtered out, so any header reaches the far side")
				.isEqualTo("carried");
		}
	}

	@Test
	void aForgedHeaderValueIsNotCarried() throws IOException {
		Channel client = start(Duration.ZERO);
		java.util.Map<String, String> values = new java.util.HashMap<>();
		RequestContext.put(values, TENANT_HEADER, "acme\nX-Injected: yes");
		assertThat(values).as("a value that could terminate a header or forge a log line is refused before it travels")
			.doesNotContainKey(TENANT_HEADER);
	}

	@Test
	void aConnectionSpecificHeaderIsNotCarried() {
		java.util.Map<String, String> values = new java.util.HashMap<>();
		for (String name : new String[] { "connection", "proxy-connection", "keep-alive", "transfer-encoding",
				"upgrade" }) {
			RequestContext.put(values, name, "close");
		}
		assertThat(values).as("HTTP/2 rejects the stream outright when one of these arrives as metadata").isEmpty();
	}

	@Test
	void everyCallLeavesWithADeadline() throws IOException {
		Channel client = start(Duration.ofSeconds(30));
		assertThat(call(client, ECHO_DEADLINE))
			.as("a call with no deadline waits on an unhealthy server until the connection breaks")
			.isEqualTo("true");
	}

	@Test
	void withoutTheInterceptorACallHasNoDeadlineAtAll() throws IOException {
		Channel client = start(Duration.ZERO);
		assertThat(call(client, ECHO_DEADLINE)).as("which is what gRPC does out of the box").isEqualTo("false");
	}

	private static String call(Channel channel, String request) {
		return ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, request);
	}

	private Channel start(Duration defaultDeadline) throws IOException {
		String name = InProcessServerBuilder.generateName();
		GrpcExceptionHandlerInterceptor exceptions = new GrpcExceptionHandlerInterceptor(
				new CompositeGrpcExceptionHandler(new RefusalGrpcExceptionHandler(),
						new UnexpectedExceptionGrpcExceptionHandler()));
		this.server = InProcessServerBuilder.forName(name)
			.addService(
					ServerInterceptors.intercept(probeService(), exceptions, new HeaderPropagationServerInterceptor()))
			.build()
			.start();
		this.channel = InProcessChannelBuilder.forName(name).build();
		List<io.grpc.ClientInterceptor> interceptors = defaultDeadline.isZero()
				? List.of(new HeaderPropagationClientInterceptor()) : List.of(new HeaderPropagationClientInterceptor(),
						new DefaultDeadlineSetupClientInterceptor(defaultDeadline));
		return ClientInterceptors.intercept(this.channel, interceptors);
	}

	private static ServerServiceDefinition probeService() {
		return ServerServiceDefinition.builder("v31.Probe")
			.addMethod(METHOD, ServerCalls.asyncUnaryCall((String request, StreamObserver<String> observer) -> {
				switch (request) {
					case ECHO_TENANT -> {
						observer.onNext(String.valueOf(RequestContext.get(TENANT_HEADER)));
						observer.onCompleted();
					}
					case ECHO_OTHER -> {
						observer.onNext(String.valueOf(RequestContext.get(OTHER_HEADER)));
						observer.onCompleted();
					}
					case ECHO_DEADLINE -> {
						observer.onNext(String.valueOf(Context.current().getDeadline() != null));
						observer.onCompleted();
					}
					case FAIL_BUSINESS -> throw new ResponseStatusException(HttpStatus.CONFLICT,
							"Customer category 7 still has children");
					case FAIL_UNEXPECTED ->
						throw new IllegalStateException("ERROR: relation \"customer_category\" does not exist");
					default -> {
						observer.onNext(request);
						observer.onCompleted();
					}
				}
			}))
			.build();
	}

}
