package fr.openent.openbadges.badge;

import fr.openent.openbadges.core.Uris;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Construction des objets Open Badges 3.0 : {@code Achievement}, {@code Profile} et
 * {@code AchievementCredential}.
 *
 * <p>Classe <strong>pure</strong> : aucune base, aucun Vert.x, aucune clé. Elle produit le JSON,
 * la signature vient après.</p>
 *
 * <p>Une attestation Open Badges est une <em>Verifiable Credential</em> du W3C. Deux conséquences
 * commandent tout ce qui suit.</p>
 *
 * <p><strong>Elle circule hors de l'ENT.</strong> Déposée dans un portefeuille, jointe à une
 * candidature, elle est lue par des tiers qui n'ont aucun compte ici. D'où l'identité du porteur
 * par <strong>empreinte</strong> et jamais par adresse de courriel en clair : une attestation est
 * faite pour être montrée, et montrer une attestation ne doit pas livrer l'adresse de l'élève.</p>
 *
 * <p><strong>Elle est vérifiée longtemps après.</strong> Toutes les URI qu'elle porte doivent
 * continuer de répondre — celle de l'attestation, celle du badge, celle de l'émetteur, celle de
 * la liste de révocation et celle du JWKS. Aucune n'est décorative.</p>
 */
public final class Credentials {

	/** Contexte JSON-LD du modèle de données des Verifiable Credentials. */
	public static final String VC_CONTEXT = "https://www.w3.org/ns/credentials/v2";
	/** Contexte JSON-LD d'Open Badges 3.0. */
	public static final String OB_CONTEXT = "https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.3.json";

	/**
	 * Type du statut de révocation.
	 *
	 * <p>La spécification 3.0 nomme {@code 1EdTechRevocationList} ; le guide de certification,
	 * plus récent, contrôle un {@code BitstringStatusListEntry} du W3C. Les deux ne se
	 * remplacent pas — l'un est une liste d'identifiants révoqués, l'autre un index dans une
	 * suite de bits. On suit <strong>la spécification</strong>, et la liste est servie.</p>
	 */
	public static final String REVOCATION_TYPE = "1EdTechRevocationList";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final Uris uris;

	public Credentials(Uris uris) {
		this.uris = uris;
	}

	// ------------------------------------------------------------------ objets

	/** Le profil de l'émetteur : qui atteste. */
	public JsonObject issuer(JsonObject row) {
		final String id = row.getString("id");
		final JsonObject profile = new JsonObject()
				.put("id", uris.issuer(id))
				.put("type", new JsonArray().add("Profile"))
				.put("name", row.getString("name"));
		putIfPresent(profile, "url", row.getString("url"));
		putIfPresent(profile, "description", row.getString("description"));
		putIfPresent(profile, "email", row.getString("email"));
		final String image = row.getString("image_url");
		if (image != null && !image.isEmpty()) {
			profile.put("image", new JsonObject().put("id", image).put("type", "Image"));
		}
		return profile;
	}

	/** Le badge : ce qui est attesté, indépendamment de qui l'a obtenu. */
	public JsonObject achievement(JsonObject row) {
		final String id = row.getString("id");
		final JsonObject achievement = new JsonObject()
				.put("id", uris.achievement(id))
				.put("type", new JsonArray().add("Achievement"))
				.put("name", row.getString("name"))
				.put("description", row.getString("description"))
				.put("achievementType", row.getString("achievement_type", "Badge"));

		final JsonObject criteria = new JsonObject();
		putIfPresent(criteria, "narrative", row.getString("criteria_narrative"));
		putIfPresent(criteria, "id", row.getString("criteria_url"));
		if (!criteria.isEmpty()) {
			achievement.put("criteria", criteria);
		}
		final String image = row.getString("image_url");
		if (image != null && !image.isEmpty()) {
			achievement.put("image", new JsonObject().put("id", image).put("type", "Image"));
		}
		final JsonArray tags = array(row, "tags");
		if (!tags.isEmpty()) {
			achievement.put("tag", tags);
		}
		final JsonArray alignments = alignments(array(row, "alignments"));
		if (!alignments.isEmpty()) {
			achievement.put("alignment", alignments);
		}
		return achievement;
	}

	/**
	 * Les alignements d'un badge vers un référentiel de compétences.
	 *
	 * <p>C'est ici que se rejoignent les deux normes : {@code targetUrl} peut désigner un
	 * {@code CFItem} servi par le module {@code case}, et l'attestation dit alors exactement
	 * quelle compétence du socle elle atteste — vérifiable par un tiers, puisque l'URI répond.</p>
	 */
	private JsonArray alignments(JsonArray declared) {
		final JsonArray alignments = new JsonArray();
		for (int i = 0; i < declared.size(); i++) {
			final Object value = declared.getValue(i);
			if (!(value instanceof JsonObject)) {
				continue;
			}
			final JsonObject source = (JsonObject) value;
			final String targetUrl = source.getString("targetUrl");
			final String targetName = source.getString("targetName");
			if (targetUrl == null || targetName == null) {
				continue;
			}
			final JsonObject alignment = new JsonObject()
					.put("type", new JsonArray().add("Alignment"))
					.put("targetName", targetName)
					.put("targetUrl", targetUrl);
			putIfPresent(alignment, "targetFramework", source.getString("targetFramework"));
			putIfPresent(alignment, "targetCode", source.getString("targetCode"));
			putIfPresent(alignment, "targetDescription", source.getString("targetDescription"));
			putIfPresent(alignment, "targetType", source.getString("targetType"));
			alignments.add(alignment);
		}
		return alignments;
	}

	/** L'attestation complète, non signée. */
	public JsonObject credential(JsonObject credentialRow, JsonObject achievementRow,
								 JsonObject issuerRow) {
		final String id = credentialRow.getString("id");
		final JsonObject achievement = achievement(achievementRow);

		final JsonObject subject = new JsonObject()
				.put("id", uris.subject(credentialRow.getString("subject_user_id")))
				.put("type", new JsonArray().add("AchievementSubject"))
				.put("achievement", achievement);
		putIfPresent(subject, "narrative", credentialRow.getString("narrative"));
		final JsonObject identity = identity(credentialRow);
		if (identity != null) {
			subject.put("identifier", new JsonArray().add(identity));
		}

		final JsonObject credential = new JsonObject()
				.put("@context", new JsonArray().add(VC_CONTEXT).add(OB_CONTEXT))
				.put("id", uris.credential(id))
				.put("type", new JsonArray().add("VerifiableCredential").add("OpenBadgeCredential"))
				.put("name", credentialRow.getString("name", achievementRow.getString("name")))
				.put("issuer", issuer(issuerRow))
				.put("validFrom", dateTime(credentialRow.getString("valid_from")))
				.put("credentialSubject", subject)
				.put("credentialStatus", new JsonObject()
						.put("id", uris.revocationList(issuerRow.getString("id")) + "#" + id)
						.put("type", REVOCATION_TYPE));
		putIfPresent(credential, "validUntil", dateTime(credentialRow.getString("valid_until")));
		return credential;
	}

	/**
	 * L'identité du porteur, par empreinte.
	 *
	 * <p>{@code identityHash} vaut {@code sha256$<hex>} de l'adresse <em>suivie du sel</em>, et
	 * le sel voyage en clair avec l'attestation : c'est ce qui permet à un vérificateur à qui le
	 * porteur donne son adresse de confirmer qu'elle correspond, sans que l'attestation seule ne
	 * révèle quoi que ce soit.</p>
	 */
	private JsonObject identity(JsonObject row) {
		final String hash = row.getString("identity_hash");
		if (hash == null || hash.isEmpty()) {
			return null;
		}
		final JsonObject identity = new JsonObject()
				.put("type", "IdentityObject")
				.put("identityType", row.getString("identity_type", "emailAddress"))
				.put("hashed", Boolean.TRUE)
				.put("identityHash", hash);
		putIfPresent(identity, "salt", row.getString("identity_salt"));
		return identity;
	}

	// ------------------------------------------------------------------ preuve

	/**
	 * Le contenu à signer, au format VC-JWT.
	 *
	 * <p>Les champs de l'attestation sont au premier niveau du contenu, doublés des claims
	 * enregistrés que la norme attend. {@code exp} n'est posé que si l'attestation expire : un
	 * badge, par nature, ne se périme pas.</p>
	 */
	public JsonObject jwtPayload(JsonObject credential, long nowSeconds) {
		final JsonObject payload = credential.copy();
		payload.put("iss", credential.getJsonObject("issuer").getString("id"));
		payload.put("sub", credential.getJsonObject("credentialSubject").getString("id"));
		payload.put("jti", credential.getString("id"));
		payload.put("iat", nowSeconds);
		payload.put("nbf", nowSeconds);
		return payload;
	}

	// ------------------------------------------------------------------ empreintes

	/** Un sel tiré au hasard, publié avec l'attestation. */
	public static String salt() {
		final byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * L'empreinte d'une identité, au format attendu par la norme.
	 *
	 * <p>L'adresse est mise en minuscules et débarrassée de ses espaces avant d'être hachée :
	 * sans cela, la même adresse saisie différemment produirait deux empreintes, et le
	 * vérificateur conclurait à tort que l'attestation n'est pas la sienne.</p>
	 */
	public static String identityHash(String identity, String salt) {
		if (identity == null) {
			return null;
		}
		final String normalized = identity.trim().toLowerCase();
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] hash = digest.digest((normalized + (salt == null ? "" : salt))
					.getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				final int value = b & 0xFF;
				hex.append(Character.forDigit(value >>> 4, 16)).append(Character.forDigit(value & 0x0F, 16));
			}
			return "sha256$" + hex;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 indisponible", e);
		}
	}

	/** Vrai si cette identité est bien celle que l'attestation désigne. */
	public static boolean identityMatches(String identity, String salt, String expectedHash) {
		final String computed = identityHash(identity, salt);
		if (computed == null || expectedHash == null) {
			return false;
		}
		return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
				expectedHash.getBytes(StandardCharsets.US_ASCII));
	}

	// ------------------------------------------------------------------ utilitaires

	/**
	 * Horodatage ISO-8601 en temps universel.
	 *
	 * <p>mod-postgresql rend {@code 2026-10-15 23:19:00.000} ; une Verifiable Credential veut
	 * {@code 2026-10-15T23:19:00Z}. Sans le {@code Z}, le vérificateur lit une heure locale dont
	 * il ignore le fuseau.</p>
	 */
	static String dateTime(String timestamp) {
		if (timestamp == null || timestamp.isEmpty()) {
			return null;
		}
		String value = timestamp.trim().replace(' ', 'T');
		final int dot = value.indexOf('.');
		if (dot > 0) {
			value = value.substring(0, dot);
		}
		if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:?\\d{2}$")) {
			return value;
		}
		return value + "Z";
	}

	private static JsonArray array(JsonObject row, String key) {
		final Object value = row.getValue(key);
		if (value instanceof JsonArray) {
			return (JsonArray) value;
		}
		return value instanceof String ? new JsonArray((String) value) : new JsonArray();
	}

	private static void putIfPresent(JsonObject target, String key, String value) {
		if (value != null && !value.isEmpty()) {
			target.put(key, value);
		}
	}
}
