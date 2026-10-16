package fr.openent.openbadges.security;

import fr.openent.openbadges.core.BadgeException;
import fr.openent.openbadges.service.ClientRepository;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

/** Authentifie un appel d'API : jeton Bearer valide, client toujours actif, scope requis présent. */
public class Authenticator {

	private final AccessTokens tokens;
	private final ClientRepository clients;

	public Authenticator(AccessTokens tokens, ClientRepository clients) {
		this.tokens = tokens;
		this.clients = clients;
	}

	/**
	 * @param acceptedScopes l'un de ces scopes suffit (ex. roster-core.readonly ou roster.readonly)
	 */
	public Future<Client> authenticate(HttpServerRequest request, String... acceptedScopes) {
		final JsonObject claims;
		try {
			claims = tokens.verify(request.getHeader("Authorization"), System.currentTimeMillis() / 1000L);
		} catch (BadgeException e) {
			return Future.failedFuture(e);
		}
		return clients.findEnabled(claims.getString("sub")).compose(row -> {
			if (row == null) {
				return Future.failedFuture(new BadgeException(401, "unknown.client"));
			}
			// Les scopes retirés au client depuis l'émission du jeton cessent d'être acceptés.
			final java.util.List<String> scopes = Scopes.split(claims.getString("scope"));
			scopes.retainAll(Client.allowedScopes(row));
			final Client client = Client.fromRow(row, scopes);
			if (!client.hasAny(acceptedScopes)) {
				return Future.failedFuture(new BadgeException(403, "insufficient.scope"));
			}
			return Future.succeededFuture(client);
		});
	}
}
