package fr.openent.openbadges.service;

import fr.wseduc.webutils.Either;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;

/**
 * Ce que l'annuaire sait des établissements.
 *
 * <p>Un émetteur Open Badges n'est pas une fiche à saisir : c'est un établissement qui existe
 * déjà dans l'annuaire, avec son nom et son UAI. Les recopier à la main, c'est se garantir deux
 * sources de vérité qui divergeront — un établissement renommé laisserait une attestation au
 * nom d'un collège qui n'existe plus sous ce nom.</p>
 */
public class DirectoryService {

	/**
	 * Les établissements d'<strong>enseignement</strong> qui ont au moins un usager.
	 *
	 * <p>Les deux conditions comptent. L'annuaire national verse aussi des services — budget,
	 * CASNAV, circonscriptions, inspections — qui ne décerneront jamais de badge ; et une
	 * structure sans usager ne décernera rien non plus. Sur une instance réelle, cela ramène
	 * 534 structures à 360 établissements.</p>
	 */
	public Future<JsonArray> teachingStructures() {
		return query(
				"MATCH (s:Structure) "
				+ "WHERE toUpper(coalesce(s.type,'')) CONTAINS 'COLLEGE' "
				+ "   OR toUpper(coalesce(s.type,'')) CONTAINS 'ECOLE' "
				+ "   OR toUpper(coalesce(s.type,'')) CONTAINS 'LYCEE' "
				+ "MATCH (s)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) "
				+ "WITH s, count(DISTINCT u) AS users WHERE users > 0 "
				+ "RETURN s.id AS id, s.name AS name, s.UAI AS uai "
				+ "ORDER BY s.name", new JsonObject());
	}

	/** Un établissement précis, ou {@code null}. */
	public Future<JsonObject> structure(String structureId) {
		return query("MATCH (s:Structure {id:{id}}) RETURN s.id AS id, s.name AS name, s.UAI AS uai",
				new JsonObject().put("id", structureId))
				.map(rows -> rows.isEmpty() ? null : rows.getJsonObject(0));
	}

	/**
	 * Les établissements d'un usager.
	 *
	 * <p>Sert à savoir au nom de qui un enseignant délivre. Un enseignant rattaché à deux
	 * établissements doit préciser lequel : deviner lui ferait signer au nom du mauvais.</p>
	 */
	public Future<JsonArray> structuresOf(String userId) {
		return query("MATCH (u:User {id:{id}})-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) "
				+ "RETURN DISTINCT s.id AS id, s.name AS name, s.UAI AS uai ORDER BY s.name",
				new JsonObject().put("id", userId));
	}

	private Future<JsonArray> query(String cypher, JsonObject params) {
		final Promise<JsonArray> promise = Promise.promise();
		Neo4j.getInstance().execute(cypher, params, Neo4jResult.validResultHandler(
				(Either<String, JsonArray> either) -> {
					if (either.isRight()) {
						promise.complete(either.right().getValue());
					} else {
						promise.fail(either.left().getValue());
					}
				}));
		return promise.future();
	}
}
