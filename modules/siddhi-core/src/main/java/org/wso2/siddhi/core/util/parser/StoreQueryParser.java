/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.util.parser;

import org.wso2.siddhi.core.aggregation.AggregationRuntime;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.state.MetaStateEvent;
import org.wso2.siddhi.core.event.state.StateEventPool;
import org.wso2.siddhi.core.event.state.populater.StateEventPopulatorFactory;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent.EventType;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventFactory;
import org.wso2.siddhi.core.event.stream.populater.ComplexEventPopulater;
import org.wso2.siddhi.core.event.stream.populater.StreamEventPopulaterFactory;
import org.wso2.siddhi.core.exception.StoreQueryCreationException;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.DeleteStoreQueryRuntime;
import org.wso2.siddhi.core.query.FindStoreQueryRuntime;
import org.wso2.siddhi.core.query.SelectInsertIntoQueryRuntime;
import org.wso2.siddhi.core.query.StoreQueryRuntime;
import org.wso2.siddhi.core.query.UpdateOrInsertQueryRuntime;
import org.wso2.siddhi.core.query.UpdateStoreQueryRuntime;
import org.wso2.siddhi.core.query.output.callback.OutputCallback;
import org.wso2.siddhi.core.query.output.ratelimit.PassThroughOutputRateLimiter;
import org.wso2.siddhi.core.query.processor.stream.window.QueryableProcessor;
import org.wso2.siddhi.core.query.selector.QuerySelector;
import org.wso2.siddhi.core.table.CompiledUpdateSet;
import org.wso2.siddhi.core.table.Table;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.collection.operator.CompiledCondition;
import org.wso2.siddhi.core.util.collection.operator.CompiledSelection;
import org.wso2.siddhi.core.util.collection.operator.IncrementalAggregateCompileCondition;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import org.wso2.siddhi.core.util.lock.LockWrapper;
import org.wso2.siddhi.core.util.parser.helper.QueryParserHelper;
import org.wso2.siddhi.core.util.snapshot.SnapshotService;
import org.wso2.siddhi.core.window.Window;
import org.wso2.siddhi.query.api.aggregation.Within;
import org.wso2.siddhi.query.api.definition.AbstractDefinition;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.siddhi.query.api.definition.TableDefinition;
import org.wso2.siddhi.query.api.execution.query.StoreQuery;
import org.wso2.siddhi.query.api.execution.query.input.store.AggregationInputStore;
import org.wso2.siddhi.query.api.execution.query.input.store.ConditionInputStore;
import org.wso2.siddhi.query.api.execution.query.input.store.InputStore;
import org.wso2.siddhi.query.api.execution.query.output.stream.DeleteStream;
import org.wso2.siddhi.query.api.execution.query.output.stream.InsertIntoStream;
import org.wso2.siddhi.query.api.execution.query.output.stream.OutputStream;
import org.wso2.siddhi.query.api.execution.query.output.stream.ReturnStream;
import org.wso2.siddhi.query.api.execution.query.output.stream.UpdateOrInsertStream;
import org.wso2.siddhi.query.api.execution.query.output.stream.UpdateStream;
import org.wso2.siddhi.query.api.execution.query.selection.Selector;
import org.wso2.siddhi.query.api.expression.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class to parse {@link StoreQueryRuntime}.
 */
public class StoreQueryParser {

    /**
     * Parse a storeQuery and return corresponding StoreQueryRuntime.
     *
     * @param storeQuery       storeQuery to be parsed.
     * @param siddhiAppContext associated Siddhi app context.
     * @param tableMap         keyvalue containing tables.
     * @param windowMap        keyvalue containing windows.
     * @param aggregationMap   keyvalue containing aggregation runtimes.
     * @return StoreQueryRuntime
     */
    public static StoreQueryRuntime parse(StoreQuery storeQuery, SiddhiAppContext siddhiAppContext,
                                          Map<String, Table> tableMap, Map<String, Window> windowMap,
                                          Map<String, AggregationRuntime> aggregationMap) {
        String queryName = null;
        final LockWrapper lockWrapper = new LockWrapper("StoreQueryLock");
        lockWrapper.setLock(new ReentrantLock());
        if (storeQuery.getInputStore() != null) {
            queryName = "store_select_query_" + storeQuery.getInputStore().getStoreId();
            InputStore inputStore = storeQuery.getInputStore();
            int metaPosition = SiddhiConstants.UNKNOWN_STATE;
            Within within = null;
            Expression per = null;
            try {
                SnapshotService.getSkipSnapshotableThreadLocal().set(true);
                Expression onCondition = Expression.value(true);
                MetaStreamEvent metaStreamEvent = new MetaStreamEvent();
                metaStreamEvent.setInputReferenceId(inputStore.getStoreReferenceId());

                if (inputStore instanceof AggregationInputStore) {
                    AggregationInputStore aggregationInputStore = (AggregationInputStore) inputStore;
                    if (aggregationMap.get(inputStore.getStoreId()) == null) {
                        throw new StoreQueryCreationException("Aggregation \"" + inputStore.getStoreId() +
                                "\" has not been defined");
                    }
                    if (aggregationInputStore.getPer() != null && aggregationInputStore.getWithin() != null) {
                        within = aggregationInputStore.getWithin();
                        per = aggregationInputStore.getPer();
                    } else if (aggregationInputStore.getPer() != null || aggregationInputStore.getWithin() != null) {
                        throw new StoreQueryCreationException(
                                inputStore.getStoreId() + " should either have both 'within' " +
                                        "and 'per' defined or none.");
                    }
                    if (((AggregationInputStore) inputStore).getOnCondition() != null) {
                        onCondition = ((AggregationInputStore) inputStore).getOnCondition();
                    }
                } else if (inputStore instanceof ConditionInputStore) {
                    if (((ConditionInputStore) inputStore).getOnCondition() != null) {
                        onCondition = ((ConditionInputStore) inputStore).getOnCondition();
                    }
                }
                List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();
                Table table = tableMap.get(inputStore.getStoreId());
                if (table != null) {
                    return constructStoreQueryRuntime(table, storeQuery, siddhiAppContext, tableMap, windowMap,
                            queryName,
                            metaPosition, onCondition, metaStreamEvent, variableExpressionExecutors, lockWrapper);
                } else {
                    AggregationRuntime aggregation = aggregationMap.get(inputStore.getStoreId());
                    if (aggregation != null) {
                        return constructStoreQueryRuntime(aggregation, storeQuery, siddhiAppContext, tableMap, queryName,
                                within, per, onCondition, metaStreamEvent, variableExpressionExecutors);
                    } else {
                        Window window = windowMap.get(inputStore.getStoreId());
                        if (window != null) {
                            return constructStoreQueryRuntime(window, storeQuery, siddhiAppContext, tableMap, queryName,
                                    metaPosition, onCondition, metaStreamEvent, variableExpressionExecutors);
                        } else {
                            throw new StoreQueryCreationException(
                                    inputStore.getStoreId() + " is neither a table, aggregation or window");
                        }
                    }
                }

            } finally {
                SnapshotService.getSkipSnapshotableThreadLocal().set(null);
            }
        } else if (storeQuery.isDeleteQuery()) {
            DeleteStream outputStream = (DeleteStream) storeQuery.getOutputStream();
            queryName = "store_delete_query_" + outputStream.getId();
            int metaPosition = SiddhiConstants.UNKNOWN_STATE;
            Within within = null;
            Expression per = null;
            try {
                SnapshotService.getSkipSnapshotableThreadLocal().set(true);
                Expression onCondition = outputStream.getOnDeleteExpression();
                MetaStreamEvent metaStreamEvent = new MetaStreamEvent();
                metaStreamEvent.setInputReferenceId(outputStream.getId());

                List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();
                Table table = tableMap.get(outputStream.getId());
                if (table != null) {
                    return constructStoreQueryRuntime(table, storeQuery, siddhiAppContext, tableMap, windowMap,
                            queryName,
                            metaPosition, onCondition, metaStreamEvent, variableExpressionExecutors, lockWrapper);
                } else {
                    throw new StoreQueryCreationException(outputStream.getId() + " is not a table.");
                }

            } finally {
                SnapshotService.getSkipSnapshotableThreadLocal().set(null);
            }
        } else if (storeQuery.isUpdateQuery()) {
            UpdateStream outputStream = (UpdateStream) storeQuery.getOutputStream();
            queryName = "store_update_query_" + outputStream.getId();
            int metaPosition = SiddhiConstants.UNKNOWN_STATE;
            Within within = null;
            Expression per = null;
            try {
                SnapshotService.getSkipSnapshotableThreadLocal().set(true);
                Expression onCondition = outputStream.getOnUpdateExpression();
                MetaStreamEvent metaStreamEvent = new MetaStreamEvent();
                metaStreamEvent.setInputReferenceId(outputStream.getId());

                List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();
                Table table = tableMap.get(outputStream.getId());
                if (table != null) {
                    return constructStoreQueryRuntime(table, storeQuery, siddhiAppContext, tableMap, windowMap,
                            queryName,
                            metaPosition, onCondition, metaStreamEvent, variableExpressionExecutors, lockWrapper);
                } else {
                    throw new StoreQueryCreationException(outputStream.getId() + "is not a table.");
                }

            } finally {
                SnapshotService.getSkipSnapshotableThreadLocal().set(null);
            }
        } else if (storeQuery.isUpdateOrInsertQuery()) {
            UpdateOrInsertStream outputStream = (UpdateOrInsertStream) storeQuery.getOutputStream();
            queryName = "store_update_or_insert_into_query_" + outputStream.getId();
            int metaPosition = SiddhiConstants.UNKNOWN_STATE;
            Within within = null;
            Expression per = null;
            try {
                SnapshotService.getSkipSnapshotableThreadLocal().set(true);
                Expression onCondition = outputStream.getOnUpdateExpression();
                MetaStreamEvent metaStreamEvent = new MetaStreamEvent();
                metaStreamEvent.setInputReferenceId(outputStream.getId());

                List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();
                Table table = tableMap.get(outputStream.getId());

                if (table != null) {
                    return constructStoreQueryRuntime(table, storeQuery, siddhiAppContext, tableMap, windowMap,
                            queryName,
                            metaPosition, onCondition, metaStreamEvent, variableExpressionExecutors, lockWrapper);
                } else {
                    throw new StoreQueryCreationException(outputStream.getId() + "is not a table.");
                }

            } finally {
                SnapshotService.getSkipSnapshotableThreadLocal().set(null);
            }
        } else if (storeQuery.isSelectInsertIntoQuery()) {
            InsertIntoStream outputStream = (InsertIntoStream) storeQuery.getOutputStream();
            queryName = "store_select_insert_into_query_" + outputStream.getId();
            int metaPosition = SiddhiConstants.UNKNOWN_STATE;
            Within within = null;
            Expression per = null;
            try {
                SnapshotService.getSkipSnapshotableThreadLocal().set(true);
                Expression onCondition = Expression.value(true);
                MetaStreamEvent metaStreamEvent = new MetaStreamEvent();
                metaStreamEvent.setInputReferenceId(outputStream.getId());

                List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();
                Table table = tableMap.get(outputStream.getId());

                if (table != null) {
                    return constructStoreQueryRuntime(table, storeQuery, siddhiAppContext, tableMap, windowMap,
                            queryName,
                            metaPosition, onCondition, metaStreamEvent, variableExpressionExecutors, lockWrapper);
                } else {
                    throw new StoreQueryCreationException(outputStream.getId() + "is not a table.");
                }

            } finally {
                SnapshotService.getSkipSnapshotableThreadLocal().set(null);
            }
        }
        return null;
    }

    private static StoreQueryRuntime constructStoreQueryRuntime(
            Window window, StoreQuery storeQuery,
            SiddhiAppContext siddhiAppContext, Map<String, Table> tableMap, String queryName, int metaPosition,
            Expression onCondition, MetaStreamEvent metaStreamEvent,
            List<VariableExpressionExecutor> variableExpressionExecutors) {
        metaStreamEvent.setEventType(EventType.WINDOW);
        initMetaStreamEvent(metaStreamEvent, window.getWindowDefinition());
        MatchingMetaInfoHolder metaStreamInfoHolder = generateMatchingMetaInfoHolder(metaStreamEvent,
                window.getWindowDefinition());
        CompiledCondition compiledCondition = window.compileCondition(onCondition,
                generateMatchingMetaInfoHolder(metaStreamEvent, window.getWindowDefinition()),
                siddhiAppContext, variableExpressionExecutors, tableMap, queryName);
        FindStoreQueryRuntime findStoreQueryRuntime = new FindStoreQueryRuntime(window, compiledCondition,
                queryName, metaStreamEvent);
        populateFindStoreQueryRuntime(findStoreQueryRuntime, metaStreamInfoHolder, storeQuery.getSelector(),
                variableExpressionExecutors, siddhiAppContext, tableMap, queryName, metaPosition);
        return findStoreQueryRuntime;
    }

    private static StoreQueryRuntime constructStoreQueryRuntime(AggregationRuntime aggregation, StoreQuery storeQuery,
                                                                SiddhiAppContext siddhiAppContext,
                                                                Map<String, Table> tableMap, String queryName,
                                                                Within within, Expression per, Expression onCondition,
                                                                MetaStreamEvent metaStreamEvent,
                                                                List<VariableExpressionExecutor>
                                                                        variableExpressionExecutors) {
        int metaPosition;
        metaStreamEvent.setEventType(EventType.AGGREGATE);
        initMetaStreamEvent(metaStreamEvent, aggregation.getAggregationDefinition());
        MatchingMetaInfoHolder metaStreamInfoHolder = generateMatchingMetaInfoHolder(metaStreamEvent,
                aggregation.getAggregationDefinition());
        CompiledCondition compiledCondition = aggregation.compileExpression(onCondition, within, per,
                metaStreamInfoHolder, variableExpressionExecutors, tableMap, queryName, siddhiAppContext);
        metaStreamInfoHolder = ((IncrementalAggregateCompileCondition) compiledCondition).
                getAlteredMatchingMetaInfoHolder();
        FindStoreQueryRuntime findStoreQueryRuntime = new FindStoreQueryRuntime(aggregation, compiledCondition,
                queryName, metaStreamEvent);
        metaPosition = 1;
        populateFindStoreQueryRuntime(findStoreQueryRuntime, metaStreamInfoHolder,
                storeQuery.getSelector(), variableExpressionExecutors, siddhiAppContext, tableMap,
                queryName, metaPosition);
        ComplexEventPopulater complexEventPopulater = StreamEventPopulaterFactory.constructEventPopulator(
                metaStreamInfoHolder.getMetaStateEvent().getMetaStreamEvent(0), 0,
                ((IncrementalAggregateCompileCondition) compiledCondition).getAdditionalAttributes());
        ((IncrementalAggregateCompileCondition) compiledCondition)
                .setComplexEventPopulater(complexEventPopulater);
        return findStoreQueryRuntime;
    }

    private static StoreQueryRuntime constructStoreQueryRuntime(Table table, StoreQuery storeQuery,
                                                                SiddhiAppContext siddhiAppContext,
                                                                Map<String, Table> tableMap, Map<String, Window>
                                                                        windowMap, String
                                                                        queryName,
                                                                int metaPosition, Expression onCondition,
                                                                MetaStreamEvent metaStreamEvent,
                                                                List<VariableExpressionExecutor>
                                                                        variableExpressionExecutors,
                                                                LockWrapper lockWrapper) {
        metaStreamEvent.setEventType(EventType.TABLE);
        initMetaStreamEvent(metaStreamEvent, table.getTableDefinition());
        MatchingMetaInfoHolder metaStreamInfoHolder = generateMatchingMetaInfoHolder(metaStreamEvent,
                table.getTableDefinition());
        CompiledCondition compiledCondition = table.compileCondition(onCondition, metaStreamInfoHolder,
                siddhiAppContext, variableExpressionExecutors, tableMap, queryName);

        StoreQueryRuntime storeQueryRuntime = null;
        if (table instanceof QueryableProcessor) {
            List<Attribute> expectedOutputAttributes = buildExpectedOutputAttributes(storeQuery, siddhiAppContext,
                    tableMap, queryName, metaPosition, metaStreamInfoHolder);
            CompiledSelection compiledSelection = ((QueryableProcessor) table).compileSelection(
                    storeQuery.getSelector(), expectedOutputAttributes, metaStreamInfoHolder, siddhiAppContext,
                    variableExpressionExecutors, tableMap, queryName);

            QueryParserHelper.reduceMetaComplexEvent(metaStreamInfoHolder.getMetaStateEvent());
            QueryParserHelper.updateVariablePosition(metaStreamInfoHolder.getMetaStateEvent(),
                    variableExpressionExecutors);
            return storeQueryRuntime;
        } else {

            if (storeQuery.isDeleteQuery()) {
                storeQueryRuntime = new DeleteStoreQueryRuntime(table, compiledCondition, queryName, metaStreamEvent);
                populateStoreQueryRuntime((DeleteStoreQueryRuntime) storeQueryRuntime,
                        metaStreamInfoHolder, variableExpressionExecutors,
                        siddhiAppContext, tableMap, windowMap, queryName, metaPosition, storeQuery, lockWrapper);
            } else if (storeQuery.isUpdateOrInsertQuery()) {
                storeQueryRuntime = new UpdateOrInsertQueryRuntime(table, compiledCondition,
                        queryName, metaStreamEvent);
                populateStoreQueryRuntime((UpdateOrInsertQueryRuntime) storeQueryRuntime,
                        metaStreamInfoHolder, variableExpressionExecutors,
                        siddhiAppContext, tableMap, windowMap, queryName, metaPosition, storeQuery, lockWrapper);
            } else if (storeQuery.isUpdateQuery()) {
                storeQueryRuntime = new UpdateStoreQueryRuntime(table, compiledCondition, queryName,
                        metaStreamEvent);
                populateStoreQueryRuntime((UpdateStoreQueryRuntime) storeQueryRuntime,
                        metaStreamInfoHolder, variableExpressionExecutors,
                        siddhiAppContext, tableMap, windowMap, queryName, metaPosition, storeQuery, lockWrapper);

            } else if (storeQuery.isSelectInsertIntoQuery()) {
                storeQueryRuntime = new SelectInsertIntoQueryRuntime(table,  queryName,
                        metaStreamEvent);
                populateStoreQueryRuntime(storeQueryRuntime, metaStreamInfoHolder, variableExpressionExecutors,
                        siddhiAppContext, tableMap, windowMap, queryName, metaPosition, storeQuery, lockWrapper);
            } else {
                storeQueryRuntime = new FindStoreQueryRuntime(table, compiledCondition, queryName,
                        metaStreamEvent);
                populateFindStoreQueryRuntime((FindStoreQueryRuntime) storeQueryRuntime,
                        metaStreamInfoHolder, storeQuery.getSelector(),
                        variableExpressionExecutors, siddhiAppContext, tableMap, queryName, metaPosition);
            }

            return storeQueryRuntime;
        }
    }

    private static List<Attribute> buildExpectedOutputAttributes(
            StoreQuery storeQuery, SiddhiAppContext siddhiAppContext, Map<String, Table> tableMap,
            String queryName, int metaPosition, MatchingMetaInfoHolder metaStreamInfoHolder) {
        MetaStateEvent selectMetaStateEvent =
                new MetaStateEvent(metaStreamInfoHolder.getMetaStateEvent().getMetaStreamEvents());
        SelectorParser.parse(storeQuery.getSelector(),
                new ReturnStream(OutputStream.OutputEventType.CURRENT_EVENTS), siddhiAppContext,
                selectMetaStateEvent, tableMap, new ArrayList<>(), queryName,
                metaPosition);
        return selectMetaStateEvent.getOutputStreamDefinition().getAttributeList();
    }

    private static void populateFindStoreQueryRuntime(FindStoreQueryRuntime findStoreQueryRuntime,
                                                      MatchingMetaInfoHolder metaStreamInfoHolder, Selector selector,
                                                      List<VariableExpressionExecutor> variableExpressionExecutors,
                                                      SiddhiAppContext siddhiAppContext,
                                                      Map<String, Table> tableMap,
                                                      String queryName, int metaPosition) {
        QuerySelector querySelector = SelectorParser.parse(selector,
                new ReturnStream(OutputStream.OutputEventType.CURRENT_EVENTS), siddhiAppContext,
                metaStreamInfoHolder.getMetaStateEvent(), tableMap, variableExpressionExecutors, queryName,
                metaPosition);
        QueryParserHelper.reduceMetaComplexEvent(metaStreamInfoHolder.getMetaStateEvent());
        QueryParserHelper.updateVariablePosition(metaStreamInfoHolder.getMetaStateEvent(), variableExpressionExecutors);
        querySelector.setEventPopulator(
                StateEventPopulatorFactory.constructEventPopulator(metaStreamInfoHolder.getMetaStateEvent()));
        findStoreQueryRuntime.setStateEventPool(new StateEventPool(metaStreamInfoHolder.getMetaStateEvent(), 5));
        findStoreQueryRuntime.setSelector(querySelector);
        findStoreQueryRuntime.setOutputAttributes(metaStreamInfoHolder.getMetaStateEvent().
                getOutputStreamDefinition().getAttributeList());
    }

    /*
    * This method is used to pupulate following two types of store query runtimes.
    *          1. Update Or Insert Into Store Query Runtime.
    *          2. Select Insert Into Store Query Runtime.
    *          3. Delete Query Runtime.
    *          4. Updatre Query Runtime
    **/
    private static void populateStoreQueryRuntime(StoreQueryRuntime storeQueryRuntime,
                                                  MatchingMetaInfoHolder metaStreamInfoHolder,
                                                  List<VariableExpressionExecutor> variableExpressionExecutors,
                                                  SiddhiAppContext siddhiAppContext,
                                                  Map<String, Table> tableMap, Map<String, Window> windowMap,
                                                  String queryName, int metaPosition, StoreQuery storeQuery,
                                                  LockWrapper lockWrapper) {
        QuerySelector querySelector = SelectorParser.parse(storeQuery.getSelector(),
                storeQuery.getOutputStream(), siddhiAppContext,
                metaStreamInfoHolder.getMetaStateEvent(), tableMap, variableExpressionExecutors, queryName,
                metaPosition);

        PassThroughOutputRateLimiter rateLimiter = new PassThroughOutputRateLimiter(queryName);
        rateLimiter.init(siddhiAppContext, lockWrapper, queryName);
        OutputCallback outputCallback = OutputParser.constructOutputCallback(storeQuery.getOutputStream(),
                metaStreamInfoHolder.getMetaStateEvent().getOutputStreamDefinition(), tableMap, windowMap,
                siddhiAppContext, true, queryName);
        rateLimiter.setOutputCallback(outputCallback);
        querySelector.setNextProcessor(rateLimiter);

        QueryParserHelper.reduceMetaComplexEvent(metaStreamInfoHolder.getMetaStateEvent());
        QueryParserHelper.updateVariablePosition(metaStreamInfoHolder.getMetaStateEvent(), variableExpressionExecutors);
        querySelector.setEventPopulator(
                StateEventPopulatorFactory.constructEventPopulator(metaStreamInfoHolder.getMetaStateEvent()));

        if (storeQueryRuntime instanceof SelectInsertIntoQueryRuntime) {
            SelectInsertIntoQueryRuntime selectInsertIntoQueryRuntime = (SelectInsertIntoQueryRuntime)
                    storeQueryRuntime;
            selectInsertIntoQueryRuntime.setStateEventPool(
                    new StateEventPool(metaStreamInfoHolder.getMetaStateEvent(), 5));
            selectInsertIntoQueryRuntime.setSelector(querySelector);
            selectInsertIntoQueryRuntime.setOutputAttributes(metaStreamInfoHolder.getMetaStateEvent().
                    getOutputStreamDefinition().getAttributeList());
        } else if (storeQueryRuntime instanceof UpdateOrInsertQueryRuntime){
            UpdateOrInsertQueryRuntime UpdateOrInsertIntoStoreQueryRuntime = (UpdateOrInsertQueryRuntime)
                    storeQueryRuntime;
            UpdateOrInsertIntoStoreQueryRuntime.setStateEventPool(
                    new StateEventPool(metaStreamInfoHolder.getMetaStateEvent(), 5));
            UpdateOrInsertIntoStoreQueryRuntime.setSelector(querySelector);
            UpdateOrInsertIntoStoreQueryRuntime.setOutputAttributes(metaStreamInfoHolder.getMetaStateEvent().
                    getOutputStreamDefinition().getAttributeList());
        } else if (storeQueryRuntime instanceof DeleteStoreQueryRuntime) {
            DeleteStoreQueryRuntime deleteStoreQueryRuntime = (DeleteStoreQueryRuntime) storeQueryRuntime;
            deleteStoreQueryRuntime.setStateEventPool(
                    new StateEventPool(metaStreamInfoHolder.getMetaStateEvent(), 5));
            deleteStoreQueryRuntime.setSelector(querySelector);
            deleteStoreQueryRuntime.setOutputAttributes(metaStreamInfoHolder.getMetaStateEvent().
                    getOutputStreamDefinition().getAttributeList());
        } else if (storeQueryRuntime instanceof UpdateStoreQueryRuntime) {
            UpdateStoreQueryRuntime udpateStoreQueryRuntime = (UpdateStoreQueryRuntime) storeQueryRuntime;
            udpateStoreQueryRuntime.setStateEventPool(
                    new StateEventPool(metaStreamInfoHolder.getMetaStateEvent(), 5));
            udpateStoreQueryRuntime.setSelector(querySelector);
            udpateStoreQueryRuntime.setOutputAttributes(metaStreamInfoHolder.getMetaStateEvent().
                    getOutputStreamDefinition().getAttributeList());
        }
    }

    private static MatchingMetaInfoHolder generateMatchingMetaInfoHolder(MetaStreamEvent metaStreamEvent,
                                                                         AbstractDefinition definition) {
        MetaStateEvent metaStateEvent = new MetaStateEvent(1);
        metaStateEvent.addEvent(metaStreamEvent);
        return new MatchingMetaInfoHolder(metaStateEvent, -1, 0, definition,
                definition, 0);
    }

    private static void initMetaStreamEvent(MetaStreamEvent metaStreamEvent, AbstractDefinition inputDefinition) {
        metaStreamEvent.addInputDefinition(inputDefinition);
        metaStreamEvent.initializeAfterWindowData();
        if (inputDefinition != null) {
            inputDefinition.getAttributeList().forEach(metaStreamEvent::addData);
        }
    }

}
