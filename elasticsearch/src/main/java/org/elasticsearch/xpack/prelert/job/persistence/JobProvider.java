/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.persistence;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefIterator;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.mapper.UidFieldMapper;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermsQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xpack.prelert.action.DeleteJobAction;
import org.elasticsearch.xpack.prelert.job.CategorizerState;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.ModelSizeStats;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.ModelState;
import org.elasticsearch.xpack.prelert.job.audit.AuditActivity;
import org.elasticsearch.xpack.prelert.job.audit.AuditMessage;
import org.elasticsearch.xpack.prelert.job.audit.Auditor;
import org.elasticsearch.xpack.prelert.job.persistence.BucketsQueryBuilder.BucketsQuery;
import org.elasticsearch.xpack.prelert.job.persistence.InfluencersQueryBuilder.InfluencersQuery;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;
import org.elasticsearch.xpack.prelert.job.results.AnomalyRecord;
import org.elasticsearch.xpack.prelert.job.results.Bucket;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinition;
import org.elasticsearch.xpack.prelert.job.results.Influencer;
import org.elasticsearch.xpack.prelert.job.results.ModelDebugOutput;
import org.elasticsearch.xpack.prelert.job.results.PerPartitionMaxProbabilities;
import org.elasticsearch.xpack.prelert.job.results.Result;
import org.elasticsearch.xpack.prelert.job.usage.Usage;
import org.elasticsearch.xpack.prelert.lists.ListDocument;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;
import org.elasticsearch.xpack.prelert.utils.FixBlockingClientOperations;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class JobProvider {
    private static final Logger LOGGER = Loggers.getLogger(JobProvider.class);

    /**
     * The index to store total usage/metering information
     */
    public static final String PRELERT_USAGE_INDEX = "prelert-usage";

    /**
     * Where to store the prelert info in Elasticsearch - must match what's
     * expected by kibana/engineAPI/app/directives/prelertLogUsage.js
     */
    private static final String PRELERT_INFO_INDEX = "prelert-int";

    private static final String ASYNC = "async";

    private static final List<String> SECONDARY_SORT = Arrays.asList(
            AnomalyRecord.ANOMALY_SCORE.getPreferredName(),
            AnomalyRecord.OVER_FIELD_VALUE.getPreferredName(),
            AnomalyRecord.PARTITION_FIELD_VALUE.getPreferredName(),
            AnomalyRecord.BY_FIELD_VALUE.getPreferredName(),
            AnomalyRecord.FIELD_NAME.getPreferredName(),
            AnomalyRecord.FUNCTION.getPreferredName()
    );

    private static final int RECORDS_SIZE_PARAM = 500;


    private final Client client;
    private final int numberOfReplicas;
    private final ParseFieldMatcher parseFieldMatcher;

    public JobProvider(Client client, int numberOfReplicas, ParseFieldMatcher parseFieldMatcher) {
        this.parseFieldMatcher = parseFieldMatcher;
        this.client = Objects.requireNonNull(client);
        this.numberOfReplicas = numberOfReplicas;
    }

    /**
     * If the {@value JobProvider#PRELERT_USAGE_INDEX} index does
     * not exist then create it here with the usage document mapping.
     */
    public void createUsageMeteringIndex(BiConsumer<Boolean, Exception> listener) {
        try {
            LOGGER.info("Creating the internal '{}' index", PRELERT_USAGE_INDEX);
            XContentBuilder usageMapping = ElasticsearchMappings.usageMapping();
            LOGGER.trace("ES API CALL: create index {}", PRELERT_USAGE_INDEX);
            client.admin().indices().prepareCreate(PRELERT_USAGE_INDEX)
                    .setSettings(mlResultsIndexSettings())
                    .addMapping(Usage.TYPE, usageMapping)
                    .execute(ActionListener.wrap(r -> listener.accept(true, null), e -> listener.accept(false, e)));
        } catch (Exception e) {
            LOGGER.warn("Error creating the usage metering index", e);
        }
    }

    /**
     * Build the Elasticsearch index settings that we want to apply to results
     * indexes.  It's better to do this in code rather than in elasticsearch.yml
     * because then the settings can be applied regardless of whether we're
     * using our own Elasticsearch to store results or a customer's pre-existing
     * Elasticsearch.
     *
     * @return An Elasticsearch builder initialised with the desired settings
     * for Prelert indexes.
     */
    Settings.Builder mlResultsIndexSettings() {
        return Settings.builder()
                // Our indexes are small and one shard puts the
                // least possible burden on Elasticsearch
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, numberOfReplicas)
                // Sacrifice durability for performance: in the event of power
                // failure we can lose the last 5 seconds of changes, but it's
                // much faster
                .put(IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING.getKey(), ASYNC)
                // We need to allow fields not mentioned in the mappings to
                // pick up default mappings and be used in queries
                .put(MapperService.INDEX_MAPPER_DYNAMIC_SETTING.getKey(), true)
                // set the default all search field
                .put(IndexSettings.DEFAULT_FIELD_SETTING.getKey(), ElasticsearchMappings.ALL_FIELD_VALUES);
    }

    /**
     * Build the Elasticsearch index settings that we want to apply to the state
     * index.  It's better to do this in code rather than in elasticsearch.yml
     * because then the settings can be applied regardless of whether we're
     * using our own Elasticsearch to store results or a customer's pre-existing
     * Elasticsearch.
     *
     * @return An Elasticsearch builder initialised with the desired settings
     * for Prelert indexes.
     */
    Settings.Builder mlStateIndexSettings() {
        // TODO review these settings
        return Settings.builder()
                // Our indexes are small and one shard puts the
                // least possible burden on Elasticsearch
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, numberOfReplicas)
                // Sacrifice durability for performance: in the event of power
                // failure we can lose the last 5 seconds of changes, but it's
                // much faster
                .put(IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING.getKey(), ASYNC);
    }

    /**
     * Create the Elasticsearch index and the mappings
     */
    public void createJobResultIndex(Job job, ActionListener<Boolean> listener) {
        Collection<String> termFields = (job.getAnalysisConfig() != null) ? job.getAnalysisConfig().termFields() : Collections.emptyList();
        try {
            XContentBuilder resultsMapping = ElasticsearchMappings.resultsMapping(termFields);
            XContentBuilder categoryDefinitionMapping = ElasticsearchMappings.categoryDefinitionMapping();
            XContentBuilder dataCountsMapping = ElasticsearchMappings.dataCountsMapping();
            XContentBuilder usageMapping = ElasticsearchMappings.usageMapping();
            XContentBuilder auditMessageMapping = ElasticsearchMappings.auditMessageMapping();
            XContentBuilder auditActivityMapping = ElasticsearchMappings.auditActivityMapping();
            XContentBuilder modelSnapshotMapping = ElasticsearchMappings.modelSnapshotMapping();

            String jobId = job.getId();
            boolean createIndexAlias = !job.getIndexName().equals(job.getId());
            String indexName = AnomalyDetectorsIndex.jobResultsIndexName(job.getIndexName());

            LOGGER.trace("ES API CALL: create index {}", indexName);
            CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);
            createIndexRequest.settings(mlResultsIndexSettings());
            createIndexRequest.mapping(Result.TYPE.getPreferredName(), resultsMapping);
            createIndexRequest.mapping(CategoryDefinition.TYPE.getPreferredName(), categoryDefinitionMapping);
            createIndexRequest.mapping(DataCounts.TYPE.getPreferredName(), dataCountsMapping);
            createIndexRequest.mapping(ModelSnapshot.TYPE.getPreferredName(), modelSnapshotMapping);
            // NORELASE These mappings shouldn't go in the results index once the index
            // strategy has been reworked
            createIndexRequest.mapping(Usage.TYPE, usageMapping);
            createIndexRequest.mapping(AuditMessage.TYPE.getPreferredName(), auditMessageMapping);
            createIndexRequest.mapping(AuditActivity.TYPE.getPreferredName(), auditActivityMapping);


            if (createIndexAlias) {
                final ActionListener<Boolean> responseListener = listener;
                listener = ActionListener.wrap(aBoolean -> {
                            client.admin().indices().prepareAliases()
                                    .addAlias(indexName, AnomalyDetectorsIndex.jobResultsIndexName(jobId))
                                    .execute(ActionListener.wrap(r -> responseListener.onResponse(true), responseListener::onFailure));
                        },
                        listener::onFailure);
            }

            final ActionListener<Boolean> createdListener = listener;
            client.admin().indices().create(createIndexRequest,
                    ActionListener.wrap(r -> createdListener.onResponse(true), createdListener::onFailure));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    public void createJobStateIndex(BiConsumer<Boolean, Exception> listener) {
        try {
            XContentBuilder categorizerStateMapping = ElasticsearchMappings.categorizerStateMapping();
            XContentBuilder quantilesMapping = ElasticsearchMappings.quantilesMapping();
            XContentBuilder modelStateMapping = ElasticsearchMappings.modelStateMapping();

            LOGGER.trace("ES API CALL: create state index {}", AnomalyDetectorsIndex.jobStateIndexName());
            CreateIndexRequest createIndexRequest = new CreateIndexRequest(AnomalyDetectorsIndex.jobStateIndexName());
            createIndexRequest.settings(mlStateIndexSettings());
            createIndexRequest.mapping(CategorizerState.TYPE, categorizerStateMapping);
            createIndexRequest.mapping(Quantiles.TYPE.getPreferredName(), quantilesMapping);
            createIndexRequest.mapping(ModelState.TYPE.getPreferredName(), modelStateMapping);

            client.admin().indices().create(createIndexRequest,
                    ActionListener.wrap(r -> listener.accept(true, null), e -> listener.accept(false, e)));
        } catch (Exception e) {
            LOGGER.error("Error creating the " + AnomalyDetectorsIndex.jobStateIndexName() + " index", e);
        }
    }


    /**
     * Delete all the job related documents from the database.
     */
    // TODO: should live together with createJobRelatedIndices (in case it moves)?
    public void deleteJobRelatedIndices(String jobId, ActionListener<DeleteJobAction.Response> listener) {
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        LOGGER.trace("ES API CALL: delete index {}", indexName);

        try {
            DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(indexName);
            client.admin().indices().delete(deleteIndexRequest,
                    ActionListener.wrap(r -> listener.onResponse(new DeleteJobAction.Response(r.isAcknowledged())), listener::onFailure));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Get the job's data counts
     *
     * @param jobId The job id
     */
    public void dataCounts(String jobId, Consumer<DataCounts> handler, Consumer<Exception> errorHandler) {
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        GetRequest getRequest = new GetRequest(indexName, DataCounts.TYPE.getPreferredName(), jobId + DataCounts.DOCUMENT_SUFFIX);
        client.get(getRequest, ActionListener.wrap(
                response -> {
                    if (response.isExists() == false) {
                        handler.accept(new DataCounts(jobId));
                    } else {
                        BytesReference source = response.getSourceAsBytesRef();
                        try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                            handler.accept(DataCounts.PARSER.apply(parser, () -> parseFieldMatcher));
                        } catch (IOException e) {
                            throw new ElasticsearchParseException("failed to parse bucket", e);
                        }
                    }
                },
                e -> {
                    if (e instanceof IndexNotFoundException) {
                        errorHandler.accept(ExceptionsHelper.missingJobException(jobId));
                    } else {
                        errorHandler.accept(e);
                    }
                }));
    }

    /**
     * Search for buckets with the parameters in the {@link BucketsQueryBuilder}
     */
    public void buckets(String jobId, BucketsQuery query, Consumer<QueryPage<Bucket>> handler, Consumer<Exception> errorHandler)
            throws ResourceNotFoundException {
        ResultsFilterBuilder rfb = new ResultsFilterBuilder();
        if (query.getTimestamp() != null) {
            rfb.timeRange(Bucket.TIMESTAMP.getPreferredName(), query.getTimestamp());
        } else {
            rfb.timeRange(Bucket.TIMESTAMP.getPreferredName(), query.getStart(), query.getEnd())
                    .score(Bucket.ANOMALY_SCORE.getPreferredName(), query.getAnomalyScoreFilter())
                    .score(Bucket.MAX_NORMALIZED_PROBABILITY.getPreferredName(), query.getNormalizedProbability())
                    .interim(Bucket.IS_INTERIM.getPreferredName(), query.isIncludeInterim());
        }

        SortBuilder<?> sortBuilder = new FieldSortBuilder(query.getSortField())
                .order(query.isSortDescending() ? SortOrder.DESC : SortOrder.ASC);

        QueryBuilder boolQuery = new BoolQueryBuilder()
                .filter(rfb.build())
                .filter(QueryBuilders.termQuery(Result.RESULT_TYPE.getPreferredName(), Bucket.RESULT_TYPE_VALUE));
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        SearchRequest searchRequest = new SearchRequest(indexName);
        searchRequest.types(Result.TYPE.getPreferredName());
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.sort(sortBuilder);
        searchSourceBuilder.query(boolQuery);
        searchSourceBuilder.from(query.getFrom());
        searchSourceBuilder.size(query.getSize());
        searchRequest.source(searchSourceBuilder);

        MultiSearchRequest mrequest = new MultiSearchRequest();
        mrequest.add(searchRequest);
        if (Strings.hasLength(query.getPartitionValue())) {
            mrequest.add(createPartitionMaxNormailizedProbabilitiesRequest(jobId, query.getStart(), query.getEnd(),
                    query.getPartitionValue()));
        }

        client.multiSearch(mrequest, ActionListener.wrap(mresponse -> {
            MultiSearchResponse.Item item1 = mresponse.getResponses()[0];
            if (item1.isFailure()) {
                Exception e = item1.getFailure();
                if (e instanceof IndexNotFoundException) {
                    errorHandler.accept(ExceptionsHelper.missingJobException(jobId));
                } else {
                    errorHandler.accept(e);
                }
                return;
            }

            SearchResponse searchResponse = item1.getResponse();
            SearchHits hits = searchResponse.getHits();
            if (query.getTimestamp() != null) {
                if (hits.getTotalHits() == 0) {
                    throw QueryPage.emptyQueryPage(Bucket.RESULTS_FIELD);
                } else if (hits.getTotalHits() > 1) {
                    LOGGER.error("Found more than one bucket with timestamp [{}]" + " from index {}", query.getTimestamp(), indexName);
                }
            }

            List<Bucket> results = new ArrayList<>();
            for (SearchHit hit : hits.getHits()) {
                BytesReference source = hit.getSourceRef();
                try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                    Bucket bucket = Bucket.PARSER.apply(parser, () -> parseFieldMatcher);
                    if (query.isIncludeInterim() || bucket.isInterim() == false) {
                        results.add(bucket);
                    }
                } catch (IOException e) {
                    throw new ElasticsearchParseException("failed to parse bucket", e);
                }
            }

            if (query.getTimestamp() != null && results.isEmpty()) {
                throw QueryPage.emptyQueryPage(Bucket.RESULTS_FIELD);
            }

            QueryPage<Bucket> buckets = new QueryPage<>(results, searchResponse.getHits().getTotalHits(), Bucket.RESULTS_FIELD);
            if (Strings.hasLength(query.getPartitionValue())) {
                MultiSearchResponse.Item item2 = mresponse.getResponses()[1];
                if (item2.isFailure()) {
                    Exception e = item2.getFailure();
                    if (e instanceof IndexNotFoundException) {
                        errorHandler.accept(ExceptionsHelper.missingJobException(jobId));
                    } else {
                        errorHandler.accept(e);
                    }
                    return;
                }
                List<PerPartitionMaxProbabilities> partitionProbs =
                        handlePartitionMaxNormailizedProbabilitiesResponse(item2.getResponse());
                mergePartitionScoresIntoBucket(partitionProbs, buckets.results(), query.getPartitionValue());
                for (Bucket b : buckets.results()) {
                    if (query.isExpand() && b.getRecordCount() > 0) {
                        this.expandBucketForPartitionValue(jobId, query.isIncludeInterim(), b, query.getPartitionValue());
                    }
                    b.setAnomalyScore(b.partitionAnomalyScore(query.getPartitionValue()));
                }
            } else {
                for (Bucket b : buckets.results()) {
                    if (query.isExpand() && b.getRecordCount() > 0) {
                        expandBucket(jobId, query.isIncludeInterim(), b);
                    }
                }
            }
            handler.accept(buckets);
        }, errorHandler));
    }

    void mergePartitionScoresIntoBucket(List<PerPartitionMaxProbabilities> partitionProbs, List<Bucket> buckets, String partitionValue) {
        Iterator<PerPartitionMaxProbabilities> itr = partitionProbs.iterator();
        PerPartitionMaxProbabilities partitionProb = itr.hasNext() ? itr.next() : null;
        for (Bucket b : buckets) {
            if (partitionProb == null) {
                b.setMaxNormalizedProbability(0.0);
            } else {
                if (partitionProb.getTimestamp().equals(b.getTimestamp())) {
                    b.setMaxNormalizedProbability(partitionProb.getMaxProbabilityForPartition(partitionValue));
                    partitionProb = itr.hasNext() ? itr.next() : null;
                } else {
                    b.setMaxNormalizedProbability(0.0);
                }
            }
        }
    }

    private SearchRequest createPartitionMaxNormailizedProbabilitiesRequest(String jobId, Object epochStart, Object epochEnd,
                                                                            String partitionFieldValue) {
        QueryBuilder timeRangeQuery = new ResultsFilterBuilder()
                .timeRange(Bucket.TIMESTAMP.getPreferredName(), epochStart, epochEnd)
                .build();

        QueryBuilder boolQuery = new BoolQueryBuilder()
                .filter(timeRangeQuery)
                .filter(new TermsQueryBuilder(Result.RESULT_TYPE.getPreferredName(), PerPartitionMaxProbabilities.RESULT_TYPE_VALUE))
                .filter(new TermsQueryBuilder(AnomalyRecord.PARTITION_FIELD_VALUE.getPreferredName(), partitionFieldValue));

        FieldSortBuilder sb = new FieldSortBuilder(Bucket.TIMESTAMP.getPreferredName()).order(SortOrder.ASC);
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.sort(sb);
        sourceBuilder.query(boolQuery);
        SearchRequest searchRequest = new SearchRequest(indexName);
        searchRequest.source(sourceBuilder);
        return searchRequest;
    }

    private List<PerPartitionMaxProbabilities> handlePartitionMaxNormailizedProbabilitiesResponse(SearchResponse searchResponse) {
        List<PerPartitionMaxProbabilities> results = new ArrayList<>();
        for (SearchHit hit : searchResponse.getHits().getHits()) {
            BytesReference source = hit.getSourceRef();
            try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                results.add(PerPartitionMaxProbabilities.PARSER.apply(parser, () -> parseFieldMatcher));
            } catch (IOException e) {
                throw new ElasticsearchParseException("failed to parse PerPartitionMaxProbabilities", e);
            }
        }
        return results;
    }

    // TODO (norelease): Use scroll search instead of multiple searches with increasing from
    private int expandBucketForPartitionValue(String jobId, boolean includeInterim, Bucket bucket,
                                             String partitionFieldValue) throws ResourceNotFoundException {
        int from = 0;

        QueryPage<AnomalyRecord> page = bucketRecords(
                jobId, bucket, from, RECORDS_SIZE_PARAM, includeInterim,
                AnomalyRecord.PROBABILITY.getPreferredName(), false, partitionFieldValue);
        bucket.setRecords(page.results());

        while (page.count() > from + RECORDS_SIZE_PARAM) {
            from += RECORDS_SIZE_PARAM;
            page = bucketRecords(
                    jobId, bucket, from, RECORDS_SIZE_PARAM, includeInterim,
                    AnomalyRecord.PROBABILITY.getPreferredName(), false, partitionFieldValue);
            bucket.getRecords().addAll(page.results());
        }

        return bucket.getRecords().size();
    }


    /**
     * Returns a {@link BatchedDocumentsIterator} that allows querying
     * and iterating over a large number of buckets of the given job
     *
     * @param jobId the id of the job for which buckets are requested
     * @return a bucket {@link BatchedDocumentsIterator}
     */
    public BatchedDocumentsIterator<Bucket> newBatchedBucketsIterator(String jobId) {
        return new ElasticsearchBatchedBucketsIterator(client, jobId, parseFieldMatcher);
    }

    /**
     * Expand a bucket to include the associated records.
     *
     * @param jobId          the job id
     * @param includeInterim Include interim results
     * @param bucket         The bucket to be expanded
     * @return The number of records added to the bucket
     */
    // TODO (norelease): Use scroll search instead of multiple searches with increasing from
    public int expandBucket(String jobId, boolean includeInterim, Bucket bucket) throws ResourceNotFoundException {
        int from = 0;

        QueryPage<AnomalyRecord> page = bucketRecords(
                jobId, bucket, from, RECORDS_SIZE_PARAM, includeInterim,
                AnomalyRecord.PROBABILITY.getPreferredName(), false, null);
        bucket.setRecords(page.results());

        while (page.count() > from + RECORDS_SIZE_PARAM) {
            from += RECORDS_SIZE_PARAM;
            page = bucketRecords(
                    jobId, bucket, from, RECORDS_SIZE_PARAM, includeInterim,
                    AnomalyRecord.PROBABILITY.getPreferredName(), false, null);
            bucket.getRecords().addAll(page.results());
        }

        return bucket.getRecords().size();
    }

    QueryPage<AnomalyRecord> bucketRecords(String jobId,
                                           Bucket bucket, int from, int size, boolean includeInterim,
                                           String sortField, boolean descending, String partitionFieldValue)
            throws ResourceNotFoundException {
        // Find the records using the time stamp rather than a parent-child
        // relationship.  The parent-child filter involves two queries behind
        // the scenes, and Elasticsearch documentation claims it's significantly
        // slower.  Here we rely on the record timestamps being identical to the
        // bucket timestamp.
        QueryBuilder recordFilter = QueryBuilders.termQuery(Bucket.TIMESTAMP.getPreferredName(), bucket.getTimestamp().getTime());

        recordFilter = new ResultsFilterBuilder(recordFilter)
                .interim(AnomalyRecord.IS_INTERIM.getPreferredName(), includeInterim)
                .term(AnomalyRecord.PARTITION_FIELD_VALUE.getPreferredName(), partitionFieldValue)
                .build();

        FieldSortBuilder sb = null;
        if (sortField != null) {
            sb = new FieldSortBuilder(sortField)
                    .missing("_last")
                    .order(descending ? SortOrder.DESC : SortOrder.ASC);
        }

        return records(jobId, from, size, recordFilter, sb, SECONDARY_SORT,
                descending);
    }

    /**
     * Get a page of {@linkplain CategoryDefinition}s for the given <code>jobId</code>.
     *
     * @param jobId the job id
     * @param from  Skip the first N categories. This parameter is for paging
     * @param size  Take only this number of categories
     */
    public void categoryDefinitions(String jobId, String categoryId, Integer from, Integer size,
                                    Consumer<QueryPage<CategoryDefinition>> handler,
                                    Consumer<Exception> errorHandler) {
        if (categoryId != null && (from != null || size != null)) {
            throw new IllegalStateException("Both categoryId and pageParams are specified");
        }

        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        LOGGER.trace("ES API CALL: search all of type {} from index {} sort ascending {} from {} size {}",
                CategoryDefinition.TYPE.getPreferredName(), indexName, CategoryDefinition.CATEGORY_ID.getPreferredName(), from, size);

        SearchRequest searchRequest = new SearchRequest(indexName);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        if (categoryId != null) {
            String uid = Uid.createUid(CategoryDefinition.TYPE.getPreferredName(), categoryId);
            sourceBuilder.query(QueryBuilders.termQuery(UidFieldMapper.NAME, uid));
            searchRequest.routing(categoryId);
        } else if (from != null && size != null) {
            searchRequest.types(CategoryDefinition.TYPE.getPreferredName());
            sourceBuilder.from(from).size(size)
                    .sort(new FieldSortBuilder(CategoryDefinition.CATEGORY_ID.getPreferredName()).order(SortOrder.ASC));
        } else {
            throw new IllegalStateException("Both categoryId and pageParams are not specified");
        }
        searchRequest.source(sourceBuilder);
        client.search(searchRequest, ActionListener.wrap(searchResponse -> {
            SearchHit[] hits = searchResponse.getHits().getHits();
            List<CategoryDefinition> results = new ArrayList<>(hits.length);
            for (SearchHit hit : hits) {
                BytesReference source = hit.getSourceRef();
                try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                    CategoryDefinition categoryDefinition = CategoryDefinition.PARSER.apply(parser, () -> parseFieldMatcher);
                    results.add(categoryDefinition);
                } catch (IOException e) {
                    throw new ElasticsearchParseException("failed to parse category definition", e);
                }
            }
            QueryPage<CategoryDefinition> result =
                    new QueryPage<>(results, searchResponse.getHits().getTotalHits(), CategoryDefinition.RESULTS_FIELD);
            handler.accept(result);
        }, e -> {
            if (e instanceof IndexNotFoundException) {
                errorHandler.accept(ExceptionsHelper.missingJobException(jobId));
            } else {
                errorHandler.accept(e);
            }
        }));
    }

    /**
     * Search for anomaly records with the parameters in the
     * {@link org.elasticsearch.xpack.prelert.job.persistence.RecordsQueryBuilder.RecordsQuery}
     *
     * @return QueryPage of AnomalyRecords
     */
    public QueryPage<AnomalyRecord> records(String jobId, RecordsQueryBuilder.RecordsQuery query)
            throws ResourceNotFoundException {
        QueryBuilder fb = new ResultsFilterBuilder()
                .timeRange(Bucket.TIMESTAMP.getPreferredName(), query.getStart(), query.getEnd())
                .score(AnomalyRecord.ANOMALY_SCORE.getPreferredName(), query.getAnomalyScoreThreshold())
                .score(AnomalyRecord.NORMALIZED_PROBABILITY.getPreferredName(), query.getNormalizedProbabilityThreshold())
                .interim(AnomalyRecord.IS_INTERIM.getPreferredName(), query.isIncludeInterim())
                .term(AnomalyRecord.PARTITION_FIELD_VALUE.getPreferredName(), query.getPartitionFieldValue()).build();

        return records(jobId, query.getFrom(), query.getSize(), fb, query.getSortField(), query.isSortDescending());
    }


    private QueryPage<AnomalyRecord> records(String jobId,
                                             int from, int size, QueryBuilder recordFilter,
                                             String sortField, boolean descending)
            throws ResourceNotFoundException {
        FieldSortBuilder sb = null;
        if (sortField != null) {
            sb = new FieldSortBuilder(sortField)
                    .missing("_last")
                    .order(descending ? SortOrder.DESC : SortOrder.ASC);
        }

        return records(jobId, from, size, recordFilter, sb, SECONDARY_SORT, descending);
    }


    /**
     * The returned records have their id set.
     */
    private QueryPage<AnomalyRecord> records(String jobId, int from, int size,
                                             QueryBuilder recordFilter, FieldSortBuilder sb, List<String> secondarySort,
                                             boolean descending) throws ResourceNotFoundException {
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);

        recordFilter = new BoolQueryBuilder()
                .filter(recordFilter)
                .filter(new TermsQueryBuilder(Result.RESULT_TYPE.getPreferredName(), AnomalyRecord.RESULT_TYPE_VALUE));

        SearchRequest searchRequest = new SearchRequest(indexName);
        searchRequest.types(Result.TYPE.getPreferredName());
        searchRequest.source(new SearchSourceBuilder()
                .from(from)
                .size(size)
                .query(recordFilter)
                .sort(sb == null ? SortBuilders.fieldSort(ElasticsearchMappings.ES_DOC) : sb)
                .fetchSource(true)
        );

        for (String sortField : secondarySort) {
            searchRequest.source().sort(sortField, descending ? SortOrder.DESC : SortOrder.ASC);
        }

        SearchResponse searchResponse;
        try {
            LOGGER.trace("ES API CALL: search all of result type {} from index {}{}{}  with filter after sort from {} size {}",
                    AnomalyRecord.RESULT_TYPE_VALUE, indexName, (sb != null) ? " with sort" : "",
                    secondarySort.isEmpty() ? "" : " with secondary sort", from, size);

            searchResponse = FixBlockingClientOperations.executeBlocking(client, SearchAction.INSTANCE, searchRequest);
        } catch (IndexNotFoundException e) {
            throw ExceptionsHelper.missingJobException(jobId);
        }

        List<AnomalyRecord> results = new ArrayList<>();
        for (SearchHit hit : searchResponse.getHits().getHits()) {
            BytesReference source = hit.getSourceRef();
            try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                results.add(AnomalyRecord.PARSER.apply(parser, () -> parseFieldMatcher));
            } catch (IOException e) {
                throw new ElasticsearchParseException("failed to parse records", e);
            }
        }

        return new QueryPage<>(results, searchResponse.getHits().getTotalHits(), AnomalyRecord.RESULTS_FIELD);
    }

    /**
     * Return a page of influencers for the given job and within the given date
     * range
     *
     * @param jobId The job ID for which influencers are requested
     * @param query the query
     * @return QueryPage of Influencer
     */
    public QueryPage<Influencer> influencers(String jobId, InfluencersQuery query) throws ResourceNotFoundException {
        QueryBuilder fb = new ResultsFilterBuilder()
                .timeRange(Bucket.TIMESTAMP.getPreferredName(), query.getStart(), query.getEnd())
                .score(Bucket.ANOMALY_SCORE.getPreferredName(), query.getAnomalyScoreFilter())
                .interim(Bucket.IS_INTERIM.getPreferredName(), query.isIncludeInterim())
                .build();

        return influencers(jobId, query.getFrom(), query.getSize(), fb, query.getSortField(),
                query.isSortDescending());
    }

    private QueryPage<Influencer> influencers(String jobId, int from, int size, QueryBuilder queryBuilder, String sortField,
                                              boolean sortDescending) throws ResourceNotFoundException {
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        LOGGER.trace("ES API CALL: search all of result type {} from index {}{}  with filter from {} size {}",
                () -> Influencer.RESULT_TYPE_VALUE, () -> indexName,
                () -> (sortField != null) ?
                        " with sort " + (sortDescending ? "descending" : "ascending") + " on field " + sortField : "",
                () -> from, () -> size);

        queryBuilder = new BoolQueryBuilder()
                .filter(queryBuilder)
                .filter(new TermsQueryBuilder(Result.RESULT_TYPE.getPreferredName(), Influencer.RESULT_TYPE_VALUE));

        SearchRequest searchRequest = new SearchRequest(indexName);
        searchRequest.types(Result.TYPE.getPreferredName());
        FieldSortBuilder sb = sortField == null ? SortBuilders.fieldSort(ElasticsearchMappings.ES_DOC)
                : new FieldSortBuilder(sortField).order(sortDescending ? SortOrder.DESC : SortOrder.ASC);
        searchRequest.source(new SearchSourceBuilder().query(queryBuilder).from(from).size(size).sort(sb));

        SearchResponse response;
        try {
            response = FixBlockingClientOperations.executeBlocking(client, SearchAction.INSTANCE, searchRequest);
        } catch (IndexNotFoundException e) {
            throw ExceptionsHelper.missingJobException(jobId);
        }

        List<Influencer> influencers = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            BytesReference source = hit.getSourceRef();
            try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                influencers.add(Influencer.PARSER.apply(parser, () -> parseFieldMatcher));
            } catch (IOException e) {
                throw new ElasticsearchParseException("failed to parse influencer", e);
            }
        }

        return new QueryPage<>(influencers, response.getHits().getTotalHits(), Influencer.RESULTS_FIELD);
    }

    /**
     * Get the influencer for the given job for id
     *
     * @param jobId        the job id
     * @param influencerId The unique influencer Id
     * @return Optional Influencer
     */
    public Optional<Influencer> influencer(String jobId, String influencerId) {
        throw new IllegalStateException();
    }

    /**
     * Returns a {@link BatchedDocumentsIterator} that allows querying
     * and iterating over a large number of influencers of the given job
     *
     * @param jobId the id of the job for which influencers are requested
     * @return an influencer {@link BatchedDocumentsIterator}
     */
    public BatchedDocumentsIterator<Influencer> newBatchedInfluencersIterator(String jobId) {
        return new ElasticsearchBatchedInfluencersIterator(client, jobId, parseFieldMatcher);
    }

    /**
     * Returns a {@link BatchedDocumentsIterator} that allows querying
     * and iterating over a number of model snapshots of the given job
     *
     * @param jobId the id of the job for which model snapshots are requested
     * @return a model snapshot {@link BatchedDocumentsIterator}
     */
    public BatchedDocumentsIterator<ModelSnapshot> newBatchedModelSnapshotIterator(String jobId) {
        return new ElasticsearchBatchedModelSnapshotIterator(client, jobId, parseFieldMatcher);
    }

    /**
     * Get the persisted quantiles state for the job
     */
    public Optional<Quantiles> getQuantiles(String jobId) {
        String indexName = AnomalyDetectorsIndex.jobStateIndexName();
        try {
            String quantilesId = Quantiles.quantilesId(jobId);

            LOGGER.trace("ES API CALL: get ID {} type {} from index {}", quantilesId, Quantiles.TYPE.getPreferredName(), indexName);
            GetRequest getRequest = new GetRequest(indexName, Quantiles.TYPE.getPreferredName(), quantilesId);
            // can be blocking as it is called from a thread from generic pool:
            GetResponse response = client.get(getRequest).actionGet();
            if (!response.isExists()) {
                LOGGER.info("There are currently no quantiles for job " + jobId);
                return Optional.empty();
            }
            BytesReference source = response.getSourceAsBytesRef();
            try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                Quantiles quantiles = Quantiles.PARSER.apply(parser, () -> parseFieldMatcher);
                if (quantiles.getQuantileState() == null) {
                    LOGGER.error("Inconsistency - no " + Quantiles.QUANTILE_STATE
                            + " field in quantiles for job " + jobId);
                }
                return Optional.of(quantiles);
            } catch (IOException e) {
                throw new ElasticsearchParseException("failed to parse quantiles", e);
            }
        } catch (IndexNotFoundException e) {
            LOGGER.error("Missing index when getting quantiles", e);
            throw e;
        }
    }

    /**
     * Get model snapshots for the job ordered by descending restore priority.
     *
     * @param jobId the job id
     * @param from  number of snapshots to from
     * @param size  number of snapshots to retrieve
     * @return page of model snapshots
     */
    public QueryPage<ModelSnapshot> modelSnapshots(String jobId, int from, int size) {
        return modelSnapshots(jobId, from, size, null, null, null, true, null, null);
    }

    /**
     * Get model snapshots for the job ordered by descending restore priority.
     *
     * @param jobId          the job id
     * @param from           number of snapshots to from
     * @param size           number of snapshots to retrieve
     * @param startEpochMs   earliest time to include (inclusive)
     * @param endEpochMs     latest time to include (exclusive)
     * @param sortField      optional sort field name (may be null)
     * @param sortDescending Sort in descending order
     * @param snapshotId     optional snapshot ID to match (null for all)
     * @param description    optional description to match (null for all)
     * @return page of model snapshots
     */
    public QueryPage<ModelSnapshot> modelSnapshots(String jobId, int from, int size,
                                                   String startEpochMs, String endEpochMs, String sortField, boolean sortDescending,
                                                   String snapshotId, String description) {
        boolean haveId = snapshotId != null && !snapshotId.isEmpty();
        boolean haveDescription = description != null && !description.isEmpty();
        ResultsFilterBuilder fb;
        if (haveId || haveDescription) {
            BoolQueryBuilder query = QueryBuilders.boolQuery();
            if (haveId) {
                query.must(QueryBuilders.termQuery(ModelSnapshot.SNAPSHOT_ID.getPreferredName(), snapshotId));
            }
            if (haveDescription) {
                query.must(QueryBuilders.termQuery(ModelSnapshot.DESCRIPTION.getPreferredName(), description));
            }

            fb = new ResultsFilterBuilder(query);
        } else {
            fb = new ResultsFilterBuilder();
        }

        return modelSnapshots(jobId, from, size,
                (sortField == null || sortField.isEmpty()) ? ModelSnapshot.RESTORE_PRIORITY.getPreferredName() : sortField,
                sortDescending, fb.timeRange(
                        Bucket.TIMESTAMP.getPreferredName(), startEpochMs, endEpochMs).build());
    }

    private QueryPage<ModelSnapshot> modelSnapshots(String jobId, int from, int size,
                                                    String sortField, boolean sortDescending, QueryBuilder qb) {
        FieldSortBuilder sb = new FieldSortBuilder(sortField)
                .order(sortDescending ? SortOrder.DESC : SortOrder.ASC);

        // Wrap in a constant_score because we always want to
        // run it as a filter
        qb = new ConstantScoreQueryBuilder(qb);

        SearchResponse searchResponse;
        try {
            String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
            LOGGER.trace("ES API CALL: search all of type {} from index {} sort ascending {} with filter after sort from {} size {}",
                    ModelSnapshot.TYPE, indexName, sortField, from, size);

            SearchRequest searchRequest = new SearchRequest(indexName);
            searchRequest.types(ModelSnapshot.TYPE.getPreferredName());
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.sort(sb);
            sourceBuilder.query(qb);
            sourceBuilder.from(from);
            sourceBuilder.size(size);
            searchRequest.source(sourceBuilder);
            searchResponse = FixBlockingClientOperations.executeBlocking(client, SearchAction.INSTANCE, searchRequest);
        } catch (IndexNotFoundException e) {
            LOGGER.error("Failed to read modelSnapshots", e);
            throw e;
        }

        List<ModelSnapshot> results = new ArrayList<>();

        for (SearchHit hit : searchResponse.getHits().getHits()) {
            BytesReference source = hit.getSourceRef();
            try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                ModelSnapshot modelSnapshot = ModelSnapshot.PARSER.apply(parser, () -> parseFieldMatcher);
                results.add(modelSnapshot);
            } catch (IOException e) {
                throw new ElasticsearchParseException("failed to parse modelSnapshot", e);
            }
        }

        return new QueryPage<>(results, searchResponse.getHits().getTotalHits(), ModelSnapshot.RESULTS_FIELD);
    }

    /**
     * Given a model snapshot, get the corresponding state and write it to the supplied
     * stream.  If there are multiple state documents they are separated using <code>'\0'</code>
     * when written to the stream.
     *
     * @param jobId         the job id
     * @param modelSnapshot the model snapshot to be restored
     * @param restoreStream the stream to write the state to
     */
    public void restoreStateToStream(String jobId, ModelSnapshot modelSnapshot, OutputStream restoreStream) throws IOException {
        String indexName = AnomalyDetectorsIndex.jobStateIndexName();

        // First try to restore categorizer state.  There are no snapshots for this, so the IDs simply
        // count up until a document is not found.  It's NOT an error to have no categorizer state.
        int docNum = 0;
        while (true) {
            String docId = CategorizerState.categorizerStateDocId(jobId, ++docNum);

            LOGGER.trace("ES API CALL: get ID {} type {} from index {}", docId, CategorizerState.TYPE, indexName);

            GetResponse stateResponse = client.prepareGet(indexName, CategorizerState.TYPE, docId).get();
            if (!stateResponse.isExists()) {
                break;
            }
            writeStateToStream(stateResponse.getSourceAsBytesRef(), restoreStream);
        }

        // Finally try to restore model state.  This must come after categorizer state because that's
        // the order the C++ process expects.
        int numDocs = modelSnapshot.getSnapshotDocCount();
        for (docNum = 1; docNum <= numDocs; ++docNum) {
            String docId = String.format(Locale.ROOT, "%s_%d", modelSnapshot.getSnapshotId(), docNum);

            LOGGER.trace("ES API CALL: get ID {} type {} from index {}", docId, ModelState.TYPE, indexName);

            GetResponse stateResponse = client.prepareGet(indexName, ModelState.TYPE.getPreferredName(), docId).get();
            if (!stateResponse.isExists()) {
                LOGGER.error("Expected {} documents for model state for {} snapshot {} but failed to find {}",
                        numDocs, jobId, modelSnapshot.getSnapshotId(), docId);
                break;
            }
            writeStateToStream(stateResponse.getSourceAsBytesRef(), restoreStream);
        }
    }

    private void writeStateToStream(BytesReference source, OutputStream stream) throws IOException {
        // The source bytes are already UTF-8.  The C++ process wants UTF-8, so we
        // can avoid converting to a Java String only to convert back again.
        BytesRefIterator iterator = source.iterator();
        for (BytesRef ref = iterator.next(); ref != null; ref = iterator.next()) {
            // There's a complication that the source can already have trailing 0 bytes
            int length = ref.bytes.length;
            while (length > 0 && ref.bytes[length - 1] == 0) {
                --length;
            }
            if (length > 0) {
                stream.write(ref.bytes, 0, length);
            }
        }
        // This is dictated by RapidJSON on the C++ side; it treats a '\0' as end-of-file
        // even when it's not really end-of-file, and this is what we need because we're
        // sending multiple JSON documents via the same named pipe.
        stream.write(0);
    }

    public QueryPage<ModelDebugOutput> modelDebugOutput(String jobId, int from, int size) {
        SearchResponse searchResponse;
        try {
            String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
            LOGGER.trace("ES API CALL: search result type {} from index {} from {}, size {}",
                    ModelDebugOutput.RESULT_TYPE_VALUE, indexName, from, size);

            searchResponse = client.prepareSearch(indexName)
                    .setTypes(Result.TYPE.getPreferredName())
                    .setQuery(new TermsQueryBuilder(Result.RESULT_TYPE.getPreferredName(), ModelDebugOutput.RESULT_TYPE_VALUE))
                    .setFrom(from).setSize(size)
                    .get();
        } catch (IndexNotFoundException e) {
            throw ExceptionsHelper.missingJobException(jobId);
        }

        List<ModelDebugOutput> results = new ArrayList<>();

        for (SearchHit hit : searchResponse.getHits().getHits()) {
            BytesReference source = hit.getSourceRef();
            try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                ModelDebugOutput modelDebugOutput = ModelDebugOutput.PARSER.apply(parser, () -> parseFieldMatcher);
                results.add(modelDebugOutput);
            } catch (IOException e) {
                throw new ElasticsearchParseException("failed to parse modelDebugOutput", e);
            }
        }

        return new QueryPage<>(results, searchResponse.getHits().getTotalHits(), ModelDebugOutput.RESULTS_FIELD);
    }

    /**
     * Get the job's model size stats.
     */
    public void modelSizeStats(String jobId, Consumer<ModelSizeStats> handler, Consumer<Exception> errorHandler) {
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        LOGGER.trace("ES API CALL: get result type {} ID {} from index {}",
                ModelSizeStats.RESULT_TYPE_VALUE, ModelSizeStats.RESULT_TYPE_FIELD, indexName);

        GetRequest getRequest =
                new GetRequest(indexName, Result.TYPE.getPreferredName(), ModelSizeStats.RESULT_TYPE_FIELD.getPreferredName());
        client.get(getRequest, ActionListener.wrap(response -> {
            if (response.isExists()) {
                BytesReference source = response.getSourceAsBytesRef();
                try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
                    ModelSizeStats modelSizeStats = ModelSizeStats.PARSER.apply(parser, () -> parseFieldMatcher).build();
                    handler.accept(modelSizeStats);
                } catch (IOException e) {
                    throw new ElasticsearchParseException("failed to parse model size stats", e);
                }
            } else {
                String msg = "No memory usage details for job with id " + jobId;
                LOGGER.warn(msg);
                handler.accept(null);
            }
        }, e -> {
            if (e instanceof IndexNotFoundException) {
                handler.accept(null);
            } else {
                errorHandler.accept(e);
            }
        }));
    }

    /**
     * Retrieves the list with the given {@code listId} from the datastore.
     *
     * @param listId the id of the requested list
     * @return the matching list if it exists
     */
    public Optional<ListDocument> getList(String listId) {
        GetRequest getRequest = new GetRequest(PRELERT_INFO_INDEX, ListDocument.TYPE.getPreferredName(), listId);
        // can be blocking as it is called from a thread from generic pool:
        GetResponse response = client.get(getRequest).actionGet();
        if (!response.isExists()) {
            return Optional.empty();
        }
        BytesReference source = response.getSourceAsBytesRef();
        try (XContentParser parser = XContentFactory.xContent(source).createParser(NamedXContentRegistry.EMPTY, source)) {
            ListDocument listDocument = ListDocument.PARSER.apply(parser, () -> parseFieldMatcher);
            return Optional.of(listDocument);
        } catch (IOException e) {
            throw new ElasticsearchParseException("failed to parse list", e);
        }
    }

    /**
     * Get an auditor for the given job
     *
     * @param jobId the job id
     * @return the {@code Auditor}
     */
    public Auditor audit(String jobId) {
        return new Auditor(client, AnomalyDetectorsIndex.jobResultsIndexName(jobId), jobId);
    }
}
