package com.xwl.esplus.example.document;

import com.xwl.esplus.core.annotation.EsDocumentField;
import com.xwl.esplus.core.enums.EsFieldStrategyEnum;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * es 数据模型
 * <p>
 * Copyright © 2021 xpc1024 All Rights Reserved
 **/
@Data
public class TestDocument {
    /**
     * es中的唯一id
     */
    private String id;
    /**
     * 文档标题
     */
    private String title;
    /**
     * 文档内容
     */
    private String content;
    /**
     * 作者 加@DocumentField注解,并指明strategy = FieldStrategyEnum.NOT_EMPTY 表示更新的时候的策略为 创建者不为空字符串时才更新
     */
    @EsDocumentField(strategy = EsFieldStrategyEnum.NOT_EMPTY)
    private String creator;
    /**
     * 创建时间
     */
    private LocalDateTime gmtCreate;
    /**
     * es中实际不存在的字段,但模型中加了,为了不和es映射,可以在此类型字段上加上 注解@DocumentField,并指明exist=false
     */
    @EsDocumentField(exist = false)
    private String notExistsField;
    /**
     * 地理位置纬经度坐标 例如: "40.13933715136454,116.63441990026217"
     */
    private String location;
    /**
     * 图形
     */
    private String geoLocation;
}
