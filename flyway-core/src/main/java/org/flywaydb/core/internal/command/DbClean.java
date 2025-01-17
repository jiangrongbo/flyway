/*
 * Copyright (C) Red Gate Software Ltd 2010-2021
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core.internal.command;

import lombok.CustomLog;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.output.CleanResult;
import org.flywaydb.core.api.output.CommandResultFactory;
import org.flywaydb.core.internal.callback.CallbackExecutor;
import org.flywaydb.core.internal.database.base.Connection;
import org.flywaydb.core.internal.database.base.Database;
import org.flywaydb.core.internal.database.base.Schema;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.flywaydb.core.internal.jdbc.ExecutionTemplateFactory;
import org.flywaydb.core.internal.schemahistory.SchemaHistory;
import org.flywaydb.core.internal.util.StopWatch;
import org.flywaydb.core.internal.util.TimeFormat;

@CustomLog
public class DbClean {

    private final Schema[] schemas;
    private final Connection connection;
    private final Database database;
    private final SchemaHistory schemaHistory;
    private final CallbackExecutor callbackExecutor;
    private final boolean cleanDisabled;

    public DbClean(Database database, SchemaHistory schemaHistory, Schema[] schemas, CallbackExecutor callbackExecutor, boolean cleanDisabled) {
        this.database = database;
        this.connection = database.getMainConnection();
        this.schemaHistory = schemaHistory;
        this.schemas = schemas;
        this.callbackExecutor = callbackExecutor;
        this.cleanDisabled = cleanDisabled;
    }

    public CleanResult clean() throws FlywayException {
        if (cleanDisabled) {
            throw new FlywayException("Unable to execute clean as it has been disabled with the \"flyway.cleanDisabled\" property.");
        }

        callbackExecutor.onEvent(Event.BEFORE_CLEAN);

        CleanResult cleanResult = CommandResultFactory.createCleanResult(database.getCatalog());

        try {
            connection.changeCurrentSchemaTo(schemas[0]);

            boolean dropSchemas = false;
            try {
                dropSchemas = schemaHistory.hasSchemasMarker();
            } catch (Exception e) {
                LOG.error("Error while checking whether the schemas should be dropped", e);
            }

            dropDatabaseObjectsPreSchemas();

            for (Schema schema : schemas) {
                if (!schema.exists()) {
                    String unknownSchemaWarning = "Unable to clean unknown schema: " + schema;
                    cleanResult.addWarning(unknownSchemaWarning);
                    LOG.warn(unknownSchemaWarning);
                    continue;
                }

                if (dropSchemas) {
                    try {
                        cleanSchema(schema);
                    } catch (FlywayException e) {
                        // ignore as we drop schemas later
                    }
                } else {
                    cleanSchema(schema);
                    cleanResult.schemasCleaned.add(schema.getName());
                }
            }

            dropDatabaseObjectsPostSchemas();

            if (dropSchemas) {
                for (Schema schema : schemas) {
                    dropSchema(schema, cleanResult);
                }
            }
        } catch (FlywayException e) {
            callbackExecutor.onEvent(Event.AFTER_CLEAN_ERROR);
            throw e;
        }

        callbackExecutor.onEvent(Event.AFTER_CLEAN);
        schemaHistory.clearCache();

        return cleanResult;
    }

    /**
     * Drops database-level objects that need to be cleaned prior to schema-level objects.
     */
    private void dropDatabaseObjectsPreSchemas() {
        LOG.debug("Dropping pre-schema database level objects...");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            ExecutionTemplateFactory.createExecutionTemplate(connection.getJdbcConnection(), database).execute(() -> {
                database.cleanPreSchemas();
                return null;
            });
        } catch (FlywaySqlException e) {
            LOG.debug(e.getMessage());
            LOG.warn("Unable to drop pre-schema database level objects");
        }
        stopWatch.stop();
        LOG.info(String.format("Successfully dropped pre-schema database level objects (execution time %s)",
                TimeFormat.format(stopWatch.getTotalTimeMillis())));
    }

    /**
     * Drops database-level objects that need to be cleaned after all schema-level objects.
     */
    private void dropDatabaseObjectsPostSchemas() {
        LOG.debug("Dropping post-schema database level objects...");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            ExecutionTemplateFactory.createExecutionTemplate(connection.getJdbcConnection(), database).execute(() -> {
                database.cleanPostSchemas(schemas);
                return null;
            });
        } catch (FlywaySqlException e) {
            LOG.debug(e.getMessage());
            LOG.warn("Unable to drop post-schema database level objects");
        }
        stopWatch.stop();
        LOG.info(String.format("Successfully dropped post-schema database level objects (execution time %s)",
                TimeFormat.format(stopWatch.getTotalTimeMillis())));
    }

    private void dropSchema(final Schema schema, CleanResult cleanResult) {
        LOG.debug("Dropping schema " + schema + "...");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            ExecutionTemplateFactory.createExecutionTemplate(connection.getJdbcConnection(), database).execute(() -> {
                schema.drop();
                return null;
            });

            cleanResult.schemasDropped.add(schema.getName());

            stopWatch.stop();
            LOG.info(String.format("Successfully dropped schema %s (execution time %s)",
                    schema, TimeFormat.format(stopWatch.getTotalTimeMillis())));
        } catch (FlywaySqlException e) {
            LOG.debug(e.getMessage());
            LOG.warn("Unable to drop schema " + schema + ". It was cleaned instead.");
            cleanResult.schemasCleaned.add(schema.getName());
        }
    }

    private void cleanSchema(final Schema schema) {
        LOG.debug("Cleaning schema " + schema + " ...");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        ExecutionTemplateFactory.createExecutionTemplate(connection.getJdbcConnection(), database).execute(() -> {
            schema.clean();
            return null;
        });
        stopWatch.stop();
        LOG.info(String.format("Successfully cleaned schema %s (execution time %s)",
                schema, TimeFormat.format(stopWatch.getTotalTimeMillis())));
    }
}