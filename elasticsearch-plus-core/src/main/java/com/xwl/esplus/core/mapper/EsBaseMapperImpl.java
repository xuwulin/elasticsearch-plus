package com.xwl.esplus.core.mapper;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializeFilter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.SimplePropertyPreFilter;
import com.xwl.esplus.core.cache.BaseCache;
import com.xwl.esplus.core.cache.GlobalConfigCache;
import com.xwl.esplus.core.config.GlobalConfig;
import com.xwl.esplus.core.constant.EsConstants;
import com.xwl.esplus.core.enums.EsFieldStrategyEnum;
import com.xwl.esplus.core.enums.EsFieldTypeEnum;
import com.xwl.esplus.core.enums.EsIdTypeEnum;
import com.xwl.esplus.core.metadata.DocumentFieldInfo;
import com.xwl.esplus.core.metadata.DocumentInfo;
import com.xwl.esplus.core.param.EsIndexParam;
import com.xwl.esplus.core.param.EsIndexSettingParam;
import com.xwl.esplus.core.param.EsUpdateParam;
import com.xwl.esplus.core.toolkit.*;
import com.xwl.esplus.core.wrapper.index.EsLambdaIndexWrapper;
import com.xwl.esplus.core.wrapper.processor.EsWrapperProcessor;
import com.xwl.esplus.core.wrapper.query.EsLambdaQueryWrapper;
import com.xwl.esplus.core.wrapper.update.EsLambdaUpdateWrapper;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.PutMappingRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static com.xwl.esplus.core.wrapper.processor.EsWrapperProcessor.buildBoolQueryBuilder;
import static com.xwl.esplus.core.wrapper.processor.EsWrapperProcessor.buildSearchSourceBuilder;

/**
 * 核心，EsBaseMapper接口实现，获得常用的CRUD功能，（需要和EsWrapper类在同一包下，因为EsWrapper类中的属性是protected）
 *
 * @author xwl
 * @since 2022/3/11 19:59
 */
public class EsBaseMapperImpl<T> implements EsBaseMapper<T> {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * restHighLevel client
     */
    private RestHighLevelClient restHighLevelClient;

    /**
     * T 对应的类
     */
    private Class<T> entityClass;

    public void setRestHighLevelClient(RestHighLevelClient restHighLevelClient) {
        this.restHighLevelClient = restHighLevelClient;
    }

    public void setEntityClass(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    @Override
    public Boolean existsIndex(String indexName) {
        if (StringUtils.isBlank(indexName)) {
            throw ExceptionUtils.epe("indexName can not be null or empty");
        }
        GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
        try {
            return restHighLevelClient.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
        } catch (Exception e) {
            throw ExceptionUtils.epe("exists index exception, indexName: %s", e, indexName);
        }
    }

    @Override
    public Boolean createIndex(EsLambdaIndexWrapper<T> wrapper) {
        CreateIndexRequest createIndexRequest = new CreateIndexRequest(wrapper.getIndexName());
        // alias
        Optional.ofNullable(wrapper.getAlias())
                .ifPresent(aliasName -> {
                    Alias alias = new Alias(aliasName);
                    createIndexRequest.alias(alias);
                });

        // settings
        Settings.Builder settings = Settings.builder();
        EsIndexSettingParam settingParam = wrapper.getSetting();
        if (Objects.nonNull(settingParam)) {
            // 分片数
            Optional.ofNullable(settingParam.getNumberOfShards())
                    .ifPresent(shards -> settings.put(EsConstants.NUMBER_OF_SHARDS, shards));
            // 副本数
            Optional.ofNullable(settingParam.getNumberOfReplicas())
                    .ifPresent(replicas -> settings.put(EsConstants.NUMBER_OF_REPLICAS, replicas));
            // 自定义分词器（包含分词器、分词过滤器等）
            Optional.ofNullable(settingParam.getAnalysis())
                    .ifPresent(analysis -> settings.loadFromSource(JSONObject.toJSONString(analysis), XContentType.JSON));
            createIndexRequest.settings(settings);
        }

        // mappings
        Map<String, Object> mapping;
        if (Objects.isNull(wrapper.getMapping())) {
            List<EsIndexParam> indexParamList = wrapper.getEsIndexParamList();
            if (!CollectionUtils.isEmpty(indexParamList)) {
                // 根据参数构建索引mapping
                mapping = buildMapping(indexParamList);
                createIndexRequest.mapping(mapping);
            }
        } else {
            // 手动指定mapping，优先级高
            mapping = wrapper.getMapping();
            createIndexRequest.mapping(mapping);
        }

        try {
            CreateIndexResponse response = restHighLevelClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
            boolean acknowledged = response.isAcknowledged();
            log.info("create index [{}] result: {}", wrapper.getIndexName(), acknowledged);
            return acknowledged;
        } catch (IOException e) {
            throw ExceptionUtils.epe("create index exception, indexName: %s", e, wrapper.getIndexName());
        }
    }

    @Override
    public Boolean updateIndex(EsLambdaIndexWrapper<T> wrapper) {
        boolean existsIndex = this.existsIndex(wrapper.getIndexName());
        if (!existsIndex) {
            throw ExceptionUtils.epe("index: %s not exists", wrapper.getIndexName());
        }

        PutMappingRequest putMappingRequest = new PutMappingRequest(wrapper.getIndexName());
        if (Objects.isNull(wrapper.getMapping())) {
            if (CollectionUtils.isEmpty(wrapper.getEsIndexParamList())) {
                // 空参数列表，不更新
                return false;
            }
            // 根据参数构建索引mapping
            Map<String, Object> mapping = buildMapping(wrapper.getEsIndexParamList());
            putMappingRequest.source(mapping);
        } else {
            // 手动指定mapping，优先级高
            putMappingRequest.source(wrapper.getMapping());
        }

        try {
            AcknowledgedResponse response = restHighLevelClient.indices().putMapping(putMappingRequest, RequestOptions.DEFAULT);
            boolean acknowledged = response.isAcknowledged();
            log.info("update index [{}] result: {}", wrapper.getIndexName(), acknowledged);
            return acknowledged;
        } catch (IOException e) {
            throw ExceptionUtils.epe("update index exception, indexName: %s", e, wrapper.getIndexName());
        }
    }

    @Override
    public Boolean deleteIndex(String indexName) {
        if (StringUtils.isBlank(indexName)) {
            throw ExceptionUtils.epe("indexName can not be empty");
        }
        DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(indexName);
        try {
            AcknowledgedResponse response = restHighLevelClient.indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
            boolean acknowledged = response.isAcknowledged();
            log.info("delete index [{}] result: {}", indexName, acknowledged);
            return response.isAcknowledged();
        } catch (IOException e) {
            throw ExceptionUtils.epe("delete index exception, indexName: %s", e, indexName);
        }
    }

    @Override
    public Integer insert(T entity) {
        IndexRequest indexRequest = buildIndexRequest(entity);
        try {
            IndexResponse indexResponse = restHighLevelClient.index(indexRequest, RequestOptions.DEFAULT);
            if (Objects.equals(indexResponse.status(), RestStatus.CREATED)) {
                // 插入成功，设置文档实体的id
                setId(entity, indexResponse.getId());
                return EsConstants.ONE;
            } else if (Objects.equals(indexResponse.status(), RestStatus.OK)) {
                // id已存在，相当于更新
                return EsConstants.ZERO;
            } else {
                throw ExceptionUtils.epe("insert failed, result: %s, entity: %s", indexResponse.getResult(), entity);
            }
        } catch (IOException e) {
            throw ExceptionUtils.epe("insert exception: %s, entity: %s", e, entity);
        }
    }

    @Override
    public Integer insertBatch(Collection<T> entityList) {
        if (CollectionUtils.isEmpty(entityList)) {
            return EsConstants.ZERO;
        }
        BulkRequest bulkRequest = new BulkRequest();
        entityList.forEach(entity -> {
            IndexRequest indexRequest = buildIndexRequest(entity);
            bulkRequest.add(indexRequest);
        });
        return doBulkRequest(bulkRequest, RequestOptions.DEFAULT, entityList);
    }

    @Override
    public Integer update(T entity, EsLambdaUpdateWrapper<T> updateWrapper) {
        if (Objects.isNull(entity) && CollectionUtils.isEmpty(updateWrapper.getUpdateParamList())) {
            return EsConstants.ZERO;
        }

        SearchRequest searchRequest = new SearchRequest(getIndexName());
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        BoolQueryBuilder boolQueryBuilder = buildBoolQueryBuilder(updateWrapper.getBaseParamList());
        searchSourceBuilder.query(boolQueryBuilder);
        searchRequest.source(searchSourceBuilder);
        // 根据条件查询要更新的id集合
        List<String> ids = this.selectIds(searchRequest);
        if (CollectionUtils.isEmpty(ids)) {
            return EsConstants.ZERO;
        }

        // 更新文档内容
        String jsonData = Optional.ofNullable(entity)
                .map(this::buildJsonSource)
                .orElseGet(() -> buildJsonDoc(updateWrapper));
        BulkRequest bulkRequest = new BulkRequest();
        String indexName = getIndexName();
        ids.forEach(id -> {
            UpdateRequest updateRequest = new UpdateRequest();
            updateRequest.id(id).index(indexName);
            updateRequest.doc(jsonData, XContentType.JSON);
            bulkRequest.add(updateRequest);
        });
        return doBulkRequest(bulkRequest, RequestOptions.DEFAULT);
    }

    @Override
    public Integer updateById(T entity) {
        String idValue = getIdValue(entityClass, entity);
        UpdateRequest updateRequest = buildUpdateRequest(entity, idValue);
        try {
            UpdateResponse updateResponse = restHighLevelClient.update(updateRequest, RequestOptions.DEFAULT);
            if (Objects.equals(updateResponse.status(), RestStatus.OK)) {
                return EsConstants.ONE;
            }
        } catch (IOException e) {
            throw ExceptionUtils.epe("updateById exception, entity: %s", e, entity);
        }
        return EsConstants.ZERO;
    }

    @Override
    public Integer updateBatchById(Collection<T> entityList) {
        if (CollectionUtils.isEmpty(entityList)) {
            return EsConstants.ZERO;
        }
        BulkRequest bulkRequest = new BulkRequest();
        entityList.forEach(entity -> {
            String idValue = getIdValue(entityClass, entity);
            UpdateRequest updateRequest = buildUpdateRequest(entity, idValue);
            bulkRequest.add(updateRequest);
        });
        return doBulkRequest(bulkRequest, RequestOptions.DEFAULT);
    }

    @Override
    public Integer delete(EsLambdaQueryWrapper<T> wrapper) {
        List<T> list = this.selectList(wrapper);
        if (CollectionUtils.isEmpty(list)) {
            return EsConstants.ZERO;
        }
        BulkRequest bulkRequest = new BulkRequest();
        list.forEach(t -> {
            try {
                Method getId = t.getClass().getMethod(EsConstants.GET_ID_METHOD);
                Object invoke = getId.invoke(t);
                if (Objects.nonNull(invoke) && invoke instanceof String) {
                    String id = String.valueOf(invoke);
                    if (StringUtils.isNotBlank(id)) {
                        DeleteRequest deleteRequest = new DeleteRequest();
                        deleteRequest.id(id);
                        deleteRequest.index(getIndexName());
                        bulkRequest.add(deleteRequest);
                    }
                }
            } catch (Exception e) {
                throw ExceptionUtils.epe("delete exception", e);
            }
        });
        return doBulkRequest(bulkRequest, RequestOptions.DEFAULT);
    }

    @Override
    public Integer deleteById(Serializable id) {
        if (Objects.isNull(id) || StringUtils.isBlank(id.toString())) {
            throw ExceptionUtils.epe("id can not be null or empty");
        }
        DeleteRequest deleteRequest = new DeleteRequest();
        deleteRequest.id(id.toString());
        deleteRequest.index(getIndexName());
        try {
            DeleteResponse deleteResponse = restHighLevelClient.delete(deleteRequest, RequestOptions.DEFAULT);
            if (Objects.equals(deleteResponse.status(), RestStatus.OK)) {
                return EsConstants.ONE;
            }
        } catch (IOException e) {
            throw ExceptionUtils.epe("deleteById exception, id: %s", e, id);
        }
        return EsConstants.ZERO;
    }

    @Override
    public Integer deleteBatchByIds(Collection<? extends Serializable> idList) {
        Assert.notEmpty(idList, "the collection of id can not empty");
        BulkRequest bulkRequest = new BulkRequest();
        idList.forEach(id -> {
            if (Objects.isNull(id) || StringUtils.isEmpty(id.toString())) {
                throw ExceptionUtils.epe("id can not be null or empty");
            }
            DeleteRequest deleteRequest = new DeleteRequest();
            deleteRequest.id(id.toString());
            deleteRequest.index(getIndexName());
            bulkRequest.add(deleteRequest);
        });
        return doBulkRequest(bulkRequest, RequestOptions.DEFAULT);
    }

    @Override
    public SearchResponse search(EsLambdaQueryWrapper<T> wrapper) {
        // 构建es restHighLevel 查询参数
        SearchRequest searchRequest = new SearchRequest(getIndexName());
        SearchSourceBuilder searchSourceBuilder = buildSearchSourceBuilder(wrapper);
        searchRequest.source(searchSourceBuilder);
        if (GlobalConfigCache.getGlobalConfig().isLogEnable()) {
            log.info("DSL:\n==> {}", searchRequest.source().toString());
        }
        // 执行查询
        try {
            return restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw ExceptionUtils.epe("Elasticsearch original search （restHighLevelClient.search()）exception: {}", e.getMessage(), e);
        }
    }

    @Override
    public SearchSourceBuilder getSearchSourceBuilder(EsLambdaQueryWrapper<T> wrapper) {
        return buildSearchSourceBuilder(wrapper);
    }

    @Override
    public SearchResponse search(SearchRequest searchRequest, RequestOptions requestOptions) throws IOException {
        return restHighLevelClient.search(searchRequest, requestOptions);
    }

    @Override
    public String getSource(EsLambdaQueryWrapper<T> wrapper) {
        // 获取由本框架生成的es查询参数 用于验证生成语法的正确性
        SearchRequest searchRequest = new SearchRequest(getIndexName());
        SearchSourceBuilder searchSourceBuilder = buildSearchSourceBuilder(wrapper);
        searchRequest.source(searchSourceBuilder);
        return Optional.ofNullable(searchRequest.source())
                .map(SearchSourceBuilder::toString)
                .orElseThrow(() -> ExceptionUtils.epe("get search source exception"));
    }

    @Override
    public List<T> selectList(EsLambdaQueryWrapper<T> wrapper) {
        SearchRequest searchRequest = new SearchRequest(getIndexName());
        SearchSourceBuilder searchSourceBuilder = buildSearchSourceBuilder(wrapper);
        searchRequest.source(searchSourceBuilder);
        try {
            SearchResponse response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            return parseResultList(response, wrapper);
        } catch (Exception e) {
            throw ExceptionUtils.epe("selectList exception", e);
        }
    }

    public Settings.Builder buildSettings() {
        Settings.Builder settings = Settings.builder();
        return settings;
    }

    /**
     * 根据索引参数构建索引mapping
     *
     * @param indexParamList 索引参数列表
     * @return 索引mapping
     */
    private Map<String, Object> buildMapping(List<EsIndexParam> indexParamList) {
        Map<String, Object> mapping = new HashMap<>(1);
        Map<String, Object> properties = new HashMap<>(indexParamList.size());
        indexParamList.forEach(indexParam -> {
            Map<String, Object> fieldInfo = new HashMap<>();
            // 设置字段类型
            Optional.ofNullable(indexParam.getFieldType())
                    .ifPresent(fieldType -> fieldInfo.put(EsConstants.TYPE, fieldType));
            // 设置是否索引该字段，默认true
            Optional.ofNullable(indexParam.getIndex())
                    .ifPresent(index -> fieldInfo.put(EsConstants.INDEX, index));
            // 设置ignoreAbove，只有keyword类型才有此属性
            if (StringUtils.equals(indexParam.getFieldType(), EsFieldTypeEnum.KEYWORD.getType())) {
                Optional.ofNullable(indexParam.getIgnoreAbove())
                        .ifPresent(ignoreAbove -> fieldInfo.put(EsConstants.IGNORE_ABOVE, ignoreAbove));
            }
            // 设置format
            if (StringUtils.equals(indexParam.getFieldType(), EsFieldTypeEnum.DATE.getType())) {
                Optional.ofNullable(indexParam.getFormat())
                        .ifPresent(format -> fieldInfo.put(EsConstants.FORMAT, format));
            }
            // 设置copy_to
            Optional.ofNullable(indexParam.getCopyTo())
                    .ifPresent(copyTo -> fieldInfo.put(EsConstants.COPY_TO, copyTo));
            // 设置分词器，只有text类型才有此属性
            if (StringUtils.equals(indexParam.getFieldType(), EsFieldTypeEnum.TEXT.getType())) {
                // 创建索引时的分词器
                Optional.ofNullable(indexParam.getAnalyzer())
                        .ifPresent(analyzer -> fieldInfo.put(EsConstants.ANALYZER, analyzer));
                // 搜索时的分词器
                Optional.ofNullable(indexParam.getSearchAnalyzer())
                        .ifPresent(searchAnalyzer -> fieldInfo.put(EsConstants.SEARCH_ANALYZER, searchAnalyzer));
            }
            // 子字段properties
            Optional.ofNullable(indexParam.getProperties())
                    .ifPresent(property -> fieldInfo.put(EsConstants.PROPERTIES, buildMapping(property).get(EsConstants.PROPERTIES)));
            // 子属性fields
            Optional.ofNullable(indexParam.getFields())
                    .ifPresent(field -> fieldInfo.put(EsConstants.FIELDS, buildMapping(field).get(EsConstants.PROPERTIES)));

            properties.put(indexParam.getFieldName(), fieldInfo);
        });
        mapping.put(EsConstants.PROPERTIES, properties);
        return mapping;
    }

    /**
     * 获取索引名称
     *
     * @return 索引名称
     */
    private String getIndexName() {
        return DocumentInfoUtils.getDocumentInfo(entityClass).getIndexName();
    }

    /**
     * 构建IndexRequest
     *
     * @param entity 实体
     * @return IndexRequest
     */
    private IndexRequest buildIndexRequest(T entity) {
        IndexRequest indexRequest = new IndexRequest();
        // id处理，除下述情况，其它情况使用es默认的id，注解 > 全局配置
        DocumentInfo documentInfo = DocumentInfoUtils.getDocumentInfo(entity.getClass());
        if (StringUtils.isNotBlank(documentInfo.getId())) {
            if (EsIdTypeEnum.UUID.equals(documentInfo.getIdType())) {
                indexRequest.id(UUID.randomUUID().toString());
            } else if (EsIdTypeEnum.CUSTOMIZE.equals(documentInfo.getIdType())) {
                indexRequest.id(getIdValue(entityClass, entity));
            }
        }

        // 构建插入的json格式数据
        String jsonData = buildJsonSource(entity);
        indexRequest.index(documentInfo.getIndexName()).source(jsonData, XContentType.JSON);
        return indexRequest;
    }

    /**
     * 获取文档实体对象的id值
     *
     * @param entityClass 实体类
     * @param entity      实体对象
     * @return id值
     */
    private String getIdValue(Class<T> entityClass, T entity) {
        try {
            DocumentInfo documentInfo = DocumentInfoUtils.getDocumentInfo(entityClass);
            Field keyField = Optional.ofNullable(documentInfo.getKeyField())
                    .orElseThrow(() -> ExceptionUtils.epe("the entity id field not found"));
            // 获取id值
            Object value = keyField.get(entity);
            return Optional.ofNullable(value)
                    .map(Object::toString)
                    .orElseThrow(() -> ExceptionUtils.epe("the entity id must not be null"));
        } catch (IllegalAccessException e) {
            throw ExceptionUtils.epe("get id value exception", e);
        }
    }

    /**
     * 构建,插入/更新 的JSON对象
     *
     * @param entity 实体
     * @return json
     */
    private String buildJsonSource(T entity) {
        // 获取所有字段列表
        Class<?> entityClass = entity.getClass();
        Map<String, EsFieldStrategyEnum> columnMap = DocumentInfoUtils
                .getDocumentInfo(entityClass)
                .getFieldList()
                .stream()
                .collect(Collectors.toMap(DocumentFieldInfo::getColumn, DocumentFieldInfo::getFieldStrategy));

        // 根据字段配置的策略 决定是否加入到实际es处理字段中
        Set<String> goodColumn = new HashSet<>(columnMap.size());
        columnMap.forEach((fieldName, fieldStrategy) -> {
            Method invokeMethod = BaseCache.getEsEntityInvokeMethod(entityClass, fieldName);
            Object invoke;
            try {
                if (EsFieldStrategyEnum.IGNORED.equals(fieldStrategy) || EsFieldStrategyEnum.DEFAULT.equals(fieldStrategy)) {
                    // 忽略及无字段配置, 无全局配置 默认加入Json
                    goodColumn.add(fieldName);
                } else if (EsFieldStrategyEnum.NOT_NULL.equals(fieldStrategy)) {
                    invoke = invokeMethod.invoke(entity);
                    if (Objects.nonNull(invoke)) {
                        goodColumn.add(fieldName);
                    }
                } else if (EsFieldStrategyEnum.NOT_EMPTY.equals(fieldStrategy)) {
                    invoke = invokeMethod.invoke(entity);
                    if (Objects.nonNull(invoke) && invoke instanceof String) {
                        String value = (String) invoke;
                        if (!StringUtils.isEmpty(value)) {
                            goodColumn.add(fieldName);
                        }
                    }
                }
            } catch (Exception e) {
                throw ExceptionUtils.epe("buildJsonSource exception, entity:%s", e, entity);
            }
        });

        String jsonString;
        SimplePropertyPreFilter simplePropertyPreFilter = getSimplePropertyPreFilter(entity.getClass(), goodColumn);
        GlobalConfig globalConfig = GlobalConfigCache.getGlobalConfig();
        String dateFormat = globalConfig.getDocumentConfig().getDateFormat();
        boolean globalDateFormatEffect = false;
        boolean annotationFormatEffect = false;
        if (StringUtils.isNotBlank(dateFormat)) {
            globalDateFormatEffect = true;
        }
        // 注解 > 配置
        // 判断当前类中是否使用了@JSONField注解
        Field[] fields = entityClass.getDeclaredFields();
        for (Field field : fields) {
            JSONField jsonField = field.getAnnotation(JSONField.class);
            if (jsonField != null) {
                annotationFormatEffect = true;
                break;
            }
        }
        if (annotationFormatEffect) {
            // 使用@JSONField注解配置的日期格式
            jsonString = JSON.toJSONString(entity, simplePropertyPreFilter, SerializerFeature.WriteDateUseDateFormat);
        } else if (globalDateFormatEffect) {
            // 使用全局日期格式
            jsonString = JSON.toJSONString(entity, SerializeConfig.globalInstance, new SerializeFilter[]{simplePropertyPreFilter}, dateFormat, JSON.DEFAULT_GENERATE_FEATURE, SerializerFeature.WriteDateUseDateFormat);
        } else {
            // 不格式化日期
            jsonString = JSON.toJSONString(entity, simplePropertyPreFilter, SerializerFeature.WriteMapNullValue);
        }
        return jsonString;
    }

    /**
     * 设置fastjson toJsonString字段
     *
     * @param clazz  类
     * @param fields 字段列表
     * @return
     */
    private SimplePropertyPreFilter getSimplePropertyPreFilter(Class<?> clazz, Set<String> fields) {
        return new SimplePropertyPreFilter(clazz, fields.toArray(new String[fields.size()]));
    }

    /**
     * 设置id值
     *
     * @param entity 实体
     * @param id     主键
     */
    private void setId(T entity, String id) {
        String setMethodName = FieldUtils.generateSetFunctionName(getRealIdFieldName());
        Method invokeMethod = BaseCache.getEsEntityInvokeMethod(entityClass, setMethodName);
        try {
            invokeMethod.invoke(entity, id);
        } catch (Exception e) {
            throw ExceptionUtils.epe("setId Exception", e);
        }
    }

    /**
     * 获取id实际字段名称
     *
     * @return id实际字段名称
     */
    private String getRealIdFieldName() {
        return DocumentInfoUtils.getDocumentInfo(entityClass).getKeyProperty();
    }

    /**
     * 根据条件查询要更新的id集合
     *
     * @param searchRequest 查询参数
     * @return id集合
     */
    private List<String> selectIds(SearchRequest searchRequest) {
        try {
            SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            SearchHit[] searchHits = parseSearchHit(searchResponse);
            return Arrays.stream(searchHits)
                    .map(SearchHit::getId)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw ExceptionUtils.epe("selectIdList exception: {}", e.getMessage(), e);
        }
    }

    /**
     * 从ES返回结果中解析出SearchHit[]
     *
     * @param searchResponse es返回的响应体
     * @return 响应体中的Hit列表
     */
    private SearchHit[] parseSearchHit(SearchResponse searchResponse) {
        return Optional.ofNullable(searchResponse)
                .map(SearchResponse::getHits)
                .map(SearchHits::getHits)
                .orElse(new SearchHit[0]);
    }

    /**
     * 构建更新文档的json
     *
     * @param updateWrapper 条件
     * @return json
     */
    private String buildJsonDoc(EsLambdaUpdateWrapper<T> updateWrapper) {
        List<EsUpdateParam> updateParamList = updateWrapper.getUpdateParamList();
        JSONObject jsonObject = new JSONObject();
        updateParamList.forEach(esUpdateParam -> jsonObject.put(esUpdateParam.getField(), esUpdateParam.getValue()));
        return JSON.toJSONString(jsonObject, SerializerFeature.WriteMapNullValue);
    }

    /**
     * 执行bulk请求,并返回成功个数
     *
     * @param bulkRequest    批量请求参数
     * @param requestOptions 类型
     * @return 成功个数
     */
    private int doBulkRequest(BulkRequest bulkRequest, RequestOptions requestOptions) {
        int totalSuccess = 0;
        try {
            BulkResponse bulkResponse = restHighLevelClient.bulk(bulkRequest, requestOptions);
            Iterator<BulkItemResponse> iterator = bulkResponse.iterator();
            while (iterator.hasNext()) {
                if (Objects.equals(iterator.next().status(), RestStatus.OK)) {
                    totalSuccess++;
                }
            }
        } catch (IOException e) {
            throw ExceptionUtils.epe("doBulkRequest exception, msg:%s, cause:%s", e.getMessage(), e.getCause());
        }
        return totalSuccess;
    }

    /**
     * 执行批量操作
     *
     * @param bulkRequest    批量请求参数
     * @param requestOptions 类型
     * @param entityList     实体列表
     * @return 操作成功数量
     */
    private int doBulkRequest(BulkRequest bulkRequest, RequestOptions requestOptions, Collection<T> entityList) {
        int totalSuccess = 0;
        try {
            BulkResponse bulkResponse = restHighLevelClient.bulk(bulkRequest, requestOptions);
            if (bulkResponse.hasFailures()) {
                throw ExceptionUtils.epe("bulkRequest has failures");
            }
            Iterator<BulkItemResponse> iterator = bulkResponse.iterator();
            while (iterator.hasNext()) {
                BulkItemResponse next = iterator.next();
                if (Objects.equals(next.status(), RestStatus.CREATED)) {
                    setId((T) entityList.toArray()[totalSuccess], next.getId());
                    totalSuccess++;
                }
            }
        } catch (IOException e) {
            throw ExceptionUtils.epe("bulkRequest exception, msg:%s, cause:%s", e.getMessage(), e.getCause());
        }
        return totalSuccess;
    }

    /**
     * 构建更新数据请求参数
     *
     * @param entity  实体
     * @param idValue id值
     * @return 更新请求参数
     */
    private UpdateRequest buildUpdateRequest(T entity, String idValue) {
        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.id(idValue);
        updateRequest.index(getIndexName());
        String jsonData = buildJsonSource(entity);
        updateRequest.doc(jsonData, XContentType.JSON);
        return updateRequest;
    }

    /**
     * 从es获取到的数据中解析出对应类型的数组 默认设置id
     *
     * @param searchResponse es返回的响应体
     * @return 指定的返回类型数据列表
     */
    private List<T> parseResultList(SearchResponse searchResponse) {
        SearchHit[] searchHits = parseSearchHit(searchResponse);
        if (CollectionUtils.isEmpty(searchHits)) {
            return new ArrayList<>(0);
        }
        return Arrays.stream(searchHits)
                .map(hit -> {
                    T entity = JSON.parseObject(hit.getSourceAsString(), entityClass);
                    setId(entity, hit.getId());
                    return entity;
                }).collect(Collectors.toList());
    }

    /**
     * 从es获取到的数据中解析出对应类型的数组 id根据查询/不查询条件决定是否设置
     *
     * @param searchResponse es返回的响应体
     * @param wrapper        条件
     * @return 指定的返回类型数据列表
     */
    private List<T> parseResultList(SearchResponse searchResponse, EsLambdaQueryWrapper<T> wrapper) {
        SearchHit[] searchHits = parseSearchHit(searchResponse);
        if (CollectionUtils.isEmpty(searchHits)) {
            return new ArrayList<>(0);
        }
        return hitsToArray(searchHits, wrapper);
    }

    /**
     * 将es返回结果集解析为数组
     *
     * @param searchHits es返回结果集
     * @param wrapper    条件
     * @return
     */
    private List<T> hitsToArray(SearchHit[] searchHits, EsLambdaQueryWrapper<T> wrapper) {
        return Arrays.stream(searchHits)
                .map(hit -> {
                    T entity = JSON.parseObject(hit.getSourceAsString(), entityClass);
                    boolean includeId = EsWrapperProcessor.includeId(getRealIdFieldName(), wrapper);
                    if (includeId) {
                        setId(entity, hit.getId());
                    }
                    return entity;
                }).collect(Collectors.toList());
    }
}