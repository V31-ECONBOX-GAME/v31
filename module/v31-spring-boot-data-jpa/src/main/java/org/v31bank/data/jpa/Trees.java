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

package org.v31bank.data.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Flat nodes into trees.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
public final class Trees {

	private static final Comparator<TreeNode<?>> SIBLING_ORDER = Comparator
		.comparing((TreeNode<?> node) -> node.getSortOrder(), Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(Audited::getId, Comparator.nullsLast(Comparator.naturalOrder()));

	private Trees() {
	}

	public static <T extends TreeNode<T>> List<T> build(Collection<T> nodes) {
		Map<UUID, T> byId = new HashMap<>();
		for (T node : nodes) {
			node.setChildren(new ArrayList<>());
			if (node.getId() != null) {
				byId.put(node.getId(), node);
			}
		}
		List<T> roots = new ArrayList<>();
		for (T node : nodes) {
			T parent = (node.getParentId() != null) ? byId.get(node.getParentId()) : null;
			if (parent != null && parent != node) {
				parent.getChildren().add(node);
			}
			else {
				roots.add(node);
			}
		}
		roots.sort(SIBLING_ORDER);
		for (T node : byId.values()) {
			node.getChildren().sort(SIBLING_ORDER);
		}
		return roots;
	}

}
