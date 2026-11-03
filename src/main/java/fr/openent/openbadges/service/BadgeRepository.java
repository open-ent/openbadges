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
	private static final String CREDENTIAL_COLUMNS = "id, achievement_id, issuer_id, "
			+ "subject_user_id, name, narrative, identity_type, identity_hash, identity_salt, "
			+ "valid_from, valid_until, jws, revoked, revoked_reason, revoked_at, created_by, "
			+ "created_at";

	// ------------------------------------------------------------------ émetteurs

	public Future<JsonArray> issuers() {
		return Db.sql("SELECT " + ISSUER_COLUMNS + " FROM openbadges.issuer ORDER BY name",
				new JsonArray());
	}

	public Future<JsonObject> issuer(String id) {
		return Db.sqlOne("SELECT " + ISSUER_COLUMNS + " FROM openbadges.issuer WHERE id = ?",
				new JsonArray().add(id));
	}

	/** L'émetteur d'un établissement, ou {@code null} s'il n'a pas encore été créé. */
	public Future<JsonObject> issuerOfStructure(String structureId) {
		return Db.sqlOne("SELECT " + ISSUER_COLUMNS + " FROM openbadges.issuer "
				+ "WHERE structure_id = ?", new JsonArray().add(structureId));
	}

	/**
	 * Crée l'émetteur d'un établissement, ou met son nom à jour s'il existe déjà.
	 *
	 * <p>Idempotent, et c'est ce qui compte : la synchronisation peut être relancée autant de
	 * fois qu'on veut, et un établissement renommé dans l'annuaire voit son émetteur suivre —
	 * sans que son identifiant change, puisque c'est lui qui est inscrit dans les attestations
	 * déjà délivrées.</p>
	 */
	public Future<JsonObject> upsertIssuerForStructure(String id, String structureId, String name,
													   String url, String userId) {
		return Db.sqlOne("INSERT INTO openbadges.issuer (id, name, url, structure_id, created_by) "
				+ "VALUES (?, ?, ?, ?, ?) "
				+ "ON CONFLICT (structure_id) WHERE structure_id IS NOT NULL "
				+ "DO UPDATE SET name = EXCLUDED.name "
				+ "RETURNING " + ISSUER_COLUMNS,
				new JsonArray().add(id).add(name).add(url).add(structureId).add(userId));
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

	// ------------------------------------------------------------------ catalogue des programmes

	/** L'émetteur de la plateforme, créateur des badges du catalogue (sans établissement). */
	public Future<JsonObject> upsertPlatformIssuer(String id, String name, String url) {
		return Db.sqlOne("INSERT INTO openbadges.issuer (id, name, url) VALUES (?, ?, ?) "
				+ "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, url = EXCLUDED.url "
				+ "RETURNING " + ISSUER_COLUMNS, new JsonArray().add(id).add(name).add(url));
	}

	public Future<JsonObject> bundle(String resource) {
		return Db.sqlOne("SELECT resource, sha256 FROM openbadges.bundle WHERE resource = ?",
				new JsonArray().add(resource));
	}

	/**
	 * Enregistre un catalogue : chaque badge est créé ou mis à jour (même identifiant), puis
	 * l'empreinte du fichier, le tout dans une transaction. Un badge retiré du catalogue n'est pas
	 * supprimé — ses attestations disparaîtraient avec lui.
	 */
	public Future<Void> saveCatalogue(String resource, String sha256, String catalogue, String issuerId,
									  JsonArray badges) {
		final JsonArray statements = new JsonArray();
		for (int i = 0; i < badges.size(); i++) {
			final JsonObject badge = badges.getJsonObject(i);
			statements.add(Db.statement("INSERT INTO openbadges.achievement (id, issuer_id, name, "
					+ "description, criteria_narrative, alignments, catalogue, case_item, cycle, subject, "
					+ "levels, objectives) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb) "
					+ "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, "
					+ "description = EXCLUDED.description, criteria_narrative = EXCLUDED.criteria_narrative, "
					+ "alignments = EXCLUDED.alignments, catalogue = EXCLUDED.catalogue, "
					+ "case_item = EXCLUDED.case_item, cycle = EXCLUDED.cycle, subject = EXCLUDED.subject, "
					+ "levels = EXCLUDED.levels, objectives = EXCLUDED.objectives",
					new JsonArray().add(badge.getString("id")).add(issuerId).add(badge.getString("name"))
							.add(badge.getString("description")).add(badge.getString("criteria"))
							.add(badge.getJsonArray("alignments", new JsonArray()).encode())
							.add(catalogue).add(badge.getString("caseItem")).add(badge.getString("cycle"))
							.add(badge.getString("subject"))
							.add(badge.getJsonArray("levels", new JsonArray()).encode())
							.add(badge.getJsonArray("objectives", new JsonArray()).encode())));
		}
		statements.add(Db.statement("INSERT INTO openbadges.bundle (resource, sha256) VALUES (?, ?) "
				+ "ON CONFLICT (resource) DO UPDATE SET sha256 = EXCLUDED.sha256, loaded_at = NOW()",
				new JsonArray().add(resource).add(sha256)));
		return Db.transaction(statements).mapEmpty();
	}

	/** Les badges du catalogue, sans la liste des énoncés couverts (lourde). */
	public Future<JsonArray> catalogue() {
		return Db.sql("SELECT id, name, description, case_item, cycle, subject, levels, "
				+ "jsonb_array_length(objectives) AS objectives FROM openbadges.achievement "
				+ "WHERE catalogue IS NOT NULL ORDER BY cycle, subject, name", new JsonArray());
	}

	/** Énoncés couverts par chaque badge du catalogue : de quoi rattacher un exercice à son badge. */
	public Future<JsonArray> catalogueObjectives() {
		return Db.sql("SELECT id, name, cycle, subject, objectives FROM openbadges.achievement "
				+ "WHERE catalogue IS NOT NULL", new JsonArray());
	}

	/** Les badges non révoqués détenus par ces élèves. */
	public Future<JsonArray> heldBy(List<String> userIds) {
		final JsonArray params = new JsonArray();
		userIds.forEach(params::add);
		return Db.sql("SELECT subject_user_id, achievement_id FROM openbadges.credential "
				+ "WHERE NOT revoked AND subject_user_id IN (" + Db.placeholders(userIds.size()) + ")",
				params);
	}

	/** Les attestations d'une personne, avec le badge et l'établissement qui l'a décerné. */
	public Future<JsonArray> walletOf(String userId) {
		return Db.sql("SELECT c.id, c.name, c.narrative, c.valid_from, c.revoked, c.revoked_reason, c.jws, "
				+ "a.id AS achievement_id, a.description, a.cycle, a.subject, a.case_item, "
				+ "i.name AS issuer_name FROM openbadges.credential c "
				+ "JOIN openbadges.achievement a ON a.id = c.achievement_id "
				+ "JOIN openbadges.issuer i ON i.id = c.issuer_id "
				+ "WHERE c.subject_user_id = ? ORDER BY c.created_at DESC", new JsonArray().add(userId));
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
		return Db.sqlOne("INSERT INTO openbadges.credential (id, achievement_id, issuer_id, "
				+ "subject_user_id, name, narrative, identity_type, identity_hash, identity_salt, "
				+ "valid_from, valid_until, jws, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, "
				+ "?::timestamp, ?::timestamp, ?, ?) RETURNING " + CREDENTIAL_COLUMNS,
				new JsonArray().add(credential.getString("id"))
						.add(credential.getString("achievement_id"))
						.add(credential.getString("issuer_id"))
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

	/**
	 * Les attestations révoquées d'un émetteur, pour sa liste de révocation.
	 *
	 * <p>Sur l'émetteur de l'attestation, et non sur celui du badge : c'est l'établissement qui
	 * a délivré qui retire, et c'est sa liste que le vérificateur consulte.</p>
	 */
	public Future<JsonArray> revokedOf(String issuerId) {
		return Db.sql("SELECT id, revoked_reason FROM openbadges.credential "
				+ "WHERE issuer_id = ? AND revoked ORDER BY revoked_at DESC",
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
