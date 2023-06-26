package io.eventx.queue.postgres.models;

import io.eventx.sql.models.Query;
import io.eventx.sql.models.QueryOptions;
import io.soabase.recordbuilder.core.RecordBuilder;
import io.eventx.queue.models.MessageState;

import java.time.Instant;
import java.util.List;

@RecordBuilder
public record MessageRecordQuery(
  List<String> ids,
  List<MessageState> states,
  Instant scheduledFrom,
  Instant scheduledTo,
  Instant expirationFrom,
  Instant expirationTo,
  Integer priorityFrom,
  Integer priorityTo,
  Integer retryCounterFrom,
  Integer retryCounterTo,
  QueryOptions options
) implements Query {
}