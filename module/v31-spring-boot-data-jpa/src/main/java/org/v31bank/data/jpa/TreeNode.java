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
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;

/**
 * A tree node.
 *
 * @param <T> the model type
 * @author Xander Wang
 * @since 0.2.0
 */
@MappedSuperclass
public abstract class TreeNode<T extends TreeNode<T>> extends Audited {

	@Column(name = "parent_id")
	private UUID parentId;

	@Column(name = "sort_order")
	private Integer sortOrder;

	@Transient
	private List<T> children = new ArrayList<>();

	public UUID getParentId() {
		return this.parentId;
	}

	public void setParentId(UUID parentId) {
		this.parentId = parentId;
	}

	public Integer getSortOrder() {
		return this.sortOrder;
	}

	public void setSortOrder(Integer sortOrder) {
		this.sortOrder = sortOrder;
	}

	public List<T> getChildren() {
		return this.children;
	}

	public void setChildren(List<T> children) {
		this.children = children;
	}

}
