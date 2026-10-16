package fr.openent.openbadges.badge;

import fr.openent.openbadges.core.Uris;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CredentialsTest {

	private static final String BASE = "https://ent.example.org";
	private static final String ISSUER = "11111111-1111-4111-8111-111111111111";
	private static final String ACHIEVEMENT = "22222222-2222-4222-8222-222222222222";
	private static final String CREDENTIAL = "33333333-3333-4333-8333-333333333333";
	private static final String USER = "44444444-4444-4444-8444-444444444444";

	private final Uris uris = new Uris(BASE);
	private final Credentials credentials = new Credentials(uris);

	// ------------------------------------------------------------------ forme

	@Test
	public void une_attestation_porte_les_deux_contextes_et_les_deux_types() {
		JsonObject credential = credential();
		assertEquals(new JsonArray().add(Credentials.VC_CONTEXT).add(Credentials.OB_CONTEXT),
				credential.getJsonArray("@context"));
		assertEquals(new JsonArray().add("VerifiableCredential").add("OpenBadgeCredential"),
				credential.getJsonArray("type"));
		assertEquals(BASE + "/openbadges/credentials/" + CREDENTIAL, credential.getString("id"));
	}

	@Test
	public void l_emetteur_le_badge_et_l_attestation_portent_des_uri_absolues() {
		JsonObject credential = credential();
		assertEquals(BASE + "/openbadges/issuers/" + ISSUER,
				credential.getJsonObject("issuer").getString("id"));
		assertEquals(BASE + "/openbadges/achievements/" + ACHIEVEMENT,
				credential.getJsonObject("credentialSubject").getJsonObject("achievement")
						.getString("id"));
	}

	@Test
	public void le_porteur_est_designe_par_une_urn_et_non_par_une_adresse_web() {
		// Une URL /subjects/<id> apprendrait à quiconque lit l'attestation que cette personne a
		// un compte ici. La norme n'exige pas que l'identifiant réponde.
		assertEquals("urn:uuid:" + USER,
				credential().getJsonObject("credentialSubject").getString("id"));
	}

	@Test
	public void l_attestation_designe_sa_liste_de_revocation() {
		JsonObject status = credential().getJsonObject("credentialStatus");
		assertEquals(Credentials.REVOCATION_TYPE, status.getString("type"));
		assertEquals(BASE + "/openbadges/revocations/" + ISSUER + "#" + CREDENTIAL,
				status.getString("id"));
	}

	@Test
	public void un_badge_s_aligne_sur_une_competence_du_module_case() {
		// C'est la jonction des deux normes : targetUrl désigne un CFItem servi par le module
		// case, et l'attestation dit alors exactement quelle compétence elle atteste.
		JsonObject achievement = credentials.achievement(achievementRow());
		JsonArray alignment = achievement.getJsonArray("alignment");
		assertEquals(1, alignment.size());
		JsonObject first = alignment.getJsonObject(0);
		assertEquals("Comprendre un texte", first.getString("targetName"));
		assertEquals(BASE + "/ims/case/v1p0/CFItems/abc", first.getString("targetUrl"));
		assertEquals("CFItem", first.getString("targetType"));
	}

	@Test
	public void un_alignement_sans_cible_est_ecarte() {
		JsonObject row = achievementRow().put("alignments", new JsonArray()
				.add(new JsonObject().put("targetName", "sans adresse")));
		assertFalse(credentials.achievement(row).containsKey("alignment"));
	}

	@Test
	public void les_champs_absents_ne_laissent_pas_de_cle_vide() {
		JsonObject row = new JsonObject()
				.put("id", ACHIEVEMENT)
				.put("name", "Badge nu")
				.put("description", "Sans rien d'autre");
		JsonObject achievement = credentials.achievement(row);
		assertFalse(achievement.containsKey("criteria"));
		assertFalse(achievement.containsKey("image"));
		assertFalse(achievement.containsKey("tag"));
		assertEquals("Badge", achievement.getString("achievementType"));
	}

	@Test
	public void l_emetteur_de_l_attestation_n_est_pas_le_proprietaire_du_badge() {
		// Un badge est défini une fois par la plateforme et décerné par chaque établissement en
		// son nom : la norme distingue le créateur d'un Achievement de l'issuer d'une
		// AchievementCredential. Recâbler l'émetteur sur celui du badge obligerait à redéfinir
		// chaque badge pour chacun des établissements.
		JsonObject college = new JsonObject()
				.put("id", "55555555-5555-4555-8555-555555555555")
				.put("name", "Collège Jean Moulin");
		JsonObject credential = credentials.credential(credentialRow(), achievementRow(), college);

		assertEquals("Collège Jean Moulin", credential.getJsonObject("issuer").getString("name"));
		assertEquals(BASE + "/openbadges/issuers/55555555-5555-4555-8555-555555555555",
				credential.getJsonObject("issuer").getString("id"));
		// Le badge, lui, reste le même objet partagé.
		assertEquals(BASE + "/openbadges/achievements/" + ACHIEVEMENT,
				credential.getJsonObject("credentialSubject").getJsonObject("achievement")
						.getString("id"));
		// Et la liste de révocation est celle de l'établissement qui a délivré.
		assertTrue(credential.getJsonObject("credentialStatus").getString("id")
				.startsWith(BASE + "/openbadges/revocations/55555555-5555-4555-8555-555555555555#"));
	}

	// ------------------------------------------------------------------ identité

	@Test
	public void l_identite_du_porteur_n_apparait_que_par_empreinte() {
		JsonObject subject = credential().getJsonObject("credentialSubject");
		JsonObject identity = subject.getJsonArray("identifier").getJsonObject(0);
		assertEquals("IdentityObject", identity.getString("type"));
		assertEquals("emailAddress", identity.getString("identityType"));
		assertTrue(identity.getBoolean("hashed"));
		assertTrue(identity.getString("identityHash").startsWith("sha256$"));
		assertNotNull(identity.getString("salt"));
		// L'adresse elle-même ne doit figurer nulle part dans l'attestation.
		assertFalse(credential().encode().contains("eleve@example.org"));
	}

	@Test
	public void l_empreinte_ne_depend_ni_de_la_casse_ni_des_espaces() {
		String salt = "sel";
		String reference = Credentials.identityHash("eleve@example.org", salt);
		assertEquals(reference, Credentials.identityHash("  ELEVE@Example.ORG  ", salt));
		// Sans normalisation, la même adresse saisie autrement ferait conclure au vérificateur
		// que l'attestation n'est pas la sienne.
		assertTrue(Credentials.identityMatches("Eleve@Example.org", salt, reference));
		assertFalse(Credentials.identityMatches("autre@example.org", salt, reference));
	}

	@Test
	public void le_sel_change_l_empreinte() {
		assertFalse(Credentials.identityHash("eleve@example.org", "sel-a")
				.equals(Credentials.identityHash("eleve@example.org", "sel-b")));
		assertFalse(Credentials.salt().equals(Credentials.salt()));
	}

	@Test
	public void sans_adresse_l_attestation_n_a_pas_d_identifiant_de_porteur() {
		JsonObject row = credentialRow().put("identity_hash", (String) null);
		JsonObject subject = credentials.credential(row, achievementRow(), issuerRow())
				.getJsonObject("credentialSubject");
		assertFalse(subject.containsKey("identifier"));
		assertNull(Credentials.identityHash(null, "sel"));
	}

	// ------------------------------------------------------------------ preuve

	@Test
	public void le_contenu_signe_double_les_claims_attendus() {
		JsonObject payload = credentials.jwtPayload(credential(), 1760000000L);
		assertEquals(BASE + "/openbadges/issuers/" + ISSUER, payload.getString("iss"));
		assertEquals("urn:uuid:" + USER, payload.getString("sub"));
		assertEquals(BASE + "/openbadges/credentials/" + CREDENTIAL, payload.getString("jti"));
		assertEquals(1760000000L, payload.getLong("nbf").longValue());
		// Les champs de l'attestation restent au premier niveau du contenu.
		assertNotNull(payload.getJsonObject("credentialSubject"));
		// Un badge ne se périme pas : pas d'exp sauf validUntil déclaré.
		assertFalse(payload.containsKey("exp"));
	}

	@Test
	public void les_horodatages_sont_en_temps_universel() {
		// mod-postgresql rend « 2026-10-15 23:19:00.000 » ; sans le Z, le vérificateur lit une
		// heure locale dont il ignore le fuseau.
		assertEquals("2026-10-15T23:19:00Z", Credentials.dateTime("2026-10-15 23:19:00.000"));
		assertEquals("2026-10-15T23:19:00Z", Credentials.dateTime("2026-10-15T23:19:00Z"));
		assertEquals("2026-10-15T23:19:00+02:00", Credentials.dateTime("2026-10-15T23:19:00+02:00"));
		assertNull(Credentials.dateTime(null));
	}

	// ------------------------------------------------------------------ fixtures

	private JsonObject credential() {
		return credentials.credential(credentialRow(), achievementRow(), issuerRow());
	}

	private JsonObject credentialRow() {
		String salt = "sel-d-essai";
		return new JsonObject()
				.put("id", CREDENTIAL)
				.put("achievement_id", ACHIEVEMENT)
				.put("subject_user_id", USER)
				.put("name", "Lecteur confirmé")
				.put("narrative", "Obtenu après trois lectures suivies.")
				.put("identity_type", "emailAddress")
				.put("identity_hash", Credentials.identityHash("eleve@example.org", salt))
				.put("identity_salt", salt)
				.put("valid_from", "2026-10-15 23:19:00.000");
	}

	private JsonObject achievementRow() {
		return new JsonObject()
				.put("id", ACHIEVEMENT)
				.put("issuer_id", ISSUER)
				.put("name", "Lecteur confirmé")
				.put("description", "Sait comprendre un texte long.")
				.put("achievement_type", "Badge")
				.put("criteria_narrative", "Trois lectures suivies et un compte rendu.")
				.put("image_url", "https://ent.example.org/assets/badges/lecteur.png")
				.put("tags", new JsonArray().add("lecture").add("cycle 3"))
				.put("alignments", new JsonArray().add(new JsonObject()
						.put("targetName", "Comprendre un texte")
						.put("targetUrl", BASE + "/ims/case/v1p0/CFItems/abc")
						.put("targetFramework", "Socle commun")
						.put("targetCode", "D1.1")
						.put("targetType", "CFItem")));
	}

	private JsonObject issuerRow() {
		return new JsonObject()
				.put("id", ISSUER)
				.put("name", "Collège d'essai")
				.put("url", "https://ent.example.org")
				.put("image_url", "https://ent.example.org/assets/logo.png");
	}
}
