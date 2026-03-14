package io.jhpark.kopic.ws.routing.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jhpark.kopic.ws.routing.app.RoomDirectory;
import io.jhpark.kopic.ws.routing.domain.EngineRoute;
import io.jhpark.kopic.ws.routing.infra.redis.RoomDirectoryRedisTemplate;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CaffeineRoomDirectory implements RoomDirectory {

	private static final Duration ROOM_OWNER_TTL = Duration.ofMinutes(15);
	private static final Duration ENGINE_ROUTE_TTL = Duration.ofHours(24);

	private final Cache<String, Optional<String>> roomOwners = Caffeine.newBuilder()
		.maximumSize(100_000)
		.expireAfterAccess(ROOM_OWNER_TTL)
		.build();
	private final Cache<String, Optional<EngineRoute>> engineRoutes = Caffeine.newBuilder()
		.maximumSize(100_000)
		.expireAfterAccess(ENGINE_ROUTE_TTL)
		.build();
	private final RoomDirectoryRedisTemplate roomDirectoryRedisTemplate;

	public CaffeineRoomDirectory(
		RoomDirectoryRedisTemplate roomDirectoryRedisTemplate
	) {
		this.roomDirectoryRedisTemplate = roomDirectoryRedisTemplate;
	}

	@Override
	public Optional<EngineRoute> findOwner(String roomId) {
		if (roomId == null || roomId.isBlank()) {
			return Optional.empty();
		}

		Optional<EngineRoute> route = roomOwners.get(roomId, this::loadOwnerEngineId)
			.flatMap(engineId -> engineRoutes.get(engineId, this::loadEngineRoute));
		log.info("room directory findOwner roomId={} hit={}", roomId, route.isPresent());
		return route;
	}

	@Override
	public void putOwner(String roomId, String engineId) {
		if (roomId == null || roomId.isBlank() || engineId == null || engineId.isBlank()) {
			return;
		}

		roomDirectoryRedisTemplate.putOwnerEngineId(roomId, engineId);
		roomOwners.put(roomId, Optional.of(engineId));
	}

	@Override
	public void putRoute(String engineId, EngineRoute engineRoute) {
		if (engineId == null || engineId.isBlank() || engineRoute == null) {
			return;
		}

		roomDirectoryRedisTemplate.putEngineRoute(engineId, engineRoute);
		engineRoutes.put(engineId, Optional.of(engineRoute));
	}

	@Override
	public void removeOwner(String roomId) {
		if (roomId == null || roomId.isBlank()) {
			return;
		}

		roomDirectoryRedisTemplate.removeOwnerEngineId(roomId);
		roomOwners.invalidate(roomId);
	}

	@Override
	public void removeRoute(String engineId) {
		if (engineId == null || engineId.isBlank()) {
			return;
		}

		roomDirectoryRedisTemplate.removeEngineRoute(engineId);
		engineRoutes.invalidate(engineId);
	}

	private Optional<String> loadOwnerEngineId(String roomId) {
		Optional<String> engineId = roomDirectoryRedisTemplate.getOwnerEngineId(roomId);
		log.info("room directory load owner roomId={} engineId={}", roomId, engineId.orElse(null));
		return engineId;
	}

	private Optional<EngineRoute> loadEngineRoute(String engineId) {
		Optional<EngineRoute> route = roomDirectoryRedisTemplate.getEngineRoute(engineId);
		log.info("room directory load route engineId={} hit={}", engineId, route.isPresent());
		return route;
	}
}
