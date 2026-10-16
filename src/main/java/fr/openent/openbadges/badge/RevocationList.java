package fr.openent.openbadges.badge;

import fr.openent.openbadges.core.Uris;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * La liste de révocation d'un émetteur.
 *
 * <p>Une attestation révoquée reste signée : la signature prouve qu'elle a été émise, pas
 * qu'elle vaut toujours. Sans cette liste, retirer une attestation serait impossible — c'est
 * elle que le vérificateur consulte, par l'URI que porte le {@code credentialStatus}.</p>
 *
 * <p>La liste répond donc même quand elle est vide, et elle doit répondre <strong>pour
 * toujours</strong> : une liste qui cesse de répondre ne rend pas les attestations valides, elle
 * les rend invérifiables.</p>
 */
public final class RevocationList {

	private final Uris uris;

	public RevocationList(Uris uris) {
		this.uris = uris;
	}

	public JsonObject build(String issuerId, List<JsonObject> revoked) {
		final JsonArray credentials = new JsonArray();
		for (JsonObject row : revoked) {
			final JsonObject entry = new JsonObject()
					.put("id", uris.credential(row.getString("id")))
					.put("revoked", Boolean.TRUE);
			final String reason = row.getString("revoked_reason");
			if (reason != null && !reason.isEmpty()) {
				entry.put("revocationReason", reason);
			}
			credentials.add(entry);
		}
		return new JsonObject()
				.put("@context", new JsonArray().add(Credentials.OB_CONTEXT))
				.put("id", uris.revocationList(issuerId))
				.put("type", new JsonArray().add(Credentials.REVOCATION_TYPE))
				.put("issuer", uris.issuer(issuerId))
				.put("revokedCredential", credentials);
	}
}
