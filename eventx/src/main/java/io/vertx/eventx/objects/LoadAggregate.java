package io.vertx.eventx.objects;

import io.vertx.eventx.Command;

import java.time.Instant;

public record LoadAggregate(
  String aggregateId,
  Long versionTo,
  Instant dateTo,
  CommandHeaders headers

) implements Command {
}
