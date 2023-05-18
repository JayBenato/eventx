package io.vertx.eventx;

import io.vertx.core.json.JsonObject;
import io.vertx.eventx.exceptions.UnknownEvent;
import io.vertx.eventx.objects.ErrorSource;
import io.vertx.eventx.objects.EventxError;

public interface Aggregator<T extends Aggregate, E extends Event> {

  T apply(T aggregateState, E event);

  default String tenantId() {
    return "default";
  }

  default int currentSchemaVersion() {
    return 0;
  }

  default E transformFrom(int schemaVersion, JsonObject event) {
    throw new UnknownEvent(new EventxError(
      ErrorSource.LOGIC,
      Aggregator.class.getName(),
      "missing schema versionTo " + schemaVersion,
      "could not transform event",
      "aggregate.event.transform",
      500
    )
    );
  }


}
