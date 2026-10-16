package fr.openent.openbadges.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Secrets des clients OAuth. Le secret est tiré au hasard (256 bits) et seul son empreinte
 * SHA-256 est conservée : avec cette entropie, un hachage lent n'apporte rien.
 */
public final class Secrets {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

	private Secrets() {
	}

	public static String random(int bytes) {
		final byte[] b = new byte[bytes];
		RANDOM.nextBytes(b);
		return B64.encodeToString(b);
	}

	public static String hash(String secret) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
			return B64.encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Comparaison en temps constant du secret présenté avec l'empreinte stockée. */
	public static boolean matches(String secret, String expectedHash) {
		if (secret == null || expectedHash == null) {
			return false;
		}
		return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII),
				expectedHash.getBytes(StandardCharsets.US_ASCII));
	}
}
