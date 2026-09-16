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

package org.v31bank.data.valkey.autoconfigure;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import org.v31bank.data.valkey.ValkeyCacheErrorHandler;
import org.v31bank.data.valkey.ValkeyKeys;
import org.v31bank.data.valkey.ValkeyLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link V31ValkeyAutoConfiguration} and
 * {@link V31ValkeyCacheAutoConfiguration}.
 *
 * @author Xander Wang
 */
class V31ValkeyAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(V31ValkeyAutoConfiguration.class, V31ValkeyCacheAutoConfiguration.class))
		.withUserConfiguration(RedisConnectionConfiguration.class);

	@Test
	void registersTheTemplateKeyBuilderAndLock() {
		this.runner.run((context) -> {
			assertThat(context).hasBean("redisTemplate");
			assertThat(context).hasSingleBean(ValkeyKeys.class);
			assertThat(context).hasSingleBean(ValkeyLock.class);
			assertThat(context.getBean(ValkeyKeys.class).of("customer", "7")).isEqualTo("v31:customer:7");
		});
	}

	@Test
	void writesReadableKeysRatherThanSerialisedOnes() {
		this.runner.run((context) -> {
			RedisTemplate<?, ?> template = context.getBean("redisTemplate", RedisTemplate.class);
			assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
			assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
			assertThat(template.getValueSerializer()).isSameAs(context.getBean(RedisSerializer.class));
		});
	}

	@Test
	void takesTheNameFromSpringBootsJdkSerialisingTemplate() {
		this.runner.withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class)).run((context) -> {
			RedisTemplate<?, ?> template = context.getBean("redisTemplate", RedisTemplate.class);
			assertThat(template.getValueSerializer()).isNotInstanceOf(JdkSerializationRedisSerializer.class);
			assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
		});
	}

	@Test
	void appliesTheConfiguredKeyPrefix() {
		this.runner.withPropertyValues("v31.data.valkey.key-prefix=ledger")
			.run((context) -> assertThat(context.getBean(ValkeyKeys.class).of("balance")).isEqualTo("ledger:balance"));
	}

	@Test
	void readsBackAValueAsTheTypeItWasStoredAs() {
		this.runner.run((context) -> {
			RedisSerializer<Object> serializer = serializer(context.getBean(RedisSerializer.class));
			byte[] written = serializer.serialize(new Balance("USD", "1.25"));
			assertThat(serializer.deserialize(written)).isEqualTo(new Balance("USD", "1.25"));
		});
	}

	@Test
	void readsBackInstantsAndAmountsUnchanged() {
		this.runner.run((context) -> {
			RedisSerializer<Object> serializer = serializer(context.getBean(RedisSerializer.class));
			Movement movement = new Movement(Instant.parse("2026-08-01T09:00:00.123456Z"),
					new BigDecimal("0.00000001"));
			Object read = serializer.deserialize(serializer.serialize(movement));
			assertThat(read).isEqualTo(movement);
			assertThat(((Movement) read).amount()).isEqualByComparingTo("0.00000001");
			assertThat(((Movement) read).at()).isEqualTo(Instant.parse("2026-08-01T09:00:00.123456Z"));
		});
	}

	@Test
	void registersAnErrorHandlerThatKeepsAnOutageOutOfTheCallPath() {
		this.runner.run((context) -> {
			assertThat(context).hasSingleBean(CachingConfigurer.class);
			CacheErrorHandler handler = context.getBean(CachingConfigurer.class).errorHandler();
			assertThat(handler).isInstanceOf(ValkeyCacheErrorHandler.class);
			assertThatNoException().isThrownBy(() -> handler.handleCacheGetError(new QueryTimeoutException("down"),
					new ConcurrentMapCache("customers"), "7"));
		});
	}

	@Test
	void backsOffFromAnApplicationSuppliedTemplate() {
		this.runner.withUserConfiguration(CustomTemplateConfiguration.class)
			.run((context) -> assertThat(context.getBean("redisTemplate"))
				.isSameAs(context.getBean(CustomTemplateConfiguration.class)
					.redisTemplate(context.getBean(RedisConnectionFactory.class))));
	}

	@Test
	void givesEveryCacheAPrefixAndAnExpiry() {
		this.runner.run((context) -> {
			RedisCacheConfiguration configuration = context.getBean(RedisCacheConfiguration.class);
			assertThat(configuration.getKeyPrefixFor("customers")).startsWith("v31:cache:");
			assertThat(configuration.getTtlFunction().getTimeToLive("7", "Ada")).isBetween(Duration.ofMinutes(10),
					Duration.ofMinutes(11));
		});
	}

	@Test
	void appliesTheConfiguredDefaultExpiry() {
		this.runner.withPropertyValues("v31.data.valkey.cache.default-ttl=30s", "v31.data.valkey.cache.ttl-jitter=0")
			.run((context) -> assertThat(
					context.getBean(RedisCacheConfiguration.class).getTtlFunction().getTimeToLive("7", "Ada"))
				.isEqualTo(Duration.ofSeconds(30)));
	}

	@Test
	void spreadsExpiryRatherThanLettingAWholeCacheFallDueAtOnce() {
		this.runner.run((context) -> {
			RedisCacheConfiguration configuration = context.getBean(RedisCacheConfiguration.class);
			assertThat(IntStream.range(0, 200)
				.mapToObj((index) -> configuration.getTtlFunction().getTimeToLive("7", "Ada"))
				.collect(Collectors.toSet())).hasSizeGreaterThan(50);
		});
	}

	@Test
	void expiresAMissSoonerThanAValue() {
		this.runner.withPropertyValues("v31.data.valkey.cache.ttl-jitter=0").run((context) -> {
			RedisCacheConfiguration configuration = context.getBean(RedisCacheConfiguration.class);
			assertThat(configuration.getTtlFunction().getTimeToLive("7", null)).isEqualTo(Duration.ofMinutes(1));
			assertThat(configuration.getTtlFunction().getTimeToLive("7", "Ada")).isEqualTo(Duration.ofMinutes(10));
		});
	}

	@Test
	void appliesAPerCacheExpiry() {
		V31ValkeyProperties properties = new V31ValkeyProperties();
		properties.getCache().setTtls(Map.of("rates", Duration.ofSeconds(30)));
		properties.getCache().setTtlJitter(0);

		RedisCacheManager cacheManager = cacheManager(properties, mock(RedisConnectionFactory.class));

		assertThat(cacheManager.getCacheConfigurations().get("rates").getTtlFunction().getTimeToLive("USD", "1.25"))
			.isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void clearsACacheByScanningRatherThanByAskingForEveryKeyAtOnce() {
		RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
		RedisConnection connection = mock(RedisConnection.class);
		Cursor<byte[]> cursor = mock();
		given(connection.keyCommands()).willReturn(keyCommands);
		given(keyCommands.scan(any(ScanOptions.class))).willReturn(cursor);
		RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
		given(connectionFactory.getConnection()).willReturn(connection);
		V31ValkeyProperties properties = new V31ValkeyProperties();
		properties.getCache().setClearBatchSize(64);

		cacheManager(properties, connectionFactory).getCache("customers").invalidate();

		ArgumentCaptor<ScanOptions> options = ArgumentCaptor.forClass(ScanOptions.class);
		then(keyCommands).should().scan(options.capture());
		then(keyCommands).should(never()).keys(any());
		assertThat(options.getValue().getCount()).isEqualTo(64L);
	}

	private static RedisCacheManager cacheManager(V31ValkeyProperties properties,
			RedisConnectionFactory connectionFactory) {
		V31ValkeyCacheAutoConfiguration autoConfiguration = new V31ValkeyCacheAutoConfiguration();
		RedisCacheConfiguration configuration = autoConfiguration.valkeyCacheConfiguration(properties,
				RedisSerializer.java());
		RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(connectionFactory)
			.cacheDefaults(configuration);
		autoConfiguration.valkeyCacheManagerCustomizer(properties, configuration, connectionFactory).customize(builder);
		RedisCacheManager cacheManager = builder.build();
		cacheManager.afterPropertiesSet();
		return cacheManager;
	}

	@SuppressWarnings("unchecked")
	private static RedisSerializer<Object> serializer(RedisSerializer<?> serializer) {
		return (RedisSerializer<Object>) serializer;
	}

	public record Balance(String asset, String amount) {

	}

	public record Movement(Instant at, BigDecimal amount) {

	}

	@Configuration
	static class RedisConnectionConfiguration {

		@Bean
		RedisConnectionFactory redisConnectionFactory() {
			return mock(RedisConnectionFactory.class);
		}

		@Bean
		StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
			return new StringRedisTemplate(connectionFactory);
		}

	}

	@Configuration
	static class CustomTemplateConfiguration {

		private final RedisTemplate<String, Object> template = new RedisTemplate<>();

		@Bean
		RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
			this.template.setConnectionFactory(connectionFactory);
			return this.template;
		}

	}

}
