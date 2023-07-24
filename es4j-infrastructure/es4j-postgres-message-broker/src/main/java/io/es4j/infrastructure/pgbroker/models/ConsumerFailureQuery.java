package io.es4j.infrastructure.pgbroker.models;


import io.es4j.sql.models.Query;
import io.es4j.sql.models.QueryOptions;
import io.soabase.recordbuilder.core.RecordBuilder;

import java.util.List;

@RecordBuilder
public record ConsumerFailureQuery(
  List<String> ids,
  List<String> consumers,
  QueryOptions options
) implements Query {
}
