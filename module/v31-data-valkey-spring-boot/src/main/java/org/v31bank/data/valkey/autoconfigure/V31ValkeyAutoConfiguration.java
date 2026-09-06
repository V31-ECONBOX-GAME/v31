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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import org.v31bank.data.valkey.ValkeyKeys;
import org.v31bank.data.valkey.ValkeyLock;

/**
 * {@link AutoConfiguration Auto-configuration} for V31 Data Valkey.
 *
 * @author Xander Wang
 * @since 0.2.0
 */
@AutoConfiguration(before = DataRedisAutoConfiguration.class)
@ConditionalOnClass({ RedisConnectionFactory.class, GenericJacksonJsonRedisSerializer.class })
@EnableConfigurationProperties(V31ValkeyProperties.class)
public class V31ValkeyAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(name = "valkeyValueSerializer")
	public RedisSerializer<Object> valkeyValueSerializer() {
		return GenericJacksonJsonRedisSerializer.builder()
			.enableUnsafeDefaultTyping()
			.enableSpringCacheNullValueSupport()
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(name = "redisTemplate")
	public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
			RedisSerializer<Object> valkeyValueSerializer) {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(RedisSerializer.string());
		template.setHashKeySerializer(RedisSerializer.string());
		template.setValueSerializer(valkeyValueSerializer);
		template.setHashValueSerializer(valkeyValueSerializer);
		return template;
	}

	@Bean
	@ConditionalOnMissingBean
	public ValkeyKeys valkeyKeys(V31ValkeyProperties properties) {
		return new ValkeyKeys(properties.getKeyPrefix());
	}

	@Bean
	@ConditionalOnMissingBean
	public ValkeyLock valkeyLock(StringRedisTemplate stringRedisTemplate) {
		return new ValkeyLock(stringRedisTemplate);
	}

}
