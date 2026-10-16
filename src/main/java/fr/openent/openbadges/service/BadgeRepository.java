package fr.openent.openbadges.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Lecture et écriture des badges et des attestations (schéma {@code openbadges}). */
public class BadgeRepository {

	private static final String ISSUER_COLUMNS =
			"id, name, url, email, description, image_url, structure_id, created_by, created_at";
	private static final String ACHIEVEMENT_COLUMNS = "id, issuer_id, name, description, "
			+ "achievement_type, criteria_narrative, criteria_url, image_url, tags, alignments, "
			+ "created_by, created_at";
	private static final String CREDENTIAL_COLUMNS = "id, achievement_id, subject_user_id, name, "
			+ "narrative, identity_type, identity_hash, identity_salt, valid_from, valid_until, "
			+ "jws, revoked, revoked_reason, revoked_at, created_by, created_at";

	// ------------------------------------------------------------------ émetteurs

	public Future<JsonArray> issuers() {
		return Db.sql("SELECT " + ISSUER_COLUMNS + " FROM openbadges.issuer ORDER BY name",
				new JsonArray());
	}

	public Future<JsonObject> issuer(String id) {
		return Db.sqlOne("SELECT " + ISSUER_COLUMNS + " FROM openbadges.issuer WHERE id = ?",
				new JsonArray().add(id));
	}

	public Future<JsonObject> createIssuer(String id, JsonObject issuer, String userId) {
		return Db.sqlOne("INSERT INTO openbadges.issuer (id, name, url, email, description, "
				+ "image_url, structure_id, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING "
				+ ISSUER_COLUMNS,
				new JsonArray().add(id).add(issuer.getString("name")).add(issuer.getValue("url"))
						.add(issuer.getValue("email")).add(issuer.getValue("description"))
						.add(issuer.getValue("imageUrl")).add(issuer.getValue("structureId"))
						.add(userId));
	}

	// ------------------------------------------------------------------ badges

	public Future<JsonArray> achievements() {
		return Db.sql("SELECT " + ACHIEVEMENT_COLUMNS + " FROM openbadges.achievement "
				+ "ORDER BY name", new JsonArray());
	}

	public Future<JsonObject> achievement(String id) {
		return Db.sqlOne("SELECT " + ACHIEVEMENT_COLUMNS + " FROM openbadges.achievement "
				+ "WHERE id = ?", new JsonArray().add(id));
	}

	public Future<JsonObject> createAchievement(String id, JsonObject achievement, String userId) {
		return Db.sqlOne("INSERT INTO openbadges.achievement (id, issuer_id, name, description, "
				+ "achievement_type, criteria_narrative, criteria_url, image_url, tags, alignments, "
				+ "created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) RETURNING "
				+ ACHIEVEMENT_COLUMNS,
				new JsonArray().add(id).add(achievement.getString("issuerId"))
						.add(achievement.getString("name")).add(achievement.getString("description"))
						.add(achievement.getString("achievementType", "Badge"))
						.add(achievement.getValue("criteriaNarrative"))
						.add(achievement.getValue("criteriaUrl"))
						.add(achievement.getValue("imageUrl"))
						.add(achievement.getJsonArray("tags", new JsonArray()).encode())
						.add(achievement.getJsonArray("alignments", new JsonArray()).encode())
						.add(userId));
	}

	public Future<JsonArray> deleteAchievement(String id) {
		return Db.sql("DELETE FROM openbadges.achievement WHERE id = ? RETURNING id",
				new JsonArray().add(id));
	}

	// ------------------------------------------------------------------ attestations

	public Future<JsonObject> credential(String id) {
		return Db.sqlOne("SELECT " + CREDENTIAL_COLUMNS + " FROM openbadges.credential "
				+ "WHERE id = ?", new JsonArray().add(id));
	}

	/** Les attestations d'une personne, la plus récente d'abord. */
	public Future<JsonArray> credentialsOf(String userId, int limit, int offset) {
		return Db.sql("SELECT " + CREDENTIAL_COLUMNS + " FROM openbadges.credential "
				+ "WHERE subject_user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
				new JsonArray().add(userId).add(limit).add(offset));
	}

	public Future<JsonObject> countOf(String userId) {
		return Db.sqlOne("SELECT COUNT(*)::int AS total FROM openbadges.credential "
				+ "WHERE subject_user_id = ?", new JsonArray().add(userId));
	}

	public Future<JsonObject> insertCredential(JsonObject credential) {
		return Db.sqlOne("INSERT INTO openbadges.credential (id, achievement_id, subject_user_id, "
				+ "name, narrative, identity_type, identity_hash, identity_salt, valid_from, "
				+ "valid_until, jws, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamp, "
				+ "?::timestamp, ?, ?) RETURNING " + CREDENTIAL_COLUMNS,
				new JsonArray().add(credential.getString("id"))
						.add(credential.getString("achievement_id"))
						.add(credential.getString("subject_user_id"))
						.add(credential.getValue("name"))
						.add(credential.getValue("narrative"))
						.add(credential.getString("identity_type", "emailAddress"))
						.add(credential.getValue("identity_hash"))
						.add(credential.getValue("identity_salt"))
						.add(credential.getValue("valid_from"))
						.add(credential.getValue("valid_until"))
						.add(credential.getString("jws"))
						.add(credential.getValue("created_by")));
	}

	/**
	 * Révoque une attestation.
	 *
	 * <p>Le JWS n'est pas effacé : il reste la preuve que l'attestation a bien été émise. C'est
	 * la liste de révocation, et elle seule, qui dit qu'elle ne vaut plus.</p>
	 */
	public Future<JsonObject> revoke(String id, String reason) {
		return Db.sqlOne("UPDATE openbadges.credential SET revoked = TRUE, revoked_reason = ?, "
				+ "revoked_at = NOW() WHERE id = ? RETURNING " + CREDENTIAL_COLUMNS,
				new JsonArray().add(reason).add(id));
	}

	/** Les attestations révoquées d'un émetteur, pour sa liste de révocation. */
	public Future<JsonArray> revokedOf(String issuerId) {
		return Db.sql("SELECT c.id, c.revoked_reason FROM openbadges.credential c "
				+ "JOIN openbadges.achievement a ON a.id = c.achievement_id "
				+ "WHERE a.issuer_id = ? AND c.revoked ORDER BY c.revoked_at DESC",
				new JsonArray().add(issuerId));
	}

	public static List<JsonObject> rows(JsonArray array) {
		final List<JsonObject> list = new ArrayList<JsonObject>();
		for (int i = 0; i < array.size(); i++) {
			list.add(array.getJsonObject(i));
		}
		return list;
	}
}
