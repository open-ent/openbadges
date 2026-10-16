package fr.openent.openbadges.security;

import fr.openent.openbadges.core.BadgeException;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Jetons d'accès OAuth 2.0 (client credentials) émis et vérifiés par le module : JWT RS256
 * portant l'identifiant du client et les scopes accordés.
 */
public class AccessTokens {

	public static final String TYP = "openbadges-access-token";
	private static final long LEEWAY = 30;

	private final KeyManager keys;
	private final String issuer;

	public AccessTokens(KeyManager keys, String issuer) {
		this.keys = keys;
		this.issuer = issuer;
	}

	public String issue(String clientId, List<String> scopes, long nowSeconds, long ttlSeconds) {
		return keys.sign(new JsonObject()
				.put("iss", issuer)
				.put("aud", issuer)
				.put("typ", TYP)
				.put("sub", clientId)
				.put("scope", String.join(" ", scopes))
				.put("iat", nowSeconds)
				.put("exp", nowSeconds + ttlSeconds)
				.put("jti", Secrets.random(12)));
	}

	/** Vérifie l'en-tête Authorization « Bearer » et retourne les claims du jeton. */
	public JsonObject verify(String authorizationHeader, long nowSeconds) throws BadgeException {
		if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
			throw new BadgeException(401, "missing.bearer.token");
		}
		final Jwt.Decoded decoded = Jwt.decode(authorizationHeader.substring(7).trim());
		Jwt.verify(decoded, keys.getPublicKey());
		Jwt.checkTimes(decoded.payload, nowSeconds, LEEWAY);
		if (!TYP.equals(decoded.payload.getString("typ")) || !issuer.equals(decoded.payload.getString("iss"))) {
			throw new BadgeException(401, "invalid.token");
		}
		return decoded.payload;
	}
}
