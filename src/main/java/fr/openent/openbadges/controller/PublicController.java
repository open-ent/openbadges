package fr.openent.openbadges.controller;

import fr.openent.openbadges.OpenBadgesContext;
import fr.openent.openbadges.api.Http;
import fr.openent.openbadges.badge.Credentials;
import fr.openent.openbadges.core.BadgeException;
import fr.openent.openbadges.core.Uris;
import fr.openent.openbadges.service.BadgeRepository;
import fr.wseduc.rs.Get;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Les adresses publiques, celles que porte une attestation.
 *
 * <p><strong>Aucune de ces routes ne porte d'{@code @SecuredAction}, et c'est le fondement même
 * du format.</strong> Une attestation Open Badges est faite pour être présentée à un tiers — un
 * employeur, un établissement d'accueil, un portefeuille — qui n'a aucun compte ici. Si le JWKS,
 * le badge, l'émetteur ou la liste de révocation exigeaient une session, l'attestation serait
 * invérifiable, c'est-à-dire sans valeur.</p>
 *
 * <p>Ce qui est servi ici est donc, et seulement, ce qu'une attestation révèle déjà : le badge,
 * l'émetteur, la clé publique, et la liste des attestations retirées. L'identité du porteur n'y
 * figure que par empreinte.</p>
 */
public class PublicController extends BaseController {

	private final OpenBadgesContext ctx;
	private final BadgeRepository repository;

	public PublicController(OpenBadgesContext ctx) {
		this.ctx = ctx;
		this.repository = ctx.badges;
		this.pathPrefix = Uris.APP;
	}

	/** La clé publique qui vérifie les signatures. */
	@Get("/.well-known/jwks.json")
	public void jwks(HttpServerRequest request) {
		Http.publicJson(request, new JsonObject()
				.put("keys", new JsonArray().add(ctx.keys.jwk())));
	}

	/**
	 * Une attestation.
	 *
	 * <p>En JSON par défaut, en JWS compact si le client le demande : un portefeuille veut la
	 * forme signée, un humain qui ouvre le lien veut la lire.</p>
	 */
	@Get("/credentials/:id")
	public void credential(HttpServerRequest request) {
		final String accept = request.getHeader("Accept");
		final boolean wantsJws = accept != null && accept.contains("application/vc+jwt");
		repository.credential(request.params().get("id"))
				.compose(credential -> {
					if (credential == null) {
						return Future.failedFuture(BadgeException.notFound("credential"));
					}
					if (wantsJws) {
						return Future.succeededFuture(credential);
					}
					return repository.achievement(credential.getString("achievement_id"))
							.compose(achievement -> achievement == null
									? Future.failedFuture(BadgeException.notFound("achievement"))
									: repository.issuer(achievement.getString("issuer_id"))
											.map(issuer -> ctx.credentials.credential(credential,
													achievement, issuer)
													.put("__revoked", credential.getBoolean("revoked", false))));
				})
				.onSuccess(result -> {
					if (wantsJws) {
						Http.jwt(request, result.getString("jws"));
						return;
					}
					// L'état de révocation n'est pas une propriété de l'attestation : il se lit
					// dans la liste. On le retire de ce qu'on rend, et on pose un en-tête pour
					// qu'un lecteur pressé ne s'y trompe pas.
					final boolean revoked = result.getBoolean("__revoked", false);
					result.remove("__revoked");
					request.response().putHeader("X-Credential-Revoked", String.valueOf(revoked));
					Http.publicJson(request, result);
				})
				.onFailure(error -> Http.error(request, error));
	}

	@Get("/achievements/:id")
	public void achievement(HttpServerRequest request) {
		repository.achievement(request.params().get("id"))
				.onSuccess(row -> {
					if (row == null) {
						Http.error(request, BadgeException.notFound("achievement"));
						return;
					}
					Http.publicJson(request, ctx.credentials.achievement(row));
				})
				.onFailure(error -> Http.error(request, error));
	}

	@Get("/issuers/:id")
	public void issuer(HttpServerRequest request) {
		repository.issuer(request.params().get("id"))
				.onSuccess(row -> {
					if (row == null) {
						Http.error(request, BadgeException.notFound("issuer"));
						return;
					}
					Http.publicJson(request, ctx.credentials.issuer(row));
				})
				.onFailure(error -> Http.error(request, error));
	}

	/**
	 * La liste de révocation d'un émetteur.
	 *
	 * <p>Elle répond même vide, et doit répondre pour toujours : une liste muette ne rend pas
	 * les attestations valides, elle les rend invérifiables.</p>
	 */
	@Get("/revocations/:id")
	public void revocations(HttpServerRequest request) {
		final String issuerId = request.params().get("id");
		repository.issuer(issuerId)
				.compose(issuer -> issuer == null
						? Future.failedFuture(BadgeException.notFound("issuer"))
						: repository.revokedOf(issuerId))
				.onSuccess(rows -> Http.publicJson(request,
						ctx.revocations.build(issuerId, BadgeRepository.rows(rows))))
				.onFailure(error -> Http.error(request, error));
	}
}
