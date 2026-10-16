package fr.openent.openbadges.core;

/**
 * Les adresses du service.
 *
 * <p>Chaque objet d'une attestation porte une URI absolue et stable. Ce n'est pas une
 * commodité : une attestation Open Badges <strong>circule hors de l'ENT</strong> — dans un
 * portefeuille, sur un profil professionnel, dans un courriel — et un vérificateur doit pouvoir
 * la contrôler des années plus tard sans rien connaître de la plateforme qui l'a émise. Toute
 * URI qui cesserait de répondre rendrait l'attestation invérifiable.</p>
 */
public final class Uris {

	/** Préfixe de l'API normée. */
	public static final String API = "/ims/ob/v3p0";
	/** Préfixe des pages publiques de vérification et de l'administration. */
	public static final String APP = "/openbadges";

	private final String base;

	public Uris(String publicBase) {
		this.base = publicBase.endsWith("/") ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
	}

	public String base() {
		return base;
	}

	public String credential(String id) {
		return base + APP + "/credentials/" + id;
	}

	public String achievement(String id) {
		return base + APP + "/achievements/" + id;
	}

	public String issuer(String id) {
		return base + APP + "/issuers/" + id;
	}

	/**
	 * Identité du porteur.
	 *
	 * <p>Un {@code urn:uuid:} et non une adresse web : l'identifiant doit être une URI stable,
	 * la norme n'exige pas qu'elle réponde, et une URL de la forme {@code /subjects/<id>}
	 * apprendrait à quiconque lit l'attestation que cette personne a un compte ici. Une
	 * attestation est faite pour être montrée ; elle ne doit rien dire de plus que ce qu'elle
	 * atteste.</p>
	 */
	public String subject(String userId) {
		return "urn:uuid:" + userId;
	}

	public String revocationList(String issuerId) {
		return base + APP + "/revocations/" + issuerId;
	}

	public String jwks() {
		return base + APP + "/.well-known/jwks.json";
	}

	public String tokenEndpoint() {
		return base + APP + "/oauth/token";
	}

	public String api() {
		return base + API;
	}
}
