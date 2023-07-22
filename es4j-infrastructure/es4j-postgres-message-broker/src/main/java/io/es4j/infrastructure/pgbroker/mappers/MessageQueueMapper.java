package io.es4j.infrastructure.pgbroker.mappers;



import io.es4j.infrastructure.pgbroker.models.MessageRecord;
import io.es4j.infrastructure.pgbroker.models.MessageRecordID;
import io.es4j.infrastructure.pgbroker.models.MessageRecordQuery;
import io.es4j.infrastructure.pgbroker.models.MessageState;
import io.es4j.sql.RecordMapper;
import io.es4j.sql.generator.filters.QueryBuilder;
import io.es4j.sql.models.QueryFilter;
import io.es4j.sql.models.QueryFilters;
import io.vertx.sqlclient.Row;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;


public class MessageQueueMapper implements RecordMapper<MessageRecordID, MessageRecord, MessageRecordQuery> {
    private static final String MESSAGE_ID = "message_id";
    private static final String SCHEDULED = "scheduled";
    private static final String EXPIRATION = "expiration";
    private static final String PRIORITY = "priority";
    private static final String RETRY_COUNTER = "retry_counter";
    private static final String STATE = "state";
    private static final String PAYLOAD = "payload";
    private static final String FAILURES = "failure";
    private static final String PARTITION_KEY = "partition_key";
    private static final String VERTICLE_ID = "verticle_id";
    private static final String PAYLOAD_CLASS = "queue";
    private static final String MESSAGE_SEQUENCE = "message_sequence";
    private static final String PARTITION_ID = "partition_id";
    private static final String TASK_QUEUE = "queue";

    public static MessageQueueMapper INSTANCE = new MessageQueueMapper();

    private MessageQueueMapper() {
    }

    @Override
    public String table() {
        return TASK_QUEUE;
    }

    @Override
    public Set<String> columns() {
        return Set.of(MESSAGE_ID, PAYLOAD_CLASS, SCHEDULED, EXPIRATION, PRIORITY, RETRY_COUNTER, STATE, PAYLOAD, FAILURES, VERTICLE_ID, PARTITION_ID, PARTITION_KEY);
    }

    @Override
    public Set<String> updatableColumns() {
        return Set.of(RETRY_COUNTER, STATE, FAILURES, VERTICLE_ID);
    }

    @Override
    public Set<String> keyColumns() {
        return Set.of(MESSAGE_ID);
    }

    @Override
    public MessageRecord rowMapper(Row row) {
        return new MessageRecord(
            row.getString(MESSAGE_ID),
            row.getLocalDateTime(SCHEDULED) != null ? row.getLocalDateTime(SCHEDULED).toInstant(ZoneOffset.UTC) : null,
            row.getLocalDateTime(EXPIRATION) != null ? row.getLocalDateTime(EXPIRATION).toInstant(ZoneOffset.UTC) : null,
            row.getInteger(PRIORITY),
            row.getInteger(RETRY_COUNTER),
            MessageState.valueOf(row.getString(STATE)),
            row.getString(PAYLOAD_CLASS),
            row.getJsonObject(PAYLOAD),
            row.getJsonObject(FAILURES),
            row.getString(VERTICLE_ID),
            row.getLong(MESSAGE_SEQUENCE),
            row.getString(PARTITION_ID),
            row.getString(PARTITION_KEY),
            baseRecord(row)
        );
    }

    @Override
    public void params(Map<String, Object> params, MessageRecord actualRecord) {
        params.put(MESSAGE_ID, actualRecord.id());
        if (actualRecord.scheduled() != null) {
            params.put(SCHEDULED, LocalDateTime.ofInstant(actualRecord.scheduled(), ZoneOffset.UTC));
        }
        if (actualRecord.expiration() != null) {
            params.put(EXPIRATION, LocalDateTime.ofInstant(actualRecord.expiration(), ZoneOffset.UTC));
        }
        params.put(PRIORITY, actualRecord.priority());
        params.put(RETRY_COUNTER, actualRecord.retryCounter());
        params.put(STATE, actualRecord.messageState().name());
        params.put(PAYLOAD, actualRecord.payload());
        params.put(PAYLOAD_CLASS, actualRecord.payloadClass());
        if (actualRecord.failedProcessors() != null && !actualRecord.failedProcessors().isEmpty()) {
            params.put(FAILURES, actualRecord.failedProcessors());
        }
        params.put(VERTICLE_ID, actualRecord.verticleId());
        params.put(MESSAGE_SEQUENCE, actualRecord.messageSequence());
        params.put(PARTITION_KEY, actualRecord.partitionKey());
        params.put(PARTITION_ID, actualRecord.partitionId());
    }

    @Override
    public void keyParams(Map<String, Object> params, MessageRecordID key) {
        params.put(MESSAGE_ID, key.id());
    }

    @Override
    public void queryBuilder(MessageRecordQuery query, QueryBuilder builder) {
        builder.iLike(
                new QueryFilters<>(String.class)
                    .filterColumn(MESSAGE_ID)
                    .filterParams(query.ids())
            )
            .iLike(
                new QueryFilters<>(MessageState.class)
                    .filterColumn(STATE)
                    .filterParams(query.states())
            )
            .from(
                new QueryFilter<>(Instant.class)
                    .filterColumn(SCHEDULED)
                    .filterParam(query.scheduledFrom())
            )
            .to(
                new QueryFilter<>(Instant.class)
                    .filterColumn(SCHEDULED)
                    .filterParam(query.scheduledTo())
            )
            .from(
                new QueryFilter<>(Integer.class)
                    .filterColumn(RETRY_COUNTER)
                    .filterParam(query.retryCounterFrom())
            )
            .to(
                new QueryFilter<>(Integer.class)
                    .filterColumn(RETRY_COUNTER)
                    .filterParam(query.retryCounterTo())
            )
            .from(
                new QueryFilter<>(Integer.class)
                    .filterColumn(PRIORITY)
                    .filterParam(query.priorityFrom())
            )
            .to(
                new QueryFilter<>(Integer.class)
                    .filterColumn(PRIORITY)
                    .filterParam(query.priorityTo())
            );
    }

}
