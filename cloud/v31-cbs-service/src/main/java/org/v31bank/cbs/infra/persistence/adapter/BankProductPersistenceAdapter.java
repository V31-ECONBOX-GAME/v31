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

package org.v31bank.cbs.infra.persistence.adapter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Repository;

import org.v31bank.cbs.application.dto.BankProductPageQuery;
import org.v31bank.cbs.application.port.out.BankProductPort;
import org.v31bank.cbs.domain.constant.BankProductCategory;
import org.v31bank.cbs.domain.constant.BankProductStatus;
import org.v31bank.cbs.domain.model.BankProduct;
import org.v31bank.cbs.infra.persistence.valkey.BankProductValkeyKeys;
import org.v31bank.core.HttpResponse;
import org.v31bank.core.Uuids;

/**
 * {@link BankProductPort} adapter backed by Valkey.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@Repository
public class BankProductPersistenceAdapter implements BankProductPort {

	private static final Duration INTERSECTION_TTL = Duration.ofSeconds(30);

	private final StringRedisTemplate valkey;

	private final RedisSerializer<Object> serializer;

	private final BankProductValkeyKeys keys;

	public BankProductPersistenceAdapter(StringRedisTemplate valkey, RedisSerializer<Object> valkeyValueSerializer,
			BankProductValkeyKeys keys) {
		this.valkey = valkey;
		this.serializer = valkeyValueSerializer;
		this.keys = keys;
	}

	@Override
	public boolean claimCode(String code, UUID id) {
		return Boolean.TRUE.equals(this.valkey.opsForValue().setIfAbsent(this.keys.code(code), id.toString()));
	}

	@Override
	public void releaseCode(String code) {
		this.valkey.delete(this.keys.code(code));
	}

	@Override
	public BankProduct save(BankProduct product) {
		String member = product.getId().toString();
		String json = toJson(product);
		double score = product.getCreatedDate().toEpochMilli();
		BankProductValkeyKeys names = this.keys;
		this.valkey.execute(new SessionCallback<Object>() {
			@Override
			public <K, V> Object execute(RedisOperations<K, V> operations) {
				@SuppressWarnings("unchecked")
				RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
				ops.multi();
				ops.opsForValue().set(names.product(product.getId()), json);
				ops.opsForZSet().add(names.index(), member, score);
				for (BankProductCategory category : BankProductCategory.values()) {
					ops.opsForZSet().remove(names.categoryIndex(category), member);
				}
				for (BankProductStatus status : BankProductStatus.values()) {
					ops.opsForZSet().remove(names.statusIndex(status), member);
				}
				ops.opsForZSet().add(names.categoryIndex(product.getCategory()), member, score);
				ops.opsForZSet().add(names.statusIndex(product.getStatus()), member, score);
				return ops.exec();
			}
		});
		return product;
	}

	@Override
	public Optional<BankProduct> findById(UUID id) {
		return Optional.ofNullable(this.valkey.opsForValue().get(this.keys.product(id))).map(this::fromJson);
	}

	@Override
	public HttpResponse<List<BankProduct>> findPage(BankProductPageQuery query) {
		int number = query.normalizedPageNumber();
		int size = query.normalizedPageSize();
		String index = indexFor(query);
		try {
			Long total = this.valkey.opsForZSet().zCard(index);
			if (total == null || total == 0) {
				return HttpResponse.page(List.of(), 0);
			}
			long offset = (long) (number - 1) * size;
			Set<String> members = this.valkey.opsForZSet().reverseRange(index, offset, offset + size - 1L);
			return HttpResponse.page(read(members), total);
		}
		finally {
			if (isIntersection(query)) {
				this.valkey.delete(index);
			}
		}
	}

	@Override
	public void delete(BankProduct product) {
		String member = product.getId().toString();
		BankProductValkeyKeys names = this.keys;
		this.valkey.execute(new SessionCallback<Object>() {
			@Override
			public <K, V> Object execute(RedisOperations<K, V> operations) {
				@SuppressWarnings("unchecked")
				RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
				ops.multi();
				ops.delete(names.product(product.getId()));
				ops.opsForZSet().remove(names.index(), member);
				for (BankProductCategory category : BankProductCategory.values()) {
					ops.opsForZSet().remove(names.categoryIndex(category), member);
				}
				for (BankProductStatus status : BankProductStatus.values()) {
					ops.opsForZSet().remove(names.statusIndex(status), member);
				}
				return ops.exec();
			}
		});
	}

	private String indexFor(BankProductPageQuery query) {
		if (isIntersection(query)) {
			String destination = this.keys.intersection(Uuids.timeOrdered().toString());
			this.valkey.opsForZSet()
				.intersectAndStore(this.keys.categoryIndex(query.getCategory()),
						this.keys.statusIndex(query.getStatus()), destination);
			this.valkey.expire(destination, INTERSECTION_TTL);
			return destination;
		}
		if (query.getCategory() != null) {
			return this.keys.categoryIndex(query.getCategory());
		}
		if (query.getStatus() != null) {
			return this.keys.statusIndex(query.getStatus());
		}
		return this.keys.index();
	}

	private static boolean isIntersection(BankProductPageQuery query) {
		return query.getCategory() != null && query.getStatus() != null;
	}

	private List<BankProduct> read(Set<String> members) {
		if (members == null || members.isEmpty()) {
			return List.of();
		}
		List<String> productKeys = members.stream().map((id) -> this.keys.product(UUID.fromString(id))).toList();
		List<String> values = this.valkey.opsForValue().multiGet(productKeys);
		if (values == null) {
			return List.of();
		}
		return values.stream().filter(Objects::nonNull).map(this::fromJson).toList();
	}

	private String toJson(BankProduct product) {
		return new String(Objects.requireNonNull(this.serializer.serialize(product)), StandardCharsets.UTF_8);
	}

	private BankProduct fromJson(String json) {
		return (BankProduct) this.serializer.deserialize(json.getBytes(StandardCharsets.UTF_8));
	}

}
