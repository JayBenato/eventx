package io.eventx.infrastructure.cache;


import com.google.auto.service.AutoService;
import io.eventx.Aggregate;
import io.eventx.core.objects.AggregateState;
import io.eventx.infrastructure.models.AggregateKey;
import io.eventx.infrastructure.models.AggregatePlainKey;
import io.eventx.infrastructure.AggregateCache;


@AutoService(AggregateCache.class)
public class CaffeineAggregateCache implements AggregateCache {

  @Override
  public <T extends Aggregate> AggregateState<T> get(AggregateKey<T> aggregateKey) {
    return CaffeineWrapper.get(key(aggregateKey));
  }

  public static <T extends Aggregate> AggregatePlainKey key(AggregateKey<T> aggregateKey) {
    return new AggregatePlainKey(aggregateKey.aggregateClass().getName(), aggregateKey.aggregateId(), aggregateKey.tenantId());
  }

  @Override
  public <T extends Aggregate> void put(AggregateKey<T> aggregateKey, AggregateState<T> aggregate) {
    CaffeineWrapper.put(key(aggregateKey), aggregate);
  }


}