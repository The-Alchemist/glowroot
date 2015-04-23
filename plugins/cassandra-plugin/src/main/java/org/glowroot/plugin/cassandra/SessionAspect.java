/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.plugin.cassandra;

import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.Nullable;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.TimerName;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.api.weaving.Shim;
import org.glowroot.plugin.cassandra.ResultSetAspect.HasLastQueryMessageSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class SessionAspect {

    private static final PluginServices pluginServices = PluginServices.get("cassandra");

    // volatile is not needed here as it piggybacks on PluginServicesImpl.memoryBarrier
    private static int stackTraceThresholdMillis;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                Double value =
                        pluginServices.getDoubleProperty("stackTraceThresholdMillis").value();
                stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
            }
        });
    }

    @Shim("com.datastax.driver.core.Statement")
    public interface Statement {}

    @Shim("com.datastax.driver.core.RegularStatement")
    public interface RegularStatement extends Statement {

        @Nullable
        String getQueryString();
    }

    @Shim("com.datastax.driver.core.BoundStatement")
    public interface BoundStatement extends Statement {

        @Shim("com.datastax.driver.core.PreparedStatement preparedStatement()")
        @Nullable
        PreparedStatement preparedStatement();
    }

    @Shim("com.datastax.driver.core.BatchStatement")
    public interface BatchStatement extends Statement {

        @Nullable
        Collection<Statement> getStatements();
    }

    @Shim("com.datastax.driver.core.PreparedStatement")
    public interface PreparedStatement {

        @Nullable
        String getQueryString();
    }

    @Pointcut(className = "com.datastax.driver.core.Session", methodName = "execute|executeAsync",
            methodParameterTypes = {"com.datastax.driver.core.Statement"},
            ignoreSelfNested = true, timerName = "cql execute")
    public static class ExecuteStatementAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(ExecuteStatementAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindParameter @Nullable Object arg) {
            if (arg == null) {
                // seems nothing sensible to do here other than ignore
                return null;
            }
            MessageSupplier messageSupplier;
            if (arg instanceof String) {
                String query = (String) arg;
                messageSupplier = new QueryMessageSupplier(query);
            } else if (arg instanceof RegularStatement) {
                String query = ((RegularStatement) arg).getQueryString();
                messageSupplier = new QueryMessageSupplier(nullToEmpty(query));
            } else if (arg instanceof BoundStatement) {
                PreparedStatement preparedStatement = ((BoundStatement) arg).preparedStatement();
                String query = preparedStatement == null ? "" : preparedStatement.getQueryString();
                messageSupplier = new QueryMessageSupplier(nullToEmpty(query));
            } else if (arg instanceof BatchStatement) {
                Collection<Statement> statements = ((BatchStatement) arg).getStatements();
                if (statements == null) {
                    statements = new ArrayList<Statement>();
                }
                messageSupplier = BatchQueryMessageSupplier.from(statements);
            } else {
                return null;
            }
            return pluginServices.startTraceEntry(messageSupplier, timerName);
        }
        @OnReturn
        public static void onReturn(
                @BindReturn @Nullable HasLastQueryMessageSupplier resultSetOrResultSetFuture,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                if (resultSetOrResultSetFuture != null) {
                    MessageSupplier messageSupplier = traceEntry.getMessageSupplier();
                    if (messageSupplier instanceof QueryMessageSupplier) {
                        resultSetOrResultSetFuture.setGlowrootLastQueryMessageSupplier(
                                (QueryMessageSupplier) messageSupplier);
                    }
                }
                traceEntry.endWithStackTrace(stackTraceThresholdMillis, MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(ErrorMessage.from(t));
            }
        }
        private static String nullToEmpty(@Nullable String string) {
            return string == null ? "" : string;
        }
    }
}