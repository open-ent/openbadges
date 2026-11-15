package fr.openent.openbadges.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PracticeAwardsTest {

	private static final JsonArray CATALOGUE = new JsonArray()
			.add(new JsonObject().put("id", "b-calcul").put("name", "Calculer")
					.put("objectives", new JsonArray().add("item-add").add("item-sub")))
			.add(new JsonObject().put("id", "b-lire").put("name", "Lire")
					.put("objectives", new JsonArray().add("item-lire")));

	private static JsonObject result(long subject, String item, double score) {
		return new JsonObject().put("subjectId", subject).put("cfitem", item)
				.put("title", "Sujet " + subject).put("score", score).put("maxScore", 10.0);
	}

	@Test
	public void troisSujetsReussisSurLesEnoncesDuBadgeLeDecernent() {
		final JsonArray results = new JsonArray()
				.add(result(1, "item-add", 9)).add(result(2, "item-sub", 8)).add(result(3, "item-add", 10))
				.add(result(4, "item-lire", 10));
		final List<JsonObject> due = PracticeAwards.eligible(CATALOGUE, Collections.<String>emptySet(), results, 0.8, 3);
		assertEquals(1, due.size());
		assertEquals("b-calcul", due.get(0).getString("id"));
		assertEquals(3, due.get(0).getJsonArray("evidence").size());
	}

	@Test
	public void unSujetCompteUneFoisEtSeulementReussi() {
		final JsonArray results = new JsonArray()
				// Le même sujet aligné sur deux énoncés du badge, et refait : un seul sujet.
				.add(result(1, "item-add", 9)).add(result(1, "item-sub", 9)).add(result(1, "item-add", 10))
				// Un échec ne compte pas.
				.add(result(2, "item-add", 7.9))
				.add(result(3, "item-sub", 8));
		assertTrue(PracticeAwards.eligible(CATALOGUE, Collections.<String>emptySet(), results, 0.8, 3).isEmpty());
		final List<JsonObject> two = PracticeAwards.eligible(CATALOGUE, Collections.<String>emptySet(), results, 0.8, 2);
		assertEquals(2, two.get(0).getJsonArray("evidence").size());
		// Le meilleur score du sujet sert de preuve.
		assertEquals(10.0, two.get(0).getJsonArray("evidence").getJsonObject(0).getDouble("score"), 0);
	}

	@Test
	public void unBadgeDetenuNEstPasRedecerne() {
		final JsonArray results = new JsonArray()
				.add(result(1, "item-add", 9)).add(result(2, "item-sub", 8)).add(result(3, "item-add", 10));
		assertTrue(PracticeAwards.eligible(CATALOGUE, new HashSet<String>(Collections.singleton("b-calcul")),
				results, 0.8, 3).isEmpty());
	}

	@Test
	public void leRecitDitCeQueLAttestationProuve() {
		final String text = PracticeAwards.narrative(new JsonArray()
				.add(new JsonObject().put("title", "Additions").put("score", 9.0).put("maxScore", 10.0))
				.add(new JsonObject().put("title", "Soustractions").put("score", 7.5).put("maxScore", 10.0)));
		assertTrue(text.startsWith("Badge d'entraînement, décerné automatiquement"));
		assertTrue(text.contains("\n- Additions : 9 / 10"));
		assertTrue(text.contains("\n- Soustractions : 7.5 / 10"));
	}
}
