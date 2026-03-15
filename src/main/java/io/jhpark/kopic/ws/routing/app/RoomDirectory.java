package io.jhpark.kopic.ws.routing.app;

import io.jhpark.kopic.ws.routing.domain.EngineRoute;
import java.util.Optional;

public interface RoomDirectory {

	Optional<EngineRoute> findOwner(String roomId);

	void invalidateOwnerCache(String roomId);

	void invalidateRouteCache(String engineId);

	void putOwner(String roomId, String engineId);

	void putRoute(String engineId, EngineRoute engineRoute);

	void removeOwner(String roomId);

	void removeRoute(String engineId);
}
