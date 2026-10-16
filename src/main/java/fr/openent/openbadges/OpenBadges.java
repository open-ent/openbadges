package fr.openent.openbadges;

import fr.openent.openbadges.controller.AdminController;
import fr.openent.openbadges.controller.ObController;
import fr.openent.openbadges.controller.PublicController;
import fr.openent.openbadges.controller.TokenController;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.entcore.common.http.BaseServer;

/**
 * Module Open Badges 3.0 (1EdTech) : Open ENT émetteur d'attestations vérifiables, signées en
 * VC-JWT et contrôlables hors de la plateforme.
 */
public class OpenBadges extends BaseServer {

	@Override
	public void start(final Promise<Void> startPromise) throws Exception {
		final Promise<Void> promise = Promise.promise();
		super.start(promise);
		promise.future()
				.compose(v -> initOpenBadges(new OpenBadgesContext(vertx, vertx.eventBus(), config)))
				.onComplete(startPromise);
	}

	private Future<Void> initOpenBadges(OpenBadgesContext context) {
		return addController(new TokenController(context))
				.compose(v -> addController(new ObController(context)))
				.compose(v -> addController(new PublicController(context)))
				.compose(v -> addController(new AdminController(context)))
				.mapEmpty();
	}
}
