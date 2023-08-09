/*
 *    Copyright 2009-2013 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

  private XPathParser parser;
  private MapperBuilderAssistant builderAssistant;
  private Map<String, XNode> sqlFragments;
  private String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  /**
   *
   * @param inputStream mapper文件流
   * @param configuration 配置对象
   * @param resource mapper文件源
   * @param sqlFragments
   */
  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 解析mapper.xml文件
   */
  public void parse() {
    if (!configuration.isResourceLoaded(resource)) {
      configurationElement(parser.evalNode("/mapper"));
      configuration.addLoadedResource(resource);
      bindMapperForNamespace();
    }

    parsePendingResultMaps();
    parsePendingChacheRefs();
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   * 解析mapper.xml 文件的 mapper节点
   * 每个mapper 必须有namespace
   * 解析 cache-ref 节点 仅一个
   * 解析 cache 节点 仅一个
   * 解析 parameterMap 节点 多个
   * 解析 resultMap 节点 多个
   * 解析 sql 节点 多个
   * 解析 select|insert|update|delete 节点 多个
   * @param context
   */
  private void configurationElement(XNode context) {
    try {
      String namespace = context.getStringAttribute("namespace");
      if (namespace.equals("")) {
    	  throw new BuilderException("Mapper's namespace cannot be empty");
      }
      // 设置命名空间 到 mapper助理
      builderAssistant.setCurrentNamespace(namespace);
      cacheRefElement(context.evalNode("cache-ref"));
      cacheElement(context.evalNode("cache"));
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      sqlElement(context.evalNodes("/mapper/sql"));
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. Cause: " + e, e);
    }
  }

  /**
   * 解析所有 DML到 mappedStatement
   * @param list
   */
  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingChacheRefs() {
	  Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
	  synchronized (incompleteCacheRefs) {
		  Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
		  while (iter.hasNext()) {
			  try {
				  iter.next().resolveCacheRef();
				  iter.remove();
			  } catch (IncompleteElementException e) {
				  // Cache ref is still missing a resource...
			  }
		  }
	  }
  }

  private void parsePendingStatements() {
	  Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
	  synchronized (incompleteStatements) {
		  Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
		  while (iter.hasNext()) {
			  try {
				  iter.next().parseStatementNode();
				  iter.remove();
			  } catch (IncompleteElementException e) {
				  // Statement is still missing a resource...
			  }
		  }
	  }
  }

  /**
   * 以命名空间存储 cache-ref的 namespace, configuration中Map<String, String> cacheRefMap
   * 其中通过助理《builderAssistant》来操作configuration 中的caches
   *
   * <!ELEMENT cache-ref EMPTY>
   * <!ATTLIST cache-ref
   * namespace CDATA #REQUIRED
   * >
   * @param context
   */
  private void cacheRefElement(XNode context) {
    if (context != null) {
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
    	  cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        /**
         * 如果 configuration.caches中不存在该命名空间的cache，那么登记到configuration.incompleteCacheRefs
          */
    	  configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  /**
   * 解析 cache 节点
   *
   * <!ELEMENT cache (property*)>
   * <!ATTLIST cache
   * type CDATA #IMPLIED
   * eviction CDATA #IMPLIED
   * flushInterval CDATA #IMPLIED
   * size CDATA #IMPLIED
   * readOnly CDATA #IMPLIED
   * >
   * @param context
   * @throws Exception
   */
  private void cacheElement(XNode context) throws Exception {
    if (context != null) {
      /**
       * 永久缓存
       */
      String type = context.getStringAttribute("type", "PERPETUAL");
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      /**
       * 先进先出策略
       */
      String eviction = context.getStringAttribute("eviction", "LRU");
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      /**
       * 刷新时间
       */
      Long flushInterval = context.getLongAttribute("flushInterval");
      /**
       * 缓存大小， 默认在缓存实现中：1024
       */
      Integer size = context.getIntAttribute("size");
      /**
       * 是否仅读，默认可读写
       */
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      Properties props = context.getChildrenAsProperties();
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, props);
    }
  }

  /**
   * <!ELEMENT parameterMap (parameter+)?>
   * <!ATTLIST parameterMap
   * id CDATA #REQUIRED  参数ID
   * type CDATA #REQUIRED  参数类型
   * >
   *
   * <!ELEMENT parameter EMPTY>
   * <!ATTLIST parameter
   * property CDATA #REQUIRED 属性名
   * javaType CDATA #IMPLIED  java类型
   * jdbcType CDATA #IMPLIED  JDBC类型
   * mode (IN | OUT | INOUT) #IMPLIED 输入输出
   * resultMap CDATA #IMPLIED 结果映射
   * scale CDATA #IMPLIED
   * typeHandler CDATA #IMPLIED  类型转换器
   * >
   *
   * @param list
   * @throws Exception
   */
  private void parameterMapElement(List<XNode> list) throws Exception {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      /**
       * 为该 parameterMap 以ID 绑定所有属性和类型
       */
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) throws Exception {
    for (XNode resultMapNode : list) {
      try {
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.<ResultMapping> emptyList());
  }

  /**
   * <!ELEMENT resultMap (constructor?,id*,result*,association*,collection*, discriminator?)>
   * <!ATTLIST resultMap
   * id CDATA #REQUIRED
   * type CDATA #REQUIRED
   * extends CDATA #IMPLIED
   * autoMapping (true|false) #IMPLIED
   * >
   *
   * <!ELEMENT constructor (idArg*,arg*)>
   *
   * <!ELEMENT id EMPTY>
   * <!ATTLIST id
   * property CDATA #IMPLIED
   * javaType CDATA #IMPLIED
   * column CDATA #IMPLIED
   * jdbcType CDATA #IMPLIED
   * typeHandler CDATA #IMPLIED
   * >
   *
   * <!ELEMENT result EMPTY>
   * <!ATTLIST result
   * property CDATA #IMPLIED
   * javaType CDATA #IMPLIED
   * column CDATA #IMPLIED
   * jdbcType CDATA #IMPLIED
   * typeHandler CDATA #IMPLIED
   * >
   *
   * <!ELEMENT collection (constructor?,id*,result*,association*,collection*, discriminator?)>
   * <!ATTLIST collection
   * property CDATA #REQUIRED
   * column CDATA #IMPLIED
   * javaType CDATA #IMPLIED
   * ofType CDATA #IMPLIED
   * jdbcType CDATA #IMPLIED
   * select CDATA #IMPLIED
   * resultMap CDATA #IMPLIED
   * typeHandler CDATA #IMPLIED
   * notNullColumn CDATA #IMPLIED
   * columnPrefix CDATA #IMPLIED
   * resultSet CDATA #IMPLIED
   * foreignColumn CDATA #IMPLIED
   * autoMapping (true|false) #IMPLIED
   * fetchType (lazy|eager) #IMPLIED
   * >
   *
   * <!ELEMENT association (constructor?,id*,result*,association*,collection*, discriminator?)>
   * <!ATTLIST association
   * property CDATA #REQUIRED
   * column CDATA #IMPLIED
   * javaType CDATA #IMPLIED
   * jdbcType CDATA #IMPLIED
   * select CDATA #IMPLIED
   * resultMap CDATA #IMPLIED
   * typeHandler CDATA #IMPLIED
   * notNullColumn CDATA #IMPLIED
   * columnPrefix CDATA #IMPLIED
   * resultSet CDATA #IMPLIED
   * foreignColumn CDATA #IMPLIED
   * autoMapping (true|false) #IMPLIED
   * fetchType (lazy|eager) #IMPLIED
   * >
   *
   * <!ELEMENT discriminator (case+)>
   * <!ATTLIST discriminator
   * column CDATA #IMPLIED
   * javaType CDATA #REQUIRED
   * jdbcType CDATA #IMPLIED
   * typeHandler CDATA #IMPLIED
   * >
   *
   * 处理每一个 resultMap
   *
   */
  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    String id = resultMapNode.getStringAttribute("id", // 结果ID
        resultMapNode.getValueBasedIdentifier());
    String type = resultMapNode.getStringAttribute("type", // 结果映射的类型
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    String extend = resultMapNode.getStringAttribute("extends"); // 继承关系
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping"); // 是否自动映射
    Class<?> typeClass = resolveClass(type);
    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
    resultMappings.addAll(additionalResultMappings);
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {
      if ("constructor".equals(resultChild.getName())) { // 解析 构造器映射
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        ArrayList<ResultFlag> flags = new ArrayList<ResultFlag>();
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        /**
         * 添加resultMap 中的所有子节点ResultMapping 到集合中
         */
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  /**
   * constructor
   *    idArg？
   *    arg
   * @param resultChild
   * @param resultType
   * @param resultMappings
   * @throws Exception
   */
  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
      ArrayList<ResultFlag> flags = new ArrayList<ResultFlag>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);
      }
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<String, String>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

  private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
    for (XNode context : list) {
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) sqlFragments.put(id, context);
    }
  }
  
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      if (databaseId != null) {
        return false;
      }
      // skip this fragment if there is a previous one with a not null databaseId
      if (this.sqlFragments.containsKey(id)) {
        XNode context = this.sqlFragments.get(id);
        if (context.getStringAttribute("databaseId") != null) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * constructor 中的 idArg 和 arg 都包含 resultMap
   * association 也包含 resultMap
   * @param context
   * @param resultType
   * @param flags
   * @return  ResultMap 的Id
   * @throws Exception
   */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, ArrayList<ResultFlag> flags) throws Exception {
    String property = context.getStringAttribute("property");
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String nestedSelect = context.getStringAttribute("select");
    String nestedResultMap = context.getStringAttribute("resultMap",
        processNestedResultMappings(context, Collections.<ResultMapping> emptyList()));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resulSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resulSet, foreignColumn, lazy);
  }

  /**
   * 如果是 association、collection、case 节点 并且属性 select 为空则去获取该节点下的 ResultMap
   * 否则返回为空
   *
   * @param context
   * @param resultMappings
   * @return
   * @throws Exception
   */
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      if (context.getStringAttribute("select") == null) {
        ResultMap resultMap = resultMapElement(context, resultMappings);
        return resultMap.getId();
      }
    }
    return null;
  }

  private void bindMapperForNamespace() {
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //ignore, bound type is not required
      }
      if (boundType != null) {
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
          configuration.addLoadedResource("namespace:" + namespace);
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
