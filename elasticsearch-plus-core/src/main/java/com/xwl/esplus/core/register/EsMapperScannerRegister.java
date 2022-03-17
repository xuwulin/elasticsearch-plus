package com.xwl.esplus.core.register;

import com.xwl.esplus.core.annotation.EsMapperScan;
import com.xwl.esplus.core.toolkit.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * 注册bean，参照mybatis-spring
 * ImportBeanDefinitionRegistrar注入FactoryBean到SpringIOC中，
 * 而在FactoryBean中定义了类型T的动态代理，通过对InvocationHandler接口的实现来添加自定义行为，这里使用jdk默认的代理，只支持接口类型。
 *
 * ImportBeanDefinitionRegistrar，在Spring中，加载它的实现类，只有一个方法就是配合@Impor使用，是主要负责Bean 的动态注入的。
 *
 * @author xwl
 * @since 2022/3/11 20:29
 */
public class EsMapperScannerRegister implements ImportBeanDefinitionRegistrar, ResourceLoaderAware {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private ResourceLoader resourceLoader;

    /**
     * 根据需要注册 bean 定义。
     *
     * @param importingClassMetadata 导入类的注解元数据
     * @param registry               当前bean定义注册表
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        // 获取自定义注解@EsMapperScan中的属性值（value/basePackages，字符串数组或字符串）
        AnnotationAttributes annAttrs = AnnotationAttributes
                .fromMap(importingClassMetadata.getAnnotationAttributes(EsMapperScan.class.getName()));

        ClassPathMapperScanner scanner = new ClassPathMapperScanner(registry);
        // this check is needed in Spring 3.1
        // java8写法
//        Optional.ofNullable(resourceLoader).ifPresent(scanner::setResourceLoader);
        // 普通写法
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }

        // @EsMapperScan注解扫描的包
        List<String> basePackages = new ArrayList<>();
        // Stream写法
        /*basePackages.addAll(Arrays.stream(annAttrs.getStringArray("value"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toList()));
        basePackages.addAll(Arrays.stream(annAttrs.getStringArray("basePackages"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toList()));
        basePackages.addAll(Arrays.stream(annAttrs.getClassArray("basePackageClasses"))
                        .map(ClassUtils::getPackageName)
                        .collect(Collectors.toList()));*/
        // 普通写法
        for (String pkg : annAttrs.getStringArray("value")) {
            if (StringUtils.hasText(pkg)) {
                basePackages.add(pkg);
            }
        }
        for (String pkg : annAttrs.getStringArray("basePackages")) {
            if (StringUtils.hasText(pkg)) {
                basePackages.add(pkg);
            }
        }
        for (Class<?> clazz : annAttrs.getClassArray("basePackageClasses")) {
            basePackages.add(ClassUtils.getPackageName(clazz));
        }
        /*if (CollectionUtils.isEmpty(basePackages)) {
            // 标注@EsMapperScan注解所在类的包
            basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
        }*/

        // 包含过滤器
        for (AnnotationAttributes filter : annAttrs.getAnnotationArray("includeFilters")) {
            for (TypeFilter typeFilter : typeFiltersFor(filter)) {
                scanner.addIncludeFilter(typeFilter);
            }
        }
        // 排斥过滤器
        for (AnnotationAttributes filter : annAttrs.getAnnotationArray("excludeFilters")) {
            for (TypeFilter typeFilter : typeFiltersFor(filter)) {
                scanner.addExcludeFilter(typeFilter);
            }
        }
        if (CollectionUtils.isEmpty(basePackages)) {
            throw ExceptionUtils.epe("Annotation @EsMapper must be value(basePackages) or basePackageClasses");
        }
        // 注册过滤器，自定义扫描规则，与Spring的默认机制不同
        scanner.registerFilters();
        // 开始扫描，对包路径进行扫描
        scanner.doScan(StringUtils.toStringArray(basePackages));
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * @param filterAttributes
     * @return
     */
    private List<TypeFilter> typeFiltersFor(AnnotationAttributes filterAttributes) {
        List<TypeFilter> typeFilters = new ArrayList<>();
        FilterType filterType = filterAttributes.getEnum("type");

        for (Class<?> filterClass : filterAttributes.getClassArray("classes")) {
            switch (filterType) {
                case ANNOTATION:
                    Assert.isAssignable(Annotation.class, filterClass,
                            "@EsMapperScan ANNOTATION type filter requires an annotation type");
                    Class<Annotation> annotationType = (Class<Annotation>) filterClass;
                    typeFilters.add(new AnnotationTypeFilter(annotationType));
                    break;
                case ASSIGNABLE_TYPE:
                    typeFilters.add(new AssignableTypeFilter(filterClass));
                    break;
                case CUSTOM:
                    Assert.isAssignable(TypeFilter.class, filterClass,
                            "@EsMapperScan CUSTOM type filter requires a TypeFilter implementation");
                    TypeFilter filter = BeanUtils.instantiateClass(filterClass, TypeFilter.class);
                    typeFilters.add(filter);
                    break;
                default:
                    throw new IllegalArgumentException("Filter type not supported with Class value: " + filterType);
            }
        }
        return typeFilters;
    }
}
