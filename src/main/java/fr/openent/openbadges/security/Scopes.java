package fr.openent.openbadges.security;

import io.vertx.core.json.JsonArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Scopes OAuth 2.0 définis par l'API Open Badges 3.0. */
public final class Scopes {

	private static final String PREFIX = "https://purl.imsglobal.org/spec/ob/v3p0/scope/";

	public static final String CREDENTIAL_READONLY = PREFIX + "credential.readonly";
	public static final String CREDENTIAL_UPSERT = PREFIX + "credential.upsert";
	public static final String PROFILE_READONLY = PREFIX + "profile.readonly";
	public static final String PROFILE_UPDATE = PREFIX + "profile.update";

	public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
			CREDENTIAL_READONLY, CREDENTIAL_UPSERT, PROFILE_READONLY, PROFILE_UPDATE));

	private Scopes() {
	}

	public static List<String> split(String scope) {
		final List<String> scopes = new ArrayList<String>();
		if (scope != null) {
			for (String s : scope.trim().split("\\s+")) {
				if (!s.isEmpty() && !scopes.contains(s)) {
					scopes.add(s);
				}
			}
		}
		return scopes;
	}

	/**
	 * Scopes accordés : ceux demandés et autorisés pour le client. Sans scope demandé, le client
	 * reçoit tous ses scopes autorisés (RFC 6749 §3.3 laisse ce choix au serveur).
	 */
	public static List<String> grant(String requested, JsonArray allowed) {
		final List<String> granted = new ArrayList<String>();
		final List<String> wanted = split(requested);
		if (allowed == null) {
			return granted;
		}
		if (wanted.isEmpty()) {
			for (Object o : allowed) {
				if (o instanceof String && ALL.contains(o) && !granted.contains(o)) {
					granted.add((String) o);
				}
			}
			return granted;
		}
		for (String s : wanted) {
			if (allowed.contains(s) && !granted.contains(s)) {
				granted.add(s);
			}
		}
		return granted;
	}
}
