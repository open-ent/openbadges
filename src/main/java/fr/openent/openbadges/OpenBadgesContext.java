package fr.openent.openbadges;

import fr.openent.openbadges.badge.Credentials;
import fr.openent.openbadges.badge.RevocationList;
import fr.openent.openbadges.core.BadgeException;
import fr.openent.openbadges.core.Uris;
import fr.openent.openbadges.security.AccessTokens;
import fr.openent.openbadges.security.Authenticator;
import fr.openent.openbadges.security.KeyManager;
import fr.openent.openbadges.service.BadgeRepository;
import fr.openent.openbadges.service.ClientRepository;
import fr.openent.openbadges.service.DirectoryService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;

/** Dépendances partagées par les contrôleurs du module. */
public class OpenBadgesContext {

	public final Vertx vertx;
	public final EventBus eb;
	public final JsonObject config;
	public final String publicBase;
	public final long accessTokenTtl;
	public final int maxLimit;
	public final Uris uris;
	public final KeyManager keys;
	public final AccessTokens tokens;
	public final Credentials credentials;
	public final RevocationList revocations;
	public final ClientRepository clients = new ClientRepository();
	public final BadgeRepository badges = new BadgeRepository();
	public final DirectoryService directory = new DirectoryService();
	public final Authenticator auth;

	public OpenBadgesContext(Vertx vertx, EventBus eb, JsonObject config) {
		final JsonObject settings = config.getJsonObject("openbadges", new JsonObject());
		this.vertx = vertx;
		this.eb = eb;
		this.config = settings;
		this.publicBase = stripTrailingSlash(settings.getString("issuer",
				config.getString("host", "http://localhost:8090")));
		this.accessTokenTtl = settings.getLong("access-token-ttl-seconds", 3600L);
		this.maxLimit = settings.getInteger("max-limit", 500);
		this.uris = new Uris(publicBase);
		this.keys = KeyManager.fromConfig(settings);
		this.tokens = new AccessTokens(keys, publicBase);
		this.credentials = new Credentials(uris);
		this.revocations = new RevocationList(uris);
		this.auth = new Authenticator(tokens, clients);
	}

	/**
	 * L'émetteur par défaut de la plateforme.
	 *
	 * <p>Le premier déclaré, à défaut d'un identifiant configuré. Sans aucun émetteur, le
	 * service refuse plutôt que d'en inventer un : une attestation sans émetteur identifiable
	 * n'atteste rien.</p>
	 */
	public Future<JsonObject> defaultIssuer() {
		final String configured = config.getString("default-issuer");
		if (configured != null && !configured.isEmpty()) {
			return badges.issuer(configured).compose(issuer -> issuer == null
					? Future.<JsonObject>failedFuture(BadgeException.notFound("issuer"))
					: Future.succeededFuture(issuer));
		}
		return badges.issuers().compose(rows -> rows.isEmpty()
				? Future.<JsonObject>failedFuture(new BadgeException(404, "no.issuer.configured"))
				: Future.succeededFuture(rows.getJsonObject(0)));
	}

	public String tokenEndpoint() {
		return uris.tokenEndpoint();
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
