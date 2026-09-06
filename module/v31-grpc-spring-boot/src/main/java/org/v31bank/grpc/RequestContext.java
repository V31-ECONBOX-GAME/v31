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

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.grpc.Context;

/**
 * What travels with a request to the next hop.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public final class RequestContext {

	static final Context.Key<Map<String, String>> CONTEXT_KEY = Context.key("v31-request-context");

	private static final Pattern ACCEPTED_VALUE = Pattern.compile("[\\x20-\\x7E]{1,256}");

	private static final Set<String> CONNECTION_SPECIFIC = Set.of("connection", "proxy-connection", "keep-alive",
			"transfer-encoding", "upgrade");

	private static final ThreadLocal<Map<String, String>> THREAD_LOCAL = new ThreadLocal<>();

	private RequestContext() {
	}

	public static Map<String, String> current() {
		Map<String, String> fromContext = CONTEXT_KEY.get();
		if (fromContext != null) {
			return fromContext;
		}
		Map<String, String> fromThread = THREAD_LOCAL.get();
		return (fromThread != null) ? fromThread : Map.of();
	}

	public static String get(String name) {
		return current().get(name);
	}

	public static Scope attach(Map<String, String> values) {
		Map<String, String> previous = THREAD_LOCAL.get();
		THREAD_LOCAL.set(Map.copyOf(values));
		return () -> {
			if (previous != null) {
				THREAD_LOCAL.set(previous);
			}
			else {
				THREAD_LOCAL.remove();
			}
		};
	}

	static void put(Map<String, String> values, String name, String value) {
		if (value != null && !CONNECTION_SPECIFIC.contains(name) && ACCEPTED_VALUE.matcher(value).matches()) {
			values.put(name, value);
		}
	}

	@FunctionalInterface
	public interface Scope extends AutoCloseable {

		@Override
		void close();

	}

}
