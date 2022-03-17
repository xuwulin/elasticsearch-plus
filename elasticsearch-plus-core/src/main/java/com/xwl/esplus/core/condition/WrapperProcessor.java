package com.xwl.esplus.core.condition;

import com.xwl.esplus.core.enums.EsAttachTypeEnum;
import com.xwl.esplus.core.param.EsAggregationParam;
import com.xwl.esplus.core.param.EsBaseParam;
import com.xwl.esplus.core.param.EsGeoParam;
import com.xwl.esplus.core.toolkit.CollectionUtils;
import com.xwl.esplus.core.toolkit.EsQueryTypeUtils;
import com.xwl.esplus.core.toolkit.OptionalUtils;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.AvgAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.MaxAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.MinAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.SumAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.xwl.esplus.core.constant.EsConstants.DEFAULT_SIZE;
import static com.xwl.esplus.core.enums.EsBaseParamTypeEnum.*;

/**
 * wrapper处理类
 *
 * @author xwl
 * @since 2022/3/16 15:06
 */
public class WrapperProcessor {
    private WrapperProcessor() {
    }

    /**
     * 构建es查询入参
     *
     * @param wrapper 条件
     * @return ES查询参数
     */
    public static SearchSourceBuilder buildSearchSourceBuilder(LambdaEsQueryWrapper<?> wrapper) throws IOException {
        // 初始化boolQueryBuilder 参数
        BoolQueryBuilder boolQueryBuilder = initBoolQueryBuilder(wrapper.baseParamList);

        // 初始化searchSourceBuilder 参数
        SearchSourceBuilder searchSourceBuilder = initSearchSourceBuilder(wrapper);

        // 初始化geo相关: BoundingBox,geoDistance,geoPolygon,geoShape
        GeoBoundingBoxQueryBuilder geoBoundingBoxQueryBuilder = initGeoBoundingBoxQueryBuilder(wrapper.geoParam);
        GeoDistanceQueryBuilder geoDistanceQueryBuilder = initGeoDistanceQueryBuilder(wrapper.geoParam);
        GeoPolygonQueryBuilder geoPolygonQueryBuilder = initGeoPolygonQueryBuilder(wrapper.geoParam);
        GeoShapeQueryBuilder geoShapeQueryBuilder = initGeoShapeQueryBuilder(wrapper.geoParam);

        // 设置参数
        Optional.ofNullable(geoBoundingBoxQueryBuilder).ifPresent(boolQueryBuilder::filter);
        Optional.ofNullable(geoDistanceQueryBuilder).ifPresent(boolQueryBuilder::filter);
        Optional.ofNullable(geoPolygonQueryBuilder).ifPresent(boolQueryBuilder::filter);
        Optional.ofNullable(geoShapeQueryBuilder).ifPresent(boolQueryBuilder::filter);
        searchSourceBuilder.query(boolQueryBuilder);
        return searchSourceBuilder;
    }

    /**
     * 初始化BoolQueryBuilder
     *
     * @param baseParamList 基础参数列表
     * @return BoolQueryBuilder
     */
    public static BoolQueryBuilder initBoolQueryBuilder(List<EsBaseParam> baseParamList) {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        // 用于连接and,or条件内的多个查询条件,包装成boolQuery
        BoolQueryBuilder inner = null;
        // 是否有外层or
        boolean hasOuterOr = false;
        for (int i = 0; i < baseParamList.size(); i++) {
            EsBaseParam baseEsParam = baseParamList.get(i);
            if (Objects.equals(AND_LEFT_BRACKET.getType(), baseEsParam.getType()) || Objects.equals(OR_LEFT_BRACKET.getType(), baseEsParam.getType())) {
                // 说明有and或者or
                for (int j = i + 1; j < baseParamList.size(); j++) {
                    if (Objects.equals(baseParamList.get(j).getType(), OR_ALL.getType())) {
                        // 说明左括号内出现了内层or查询条件
                        for (int k = i + 1; k < j; k++) {
                            // 内层or只会出现在中间,此处将内层or之前的查询条件类型进行处理
                            EsBaseParam.setUp(baseParamList.get(k));
                        }
                    }
                }
                inner = QueryBuilders.boolQuery();
            }

            // 此处处理所有内外层or后面的查询条件类型
            if (Objects.equals(baseEsParam.getType(), OR_ALL.getType())) {
                hasOuterOr = true;
            }
            if (hasOuterOr) {
                EsBaseParam.setUp(baseEsParam);
            }

            // 处理括号中and和or的最终连接类型 and->must, or->should
            if (Objects.equals(AND_RIGHT_BRACKET.getType(), baseEsParam.getType())) {
                boolQueryBuilder.must(inner);
                inner = null;
            }
            if (Objects.equals(OR_RIGHT_BRACKET.getType(), baseEsParam.getType())) {
                boolQueryBuilder.should(inner);
                inner = null;
            }

            // 添加字段名称,值,查询类型等
            if (Objects.isNull(inner)) {
                addQuery(baseEsParam, boolQueryBuilder);
            } else {
                addQuery(baseEsParam, inner);
            }
        }
        return boolQueryBuilder;
    }

    /**
     * 初始化SearchSourceBuilder
     *
     * @param wrapper 条件
     * @return SearchSourceBuilder
     */
    private static SearchSourceBuilder initSearchSourceBuilder(LambdaEsQueryWrapper<?> wrapper) {
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        // 设置高亮字段
        if (!CollectionUtils.isEmpty(wrapper.highLightParamList)) {
            wrapper.highLightParamList.forEach(highLightParam -> {
                HighlightBuilder highlightBuilder = new HighlightBuilder();
                highLightParam.getFields().forEach(highlightBuilder::field);
                highlightBuilder.preTags(highLightParam.getPreTag());
                highlightBuilder.postTags(highLightParam.getPostTag());
                searchSourceBuilder.highlighter(highlightBuilder);
            });
        }

        // 设置排序字段
        if (!CollectionUtils.isEmpty(wrapper.sortParamList)) {
            wrapper.sortParamList.forEach(sortParam -> {
                SortOrder sortOrder = sortParam.getAsc() ? SortOrder.ASC : SortOrder.DESC;
                sortParam.getFields().forEach(field -> {
                    FieldSortBuilder fieldSortBuilder = new FieldSortBuilder(field).order(sortOrder);
                    searchSourceBuilder.sort(fieldSortBuilder);
                });
            });
        }

        // 设置查询或不查询字段
        if (CollectionUtils.isNotEmpty(wrapper.include) || CollectionUtils.isNotEmpty(wrapper.exclude)) {
            searchSourceBuilder.fetchSource(wrapper.include, wrapper.exclude);
        }

        // 设置查询起止参数
        Optional.ofNullable(wrapper.from).ifPresent(searchSourceBuilder::from);
        OptionalUtils.ofNullable(wrapper.size).ifPresent(searchSourceBuilder::size, DEFAULT_SIZE);

        // 设置聚合参数
        if (!CollectionUtils.isEmpty(wrapper.aggregationParamList)) {
            initAggregations(wrapper.aggregationParamList, searchSourceBuilder);
        }

        return searchSourceBuilder;
    }

    /**
     * 设置聚合参数
     *
     * @param aggregationParamList 聚合参数列表
     * @param searchSourceBuilder  es searchSourceBuilder
     */
    private static void initAggregations(List<EsAggregationParam> aggregationParamList, SearchSourceBuilder searchSourceBuilder) {
        aggregationParamList.forEach(aggregationParam -> {
            switch (aggregationParam.getAggregationType()) {
                case AVG:
                    AvgAggregationBuilder avg = AggregationBuilders.avg(aggregationParam.getName()).field(aggregationParam.getField());
                    searchSourceBuilder.aggregation(avg);
                    break;
                case MIN:
                    MinAggregationBuilder min = AggregationBuilders.min(aggregationParam.getName()).field(aggregationParam.getField());
                    searchSourceBuilder.aggregation(min);
                    break;
                case MAX:
                    MaxAggregationBuilder max = AggregationBuilders.max(aggregationParam.getName()).field(aggregationParam.getField());
                    searchSourceBuilder.aggregation(max);
                    break;
                case SUM:
                    SumAggregationBuilder sum = AggregationBuilders.sum(aggregationParam.getName()).field(aggregationParam.getField());
                    searchSourceBuilder.aggregation(sum);
                    break;
                case TERMS:
                    TermsAggregationBuilder terms = AggregationBuilders.terms(aggregationParam.getName()).field(aggregationParam.getField());
                    searchSourceBuilder.aggregation(terms);
                    break;
                default:
                    throw new UnsupportedOperationException("不支持的聚合类型,参见AggregationTypeEnum");
            }

        });
    }


    /**
     * 初始化GeoBoundingBoxQueryBuilder
     *
     * @param geoParam Geo相关参数
     * @return GeoBoundingBoxQueryBuilder
     */
    private static GeoBoundingBoxQueryBuilder initGeoBoundingBoxQueryBuilder(EsGeoParam geoParam) {
        // 参数校验
        boolean invalidParam = Objects.isNull(geoParam)
                || (Objects.isNull(geoParam.getTopLeft()) || Objects.isNull(geoParam.getBottomRight()));
        if (invalidParam) {
            return null;
        }

        GeoBoundingBoxQueryBuilder builder = QueryBuilders.geoBoundingBoxQuery(geoParam.getField());
        Optional.ofNullable(geoParam.getBoost()).ifPresent(builder::boost);
        builder.setCorners(geoParam.getTopLeft(), geoParam.getBottomRight());
        return builder;
    }

    /**
     * 初始化GeoDistanceQueryBuilder
     *
     * @param geoParam Geo相关参数
     * @return GeoDistanceQueryBuilder
     */
    private static GeoDistanceQueryBuilder initGeoDistanceQueryBuilder(EsGeoParam geoParam) {
        // 参数校验
        boolean invalidParam = Objects.isNull(geoParam)
                || (Objects.isNull(geoParam.getDistanceStr()) && Objects.isNull(geoParam.getDistance()));
        if (invalidParam) {
            return null;
        }

        GeoDistanceQueryBuilder builder = QueryBuilders.geoDistanceQuery(geoParam.getField());
        Optional.ofNullable(geoParam.getBoost()).ifPresent(builder::boost);
        // 距离来源: 双精度类型+单位或字符串类型
        Optional.ofNullable(geoParam.getDistanceStr()).ifPresent(builder::distance);
        Optional.ofNullable(geoParam.getDistance())
                .ifPresent(distance -> builder.distance(distance, geoParam.getDistanceUnit()));
        Optional.ofNullable(geoParam.getCentralGeoPoint()).ifPresent(builder::point);
        return builder;
    }

    /**
     * 初始化 GeoPolygonQueryBuilder
     *
     * @param geoParam Geo相关参数
     * @return GeoPolygonQueryBuilder
     */
    private static GeoPolygonQueryBuilder initGeoPolygonQueryBuilder(EsGeoParam geoParam) {
        // 参数校验
        boolean invalidParam = Objects.isNull(geoParam) || CollectionUtils.isEmpty(geoParam.getGeoPoints());
        if (invalidParam) {
            return null;
        }

        GeoPolygonQueryBuilder builder = QueryBuilders.geoPolygonQuery(geoParam.getField(), geoParam.getGeoPoints());
        Optional.ofNullable(geoParam.getBoost()).ifPresent(builder::boost);
        return builder;
    }

    /**
     * 初始化 GeoShapeQueryBuilder
     *
     * @param geoParam Geo相关参数
     * @return GeoShapeQueryBuilder
     */
    private static GeoShapeQueryBuilder initGeoShapeQueryBuilder(EsGeoParam geoParam) throws IOException {
        // 参数校验
        boolean invalidParam = Objects.isNull(geoParam)
                || (Objects.isNull(geoParam.getIndexedShapeId()) && Objects.isNull(geoParam.getGeometry()));
        if (invalidParam) {
            return null;
        }

        GeoShapeQueryBuilder builder = QueryBuilders.geoShapeQuery(geoParam.getField(), geoParam.getGeometry());
        Optional.ofNullable(geoParam.getShapeRelation()).ifPresent(builder::relation);
        Optional.ofNullable(geoParam.getBoost()).ifPresent(builder::boost);
        return builder;
    }

    /**
     * 添加进参数容器
     *
     * @param baseEsParam      基础参数
     * @param boolQueryBuilder es boolQueryBuilder
     */
    private static void addQuery(EsBaseParam baseEsParam, BoolQueryBuilder boolQueryBuilder) {
        baseEsParam.getMustList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.MUST.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getFilterList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.FILTER.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getShouldList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.SHOULD.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getMustNotList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.MUST_NOT.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getGtList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.GT.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getLtList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.LT.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getGeList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.GE.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getLeList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.LE.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getBetweenList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.BETWEEN.getType(), fieldValueModel.getField(), fieldValueModel.getLeftValue(), fieldValueModel.getRightValue(), fieldValueModel.getBoost()));
        baseEsParam.getNotBetweenList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.NOT_BETWEEN.getType(), fieldValueModel.getField(), fieldValueModel.getLeftValue(), fieldValueModel.getRightValue(), fieldValueModel.getBoost()));
        baseEsParam.getInList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.IN.getType(), fieldValueModel.getField(), fieldValueModel.getValues(), fieldValueModel.getBoost()));
        baseEsParam.getNotInList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.NOT_IN.getType(), fieldValueModel.getField(), fieldValueModel.getValues(), fieldValueModel.getBoost()));
        baseEsParam.getIsNullList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.NOT_EXISTS.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), Optional.empty(), fieldValueModel.getBoost()));
        baseEsParam.getNotNullList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.EXISTS.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), Optional.empty(), fieldValueModel.getBoost()));
        baseEsParam.getLikeLeftList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.LIKE_LEFT.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
        baseEsParam.getLikeRightList().forEach(fieldValueModel ->
                EsQueryTypeUtils.addQueryByType(boolQueryBuilder, fieldValueModel.getEsQueryType(), EsAttachTypeEnum.LIKE_RIGHT.getType(), fieldValueModel.getOriginalAttachType(), fieldValueModel.getField(), fieldValueModel.getValue(), fieldValueModel.getBoost()));
    }

    /**
     * 查询字段中是否包含id
     *
     * @param idField 字段
     * @param wrapper 条件
     * @return 是否包含的布尔值
     */
    public static boolean includeId(String idField, LambdaEsQueryWrapper<?> wrapper) {
        if (CollectionUtils.isEmpty(wrapper.include) && CollectionUtils.isEmpty(wrapper.exclude)) {
            // 未设置, 默认返回
            return true;
        } else if (CollectionUtils.isNotEmpty(wrapper.include) && Arrays.asList(wrapper.include).contains(idField)) {
            return true;
        } else {
            return CollectionUtils.isNotEmpty(wrapper.exclude) && !Arrays.asList(wrapper.exclude).contains(idField);
        }
    }
}
