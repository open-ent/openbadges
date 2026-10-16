package fr.openent.openbadges.service;

import fr.wseduc.webutils.Either;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;
import org.entcore.common.sql.Sql;
import org.entcore.common.sql.SqlResult;

/** Accès PostgreSQL (mod-postgresql) et Neo4j sous forme de Future. */
public final class Db {

	private static final Logger log = LoggerFactory.getLogger(Db.class);

	private Db() {
	}

	/**
	 * Lecture dans le schéma d'un autre module (vie scolaire, compétences, médiacentre) : si ce
	 * module n'est pas installé, la requête échoue et on renvoie une liste vide plutôt qu'une erreur.
	 */
	public static Future<JsonArray> sqlOptional(String query, JsonArray params) {
		return sql(query, params).recover(e -> {
			log.warn("[OpenBadges] Optional query failed (module not installed?): " + e.getMessage());
			return Future.succeededFuture(new JsonArray());
		});
	}

	/** Transaction : liste d'instructions préparées {statement, values}. */
	public static Future<JsonArray> transaction(JsonArray statements) {
		final Promise<JsonArray> promise = Promise.promise();
		Sql.getInstance().transaction(statements, message -> {
			final JsonObject body = message.body();
			if ("ok".equals(body.getString("status"))) {
				promise.complete(body.getJsonArray("results", new JsonArray()));
			} else {
				promise.fail(body.getString("message", "sql.transaction.error"));
			}
		});
		return promise.future();
	}

	public static JsonObject statement(String query, JsonArray values) {
		return new JsonObject().put("statement", query).put("values", values).put("action", "prepared");
	}

	/** « ?,?,? » pour une clause IN, ou « NULL » si la liste est vide (la clause ne retient alors rien). */
	public static String placeholders(int count) {
		if (count <= 0) {
			return "NULL";
		}
		final StringBuilder sb = new StringBuilder("?");
		for (int i = 1; i < count; i++) {
			sb.append(",?");
		}
		return sb.toString();
	}

	public static Future<JsonArray> sql(String query, JsonArray params) {
		final Promise<JsonArray> promise = Promise.promise();
		Sql.getInstance().prepared(query, params, SqlResult.validResultHandler(either -> complete(promise, either)));
		return promise.future();
	}

	/** Première ligne ou null. */
	public static Future<JsonObject> sqlOne(String query, JsonArray params) {
		return sql(query, params).map(rows -> rows.isEmpty() ? null : rows.getJsonObject(0));
	}

	public static Future<JsonArray> neo4j(String query, JsonObject params) {
		final Promise<JsonArray> promise = Promise.promise();
		Neo4j.getInstance().execute(query, params, Neo4jResult.validResultHandler(either -> complete(promise, either)));
		return promise.future();
	}

	private static void complete(Promise<JsonArray> promise, Either<String, JsonArray> either) {
		if (either.isRight()) {
			promise.complete(either.right().getValue());
		} else {
			promise.fail(either.left().getValue());
		}
	}

	/** Les colonnes JSONB peuvent être renvoyées sous forme de chaîne par mod-postgresql. */
	public static JsonArray jsonArray(JsonObject row, String key) {
		final Object value = row.getValue(key);
		if (value instanceof JsonArray) {
			return (JsonArray) value;
		}
		return value instanceof String ? new JsonArray((String) value) : new JsonArray();
	}

	public static JsonObject jsonObject(JsonObject row, String key) {
		final Object value = row.getValue(key);
		if (value instanceof JsonObject) {
			return (JsonObject) value;
		}
		return value instanceof String ? new JsonObject((String) value) : null;
	}

	public static String encode(Object json) {
		if (json instanceof JsonObject) {
			return ((JsonObject) json).encode();
		}
		if (json instanceof JsonArray) {
			return ((JsonArray) json).encode();
		}
		return null;
	}
}
