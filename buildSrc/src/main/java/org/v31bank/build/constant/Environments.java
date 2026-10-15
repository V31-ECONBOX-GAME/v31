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

package org.v31bank.build.constant;

/**
 * Environment variables.
 *
 * @author Xander Wang
 */
public final class Environments {

	public static final String DOCKER_HOST = "DOCKER_HOST";

	public static final String TESTCONTAINERS_HOST_OVERRIDE = "TESTCONTAINERS_HOST_OVERRIDE";

	public static final String TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE = "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE";

	private Environments() {
	}

}
