package fr.openent.openbadges.controller;

import fr.openent.openbadges.OpenBadgesContext;
import fr.openent.openbadges.api.Http;
import fr.openent.openbadges.core.BadgeException;
import fr.openent.openbadges.security.Scopes;
import fr.openent.openbadges.security.Secrets;
import fr.openent.openbadges.service.Db;
import fr.wseduc.rs.Post;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Point de jeton OAuth 2.0 (RFC 6749 §4.4, client credentials), exigé par l API Open Badges 3.0.
 * Authentification du client par HTTP Basic (client_secret_basic) ou dans le corps
 * (client_secret_post).
 */
public class TokenController extends BaseController {

	// Le préfixe est posé par le contexte : le point de jeton vit sous /openbadges, hors de
	// l API normée, comme le fait OneRoster.

	private final OpenBadgesContext ctx;

	public TokenController(OpenBadgesContext ctx) {
		this.ctx = ctx;
		this.pathPrefix = fr.openent.openbadges.core.Uris.APP;
	}

	@Post("/oauth/token")
	public void token(HttpServerRequest request) {
		Http.form(request).compose(form -> {
			if (!"client_credentials".equals(form.get("grant_type"))) {
				return Future.<JsonObject>failedFuture(new TokenError(400, "unsupported_grant_type"));
			}
			final String[] credentials = credentials(request, form);
			if (credentials == null) {
				return Future.<JsonObject>failedFuture(new TokenError(401, "invalid_client"));
			}
			return ctx.clients.findEnabled(credentials[0]).compose(client -> {
				if (client == null || !Secrets.matches(credentials[1], client.getString("secret_hash"))) {
					return Future.failedFuture(new TokenError(401, "invalid_client"));
				}
				final List<String> granted = Scopes.grant(form.get("scope"), Db.jsonArray(client, "scopes"));
				if (granted.isEmpty()) {
					return Future.failedFuture(new TokenError(400, "invalid_scope"));
				}
				final long now = System.currentTimeMillis() / 1000L;
				return Future.succeededFuture(new JsonObject()
						.put("access_token", ctx.tokens.issue(client.getString("id"), granted, now, ctx.accessTokenTtl))
						.put("token_type", "bearer")
						.put("expires_in", ctx.accessTokenTtl)
						.put("scope", String.join(" ", granted)));
			});
		}).onSuccess(token -> Http.json(request, 200, token)).onFailure(e -> {
			final TokenError error = e instanceof TokenError ? (TokenError) e : new TokenError(500, "server_error");
			if (error.getStatus() == 401) {
				request.response().putHeader("WWW-Authenticate", "Basic realm=\"openbadges\"");
			}
			if (!(e instanceof TokenError)) {
				log.error("[OpenBadges] Token endpoint error", e);
			}
			Http.json(request, error.getStatus(), new JsonObject().put("error", error.getMessage()));
		});
	}

	/** [client_id, client_secret] depuis l'en-tête Basic, sinon le corps ; null si absents. */
	static String[] credentials(HttpServerRequest request, MultiMap form) {
		final String authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
			try {
				final String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
						StandardCharsets.UTF_8);
				final int colon = decoded.indexOf(':');
				if (colon > 0) {
					// RFC 6749 §2.3.1 : identifiant et secret sont encodés en application/x-www-form-urlencoded.
					return new String[] {urlDecode(decoded.substring(0, colon)), urlDecode(decoded.substring(colon + 1))};
				}
			} catch (IllegalArgumentException e) {
				return null;
			}
			return null;
		}
		final String id = form.get("client_id");
		final String secret = form.get("client_secret");
		return id != null && secret != null ? new String[] {id, secret} : null;
	}

	private static String urlDecode(String s) {
		try {
			return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Erreur au format RFC 6749 §5.2 ({"error": "..."}). */
	static final class TokenError extends BadgeException {
		TokenError(int status, String error) {
			super(status, error);
		}
	}
}
