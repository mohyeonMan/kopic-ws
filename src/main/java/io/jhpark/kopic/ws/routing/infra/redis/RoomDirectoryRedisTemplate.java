package io.jhpark.kopic.ws.routing.infra.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jhpark.kopic.ws.config.RedisProperties;
import io.jhpark.kopic.ws.routing.domain.EngineRoute;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RoomDirectoryRedisTemplate {

	// TODO: Remove this fallback once Lobby/GE populate Redis room directory and engine routes.
	private static final String DEFAULT_ENGINE_ID = "ge-local";
	private static final EngineRoute DEFAULT_ENGINE_ROUTE = new EngineRoute(DEFAULT_ENGINE_ID, "localhost", 6565);

	private final StringRedisTemplate stringRedisTemplate;
	private final ObjectMapper objectMapper;
	private final RedisProperties redisProperties;

	public RoomDirectoryRedisTemplate(
		StringRedisTemplate stringRedisTemplate,
		ObjectMapper objectMapper,
		RedisProperties redisProperties
	) {
		this.stringRedisTemplate = stringRedisTemplate;
		this.objectMapper = objectMapper;
		this.redisProperties = redisProperties;
	}

	public Optional<String> getOwnerEngineId(String roomId) {
		String engineId = stringRedisTemplate.opsForValue().get(roomOwnerKey(roomId));
		if (engineId == null || engineId.isBlank()) {
			return Optional.of(DEFAULT_ENGINE_ID);
		}

		return Optional.of(engineId);
	}

	public void putOwnerEngineId(String roomId, String engineId) {
		stringRedisTemplate.opsForValue().set(roomOwnerKey(roomId), engineId);
	}

	public void removeOwnerEngineId(String roomId) {
		stringRedisTemplate.delete(roomOwnerKey(roomId));
	}

	public Optional<EngineRoute> getEngineRoute(String engineId) {
		String payload = stringRedisTemplate.opsForValue().get(engineRouteKey(engineId));
		if (payload == null || payload.isBlank()) {
			if (DEFAULT_ENGINE_ID.equals(engineId)) {
				return Optional.of(DEFAULT_ENGINE_ROUTE);
			}
			return Optional.empty();
		}

		try {
			return Optional.of(objectMapper.readValue(payload, EngineRoute.class));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("failed to deserialize engine route", exception);
		}
	}

	public void putEngineRoute(String engineId, EngineRoute engineRoute) {
		try {
			stringRedisTemplate.opsForValue().set(engineRouteKey(engineId), objectMapper.writeValueAsString(engineRoute));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("failed to serialize engine route", exception);
		}
	}

	public void removeEngineRoute(String engineId) {
		stringRedisTemplate.delete(engineRouteKey(engineId));
	}

	private String roomOwnerKey(String roomId) {
		return redisProperties.roomOwnerKeyPrefix() + roomId;
	}

	private String engineRouteKey(String engineId) {
		return redisProperties.engineRouteKeyPrefix() + engineId;
	}
}
