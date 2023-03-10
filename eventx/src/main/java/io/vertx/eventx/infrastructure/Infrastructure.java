package io.vertx.eventx.infrastructure;

import io.smallrye.mutiny.Uni;

public record Infrastructure(
  AggregateCache cache,
  EventStore eventStore,
  SnapshotStore snapshotStore,
  OffsetStore offsetStore
) {

  public Uni<Void> stop() {
    return Uni.join().all(cache.start(), eventStore.close(), snapshotStore.close(), offsetStore.close())
      .usingConcurrencyOf(1).andFailFast()
      .replaceWithVoid();
  }

  public Uni<Void> start() {
    return Uni.join().all(cache.start(), eventStore.start(), snapshotStore.start(), offsetStore.start())
      .usingConcurrencyOf(1).andFailFast()
      .replaceWithVoid();
  }

}
