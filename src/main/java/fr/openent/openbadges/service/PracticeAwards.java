package fr.openent.openbadges.service;

import fr.openent.openbadges.OpenBadgesContext;
import fr.openent.openbadges.core.BadgeException;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Badges d'entraînement, décernés AUTOMATIQUEMENT à qui s'entraîne sur le site public d'exercices.
 *
 * <p>Le module exercices publie chaque tentative d'un usager connecté sur
 * {@value #ADDRESS} (réglage {@code practice-badges} d'exercizer). On relit alors toutes ses
 * tentatives alignées sur le référentiel : un badge du catalogue des programmes est décerné dès
 * que l'usager a réussi ({@code threshold}, 80 % par défaut) au moins {@code min-subjects}
 * exercices DIFFÉRENTS portant sur les énoncés que couvre ce badge.</p>
 *
 * <p>L'émetteur est la PLATEFORME, et non un établissement : personne, dans un établissement,
 * n'a validé ce badge, et l'usager du site public n'en a souvent aucun. L'attestation le dit
 * dans son récit (exercices réussis, scores), pour qu'un lecteur sache ce qu'elle prouve.</p>
 *
 * <p>Éteint par défaut ({@code practice-badges.enabled}).</p>
 */
public class PracticeAwards implements Handler<Message<JsonObject>> {

	public static final String ADDRESS = "exercizer.practice.completed";
	private static final String EXERCIZER = "exercizer.packs";
	private static final DeliveryOptions BUS = new DeliveryOptions().setSendTimeout(30_000L);
	private static final Logger log = LoggerFactory.getLogger(PracticeAwards.class);

	private final OpenBadgesContext ctx;
	private final double threshold;
	private final int minSubjects;
	/** Un usager à la fois : deux tentatives rapprochées ne décernent pas deux fois le même badge. */
	private final Set<String> busy = ConcurrentHashMap.newKeySet();

	public PracticeAwards(OpenBadgesContext ctx) {
		final JsonObject settings = ctx.config.getJsonObject("practice-badges", new JsonObject());
		this.ctx = ctx;
		this.threshold = settings.getDouble("threshold", 0.8);
		this.minSubjects = settings.getInteger("min-subjects", 3);
	}

	/** S'abonne aux tentatives si le réglage est actif. */
	public static void register(OpenBadgesContext ctx) {
		if (ctx.config.getJsonObject("practice-badges", new JsonObject()).getBoolean("enabled", false)) {
			ctx.eb.consumer(ADDRESS, new PracticeAwards(ctx));
			log.info("[openbadges] badges d'entraînement actifs (" + ADDRESS + ")");
		}
	}

	@Override
	public void handle(Message<JsonObject> message) {
		final String userId = message.body().getString("userId");
		if (userId == null || !busy.add(userId)) {
			return;
		}
		award(userId)
				.onSuccess(awarded -> {
					if (!awarded.isEmpty()) {
						log.info("[openbadges] badges d'entraînement décernés à " + userId + " : " + awarded);
					}
				})
				.onFailure(error -> log.error("[openbadges] badges d'entraînement de " + userId, error))
				.onComplete(done -> busy.remove(userId));
	}

	/** Décerne à cet usager les badges que ses tentatives justifient et qu'il n'a pas encore. */
	public Future<List<String>> award(String userId) {
		return Future.all(results(userId), ctx.badges.catalogueObjectives(),
						ctx.badges.heldBy(Collections.singletonList(userId)))
				.compose(all -> {
					final Set<String> held = new HashSet<String>();
					final JsonArray heldRows = all.resultAt(2);
					for (int i = 0; i < heldRows.size(); i++) {
						held.add(heldRows.getJsonObject(i).getString("achievement_id"));
					}
					final List<JsonObject> due = eligible(all.resultAt(1), held, all.resultAt(0),
							threshold, minSubjects);
					if (due.isEmpty()) {
						return Future.succeededFuture(Collections.<String>emptyList());
					}
					return Future.all(ctx.directory.emailOf(userId), platformIssuer()).compose(both -> {
						final String email = both.resultAt(0);
						final JsonObject issuer = both.resultAt(1);
						Future<List<String>> chain = Future.succeededFuture(new ArrayList<String>());
						for (JsonObject badge : due) {
							chain = chain.compose(done -> ctx.badges.achievement(badge.getString("id"))
									.compose(achievement -> CredentialIssuer.sign(ctx, new JsonObject()
													.put("userId", userId).put("email", email)
													.put("narrative", narrative(badge.getJsonArray("evidence"))),
											achievement, issuer, "practice"))
									.map(signed -> {
										done.add(badge.getString("name"));
										return done;
									}));
						}
						return chain;
					});
				});
	}

	/**
	 * Les badges dus : pour chaque badge du catalogue que l'usager n'a pas, les exercices réussis
	 * qui portent sur ses énoncés, un par sujet, au meilleur score. Dû à partir de
	 * {@code minSubjects} sujets distincts.
	 *
	 * @param catalogue lignes {@code id, name, objectives} (énoncés couverts)
	 * @param results   lignes {@code subjectId, cfitem, title, score, maxScore}
	 * @return les badges dus, chacun avec ses preuves ({@code evidence})
	 */
	static List<JsonObject> eligible(JsonArray catalogue, Set<String> held, JsonArray results,
									 double threshold, int minSubjects) {
		// Énoncé → sujets réussis (meilleur score par sujet).
		final Map<String, Map<Object, JsonObject>> passedByItem = new LinkedHashMap<String, Map<Object, JsonObject>>();
		for (int i = 0; i < results.size(); i++) {
			final JsonObject result = results.getJsonObject(i);
			final Double score = result.getDouble("score");
			final Double max = result.getDouble("maxScore");
			if (score == null || max == null || max <= 0 || score / max < threshold) {
				continue;
			}
			final Map<Object, JsonObject> subjects = passedByItem.computeIfAbsent(
					String.valueOf(result.getValue("cfitem")), k -> new LinkedHashMap<Object, JsonObject>());
			final Object subject = String.valueOf(result.getValue("subjectId"));
			final JsonObject best = subjects.get(subject);
			if (best == null || best.getDouble("score") / best.getDouble("maxScore") < score / max) {
				subjects.put(subject, new JsonObject().put("subjectId", result.getValue("subjectId"))
						.put("title", result.getString("title")).put("score", score).put("maxScore", max));
			}
		}
		final List<JsonObject> due = new ArrayList<JsonObject>();
		for (int i = 0; i < catalogue.size(); i++) {
			final JsonObject badge = catalogue.getJsonObject(i);
			if (held.contains(badge.getString("id"))) {
				continue;
			}
			final Map<Object, JsonObject> evidence = new LinkedHashMap<Object, JsonObject>();
			for (Object item : Db.jsonArray(badge, "objectives")) {
				for (Map.Entry<Object, JsonObject> e : passedByItem
						.getOrDefault(String.valueOf(item), Collections.<Object, JsonObject>emptyMap()).entrySet()) {
					evidence.putIfAbsent(e.getKey(), e.getValue());
				}
			}
			if (evidence.size() >= minSubjects) {
				due.add(new JsonObject().put("id", badge.getString("id")).put("name", badge.getString("name"))
						.put("evidence", new JsonArray(new ArrayList<Object>(evidence.values()))));
			}
		}
		return due;
	}

	/** Le récit de l'attestation : ce qu'elle prouve, et rien de plus. */
	static String narrative(JsonArray evidence) {
		final StringBuilder text = new StringBuilder(
				"Badge d'entraînement, décerné automatiquement après la réussite de ces exercices autocorrigés :");
		for (int i = 0; i < evidence.size(); i++) {
			final JsonObject e = evidence.getJsonObject(i);
			text.append("\n- ").append(e.getString("title")).append(" : ")
					.append(format(e.getDouble("score"))).append(" / ").append(format(e.getDouble("maxScore")));
		}
		return text.toString();
	}

	private static String format(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	private Future<JsonArray> results(String userId) {
		final Promise<JsonArray> promise = Promise.promise();
		ctx.eb.<JsonObject>request(EXERCIZER, new JsonObject().put("action", "practiceResults").put("userId", userId),
				BUS, reply -> {
					if (reply.succeeded() && "ok".equals(reply.result().body().getString("status"))) {
						promise.complete(reply.result().body().getJsonArray("results", new JsonArray()));
					} else {
						promise.fail(reply.failed() ? reply.cause().getMessage()
								: reply.result().body().getString("message"));
					}
				});
		return promise.future();
	}

	private Future<JsonObject> platformIssuer() {
		return ctx.badges.issuer(BundledCatalogue.PLATFORM_ISSUER).compose(issuer -> issuer == null
				? Future.<JsonObject>failedFuture(BadgeException.notFound("issuer"))
				: Future.succeededFuture(issuer));
	}
}
