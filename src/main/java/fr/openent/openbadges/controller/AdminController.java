package fr.openent.openbadges.controller;

import fr.openent.openbadges.OpenBadgesContext;
import fr.openent.openbadges.api.Http;
import fr.openent.openbadges.badge.Credentials;
import fr.openent.openbadges.core.BadgeException;
import fr.openent.openbadges.core.Uris;
import fr.openent.openbadges.security.Scopes;
import fr.openent.openbadges.security.Secrets;
import fr.openent.openbadges.service.BadgeRepository;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.rs.Put;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Administration : émetteurs, badges, clients OAuth et délivrance des attestations.
 *
 * <p>Deux niveaux de droit, et la distinction est volontaire. <strong>Configurer</strong> —
 * créer un émetteur, définir un badge, ouvrir un client — relève du super administrateur.
 * <strong>Délivrer</strong> relève d'un droit de workflow, {@code openbadges.issue}, qui
 * s'attribue par profil dans la gestion des accès : c'est un enseignant qui sait qu'un élève a
 * mérité son badge, pas l'administrateur de la plateforme.</p>
 */
public class AdminController extends BaseController {

	private static final DateTimeFormatter SQL_TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final OpenBadgesContext ctx;
	private final BadgeRepository repository;

	public AdminController(OpenBadgesContext ctx) {
		this.ctx = ctx;
		this.repository = ctx.badges;
		this.pathPrefix = Uris.APP;
	}

	// ================================================================= configuration

	@Get("/admin/configuration")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void configuration(HttpServerRequest request) {
		renderJson(request, new JsonObject()
				.put("api", ctx.uris.api())
				.put("discovery", ctx.uris.api() + "/discovery")
				.put("token_endpoint", ctx.uris.tokenEndpoint())
				.put("jwks_uri", ctx.uris.jwks())
				.put("scopes", new JsonArray(new ArrayList<Object>(Scopes.ALL)))
				.put("kid", ctx.keys.getKid()));
	}

	// ================================================================= émetteurs

	@Get("/admin/issuers")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void issuers(HttpServerRequest request) {
		repository.issuers()
				.onSuccess(rows -> renderJson(request, new JsonObject().put("issuers", rows)))
				.onFailure(error -> Http.error(request, error));
	}

	@Post("/admin/issuers")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void createIssuer(HttpServerRequest request) {
		user(request).compose(user -> Http.json(request).compose(body -> {
			if (blank(body.getString("name"))) {
				return Future.<JsonObject>failedFuture(new BadgeException("issuer.name.required"));
			}
			return repository.createIssuer(UUID.randomUUID().toString(), body, user.getUserId());
		}))
				.onSuccess(row -> Http.json(request, 201, ctx.credentials.issuer(row)))
				.onFailure(error -> Http.error(request, error));
	}

	// ================================================================= badges

	@Get("/admin/achievements")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void achievements(HttpServerRequest request) {
		repository.achievements()
				.onSuccess(rows -> {
					final JsonArray achievements = new JsonArray();
					for (int i = 0; i < rows.size(); i++) {
						achievements.add(ctx.credentials.achievement(rows.getJsonObject(i)));
					}
					renderJson(request, new JsonObject().put("achievements", achievements));
				})
				.onFailure(error -> Http.error(request, error));
	}

	@Post("/admin/achievements")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void createAchievement(HttpServerRequest request) {
		user(request).compose(user -> Http.json(request).compose(body -> {
			if (blank(body.getString("name")) || blank(body.getString("description"))
					|| blank(body.getString("issuerId"))) {
				return Future.<JsonObject>failedFuture(
						new BadgeException("achievement.name.description.issuer.required"));
			}
			return repository.issuer(body.getString("issuerId")).compose(issuer -> issuer == null
					? Future.<JsonObject>failedFuture(BadgeException.notFound("issuer"))
					: repository.createAchievement(UUID.randomUUID().toString(), body,
							user.getUserId()));
		}))
				.onSuccess(row -> Http.json(request, 201, ctx.credentials.achievement(row)))
				.onFailure(error -> Http.error(request, error));
	}

	@Delete("/admin/achievements/:id")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void deleteAchievement(HttpServerRequest request) {
		// Les attestations déjà délivrées disparaissent avec le badge (ON DELETE CASCADE) : ce
		// n'est pas une révocation, c'est un effacement, et il est réservé au super
		// administrateur pour cette raison.
		repository.deleteAchievement(request.params().get("id"))
				.onSuccess(rows -> {
					if (rows.isEmpty()) {
						Http.error(request, BadgeException.notFound("achievement"));
						return;
					}
					Http.noContent(request);
				})
				.onFailure(error -> Http.error(request, error));
	}

	// ================================================================= délivrance

	/**
	 * Délivre une attestation.
	 *
	 * <p>La signature est posée <strong>une fois</strong> et conservée. Resigner à la demande
	 * produirait un JWS différent à chaque appel — même contenu, signature distincte — et un
	 * porteur qui a archivé la première ne pourrait plus montrer qu'il s'agit de la même.</p>
	 */
	@Post("/admin/credentials")
	@SecuredAction("openbadges.issue")
	public void issue(HttpServerRequest request) {
		user(request).compose(user -> Http.json(request).compose(body -> {
			final String achievementId = body.getString("achievementId");
			final String userId = body.getString("userId");
			if (blank(achievementId) || blank(userId)) {
				return Future.<JsonObject>failedFuture(
						new BadgeException("achievement.and.user.required"));
			}
			return repository.achievement(achievementId).compose(achievement -> {
				if (achievement == null) {
					return Future.<JsonObject>failedFuture(BadgeException.notFound("achievement"));
				}
				// L'émetteur est l'ÉTABLISSEMENT qui décerne, pas le propriétaire du badge :
				// un badge est défini une fois et décerné par chacun en son nom.
				return issuerFor(body, user).compose(issuer ->
						sign(body, achievement, issuer, user.getUserId()));
			});
		}))
				.onSuccess(result -> Http.json(request, 201, result))
				.onFailure(error -> Http.error(request, error));
	}

	/**
	 * L'émetteur au nom duquel on délivre.
	 *
	 * <p>L'établissement est celui que la requête désigne. S'il n'est pas précisé et que
	 * l'enseignant n'en a qu'un, c'est celui-là ; s'il en a plusieurs, on <strong>refuse</strong>
	 * plutôt que de choisir — signer au nom du mauvais établissement produirait une attestation
	 * fausse que personne ne pourrait distinguer d'une vraie.</p>
	 *
	 * <p>L'émetteur est créé à la volée s'il n'existe pas encore : un établissement de
	 * l'annuaire n'a pas à être saisi deux fois.</p>
	 */
	private Future<JsonObject> issuerFor(JsonObject body, UserInfos user) {
		final String declared = body.getString("structureId");
		if (!blank(declared)) {
			return ensureIssuer(declared, user.getUserId());
		}
		return ctx.directory.structuresOf(user.getUserId()).compose(structures -> {
			if (structures.isEmpty()) {
				return Future.<JsonObject>failedFuture(
						new BadgeException("no.structure.for.user"));
			}
			if (structures.size() > 1) {
				return Future.<JsonObject>failedFuture(
						new BadgeException("structureId.required.several.structures"));
			}
			return ensureIssuer(structures.getJsonObject(0).getString("id"), user.getUserId());
		});
	}

	private Future<JsonObject> ensureIssuer(String structureId, String userId) {
		return repository.issuerOfStructure(structureId).compose(existing -> {
			if (existing != null) {
				return Future.succeededFuture(existing);
			}
			return ctx.directory.structure(structureId).compose(structure -> {
				if (structure == null) {
					return Future.<JsonObject>failedFuture(BadgeException.notFound("structure"));
				}
				return repository.upsertIssuerForStructure(UUID.randomUUID().toString(),
						structureId, structure.getString("name"), ctx.publicBase, userId);
			});
		});
	}

	/**
	 * Crée l'émetteur de chaque établissement d'enseignement peuplé.
	 *
	 * <p>Idempotent : relançable, et un établissement renommé dans l'annuaire voit son émetteur
	 * suivre sans changer d'identifiant — celui-ci est inscrit dans les attestations déjà
	 * délivrées et ne doit jamais bouger.</p>
	 */
	@Post("/admin/issuers/sync")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void syncIssuers(HttpServerRequest request) {
		user(request).compose(user -> ctx.directory.teachingStructures().compose(structures -> {
			final List<Future> creations = new ArrayList<Future>();
			for (int i = 0; i < structures.size(); i++) {
				final JsonObject structure = structures.getJsonObject(i);
				creations.add(repository.upsertIssuerForStructure(UUID.randomUUID().toString(),
						structure.getString("id"), structure.getString("name"), ctx.publicBase,
						user.getUserId()));
			}
			return io.vertx.core.CompositeFuture.join(creations)
					.map(v -> new JsonObject().put("structures", structures.size()));
		}))
				.onSuccess(result -> Http.json(request, 200, result))
				.onFailure(error -> Http.error(request, error));
	}

	private Future<JsonObject> sign(JsonObject body, JsonObject achievement, JsonObject issuer,
									String issuedBy) {
		final String id = UUID.randomUUID().toString();
		final String now = LocalDateTime.now(ZoneOffset.UTC).format(SQL_TIMESTAMP);
		final String email = body.getString("email");
		final String salt = email == null ? null : Credentials.salt();

		final JsonObject row = new JsonObject()
				.put("id", id)
				.put("achievement_id", achievement.getString("id"))
				.put("issuer_id", issuer.getString("id"))
				.put("subject_user_id", body.getString("userId"))
				.put("name", body.getString("name", achievement.getString("name")))
				.put("narrative", body.getValue("narrative"))
				.put("identity_type", "emailAddress")
				.put("identity_hash", Credentials.identityHash(email, salt))
				.put("identity_salt", salt)
				.put("valid_from", now)
				.put("valid_until", body.getValue("validUntil"))
				.put("created_by", issuedBy);

		final JsonObject credential = ctx.credentials.credential(row, achievement, issuer);
		final String jws = ctx.keys.sign(
				ctx.credentials.jwtPayload(credential, System.currentTimeMillis() / 1000L));
		return repository.insertCredential(row.copy().put("jws", jws))
				.map(stored -> new JsonObject()
						.put("credential", credential)
						.put("compactJwsString", jws));
	}

	/** Retire une attestation. Elle reste signée : c'est la liste de révocation qui fait foi. */
	@Put("/admin/credentials/:id/revocation")
	@SecuredAction("openbadges.issue")
	public void revoke(HttpServerRequest request) {
		Http.json(request)
				.compose(body -> repository.revoke(request.params().get("id"),
						body.getString("reason")))
				.compose(row -> {
					if (row == null) {
						return Future.<JsonObject>failedFuture(BadgeException.notFound("credential"));
					}
					// La liste où le vérificateur ira constater la révocation est celle de
					// l'établissement qui a délivré — l'émetteur porté par l'attestation.
					return Future.succeededFuture(new JsonObject()
							.put("id", ctx.uris.credential(row.getString("id")))
							.put("revoked", Boolean.TRUE)
							.put("revocationReason", row.getString("revoked_reason"))
							.put("revocationList",
									ctx.uris.revocationList(row.getString("issuer_id"))));
				})
				.onSuccess(result -> Http.json(request, 200, result))
				.onFailure(error -> Http.error(request, error));
	}

	// ================================================================= clients OAuth

	@Get("/admin/clients")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void clients(HttpServerRequest request) {
		ctx.clients.list()
				.onSuccess(rows -> renderJson(request, new JsonObject().put("clients", rows)))
				.onFailure(error -> Http.error(request, error));
	}

	@Post("/admin/clients")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void createClient(HttpServerRequest request) {
		user(request).compose(user -> Http.json(request).compose(body -> {
			if (blank(body.getString("name"))) {
				return Future.<JsonObject>failedFuture(new BadgeException("client.name.required"));
			}
			final String id = "ob-" + UUID.randomUUID().toString();
			final String secret = Secrets.random(32);
			return ctx.clients.create(id, body, Secrets.hash(secret), user.getUserId())
					// Le secret n'est montré qu'ici : seule son empreinte est conservée, et il
					// n'existe aucun moyen de le relire ensuite.
					.map(row -> row.copy().put("client_secret", secret));
		}))
				.onSuccess(row -> Http.json(request, 201, row))
				.onFailure(error -> Http.error(request, error));
	}

	@Delete("/admin/clients/:id")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void deleteClient(HttpServerRequest request) {
		ctx.clients.delete(request.params().get("id"))
				.onSuccess(rows -> {
					if (rows.isEmpty()) {
						Http.error(request, BadgeException.notFound("client"));
						return;
					}
					Http.noContent(request);
				})
				.onFailure(error -> Http.error(request, error));
	}

	private Future<UserInfos> user(HttpServerRequest request) {
		final Promise<UserInfos> promise = Promise.promise();
		UserUtils.getUserInfos(eb, request, user -> {
			if (user == null) {
				promise.fail(new BadgeException(401, "unauthorized"));
			} else {
				promise.complete(user);
			}
		});
		return promise.future();
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
