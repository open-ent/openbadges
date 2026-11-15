package fr.openent.openbadges.service;

import fr.openent.openbadges.OpenBadgesContext;
import fr.openent.openbadges.badge.Credentials;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Signe et enregistre une attestation : délivrance par un enseignant ({@code AdminController}) ou
 * badge d'entraînement décerné automatiquement ({@link PracticeAwards}).
 *
 * <p>La signature est posée <strong>une fois</strong> et conservée. Resigner à la demande
 * produirait un JWS différent à chaque appel — même contenu, signature distincte — et un porteur
 * qui a archivé la première ne pourrait plus montrer qu'il s'agit de la même.</p>
 */
public final class CredentialIssuer {

	private static final DateTimeFormatter SQL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private CredentialIssuer() {}

	/**
	 * @param body {@code userId} (porteur), {@code email} (son identité, par empreinte salée),
	 *             et en option {@code name}, {@code narrative}, {@code validUntil}
	 */
	public static Future<JsonObject> sign(OpenBadgesContext ctx, JsonObject body, JsonObject achievement,
										  JsonObject issuer, String issuedBy) {
		final String id = UUID.randomUUID().toString();
		final String now = LocalDateTime.now(ZoneOffset.UTC).format(SQL_TIMESTAMP);
		final String email = body.getString("email");
		final String salt = email == null ? null : Credentials.salt();

		final JsonObject row = new JsonObject()
				.put("id", id)
				.put("achievement_id", achievement.getString("id"))
				.put("issuer_id", issuer.getString("id"))
				.put("subject_user_id", body.getString("userId"))
				.put("name", body.getString("name", achievement.getString("name")))
				.put("narrative", body.getValue("narrative"))
				.put("identity_type", "emailAddress")
				.put("identity_hash", Credentials.identityHash(email, salt))
				.put("identity_salt", salt)
				.put("valid_from", now)
				.put("valid_until", body.getValue("validUntil"))
				.put("created_by", issuedBy);

		final JsonObject credential = ctx.credentials.credential(row, achievement, issuer);
		final String jws = ctx.keys.sign(
				ctx.credentials.jwtPayload(credential, System.currentTimeMillis() / 1000L));
		return ctx.badges.insertCredential(row.copy().put("jws", jws))
				.map(stored -> new JsonObject()
						.put("credential", credential)
						.put("compactJwsString", jws));
	}
}
