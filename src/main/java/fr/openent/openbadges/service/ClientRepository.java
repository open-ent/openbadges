package fr.openent.openbadges.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Clients OAuth de l'API Open Badges ({@code openbadges.client}). */
public class ClientRepository {

	private static final String PUBLIC_COLUMNS =
			"id, name, scopes, enabled, created_by, created_at, updated_at";
	private static final long CACHE_MILLIS = 30_000L;

	/** Cache court des clients actifs : chaque appel d'API relit leurs scopes sans aller en base. */
	private final Map<String, Cached> cache = new ConcurrentHashMap<String, Cached>();

	public Future<JsonObject> findEnabled(String id) {
		final Cached cached = cache.get(id);
		if (cached != null && cached.expires > System.currentTimeMillis()) {
			return Future.succeededFuture(cached.row);
		}
		return Db.sqlOne("SELECT " + PUBLIC_COLUMNS + ", secret_hash FROM openbadges.client "
				+ "WHERE id = ? AND enabled", new JsonArray().add(id)).map(row -> {
					if (row != null) {
						cache.put(id, new Cached(row, System.currentTimeMillis() + CACHE_MILLIS));
					}
					return row;
				});
	}

	public Future<JsonArray> list() {
		return Db.sql("SELECT " + PUBLIC_COLUMNS + " FROM openbadges.client ORDER BY name",
				new JsonArray());
	}

	public Future<JsonObject> create(String id, JsonObject client, String secretHash, String userId) {
		return Db.sqlOne("INSERT INTO openbadges.client (id, name, secret_hash, scopes, enabled, "
				+ "created_by) VALUES (?, ?, ?, ?::jsonb, ?, ?) RETURNING " + PUBLIC_COLUMNS,
				new JsonArray().add(id).add(client.getString("name")).add(secretHash)
						.add(client.getJsonArray("scopes", new JsonArray()).encode())
						.add(client.getBoolean("enabled", true)).add(userId));
	}

	public Future<JsonArray> delete(String id) {
		cache.remove(id);
		return Db.sql("DELETE FROM openbadges.client WHERE id = ? RETURNING id",
				new JsonArray().add(id));
	}

	private static final class Cached {
		final JsonObject row;
		final long expires;

		Cached(JsonObject row, long expires) {
			this.row = row;
			this.expires = expires;
		}
	}
}
