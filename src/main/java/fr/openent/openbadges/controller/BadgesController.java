package fr.openent.openbadges.controller;

import fr.openent.openbadges.OpenBadgesContext;
import fr.openent.openbadges.api.Http;
import fr.openent.openbadges.core.BadgeException;
import fr.openent.openbadges.core.Uris;
import fr.openent.openbadges.service.BadgeRepository;
import fr.openent.openbadges.service.Db;
import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import fr.wseduc.webutils.http.BaseController;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Badges des programmes, côté enseignant et côté élève.
 *
 * <p>L'enseignant voit, pour une de ses classes, les badges que les résultats d'exercices
 * suggèrent : un exercice aligné sur un énoncé couvert par le badge, réussi au-dessus du seuil.
 * C'est une <strong>suggestion</strong>, avec ses preuves : le badge n'est décerné que lorsque
 * l'enseignant le valide (route de délivrance d'{@link AdminController}), puisqu'il est signé au
 * nom de l'établissement.</p>
 *
 * <p>L'élève, et ses parents, voient les badges obtenus et de quoi les faire vérifier ailleurs.</p>
 */
public class BadgesController extends BaseController {

	/** Adresse du module exercices, qui rend les résultats alignés sur le référentiel. */
	private static final String EXERCIZER = "exercizer.packs";
	private static final DeliveryOptions BUS = new DeliveryOptions().setSendTimeout(30_000L);

	private final OpenBadgesContext ctx;
	private final BadgeRepository repository;
	private final double threshold;

	public BadgesController(OpenBadgesContext ctx) {
		this.ctx = ctx;
		this.repository = ctx.badges;
		this.threshold = ctx.config.getDouble("suggestion-threshold", 0.7);
		this.pathPrefix = Uris.APP;
	}

	// ================================================================= enseignant

	@Get("/catalogue")
	@SecuredAction("openbadges.issue")
	public void catalogue(HttpServerRequest request) {
		repository.catalogue()
				.onSuccess(rows -> {
					for (int i = 0; i < rows.size(); i++) {
						final JsonObject row = rows.getJsonObject(i);
						row.put("levels", Db.jsonArray(row, "levels"));
					}
					Http.json(request, 200, new JsonObject().put("badges", rows));
				})
				.onFailure(error -> Http.error(request, error));
	}

	@Get("/classes")
	@SecuredAction("openbadges.issue")
	public void classes(HttpServerRequest request) {
		user(request).compose(user -> ctx.directory.classesOf(user.getUserId()))
				.onSuccess(rows -> Http.json(request, 200, new JsonObject().put("classes", rows)))
				.onFailure(error -> Http.error(request, error));
	}

	/**
	 * Les élèves d'une classe, leurs badges et les badges que leurs résultats suggèrent.
	 *
	 * <p>Réservé aux enseignants de la classe : les résultats d'un élève ne regardent que ceux
	 * qui lui font cours.</p>
	 */
	@Get("/classes/:classId/suggestions")
	@SecuredAction("openbadges.issue")
	public void suggestions(HttpServerRequest request) {
		final String classId = request.params().get("classId");
		user(request).compose(user -> ctx.directory.classesOf(user.getUserId()).compose(classes -> {
			JsonObject klass = null;
			for (int i = 0; i < classes.size(); i++) {
				if (classId.equals(classes.getJsonObject(i).getString("id"))) {
					klass = classes.getJsonObject(i);
				}
			}
			if (klass == null) {
				return Future.<JsonObject>failedFuture(new BadgeException(403, BadgeException.FORBIDDEN));
			}
			final JsonObject theClass = klass;
			return ctx.directory.studentsOf(classId).compose(students -> {
				final List<String> ids = new ArrayList<String>();
				for (int i = 0; i < students.size(); i++) {
					ids.add(students.getJsonObject(i).getString("id"));
				}
				return Future.all(repository.catalogueObjectives(), repository.heldBy(ids), results(ids))
						.map(all -> build(theClass, students, all.resultAt(0), all.resultAt(1), all.resultAt(2)));
			});
		}))
				.onSuccess(body -> Http.json(request, 200, body))
				.onFailure(error -> Http.error(request, error));
	}

	/** Les résultats alignés des élèves ; vide si le module exercices ne répond pas. */
	private Future<JsonArray> results(List<String> students) {
		if (students.isEmpty()) {
			return Future.succeededFuture(new JsonArray());
		}
		final Promise<JsonArray> promise = Promise.promise();
		ctx.eb.<JsonObject>request(EXERCIZER, new JsonObject().put("action", "alignedResults")
				.put("students", new JsonArray(new ArrayList<Object>(students))), BUS, reply -> {
			if (reply.succeeded() && "ok".equals(reply.result().body().getString("status"))) {
				promise.complete(reply.result().body().getJsonArray("results", new JsonArray()));
			} else {
				log.warn("[openbadges] résultats d'exercices indisponibles : "
						+ (reply.failed() ? reply.cause().getMessage() : reply.result().body().getString("message")));
				promise.complete(new JsonArray());
			}
		});
		return promise.future();
	}

	private JsonObject build(JsonObject klass, JsonArray students, JsonArray catalogue, JsonArray held,
							 JsonArray results) {
		// Énoncé du référentiel → badges qui le couvrent.
		final Map<String, List<JsonObject>> badgesOf = new HashMap<String, List<JsonObject>>();
		for (int i = 0; i < catalogue.size(); i++) {
			final JsonObject badge = catalogue.getJsonObject(i);
			final JsonObject summary = new JsonObject().put("id", badge.getString("id"))
					.put("name", badge.getString("name")).put("cycle", badge.getString("cycle"))
					.put("subject", badge.getString("subject"));
			for (Object item : Db.jsonArray(badge, "objectives")) {
				badgesOf.computeIfAbsent(String.valueOf(item), k -> new ArrayList<JsonObject>()).add(summary);
			}
		}
		final Map<String, Set<String>> holds = new HashMap<String, Set<String>>();
		for (int i = 0; i < held.size(); i++) {
			final JsonObject row = held.getJsonObject(i);
			holds.computeIfAbsent(row.getString("subject_user_id"), k -> new HashSet<String>())
					.add(row.getString("achievement_id"));
		}
		// Élève → badge → suggestion avec ses preuves (un exercice une seule fois par badge).
		final Map<String, Map<String, JsonObject>> suggested = new HashMap<String, Map<String, JsonObject>>();
		for (int i = 0; i < results.size(); i++) {
			final JsonObject result = results.getJsonObject(i);
			final Double score = result.getDouble("score");
			final Double max = result.getDouble("maxScore");
			if (score == null || max == null || max <= 0 || score / max < threshold) {
				continue;
			}
			final String student = result.getString("student");
			for (JsonObject badge : badgesOf.getOrDefault(result.getString("cfitem"), new ArrayList<JsonObject>())) {
				if (holds.getOrDefault(student, new HashSet<String>()).contains(badge.getString("id"))) {
					continue;
				}
				final JsonObject suggestion = suggested
						.computeIfAbsent(student, k -> new LinkedHashMap<String, JsonObject>())
						.computeIfAbsent(badge.getString("id"), k -> badge.copy().put("evidence", new JsonArray()));
				final JsonArray evidence = suggestion.getJsonArray("evidence");
				boolean known = false;
				for (int e = 0; e < evidence.size(); e++) {
					known |= String.valueOf(result.getValue("copyId")).equals(
							String.valueOf(evidence.getJsonObject(e).getValue("copyId")));
				}
				if (!known) {
					evidence.add(new JsonObject().put("copyId", result.getValue("copyId"))
							.put("title", result.getString("title")).put("score", score).put("maxScore", max)
							.put("date", result.getValue("date")));
				}
			}
		}
		final JsonArray rows = new JsonArray();
		for (int i = 0; i < students.size(); i++) {
			final JsonObject student = students.getJsonObject(i);
			final String id = student.getString("id");
			rows.add(new JsonObject().put("id", id).put("displayName", student.getString("displayName"))
					.put("held", new JsonArray(new ArrayList<Object>(holds.getOrDefault(id, new HashSet<String>()))))
					.put("suggestions", new JsonArray(new ArrayList<Object>(
							suggested.getOrDefault(id, new LinkedHashMap<String, JsonObject>()).values()))));
		}
		return new JsonObject().put("class", klass).put("threshold", threshold).put("students", rows);
	}

	// ================================================================= élève et parents

	/**
	 * Les badges d'un élève : les siens, ou ceux d'un de ses enfants pour un parent.
	 *
	 * <p>Chaque badge vient avec son adresse publique de vérification et l'attestation signée,
	 * que l'élève peut déposer dans un portefeuille de badges hors de l'ENT.</p>
	 */
	@Get("/wallet")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void wallet(HttpServerRequest request) {
		final String requested = request.params().get("userId");
		user(request).compose(user -> {
			final String self = user.getUserId();
			final Future<Boolean> allowed = requested == null || requested.equals(self)
					? Future.succeededFuture(true)
					: ctx.directory.isChildOf(requested, self);
			return allowed.compose(ok -> {
				if (!ok) {
					return Future.<JsonObject>failedFuture(new BadgeException(403, BadgeException.FORBIDDEN));
				}
				final String holder = requested == null ? self : requested;
				return Future.all(repository.walletOf(holder), ctx.directory.childrenOf(self))
						.map(all -> {
							final JsonArray rows = all.resultAt(0);
							final JsonArray credentials = new JsonArray();
							for (int i = 0; i < rows.size(); i++) {
								final JsonObject row = rows.getJsonObject(i);
								credentials.add(new JsonObject()
										.put("id", row.getString("id"))
										.put("name", row.getString("name"))
										.put("description", row.getString("description"))
										.put("narrative", row.getValue("narrative"))
										.put("cycle", row.getValue("cycle"))
										.put("subject", row.getValue("subject"))
										.put("issuer", row.getString("issuer_name"))
										.put("issuedAt", row.getValue("valid_from"))
										.put("revoked", row.getBoolean("revoked", false))
										.put("revokedReason", row.getValue("revoked_reason"))
										.put("verifyUrl", ctx.uris.credential(row.getString("id")))
										.put("compactJwsString", row.getString("jws")));
							}
							return new JsonObject().put("userId", holder).put("credentials", credentials)
									.put("children", all.<JsonArray>resultAt(1));
						});
			});
		})
				.onSuccess(body -> Http.json(request, 200, body))
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
}
