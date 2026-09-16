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

package org.v31bank.data.valkey;

import java.util.Objects;
import java.util.StringJoiner;

/**
 * Builds this application's keys under one prefix.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public class ValkeyKeys {

	private static final String SEPARATOR = ":";

	private final String prefix;

	public ValkeyKeys(String prefix) {
		this.prefix = requireSegment(prefix, "prefix");
	}

	public String of(String... segments) {
		if (segments.length == 0) {
			throw new IllegalArgumentException("A key needs at least one segment beyond the prefix");
		}
		StringJoiner key = new StringJoiner(SEPARATOR);
		key.add(this.prefix);
		for (String segment : segments) {
			key.add(requireSegment(segment, "segment"));
		}
		return key.toString();
	}

	private static String requireSegment(String segment, String name) {
		Objects.requireNonNull(segment, name + " must not be null");
		if (segment.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		if (segment.contains(SEPARATOR)) {
			throw new IllegalArgumentException(
					"A key " + name + " must not contain '" + SEPARATOR + "', but was '" + segment + "'");
		}
		return segment;
	}

}
