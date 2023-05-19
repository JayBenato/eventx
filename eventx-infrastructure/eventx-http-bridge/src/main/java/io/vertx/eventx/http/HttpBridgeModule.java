package io.vertx.eventx.http;

import io.activej.inject.Injector;
import io.activej.inject.annotation.Inject;
import io.activej.inject.annotation.Provides;
import io.vertx.eventx.infrastructure.Bridge;
import io.vertx.eventx.infrastructure.misc.CustomClassLoader;
import io.vertx.eventx.core.objects.EventxModule;

import java.util.List;

public class HttpBridgeModule extends EventxModule {

  @Provides
  @Inject
  Bridge bridge(final CommandAuth commandAuth, final List<HttpRoute> routes, final List<HealthCheck> healthChecks) {
    return new HttpBridge(commandAuth,routes, healthChecks);
  }

  @Provides
  @Inject
  List<HttpRoute> httpRoutes(Injector injector) {
    return CustomClassLoader.loadFromInjector(injector, HttpRoute.class);
  }

  @Provides
  @Inject
  List<HealthCheck> healthChecks(Injector injector) {
    return CustomClassLoader.loadFromInjector(injector, HealthCheck.class);
  }

  @Provides
  @Inject
  CommandAuth commandAuth(Injector injector) {
    if (CustomClassLoader.checkPresence(injector,CommandAuth.class)) {

    }
    return CustomClassLoader.loadFromInjectorClass(injector, CommandAuth.class)
      .stream().findFirst().orElse(null);
  }

}
