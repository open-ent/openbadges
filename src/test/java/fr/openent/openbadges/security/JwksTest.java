package fr.openent.openbadges.security;

import fr.openent.openbadges.core.BadgeException;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * La chaîne complète qui rend une attestation vérifiable : signer ici, publier la clé, et
 * vérifier ailleurs avec la seule clé publiée.
 */
public class JwksTest {

	private final KeyManager keys = KeyManager.fromConfig(new JsonObject());

	@Test
	public void une_attestation_signee_se_verifie_avec_la_cle_publique() throws Exception {
		final String jws = keys.sign(new JsonObject().put("jti", "urn:credential:1"));
		final Jwt.Decoded decoded = Jwt.decode(jws);
		Jwt.verify(decoded, keys.getPublicKey());
		assertEquals("urn:credential:1", decoded.payload.getString("jti"));
		assertEquals(keys.getKid(), decoded.kid());
		assertEquals("JWT", decoded.header.getString("typ"));
		assertEquals(Jwt.RS256, decoded.header.getString("alg"));
	}

	/**
	 * L'épreuve qui compte : un vérificateur ne connaît que le JWKS. S'il ne peut pas reconstruire
	 * la clé à partir de ce qu'on publie, toutes les attestations sont invérifiables.
	 */
	@Test
	public void la_cle_reconstruite_depuis_le_jwks_verifie_la_signature() throws Exception {
		final String jws = keys.sign(new JsonObject().put("jti", "urn:credential:2"));
		final RSAPublicKey fromJwks = publicKeyFrom(keys.jwk());
		Jwt.verify(Jwt.decode(jws), fromJwks);
		assertEquals(keys.getPublicKey().getModulus(), fromJwks.getModulus());
	}

	@Test
	public void le_jwks_declare_ce_qu_un_verificateur_attend() {
		final JsonObject jwk = keys.jwk();
		assertEquals("RSA", jwk.getString("kty"));
		assertEquals("sig", jwk.getString("use"));
		assertEquals(Jwt.RS256, jwk.getString("alg"));
		assertEquals(keys.getKid(), jwk.getString("kid"));
	}

	/**
	 * Le piège du grand entier signé.
	 *
	 * <p>{@code BigInteger.toByteArray()} ajoute un octet nul de tête dès que le bit de poids
	 * fort est à 1 — c'est-à-dire pour la moitié des modules RSA. Un JWK qui le conserve est
	 * rejeté par les vérificateurs, et le défaut ne se voit qu'une fois sur deux selon la clé
	 * tirée : exactement le genre de panne qu'on ne reproduit pas.</p>
	 */
	@Test
	public void le_module_publie_n_a_pas_d_octet_nul_de_tete() {
		for (int attempt = 0; attempt < 20; attempt++) {
			final KeyManager manager = KeyManager.fromConfig(new JsonObject());
			final byte[] modulus = Base64.getUrlDecoder().decode(manager.jwk().getString("n"));
			assertNotEquals("octet nul de tête dans le module publié", 0, modulus[0]);
			assertEquals(manager.getPublicKey().getModulus(), new BigInteger(1, modulus));
		}
	}

	@Test
	public void une_attestation_alteree_ne_se_verifie_plus() throws Exception {
		final String jws = keys.sign(new JsonObject().put("jti", "urn:credential:3"));
		final String[] parts = jws.split("\\.");
		final String tampered = parts[0] + "."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(
						new JsonObject().put("jti", "urn:credential:999").encode().getBytes("UTF-8"))
				+ "." + parts[2];
		try {
			Jwt.verify(Jwt.decode(tampered), keys.getPublicKey());
			fail("une attestation modifiée doit être refusée");
		} catch (BadgeException expected) {
			assertEquals(401, expected.getStatus());
		}
	}

	@Test
	public void une_autre_cle_ne_verifie_pas() throws Exception {
		final String jws = keys.sign(new JsonObject().put("jti", "urn:credential:4"));
		final KeyManager other = KeyManager.fromConfig(new JsonObject());
		try {
			Jwt.verify(Jwt.decode(jws), other.getPublicKey());
			fail("la signature d'une autre clé doit être refusée");
		} catch (BadgeException expected) {
			assertEquals(401, expected.getStatus());
		}
	}

	@Test
	public void un_jeton_malforme_est_refuse_sans_exception_inattendue() {
		for (String token : new String[]{null, "", "a.b", "a.b.c.d", "pas-un-jeton"}) {
			try {
				Jwt.decode(token);
				fail("jeton accepté à tort : " + token);
			} catch (BadgeException expected) {
				assertEquals(401, expected.getStatus());
			}
		}
	}

	@Test
	public void une_cle_privee_pem_est_relue() throws Exception {
		final KeyPair pair = KeyManager.generate();
		final String pem = "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";
		final KeyManager manager = KeyManager.fromConfig(new JsonObject()
				.put("private-key", pem).put("kid", "openent-openbadges-1"));
		assertEquals("openent-openbadges-1", manager.getKid());
		// La clé publique est déduite de la privée : c'est elle qui doit vérifier.
		Jwt.verify(Jwt.decode(manager.sign(new JsonObject().put("jti", "x"))),
				manager.getPublicKey());
	}

	@Test
	public void une_cle_privee_illisible_est_refusee_clairement() {
		try {
			KeyManager.fromConfig(new JsonObject().put("private-key", "ceci n'est pas une clé"));
			fail("une clé illisible doit être refusée");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("PKCS#8"));
		}
	}

	private static RSAPublicKey publicKeyFrom(JsonObject jwk) throws Exception {
		final Base64.Decoder decoder = Base64.getUrlDecoder();
		final BigInteger modulus = new BigInteger(1, decoder.decode(jwk.getString("n")));
		final BigInteger exponent = new BigInteger(1, decoder.decode(jwk.getString("e")));
		return (RSAPublicKey) KeyFactory.getInstance("RSA")
				.generatePublic(new RSAPublicKeySpec(modulus, exponent));
	}
}
