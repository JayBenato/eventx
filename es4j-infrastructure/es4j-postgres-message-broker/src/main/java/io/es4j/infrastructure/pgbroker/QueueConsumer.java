package io.es4j.infrastructure.pgbroker;

import io.es4j.infrastructure.pgbroker.models.ConsumerTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;

import java.util.List;


/**
 * When using mono consumer the queue entry will be processed only ONCE by either the default implementation
 * which is the one that returns tenant null or the tenant specific implementation which is the implementation that
 * returns a matching tenant in the tenants() method
 *
 * @param <T> The payload, queue entry type
 */
public interface QueueConsumer<T> {

  default Uni<Void> start(Vertx vertx, JsonObject config) {
    return Uni.createFrom().voidItem();
  }

  Uni<Void> process(T payload, ConsumerTransaction consumerTransaction);

  default List<Class<? extends Throwable>> fatalExceptions() {
    return List.of();
  }

  default List<String> tenants() {
    return null;
  }

  default Boolean blockingProcessor() {
    return Boolean.FALSE;
  }

  default T parse(JsonObject jsonObject, Class<T> payloadClass) {
    return jsonObject.mapTo(payloadClass);
  }

}