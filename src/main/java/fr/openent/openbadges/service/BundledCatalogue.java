package fr.openent.openbadges.service;

import fr.openent.openbadges.OpenBadgesContext;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Catalogue de badges livré avec le module, chargé au démarrage.
 *
 * <p>Un badge par sous-domaine des programmes et par cycle, tiré du référentiel CASE du module
 * {@code case} ({@code deployment/catalogue/build.py}). Les badges sont créés par l'émetteur de la
 * plateforme et décernés par chaque établissement en son nom : un badge défini une fois sert à
 * tous. Leur alignement désigne l'énoncé du référentiel, servi publiquement par le module case —
 * un vérificateur peut donc lire quelle partie du programme l'attestation certifie.</p>
 *
 * <p>Le catalogue n'est réécrit que si le fichier a changé (empreinte dans {@code bundle}).</p>
 */
public class BundledCatalogue {

	private static final Logger log = LoggerFactory.getLogger(BundledCatalogue.class);
	private static final String RESOURCE = "catalogue/programmes-1d.json";
	private static final String CATALOGUE = "programmes-1d";
	/** Identifiant fixe de l'émetteur de la plateforme : il est inscrit dans chaque badge du catalogue. */
	private static final String PLATFORM_ISSUER = UUID.nameUUIDFromBytes(
			"openbadges:platform-issuer".getBytes(StandardCharsets.UTF_8)).toString();

	private final OpenBadgesContext ctx;

	public BundledCatalogue(OpenBadgesContext ctx) {
		this.ctx = ctx;
	}

	/**
	 * Charge le catalogue un peu après le démarrage : les scripts SQL du module (dont celui qui crée
	 * les colonnes du catalogue) s'exécutent en parallèle de son déploiement. Un échec est retenté
	 * une fois, puis journalisé — il ne bloque jamais le module.
	 */
	public void scheduleLoad() {
		ctx.vertx.setTimer(15_000L, t -> load().onFailure(first -> ctx.vertx.setTimer(60_000L, t2 -> load()
				.onFailure(error -> log.error("[openbadges] catalogue des programmes non chargé", error)))));
	}

	public Future<Void> load() {
		return read().compose(bytes -> {
			final String sha = sha256(bytes);
			return ctx.badges.bundle(RESOURCE).compose(existing -> {
				if (existing != null && sha.equals(existing.getString("sha256"))) {
					return Future.<Void>succeededFuture();
				}
				final JsonObject catalogue = new JsonObject(new String(bytes, StandardCharsets.UTF_8));
				final JsonArray badges = withAlignments(catalogue);
				return ctx.badges.upsertPlatformIssuer(PLATFORM_ISSUER,
								ctx.config.getString("platform-name", "Open ENT"), ctx.publicBase)
						.compose(issuer -> ctx.badges.saveCatalogue(RESOURCE, sha, CATALOGUE,
								issuer.getString("id"), badges))
						.onSuccess(v -> log.info("[openbadges] catalogue chargé : " + badges.size() + " badges"));
			});
		});
	}

	/** L'alignement vers l'énoncé du référentiel, à l'adresse publique du module case. */
	private JsonArray withAlignments(JsonObject catalogue) {
		final JsonArray badges = catalogue.getJsonArray("badges", new JsonArray());
		for (int i = 0; i < badges.size(); i++) {
			final JsonObject badge = badges.getJsonObject(i);
			badge.put("alignments", new JsonArray().add(new JsonObject()
					.put("targetName", badge.getString("targetName"))
					.put("targetUrl", ctx.publicBase + "/ims/case/v1p0/CFItems/" + badge.getString("caseItem"))
					.put("targetFramework", catalogue.getString("frameworkTitle"))
					.put("targetCode", badge.getString("code"))
					.put("targetType", "CFItem")));
		}
		return badges;
	}

	private Future<byte[]> read() {
		final Promise<byte[]> promise = Promise.promise();
		ctx.vertx.executeBlocking(blocking -> {
			try (InputStream in = BundledCatalogue.class.getClassLoader().getResourceAsStream(RESOURCE)) {
				if (in == null) {
					blocking.fail("ressource absente du module : " + RESOURCE);
					return;
				}
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				final byte[] buffer = new byte[65536];
				int n;
				while ((n = in.read(buffer)) > 0) {
					out.write(buffer, 0, n);
				}
				blocking.complete(out.toByteArray());
			} catch (IOException e) {
				blocking.fail(e);
			}
		}, false, promise);
		return promise.future();
	}

	static String sha256(byte[] bytes) {
		try {
			final StringBuilder hex = new StringBuilder();
			for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
