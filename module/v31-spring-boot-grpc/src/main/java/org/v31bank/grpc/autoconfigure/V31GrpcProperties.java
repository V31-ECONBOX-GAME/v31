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

package org.v31bank.grpc.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for V31 gRPC.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@ConfigurationProperties("v31.grpc")
public class V31GrpcProperties {

	private final Propagation propagation = new Propagation();

	private final Server server = new Server();

	private final Client client = new Client();

	public Propagation getPropagation() {
		return this.propagation;
	}

	public Server getServer() {
		return this.server;
	}

	public Client getClient() {
		return this.client;
	}

	public static class Propagation {

		/**
		 * Whether to carry a request's context onward.
		 */
		private boolean enabled = true;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

	public static class Server {

		private final ExceptionHandling exceptionHandling = new ExceptionHandling();

		public ExceptionHandling getExceptionHandling() {
			return this.exceptionHandling;
		}

		public static class ExceptionHandling {

			/**
			 * Whether to keep an unexpected failure's message off the wire.
			 */
			private boolean enabled = true;

			public boolean isEnabled() {
				return this.enabled;
			}

			public void setEnabled(boolean enabled) {
				this.enabled = enabled;
			}

		}

	}

	public static class Client {

		private final Deadline deadline = new Deadline();

		public Deadline getDeadline() {
			return this.deadline;
		}

	}

	public static class Deadline {

		/**
		 * Whether a call that set no deadline is given one.
		 */
		private boolean enabled = true;

		/**
		 * How long a call may run before it is given up on. Must be positive.
		 */
		private Duration duration = Duration.ofSeconds(5);

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public Duration getDuration() {
			return this.duration;
		}

		public void setDuration(Duration duration) {
			this.duration = duration;
		}

	}

}
