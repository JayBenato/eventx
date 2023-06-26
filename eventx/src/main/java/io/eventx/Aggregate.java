package io.eventx;

import io.eventx.core.objects.EventxError;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.Shareable;
import io.eventx.core.exceptions.UnknownEvent;
import io.eventx.core.objects.ErrorSource;

import java.io.Serializable;

public interface Aggregate extends Shareable, Serializable {

  String aggregateId();

  default String tenant() {
    return "default";
  }

  default int schemaVersion() {
    return 0;
  }

  default Aggregate transformSnapshot(int schemaVersion, JsonObject snapshot) {
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