package io.vertx.skeleton.evs.handlers;

import io.smallrye.mutiny.Uni;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.skeleton.evs.EntityAggregate;
import io.vertx.skeleton.evs.EventConsumer;
import io.vertx.skeleton.evs.mappers.ConsumerFailureMapper;
import io.vertx.skeleton.evs.mappers.EventJournalMapper;
import io.vertx.skeleton.evs.mappers.EventJournalOffsetMapper;
import io.vertx.skeleton.evs.objects.*;
import io.vertx.skeleton.models.*;
import io.vertx.skeleton.models.exceptions.OrmNotFoundException;
import io.vertx.skeleton.models.exceptions.VertxServiceException;
import io.vertx.skeleton.orm.Repository;
import io.vertx.skeleton.orm.RepositoryHandler;
import io.vertx.skeleton.task.SynchronizationStrategy;
import io.vertx.skeleton.task.SynchronizedTask;

import java.util.Comparator;
import java.util.List;

public class EventConsumerTask implements SynchronizedTask {

  //todo

  // System must contain a projection specific command that is part of the framework it-self.
  // UpdateProjection
  //
  //   1.Command
  //  The command porpuse is to ask for a projection update, and must implement the following rules :
  //    - Command will only affect the framework specific fields that wrap the aggregateState.
  //    - ProjectionUpdated event must be appended to event log
  //    - ProjectionUpdated event must be filtered out of the events that are passed to EventBehaviour implementors.
  //
  //
  //  2.Issuer
  //  The issuer purpose is to generate UpdateProjection command's, and must be implemented as follows :
  //  - A schduled task with cluster-wide lock that consumes events via id off-set.
  //  - query for the event-polling must ignore system events like UpdateProjection
  //  - events are than groupped per entity and reduced into projection update commands
  //  - commands are sent to entities and result in Projection implementors to be triggered inside the EntityAggregateHandler it self.
  //  -
  //
  //  * Notes
  //  By doing this projections will have the following attributes :
  //      - decouple event log appends from projections
  //      - projections updates are partitioned per entity thus giving ability to concurrently update many entities and projections
  //      - projections updates will always contain the correct state and event offsets
  //
  private final EventConsumer eventConsumer;
  private final Repository<EventJournalOffSetKey, EventJournalOffSet, EmptyQuery> eventJournalOffset;
  private final Repository<String, ConsumerFailure, EmptyQuery> consumerFailure;
  private final Logger logger;
  private final Repository<EntityEventKey, EntityEvent, EventJournalQuery> eventJournal;


  public <T extends EntityAggregate> EventConsumerTask(Class<T> entityAggregateClass, EventConsumer eventConsumer, RepositoryHandler repositoryHandler) {
    this.eventConsumer = eventConsumer;
    this.eventJournalOffset = new Repository<>(new EventJournalOffsetMapper(entityAggregateClass), repositoryHandler);
    this.consumerFailure = new Repository<>(new ConsumerFailureMapper(entityAggregateClass), repositoryHandler);
    this.eventJournal = new Repository<>(new EventJournalMapper(entityAggregateClass), repositoryHandler);
    this.logger = LoggerFactory.getLogger(eventConsumer.getClass());
  }

  @Override
  public Uni<Void> performTask() {
    return eventJournalOffset.selectByKey(new EventJournalOffSetKey(eventConsumer.getClass().getSimpleName()))
      .onFailure().recoverWithItem(this::handleOffsetFailure)
      .flatMap(
        eventJournalOffSet -> eventJournal.query(getEventJournalQuery(eventJournalOffSet))
          .flatMap(events -> handleEventOffSet(eventJournalOffSet, events))
      );
  }


  private EventJournalQuery getEventJournalQuery(final EventJournalOffSet eventJournalOffSet) {
    return new EventJournalQuery(
      null,
      eventConsumer.events() != null ? eventConsumer.events().stream().map(Class::getName).toList() : null,
      null,
      new QueryOptions(
        "id",
        false,
        null,
        null,
        null,
        null,
        null,
        100,
        eventJournalOffSet.idOffSet(),
        null
      )
    );
  }


  private Uni<Void> handleEventOffSet(final EventJournalOffSet eventJournalOffSet, final List<EntityEvent> events) {
    logger.debug("Events being handled :" + events);
    final var maxEventId = events.stream().map(event -> event.persistedRecord().id()).max(Comparator.naturalOrder()).orElseThrow();
    final var minEventId = events.stream().map(event -> event.persistedRecord().id()).min(Comparator.naturalOrder()).orElseThrow();
    logger.info("Processing events id from " + minEventId + " to " + maxEventId);
    final var polledEvents = events.stream().map(event -> new PolledEvent(event.entityId(), event.persistedRecord().tenant(), getEvent(event.eventClass(), event.event()))).toList();
    return eventConsumer.consumeEvents(polledEvents)
      .onFailure().invoke(throwable -> handleConsumerFailure(throwable, polledEvents))
      .flatMap(avoid -> handleIdOffset(eventJournalOffSet, maxEventId))
      .replaceWithVoid();
  }

  private void handleConsumerFailure(final Throwable throwable, final List<PolledEvent> polledEvents) {
    // todo persist failure.
    logger.error("Unable to handle events -> " + polledEvents, throwable);
  }


  private Uni<EventJournalOffSet> handleIdOffset(final EventJournalOffSet eventJournalOffSet, final Long maxEventId) {
    if (eventJournalOffSet.idOffSet() == 0) {
      return eventJournalOffset.insert(eventJournalOffSet.withIdOffSet(maxEventId));
    } else {
      return eventJournalOffset.updateById(eventJournalOffSet.withIdOffSet(maxEventId));
    }
  }

  private EventJournalOffSet handleOffsetFailure(final Throwable throwable) {
    if (throwable instanceof OrmNotFoundException) {
      logger.info("Inserting new offset for user event journal");
      return new EventJournalOffSet(eventConsumer.getClass().getSimpleName(), 0L, null, PersistedRecord.tenantLess());
    } else if (throwable instanceof VertxServiceException utsException) {
      logger.error("Error fetching offset", throwable);
      throw utsException;
    } else {
      logger.error("Error fetching offset", throwable);
      throw new IllegalStateException(throwable);
    }
  }


  private Object getEvent(final String eventClazz, JsonObject event) {
    try {
      final var eventClass = Class.forName(eventClazz);
      return event.mapTo(eventClass);
    } catch (Exception e) {
      logger.error("Unable to cast event", e);
      throw new IllegalArgumentException("Unable to cast event");
    }
  }

  @Override
  public SynchronizationStrategy strategy() {
    return SynchronizationStrategy.CLUSTER_WIDE;
  }
}
