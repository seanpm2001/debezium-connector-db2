/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.common.BaseSourceTask;
import io.debezium.document.DocumentReader;
import io.debezium.jdbc.DefaultMainConnectionProvidingConnectionFactory;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.ChangeEventSourceCoordinator;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.metrics.DefaultChangeEventSourceMetricsFactory;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.signal.SignalProcessor;
import io.debezium.pipeline.spi.Offsets;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaFactory;
import io.debezium.schema.SchemaNameAdjuster;
import io.debezium.spi.topic.TopicNamingStrategy;
import io.debezium.util.Clock;

/**
 * The main task executing streaming from DB2.
 * Responsible for lifecycle management the streaming code.
 *
 * @author Jiri Pechanec
 *
 */
public class Db2ConnectorTask extends BaseSourceTask<Db2Partition, Db2OffsetContext> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Db2ConnectorTask.class);
    private static final String CONTEXT_NAME = "db2-server-connector-task";

    private volatile Db2TaskContext taskContext;
    private volatile ChangeEventQueue<DataChangeEvent> queue;
    private volatile Db2Connection dataConnection;
    private volatile Db2Connection metadataConnection;
    private volatile ErrorHandler errorHandler;
    private volatile Db2DatabaseSchema schema;

    @Override
    public String version() {
        return Module.version();
    }

    @Override
    public ChangeEventSourceCoordinator<Db2Partition, Db2OffsetContext> start(Configuration config) {
        final Db2ConnectorConfig connectorConfig = new Db2ConnectorConfig(applyFetchSizeToJdbcConfig(config));
        final TopicNamingStrategy<TableId> topicNamingStrategy = connectorConfig.getTopicNamingStrategy(CommonConnectorConfig.TOPIC_NAMING_STRATEGY);
        final SchemaNameAdjuster schemaNameAdjuster = connectorConfig.schemaNameAdjuster();

        MainConnectionProvidingConnectionFactory<Db2Connection> connectionFactory = new DefaultMainConnectionProvidingConnectionFactory<>(
                () -> new Db2Connection(connectorConfig.getJdbcConfig()));
        dataConnection = connectionFactory.mainConnection();
        metadataConnection = connectionFactory.newConnection();
        try {
            dataConnection.setAutoCommit(false);
        }
        catch (SQLException e) {
            throw new ConnectException(e);
        }

        final Db2ValueConverters valueConverters = new Db2ValueConverters(connectorConfig.getDecimalMode(), connectorConfig.getTemporalPrecisionMode());
        this.schema = new Db2DatabaseSchema(connectorConfig, valueConverters, schemaNameAdjuster, topicNamingStrategy, dataConnection);
        this.schema.initializeStorage();

        Offsets<Db2Partition, Db2OffsetContext> previousOffsets = getPreviousOffsets(new Db2Partition.Provider(connectorConfig),
                new Db2OffsetContext.Loader(connectorConfig));
        final Db2Partition partition = previousOffsets.getTheOnlyPartition();
        final Db2OffsetContext previousOffset = previousOffsets.getTheOnlyOffset();

        if (previousOffset != null) {
            schema.recover(partition, previousOffset);
        }

        taskContext = new Db2TaskContext(connectorConfig, schema);

        final Clock clock = Clock.system();

        // Set up the task record queue ...
        this.queue = new ChangeEventQueue.Builder<DataChangeEvent>()
                .pollInterval(connectorConfig.getPollInterval())
                .maxBatchSize(connectorConfig.getMaxBatchSize())
                .maxQueueSize(connectorConfig.getMaxQueueSize())
                .loggingContextSupplier(() -> taskContext.configureLoggingContext(CONTEXT_NAME))
                .build();

        errorHandler = new ErrorHandler(Db2Connector.class, connectorConfig, queue, errorHandler);

        final Db2EventMetadataProvider metadataProvider = new Db2EventMetadataProvider();

        SignalProcessor<Db2Partition, Db2OffsetContext> signalProcessor = new SignalProcessor<>(
                Db2Connector.class, connectorConfig, Map.of(),
                getAvailableSignalChannels(),
                DocumentReader.defaultReader(),
                previousOffsets);

        final EventDispatcher<Db2Partition, TableId> dispatcher = new EventDispatcher<>(
                connectorConfig,
                topicNamingStrategy,
                schema,
                queue,
                connectorConfig.getTableFilters().dataCollectionFilter(),
                DataChangeEvent::new,
                metadataProvider,
                schemaNameAdjuster,
                signalProcessor);

        NotificationService<Db2Partition, Db2OffsetContext> notificationService = new NotificationService<>(getNotificationChannels(),
                connectorConfig, SchemaFactory.get(), dispatcher::enqueueNotification);

        ChangeEventSourceCoordinator<Db2Partition, Db2OffsetContext> coordinator = new ChangeEventSourceCoordinator<>(
                previousOffsets,
                errorHandler,
                Db2Connector.class,
                connectorConfig,
                new Db2ChangeEventSourceFactory(connectorConfig, metadataConnection, connectionFactory, errorHandler, dispatcher, clock, schema),
                new DefaultChangeEventSourceMetricsFactory<>(),
                dispatcher,
                schema,
                signalProcessor,
                notificationService);

        coordinator.start(taskContext, this.queue, metadataProvider);

        return coordinator;
    }

    @Override
    public List<SourceRecord> doPoll() throws InterruptedException {
        final List<DataChangeEvent> records = queue.poll();

        final List<SourceRecord> sourceRecords = records.stream()
                .map(DataChangeEvent::getRecord)
                .collect(Collectors.toList());

        return sourceRecords;
    }

    @Override
    public void doStop() {
        try {
            if (dataConnection != null) {
                // Db2 may have an active in-progress transaction associated with the connection and if so,
                // it will throw an exception during shutdown because the active transaction exists. This
                // is meant to help avoid this by rolling back the current active transaction, if exists.
                if (dataConnection.isConnected()) {
                    try {
                        dataConnection.rollback();
                    }
                    catch (SQLException e) {
                        // ignore
                    }
                }
                dataConnection.close();
            }
        }
        catch (SQLException e) {
            LOGGER.error("Exception while closing JDBC connection", e);
        }

        try {
            if (metadataConnection != null) {
                metadataConnection.close();
            }
        }
        catch (SQLException e) {
            LOGGER.error("Exception while closing JDBC metadata connection", e);
        }

        if (schema != null) {
            schema.close();
        }
    }

    @Override
    protected Iterable<Field> getAllConfigurationFields() {
        return Db2ConnectorConfig.ALL_FIELDS;
    }

    /**
     * Applies the fetch size to the driver/jdbc configuration from the connector configuration.
     *
     * @param config the connector configuration
     * @return the potentially modified configuration, never null
     */
    private static Configuration applyFetchSizeToJdbcConfig(Configuration config) {
        // By default, do not load whole result sets into memory
        if (config.getInteger(Db2ConnectorConfig.QUERY_FETCH_SIZE) > 0) {
            final String driverPrefix = CommonConnectorConfig.DRIVER_CONFIG_PREFIX;
            return config.edit()
                    .withDefault(driverPrefix + "responseBuffering", "adaptive")
                    .withDefault(driverPrefix + "fetchSize", config.getInteger(Db2ConnectorConfig.QUERY_FETCH_SIZE))
                    .build();
        }
        return config;
    }
}
