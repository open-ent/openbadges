package fr.openent.openbadges.controller;

import fr.openent.openbadges.OpenBadgesContext;
import fr.openent.openbadges.api.Http;
import fr.openent.openbadges.core.BadgeException;
import fr.openent.openbadges.core.Uris;
import fr.openent.openbadges.security.Client;
import fr.openent.openbadges.security.Scopes;
import fr.openent.openbadges.service.BadgeRepository;
import fr.wseduc.rs.Get;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * L'API Open Badges 3.0, sous {@code /ims/ob/v3p0}.
 *
 * <p>Open ENT y tient le rôle d'<strong>émetteur</strong> : il délivre des attestations, les
 * signe, les sert et les révoque. Il ne tient pas celui de <em>portefeuille</em> —
 * {@code upsertCredential}, qui sert à déposer chez un hôte une attestation émise ailleurs,
 * n'est pas implémenté, et {@code /discovery} le dit plutôt que de le laisser découvrir par un
 * 501.</p>
 *
 * <p><strong>Une limite à connaître.</strong> La norme prévoit que l'accès aux attestations
 * d'une personne passe par un {@code authorization_code} OAuth, c'est-à-dire par le consentement
 * explicite de cette personne. Ce module n'implémente que {@code client_credentials} : un client
 * autorisé lit les attestations d'un porteur qu'il désigne. C'est suffisant pour un service de
 * l'établissement, insuffisant pour un portefeuille tiers — et il faut le savoir avant d'ouvrir
 * un client à l'extérieur.</p>
 */
public class ObController extends BaseController {

	private static final String[] READ = {Scopes.CREDENTIAL_READONLY};
	private static final String[] PROFILE = {Scopes.PROFILE_READONLY};

	private final OpenBadgesContext ctx;
	private final BadgeRepository repository;

	public ObController(OpenBadgesContext ctx) {
		this.ctx = ctx;
		this.repository = ctx.badges;
		this.pathPrefix = Uris.API;
	}

	/** Description du service. Publique : c'est par elle qu'un client sait quoi appeler. */
	@Get("/discovery")
	public void discovery(HttpServerRequest request) {
		Http.publicJson(request, new JsonObject()
				.put("openapi", "3.0.0")
				.put("info", new JsonObject()
						.put("title", "Open ENT — Open Badges 3.0")
						.put("version", "3.0")
						.put("description", "Open ENT est émetteur d'attestations Open Badges 3.0. "
								+ "upsertCredential n'est pas implémenté : la plateforme émet, "
								+ "elle n'héberge pas les attestations émises ailleurs."))
				.put("servers", new JsonArray().add(new JsonObject().put("url", ctx.uris.api())))
				.put("components", new JsonObject().put("securitySchemes", new JsonObject()
						.put("OAuth2ClientCredentials", new JsonObject()
								.put("type", "oauth2")
								.put("flows", new JsonObject().put("clientCredentials", new JsonObject()
										.put("tokenUrl", ctx.uris.tokenEndpoint())
										.put("scopes", new JsonObject()
												.put(Scopes.CREDENTIAL_READONLY, "Lire les attestations")
												.put(Scopes.PROFILE_READONLY, "Lire le profil")))))))
				.put("paths", new JsonObject()
						.put("/discovery", operation("getServiceDescription", null))
						.put("/credentials", operation("getCredentials", Scopes.CREDENTIAL_READONLY))
						.put("/profile", operation("getProfile", Scopes.PROFILE_READONLY)))
				.put("x-jwks-uri", ctx.uris.jwks())
				.put("x-not-implemented", new JsonArray().add("upsertCredential").add("putProfile")));
	}

	/**
	 * Les attestations d'un porteur.
	 *
	 * <p>Le porteur est désigné par le paramètre {@code subject} : faute de consentement OAuth,
	 * il n'y a pas d'« utilisateur du jeton » à déduire. Sans ce paramètre, on refuse plutôt que
	 * de rendre les attestations de tout le monde.</p>
	 */
	@Get("/credentials")
	public void credentials(HttpServerRequest request) {
		ctx.auth.authenticate(request, READ).compose(client -> {
			final String subject = request.params().get("subject");
			if (subject == null || subject.trim().isEmpty()) {
				return Future.<JsonObject>failedFuture(new BadgeException("missing.subject"));
			}
			final int limit;
			final int offset;
			try {
				limit = Http.limit(request, ctx.maxLimit);
				offset = Http.offset(request);
			} catch (BadgeException e) {
				return Future.<JsonObject>failedFuture(e);
			}
			return CompositeFuture.all(repository.credentialsOf(subject, limit, offset),
							repository.countOf(subject))
					.compose(results -> expand(results.resultAt(0))
							.map(body -> body.put("__total", ((JsonObject) results.resultAt(1))
									.getInteger("total", 0))));
		}).onSuccess(body -> {
			final Integer total = body.getInteger("__total", 0);
			body.remove("__total");
			request.response().putHeader("X-Total-Count", String.valueOf(total));
			Http.json(request, 200, body);
		}).onFailure(error -> Http.error(request, error));
	}

	/** Le profil de l'émetteur par défaut de la plateforme. */
	@Get("/profile")
	public void profile(HttpServerRequest request) {
		ctx.auth.authenticate(request, PROFILE)
				.compose(client -> ctx.defaultIssuer())
				.onSuccess(issuer -> Http.json(request, 200, ctx.credentials.issuer(issuer)))
				.onFailure(error -> Http.error(request, error));
	}

	/**
	 * Rend les attestations sous les deux formes que la norme attend : l'objet et le JWS.
	 *
	 * <p>Les deux, parce qu'ils ne servent pas à la même chose — l'objet se lit, le JWS se
	 * vérifie — et qu'un client qui n'aurait que l'objet ne pourrait rien prouver.</p>
	 */
	private Future<JsonObject> expand(JsonArray rows) {
		final List<JsonObject> credentials = BadgeRepository.rows(rows);
		final JsonArray jws = new JsonArray();
		final List<Future> loads = new ArrayList<Future>();
		final JsonArray objects = new JsonArray();
		for (JsonObject credential : credentials) {
			jws.add(credential.getString("jws"));
			loads.add(repository.achievement(credential.getString("achievement_id"))
					.compose(achievement -> achievement == null
							? Future.succeededFuture(null)
							// L'émetteur de l'attestation, pas celui du badge : c'est
							// l'établissement qui a décerné qui apparaît dans ce qu'on rend.
							: repository.issuer(credential.getString("issuer_id"))
									.map(issuer -> ctx.credentials.credential(credential, achievement, issuer)))
					.map(object -> {
						if (object != null) {
							objects.add(object);
						}
						return null;
					}));
		}
		return CompositeFuture.join(loads)
				.map(v -> new JsonObject().put("credential", objects).put("compactJwsString", jws));
	}

	private static JsonObject operation(String operationId, String scope) {
		final JsonObject get = new JsonObject().put("operationId", operationId);
		if (scope != null) {
			get.put("security", new JsonArray().add(new JsonObject()
					.put("OAuth2ClientCredentials", new JsonArray().add(scope))));
		}
		return new JsonObject().put("get", get);
	}
}
