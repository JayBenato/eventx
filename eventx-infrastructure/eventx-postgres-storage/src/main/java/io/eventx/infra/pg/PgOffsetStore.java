package io.eventx.infra.pg;

import com.google.auto.service.AutoService;
import io.eventx.Aggregate;
import io.eventx.core.objects.JournalOffsetBuilder;
import io.eventx.infra.pg.mappers.JournalOffsetMapper;
import io.eventx.infra.pg.models.EventJournalOffSet;
import io.eventx.infra.pg.models.EventJournalOffSetKey;
import io.eventx.sql.LiquibaseHandler;
import io.eventx.sql.RepositoryHandler;
import io.eventx.sql.exceptions.IntegrityContraintViolation;
import io.eventx.sql.exceptions.NotFound;
import io.eventx.sql.models.EmptyQuery;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.eventx.infrastructure.OffsetStore;
import io.eventx.core.objects.JournalOffset;
import io.eventx.core.objects.JournalOffsetKey;
import io.eventx.sql.Repository;

import io.eventx.sql.models.BaseRecord;
import io.vertx.mutiny.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.eventx.core.CommandHandler.camelToKebab;


@AutoService(OffsetStore.class)
public class PgOffsetStore implements OffsetStore {
  private Repository<EventJournalOffSetKey, EventJournalOffSet, EmptyQuery> repository;

  private final Logger LOGGER = LoggerFactory.getLogger(PgOffsetStore.class);

  @Override
  public Uni<Void> stop() {
    return repository.repositoryHandler().close();
  }

  @Override
  public void start(Class<? extends Aggregate> aggregateClass, Vertx vertx, JsonObject config) {
    this.repository = new Repository<>(JournalOffsetMapper.INSTANCE, RepositoryHandler.leasePool(config, vertx));
  }

  @Override
  public Uni<JournalOffset> put(JournalOffset journalOffset) {
    return repository.insert(getOffSet(journalOffset))
      .onFailure(IntegrityContraintViolation.class)
      .recoverWithUni(repository.updateByKey(getOffSet(journalOffset)))
      .map(PgOffsetStore::getJournalOffset);
  }


  private static EventJournalOffSet getOffSet(JournalOffset journalOffset) {
    return new EventJournalOffSet(
      journalOffset.consumer(),
      journalOffset.idOffSet(),
      journalOffset.eventVersionOffset(),
      BaseRecord.newRecord(journalOffset.tenantId())
    );
  }

  @Override
  public Uni<JournalOffset> get(JournalOffsetKey journalOffset) {
    return repository.selectByKey(new EventJournalOffSetKey(journalOffset.consumer(), journalOffset.tenantId()))
      .map(PgOffsetStore::getJournalOffset)
      .onFailure(NotFound.class).recoverWithUni(put(JournalOffsetBuilder.builder()
          .eventVersionOffset(0L)
          .idOffSet(0L)
          .tenantId(journalOffset.tenantId())
          .consumer(journalOffset.consumer())
          .build()
        )
      );
  }


  private static JournalOffset getJournalOffset(EventJournalOffSet offset) {
    return JournalOffsetBuilder.builder()
      .consumer(offset.consumer())
      .eventVersionOffset(offset.eventVersionOffset())
      .idOffSet(offset.idOffSet())
      .tenantId(offset.baseRecord().tenant())
      .build();
  }

  @Override
  public Uni<Void> setup(Class<? extends Aggregate> aggregateClass, Vertx vertx, JsonObject configuration) {
    LOGGER.debug("Migrating database for {} with configuration {}", aggregateClass.getSimpleName(), configuration);
    return LiquibaseHandler.liquibaseString(
      vertx,
      configuration,
      "pg-offset-store.xml",
      Map.of("schema", camelToKebab(aggregateClass.getSimpleName()))
    );
  }

}