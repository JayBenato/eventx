package io.vertx.eventx.handlers;

import io.smallrye.mutiny.tuples.Tuple4;
import io.smallrye.mutiny.vertx.UniHelper;
import io.vertx.core.json.JsonArray;
import io.vertx.eventx.Aggregate;
import io.vertx.eventx.Command;
import io.vertx.eventx.common.ErrorSource;
import io.vertx.eventx.common.EventxError;
import io.vertx.eventx.infrastructure.PgInfrastructure;
import io.vertx.eventx.infrastructure.models.*;
import io.vertx.eventx.exceptions.CommandRejected;
import io.vertx.eventx.exceptions.UnknownCommand;
import io.vertx.eventx.exceptions.UnknownEvent;
import io.vertx.eventx.infrastructure.pg.models.*;
import io.vertx.eventx.objects.*;
import io.vertx.eventx.sql.exceptions.Conflict;
import io.vertx.eventx.sql.exceptions.NotFound;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;

import java.util.*;
import java.util.stream.IntStream;

public class AggregateLogic<T extends Aggregate> {
  private final List<BehaviourWrapper<T, ? extends Command>> behaviours;
  private final PgInfrastructure<T> pgInfrastructure;
  private static final Logger LOGGER = LoggerFactory.getLogger(AggregateLogic.class);
  private final AggregateConfiguration configuration;
  private final List<AggregatorWrapper<T>> aggregators;
  private final Class<T> entityClass;
  private final Map<String, String> commandClassMap = new HashMap<>();

  BehaviourContainer<T> behaviourContainer;

  public AggregateLogic(
    final Class<T> entityClass,
    final List<AggregatorWrapper<T>> aggregators,
    final List<BehaviourWrapper<T, ? extends Command>> behaviours,
    final AggregateConfiguration configuration,
    final PgInfrastructure<T> pgInfrastructure
  ) {
    this.pgInfrastructure = pgInfrastructure;
    this.entityClass = entityClass;
    this.aggregators = aggregators;
    this.behaviours = behaviours;
    this.configuration = configuration;
    if (behaviours.isEmpty()) {
      throw new IllegalStateException("Empty behaviours");
    }
    if (aggregators.isEmpty()) {
      throw new IllegalStateException("Empty behaviours");
    }
    populateCommandClassMap(behaviours);
  }

  private void populateCommandClassMap(List<BehaviourWrapper<T, ? extends Command>> commandBehaviours) {
    commandBehaviours.stream().map(
      cmd -> Tuple4.of(
        cmd.commandClass().getName(),
        cmd.commandClass().getSimpleName(),
        camelToSnake(cmd.commandClass().getSimpleName()
        ),
        cmd.commandClass().getSimpleName().toLowerCase()
      )
    ).forEach(tuple -> {
        commandClassMap.put(tuple.getItem1(), tuple.getItem1());
        commandClassMap.put(tuple.getItem2(), tuple.getItem1());
        commandClassMap.put(tuple.getItem3(), tuple.getItem1());
        commandClassMap.put(tuple.getItem4(), tuple.getItem1());
      }
    );
  }

  public Uni<JsonObject> fetch(AggregateRecordKey entityAggregateRecordKey) {
    LOGGER.debug("Loading entity locally  -> " + entityAggregateRecordKey);
    return fetch(entityAggregateRecordKey.aggregateId(), entityAggregateRecordKey.tenantId(), ConsistencyStrategy.STRONGLY_CONSISTENT)
      .map(state -> JsonObject.mapFrom(state.aggregateState()));
  }

  public Uni<JsonObject> process(String commandClass, JsonObject jsonCommand) {
    LOGGER.debug("Processing " + commandClass + " -> " + jsonCommand.encodePrettily());
    final var command = getCommand(commandClass, jsonCommand);
    return fetch(command.aggregateId(), command.headers().tenantId(), ConsistencyStrategy.EVENTUALLY_CONSISTENT)
      .flatMap(
        aggregateState -> processCommand(aggregateState, command)
          .onFailure(Conflict.class).recoverWithUni(
            () -> ensureConsistency(aggregateState, command.aggregateId(), command.headers().tenantId())
              .flatMap(reconstructedState -> processCommand(reconstructedState, command))
          )
          .onFailure().invoke(throwable -> handleRejectedCommand(throwable, command))
      )
      .invoke(this::handleSnapshot)
      .map(state -> JsonObject.mapFrom(state.aggregateState()));
  }

  private T aggregateEvent(T aggregateState, final Object event) {
    final var customAggregator = aggregators.stream()
      .filter(behaviour -> !Objects.equals(behaviour.delegate().tenantId(), "default"))
      .filter(aggregator -> aggregator.eventClass().getName().equals(event.getClass().getName()))
      .findFirst()
      .orElse(null);
    if (customAggregator == null) {
      final var defaultAggregator = aggregators.stream()
        .filter(behaviour -> Objects.equals(behaviour.delegate().tenantId(), "default"))
        .filter(aggregator -> aggregator.eventClass().getName().equals(event.getClass().getName()))
        .findFirst()
        .orElseThrow(() -> UnknownEvent.unknown(event.getClass()));
      LOGGER.info("Applying behaviour -> " + defaultAggregator.getClass().getSimpleName());
      return (T) defaultAggregator.delegate().apply(aggregateState, event);
    }
    LOGGER.info("Applying behaviour -> " + customAggregator.getClass().getSimpleName());
    return (T) customAggregator.delegate().apply(aggregateState, event);
  }

  private List<Object> applyBehaviour(final T aggregateState, final Command command) {
    final var customBehaviour = behaviours.stream()
      .filter(behaviour -> !Objects.equals(behaviour.delegate().tenantID(), "default"))
      .filter(behaviour -> behaviour.commandClass().getName().equals(command.getClass().getName()))
      .findFirst()
      .orElse(null);
    if (customBehaviour == null) {
      final var defaultBehaviour = behaviours.stream()
        .filter(behaviour -> Objects.equals(behaviour.delegate().tenantID(), "default"))
        .filter(behaviour -> behaviour.commandClass().getName().equals(command.getClass().getName()))
        .findFirst()
        .orElseThrow(() -> UnknownCommand.unknown(command.getClass()));
      LOGGER.info("Applying command Behaviour -> " + defaultBehaviour.getClass().getSimpleName());
      return defaultBehaviour.process(aggregateState, command);
    }
    behaviourContainer.process(aggregateState,command);
    LOGGER.info("Applying command behaviour -> " + customBehaviour.getClass().getSimpleName());
    return customBehaviour.process(aggregateState, command);
  }

  private Uni<EntityState<T>> fetch(String entityId, String tenant, ConsistencyStrategy consistencyStrategy) {
    LOGGER.debug("Loading entity aggregate " + entityId + " with consistency lockLevel -> " + consistencyStrategy);
    return switch (consistencyStrategy) {
      case EVENTUALLY_CONSISTENT -> {
        EntityState<T> state = null;
        if (pgInfrastructure.cache() != null) {
          state = pgInfrastructure.cache().get(new AggregateKey<>(entityClass, entityId, tenant));
        }
        if (state == null) {
          yield loadFromEventJournal(entityId, tenant);
        } else {
          yield Uni.createFrom().item(state);
        }
      }
      case STRONGLY_CONSISTENT -> {
        EntityState<T> state = null;
        if (pgInfrastructure.cache() != null) {
          state = pgInfrastructure.cache().get(new AggregateKey<>(entityClass, entityId, tenant));
        }
        if (state == null) {
          yield loadFromEventJournal(entityId, tenant);
        } else {
          yield ensureConsistency(state, entityId, tenant);
        }
      }
    };
  }

  private Uni<EntityState<T>> ensureConsistency(EntityState<T> state, final String entityId, final String tenant) {
    if (configuration.persistenceMode() == PersistenceMode.DATABASE) {
      LOGGER.warn("Ensuring consistency for entity -> " + entityId);
      return pgInfrastructure.eventJournal().stream(new StreamInstruction<>(
            entityClass,
            entityId,
            tenant,
            state.currentEventVersion()
          )
        )
        .map(events -> {
            if (events != null && !events.isEmpty()) {
              applyEvents(state, events, null);
            }
            pgInfrastructure.cache().put(state);
            return state;
          }
        )
        .onFailure(NotFound.class).recoverWithItem(state);
    } else {
      LOGGER.warn("Consistency not ensure since persistence mode is set to -> " + configuration.persistenceMode());
      return Uni.createFrom().item(state);
    }
  }

  private Uni<EntityState<T>> loadFromEventJournal(final String entityId, final String tenant) {
    final var state = new EntityState<T>(
      configuration.snapshotEvery(),
      configuration.maxNumberOfCommandsForIdempotency()
    );
    if (configuration.persistenceMode() == PersistenceMode.DATABASE) {
      LOGGER.warn("Loading entity from database -> " + entityId);
      Uni<Void> snapshotUni = Uni.createFrom().voidItem();
      if (pgInfrastructure.snapshotStore() != null) {
        snapshotUni = pgInfrastructure.snapshotStore().get(new AggregateKey<>(
              entityClass,
              entityId,
              tenant
            )
          )
          .onFailure(NotFound.class).recoverWithNull()
          .replaceWithVoid();
      }
      return snapshotUni.flatMap(avoid -> pgInfrastructure.eventJournal().stream(
            new StreamInstruction<>(
              entityClass,
              entityId,
              tenant,
              state.currentEventVersion()
            )
          )
        )
        .map(events -> {
            if (events != null && !events.isEmpty()) {
              applyEvents(state, events, null);
            }
            pgInfrastructure.cache().put(state);
            return state;
          }
        )
        .onFailure(NotFound.class).recoverWithItem(state);
    } else {
      LOGGER.warn("Wont look for entity in database since persistence mode is set to -> " + configuration.persistenceMode());
      return Uni.createFrom().item(state);
    }

  }

  private EntityState<T> loadSnapshot(final EntityState<T> state, final AggregateSnapshotRecord persistedSnapshot) {
    LOGGER.info("Loading snapshot -> " + persistedSnapshot.state().encodePrettily());
    return state.setAggregateState(persistedSnapshot.state().mapTo(entityClass))
      .setCurrentEventVersion(persistedSnapshot.eventVersion())
      .setSnapshot(persistedSnapshot)
      .setSnapshotPresent(true);
  }

  private void applyEvents(final EntityState<T> state, final List<Event> events, Command command) {
    events.stream()
      .sorted(Comparator.comparingLong(Event::eventVersion))
      .forEachOrdered(event -> {
          LOGGER.info("Aggregating event -> " + event.eventClass());
          final var newState = aggregateEvent(state.aggregateState(), getEvent(event.eventClass(), event.event()));
          LOGGER.info("New aggregate state -> " + newState);
          if (command != null && state.commands() != null && state.commands().stream().noneMatch(txId -> txId.equals(command.headers().commandID()))) {
            state.commands().add(command.headers().commandID());
          }
          if (state.eventsAfterSnapshot() != null) {
            state.eventsAfterSnapshot().add(event);
          }
          state.setAggregateState(newState)
            .setCurrentEventVersion(event.eventVersion())
            .setProcessedEventsAfterLastSnapshot(state.processedEventsAfterLastSnapshot() + 1);
        }
      );
  }


  private Tuple2<List<Event>, Command> applyBehaviour(final EntityState<T> state, final Command finalCommand) {
    final var currentVersion = state.provisoryEventVersion() == null ? 0 : state.provisoryEventVersion();
    final var events = applyBehaviour(state.aggregateState(), finalCommand);
    LOGGER.info("Events created " + new JsonArray(events).encodePrettily());
    final var array = events.toArray(new Object[0]);
    final var entityEvents = IntStream.range(1, array.length + 1)
      .mapToObj(index -> {
          final var ev = array[index - 1];
          final var nextEventVersion = currentVersion + index;
          state.setProvisoryEventVersion(nextEventVersion);
          return new Event(
            finalCommand.aggregateId(),
            ev.getClass().getName(),
            nextEventVersion,
            JsonObject.mapFrom(ev),
            finalCommand.headers().tenantId()
          );
        }
      ).toList();
    return Tuple2.of(entityEvents, finalCommand);
  }


  private <C extends Command> Uni<EntityState<T>> processCommand(final EntityState<T> state, final C command) {
    if (state.commands() != null && !state.commands().isEmpty()) {
      state.commands().stream().filter(txId -> txId.equals(command.headers().commandID()))
        .findAny()
        .ifPresent(duplicatedCommand -> {
            throw new CommandRejected(new EventxError("Command was already processed", "Command was carrying the same txId form a command previously processed -> " + duplicatedCommand, 400));
          }
        );
    }
    state.setProvisoryEventVersion(state.currentEventVersion());
    final var eventCommandTuple = applyBehaviour(state, command);
    if (configuration.persistenceMode() == PersistenceMode.DATABASE) {
      applyEvents(state, eventCommandTuple.getItem1(), command);
      return pgInfrastructure.eventJournal().append(
          new AppendInstruction<>(
            entityClass,
            state.aggregateState().aggregateId(),
            state.aggregateState().tenantID(),
            eventCommandTuple.getItem1()
          )
        )
        .map(
          avoid -> {
            pgInfrastructure.cache().put(state);
            return state;
          }
        );
    } else {
      LOGGER.warn("EntityAggregate will not be persisted to the database since persistence mode is set to -> " + configuration.persistenceMode());
      applyEvents(state, eventCommandTuple.getItem1(), command);
      pgInfrastructure.cache().put(state);
      return Uni.createFrom().item(state);
    }
  }

  private void handleSnapshot(EntityState<T> state) {
    if (state.snapshotAfter() != null && state.processedEventsAfterLastSnapshot() >= state.snapshotAfter()) {
      if (Boolean.TRUE.equals(state.snapshotPresent())) {
        LOGGER.info("Updating snapshot -> " + state);
        pgInfrastructure.snapshotStore().update(state)
          .map(avoid -> updateStateAfterSnapshot(state))
          .subscribe()
          .with(
            item -> LOGGER.info("Snapshot added -> " + item)
            , throwable -> LOGGER.error("Unable to add snapshot", throwable));
      } else {
        LOGGER.info("Adding snapshot -> " + state);
        pgInfrastructure.snapshotStore().add(state)
          .map(avoid -> updateStateAfterSnapshot(state))
          .subscribe()
          .with(
            item -> LOGGER.info("Snapshot added -> " + item),
            throwable -> LOGGER.error("Unable to add snapshot", throwable)
          );
      }
    }
  }

  private EntityState<T> updateStateAfterSnapshot(final EntityState<T> state) {
    state.eventsAfterSnapshot().clear();
    state.setProcessedEventsAfterLastSnapshot(0);
    pgInfrastructure.cache().put(state);
    return state;
  }

  private void handleRejectedCommand(final Throwable throwable, final Command command) {
    LOGGER.error("Command rejected -> ", throwable);
    if (configuration.persistenceMode() == PersistenceMode.DATABASE && pgInfrastructure.commandStore() != null) {
      if (throwable instanceof CommandRejected commandRejected) {
        pgInfrastructure.commandStore().save(new StoreCommand<>(
              entityClass,
              command.aggregateId(),
              command,
              new EventxError(
                ErrorSource.LOGIC,
                null,
                commandRejected.error().cause(),
                commandRejected.error().hint(),
                commandRejected.error().internalErrorCode()
              )
            )
          )
          .subscribe()
          .with(UniHelper.NOOP);
      } else {
        pgInfrastructure.commandStore().save(new StoreCommand<>(
              entityClass,
              command.aggregateId(),
              command,
              new EventxError(
                ErrorSource.UNKNOWN,
                null,
                throwable.getMessage(),
                throwable.getLocalizedMessage(),
                999
              )
            )
          )
          .subscribe()
          .with(UniHelper.NOOP);
      }
    }
  }


  private Command getCommand(final String commandType, final JsonObject jsonCommand) {
    try {
      final var clazz = Class.forName(Objects.requireNonNullElse(commandClassMap.get(commandType), commandType));
      final var object = jsonCommand.mapTo(clazz);
      if (object instanceof Command command) {
        return command;
      } else {
        throw new CommandRejected(new EventxError("Command is not an instance of EntityAggregateCommand", JsonObject.mapFrom(object).encode(), 500));
      }
    } catch (Exception e) {
      LOGGER.error("Unable to cast command", e);
      throw new CommandRejected(new EventxError("Unable to cast class", e.getMessage(), 500));
    }
  }

  private Object getEvent(final String eventType, JsonObject event) {
    try {
      final var eventClass = Class.forName(eventType);
      return event.mapTo(eventClass);
    } catch (Exception e) {
      LOGGER.error("Unable to cast event", e);
      throw new CommandRejected(new EventxError("Unable to cast event", e.getMessage(), 500));
    }
  }

  public static String camelToSnake(String str) {
    // Regular Expression
    String regex = "([a-z])([A-Z]+)";

    // Replacement string
    String replacement = "$1_$2";

    // Replace the given regex
    // with replacement string
    // and convert it to lower case.
    str = str
      .replaceAll(
        regex, replacement)
      .toLowerCase();

    // return string
    return str;
  }

}
