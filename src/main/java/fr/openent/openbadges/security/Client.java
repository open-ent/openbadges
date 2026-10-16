package fr.openent.openbadges.security;

import fr.openent.openbadges.service.Db;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client Open Badges authentifié : son identifiant et les scopes du jeton présenté.
 *
 * <p>Pas de périmètre d'établissement, contrairement à OneRoster : une attestation n'appartient
 * pas à un établissement mais à la personne qui l'a obtenue, et l'API ne sert jamais que les
 * attestations du porteur du jeton.</p>
 */
public final class Client {

	private final String id;
	private final String name;
	private final List<String> scopes;

	public Client(String id, String name, List<String> scopes) {
		this.id = id;
		this.name = name;
		this.scopes = Collections.unmodifiableList(new ArrayList<String>(scopes));
	}

	public static Client fromRow(JsonObject row, List<String> tokenScopes) {
		return new Client(row.getString("id"), row.getString("name"), tokenScopes);
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public List<String> getScopes() {
		return scopes;
	}

	/** Vrai si le jeton porte au moins un des scopes acceptés. */
	public boolean hasAny(String... accepted) {
		for (String s : accepted) {
			if (scopes.contains(s)) {
				return true;
			}
		}
		return false;
	}

	public static List<String> strings(JsonArray array) {
		final List<String> list = new ArrayList<String>();
		if (array != null) {
			for (Object o : array) {
				if (o instanceof String && !((String) o).isEmpty() && !list.contains(o)) {
					list.add((String) o);
				}
			}
		}
		return list;
	}

	/** Les scopes autorisés d'une ligne {@code openbadges.client}. */
	public static List<String> allowedScopes(JsonObject row) {
		return strings(Db.jsonArray(row, "scopes"));
	}
}
