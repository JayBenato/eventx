package io.vertx.eventx.core;

import io.smallrye.mutiny.Uni;
import io.vertx.eventx.EventProjection;
import io.vertx.eventx.infrastructure.EventStore;
import io.vertx.eventx.infrastructure.OffsetStore;
import io.vertx.eventx.infrastructure.misc.EventParser;
import io.vertx.eventx.infrastructure.models.EventStream;
import io.vertx.eventx.objects.JournalOffset;
import io.vertx.eventx.objects.JournalOffsetKey;
import io.vertx.eventx.objects.PolledEvent;
import io.vertx.eventx.sql.exceptions.NotFound;
import io.vertx.eventx.task.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public class EventProjectionPoller implements CronTask {

  private static final Logger logger = LoggerFactory.getLogger(EventProjectionPoller.class);
  private final EventProjection eventProjection;
  private final EventStore eventStore;
  private final OffsetStore offsetStore;

  public EventProjectionPoller(
    EventProjection eventProjections,
    EventStore eventStore,
    OffsetStore offsetStore
  ) {
    this.eventProjection = eventProjections;
    this.eventStore = eventStore;
    this.offsetStore = offsetStore;
  }

  @Override
  public Uni<Void> performTask() {
    return offsetStore.get(getOffset())
      .flatMap(journalOffset -> eventStore.fetch(streamStatement(eventProjection, journalOffset))
        .flatMap(events -> eventProjection.apply(parseEvents(events))
          .flatMap(avoid -> offsetStore.put(journalOffset.updateOffset(events)))
        )
      )
      .onFailure().invoke(throwable -> logger.error("Unable to update projection {}", eventProjection.getClass().getName(), throwable))
      .replaceWithVoid();
  }

  private JournalOffsetKey getOffset() {
    return new JournalOffsetKey(eventProjection.getClass().getName(), eventProjection.tenantID());
  }

  private static EventStream streamStatement(EventProjection eventProjection, JournalOffset journalOffset) {
    AtomicReference<EventStream> eventStream = new AtomicReference<>();
    eventProjection.filter().ifPresentOrElse(
      filter -> eventStream.set(new EventStream(
        filter.aggregates(),
        filter.events(),
        null,
        filter.tags(),
        eventProjection.tenantID(),
        journalOffset.idOffSet(),
        1000
      )),
      () -> eventStream.set(
        new EventStream(
          null,
          null,
          null,
          null,
          eventProjection.tenantID(),
          journalOffset.idOffSet(),
          1000
        )
      )
    );
    return eventStream.get();
  }


  private List<PolledEvent> parseEvents(List<io.vertx.eventx.infrastructure.models.Event> events) {
    return events.stream()
      .map(event -> new PolledEvent(
          event.aggregateClass(),
          event.aggregateId(),
          event.tenantId(),
          event.journalOffset(),
          event.eventVersion(),
          EventParser.getEvent(event.eventClass(), event.event())
        )
      )
      .toList();
  }

  @Override
  public CronTaskConfiguration configuration() {
    return CronTaskConfigurationBuilder.builder()
      .knownInterruptions(List.of(NotFound.class))
      .lockLevel(LockLevel.CLUSTER_WIDE)
      .cron(eventProjection.pollingPolicy())
      .build();
  }

}
