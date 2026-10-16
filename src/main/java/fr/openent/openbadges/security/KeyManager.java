package fr.openent.openbadges.security;

import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * Clé de signature des jetons d'accès. La clé privée (PEM PKCS#8) vient de la configuration
 * (openbadges.private-key), elle-même alimentée par un Secret Kubernetes. Sans clé configurée,
 * une clé éphémère est générée : acceptable en développement seulement (elle change à
 * chaque redémarrage, ce qui invalide les jetons en cours, et diffère entre instances).
 */
public final class KeyManager {

	private static final Logger log = LoggerFactory.getLogger(KeyManager.class);

	private final PrivateKey privateKey;
	private final RSAPublicKey publicKey;
	private final String kid;

	public KeyManager(PrivateKey privateKey, RSAPublicKey publicKey, String kid) {
		this.privateKey = privateKey;
		this.publicKey = publicKey;
		this.kid = kid;
	}

	public static KeyManager fromConfig(JsonObject moduleConfig) {
		final String pem = moduleConfig.getString("private-key", "");
		if (pem.trim().isEmpty()) {
			log.warn("[OpenBadges] No openbadges.private-key configured: generating an ephemeral RSA key (development only).");
			final KeyPair pair = generate();
			return new KeyManager(pair.getPrivate(), (RSAPublicKey) pair.getPublic(), UUID.randomUUID().toString());
		}
		final RSAPrivateCrtKey key = parsePkcs8(pem);
		return new KeyManager(key, publicFromPrivate(key), moduleConfig.getString("kid", "openent-openbadges-1"));
	}

	public static KeyPair generate() {
		try {
			final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	static RSAPrivateCrtKey parsePkcs8(String pem) {
		final String body = pem
				.replace("\\n", "\n")
				.replaceAll("-----BEGIN [A-Z ]+-----", "")
				.replaceAll("-----END [A-Z ]+-----", "")
				.replaceAll("\\s", "");
		try {
			final byte[] der = Base64.getDecoder().decode(body);
			return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
		} catch (Exception e) {
			throw new IllegalArgumentException("openbadges.private-key must be an RSA private key in PKCS#8 PEM format", e);
		}
	}

	private static RSAPublicKey publicFromPrivate(RSAPrivateCrtKey key) {
		try {
			final BigInteger modulus = key.getModulus();
			return (RSAPublicKey) KeyFactory.getInstance("RSA")
					.generatePublic(new RSAPublicKeySpec(modulus, key.getPublicExponent()));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public String sign(JsonObject payload) {
		return Jwt.sign(payload, privateKey, kid);
	}

	public RSAPublicKey getPublicKey() {
		return publicKey;
	}

	public String getKid() {
		return kid;
	}

	/**
	 * La clé publique au format JWK.
	 *
	 * <p>C'est elle que publie le JWKS, et c'est par elle qu'un vérificateur contrôle la
	 * signature d'une attestation sans rien connaître de l'ENT : une attestation Open Badges
	 * circule hors de la plateforme qui l'a émise, et doit rester vérifiable quand elle est
	 * présentée des années plus tard.</p>
	 */
	public JsonObject jwk() {
		final Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
		return new JsonObject()
				.put("kty", "RSA")
				.put("use", "sig")
				.put("alg", Jwt.RS256)
				.put("kid", kid)
				.put("n", url.encodeToString(unsigned(publicKey.getModulus())))
				.put("e", url.encodeToString(unsigned(publicKey.getPublicExponent())));
	}

	/**
	 * Un entier JWK est un grand entier non signé.
	 *
	 * <p>{@link BigInteger#toByteArray()} ajoute un octet nul de tête dès que le bit de poids
	 * fort est à 1 — c'est-à-dire pour la moitié des modules RSA. Le laisser produirait une clé
	 * que les vérificateurs rejettent.</p>
	 */
	private static byte[] unsigned(BigInteger value) {
		final byte[] bytes = value.toByteArray();
		if (bytes.length > 1 && bytes[0] == 0) {
			final byte[] trimmed = new byte[bytes.length - 1];
			System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
			return trimmed;
		}
		return bytes;
	}
}
