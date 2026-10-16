package fr.openent.openbadges.security;

import fr.openent.openbadges.core.BadgeException;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * JWT compact (JWS) signé en RS256, implémenté avec la seule cryptographie du JDK.
 * Seul l'algorithme RS256 est accepté en vérification (1EdTech Security Framework).
 */
public final class Jwt {

	public static final String RS256 = "RS256";
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder B64D = Base64.getUrlDecoder();

	private Jwt() {
	}

	public static String sign(JsonObject payload, PrivateKey key, String kid) {
		final JsonObject header = new JsonObject().put("typ", "JWT").put("alg", RS256);
		if (kid != null) {
			header.put("kid", kid);
		}
		final String signingInput = b64(header.encode()) + "." + b64(payload.encode());
		try {
			final Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(key);
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return signingInput + "." + B64.encodeToString(signature.sign());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Unable to sign JWT", e);
		}
	}

	public static Decoded decode(String token) throws BadgeException {
		if (token == null) {
			throw new BadgeException(401, "missing.jwt");
		}
		final String[] parts = token.trim().split("\\.", -1);
		if (parts.length != 3) {
			throw new BadgeException(401, "malformed.jwt");
		}
		try {
			final JsonObject header = new JsonObject(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8));
			final JsonObject payload = new JsonObject(new String(B64D.decode(parts[1]), StandardCharsets.UTF_8));
			final byte[] signature = B64D.decode(parts[2]);
			return new Decoded(header, payload, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), signature);
		} catch (RuntimeException e) {
			throw new BadgeException(401, "malformed.jwt");
		}
	}

	/** Vérifie la signature RS256 ; lève une exception si l'algorithme n'est pas RS256 ou si la signature est invalide. */
	public static void verify(Decoded jwt, PublicKey key) throws BadgeException {
		if (!RS256.equals(jwt.header.getString("alg"))) {
			throw new BadgeException(401, "unsupported.jwt.alg");
		}
		try {
			final Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initVerify(key);
			signature.update(jwt.signingInput);
			if (!signature.verify(jwt.signature)) {
				throw new BadgeException(401, "invalid.jwt.signature");
			}
		} catch (GeneralSecurityException e) {
			throw new BadgeException(401, "invalid.jwt.signature");
		}
	}

	/** Contrôle exp (obligatoire) et iat (pas dans le futur), avec une tolérance d'horloge en secondes. */
	public static void checkTimes(JsonObject payload, long nowSeconds, long leewaySeconds) throws BadgeException {
		final Long exp = asLong(payload.getValue("exp"));
		if (exp == null || exp + leewaySeconds < nowSeconds) {
			throw new BadgeException(401, "expired.jwt");
		}
		final Long iat = asLong(payload.getValue("iat"));
		if (iat != null && iat - leewaySeconds > nowSeconds) {
			throw new BadgeException(401, "jwt.issued.in.future");
		}
	}


	private static Long asLong(Object o) {
		return o instanceof Number ? ((Number) o).longValue() : null;
	}

	private static String b64(String s) {
		return B64.encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	public static final class Decoded {
		public final JsonObject header;
		public final JsonObject payload;
		private final byte[] signingInput;
		private final byte[] signature;

		private Decoded(JsonObject header, JsonObject payload, byte[] signingInput, byte[] signature) {
			this.header = header;
			this.payload = payload;
			this.signingInput = signingInput;
			this.signature = signature;
		}

		public String kid() {
			return header.getString("kid");
		}
	}
}
