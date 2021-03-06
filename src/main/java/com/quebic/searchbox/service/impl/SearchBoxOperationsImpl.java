package com.quebic.searchbox.service.impl;

import static com.quebic.searchbox.config.ConfigKeys.lua_input_parm_key;
import static com.quebic.searchbox.config.ConfigKeys.lua_input_parm_value;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quebic.searchbox.annotation.Id;
import com.quebic.searchbox.annotation.Index;
import com.quebic.searchbox.common.ErrorMessage;
import com.quebic.searchbox.common.exception.SearchResourceDuplicate;
import com.quebic.searchbox.common.exception.SearchResourceNotFound;
import com.quebic.searchbox.config.SearchBoxConfig;
import com.quebic.searchbox.exception.SearchBoxOperationsException;
import com.quebic.searchbox.exception.query.QueryFunctionNotFoundException;
import com.quebic.searchbox.exception.query.QueryFunctionParmsException;
import com.quebic.searchbox.query.Criteria;
import com.quebic.searchbox.query.JsonQueryParser;
import com.quebic.searchbox.query.Projection;
import com.quebic.searchbox.query.Query;
import com.quebic.searchbox.query.QueryContainer;
import com.quebic.searchbox.query.QueryHolder;
import com.quebic.searchbox.query.sort.Sort;
import com.quebic.searchbox.redis.JedisConnectionPool;
import com.quebic.searchbox.redis.ModelDataProcessor;
import com.quebic.searchbox.redis.ScriptProcessor;
import com.quebic.searchbox.redis.SearchDataProcessor;
import com.quebic.searchbox.service.SearchBoxOperations;
import com.quebic.searchbox.service.search.Page;
import com.quebic.searchbox.service.search.SearchResult;

@Service
public class SearchBoxOperationsImpl implements SearchBoxOperations {

	private SearchBoxConfig config;
	private QueryContainer queryContainer;

	private ModelDataProcessor modelDataRedisCaller;
	private SearchDataProcessor searchDataRedisCaller;
	private ScriptProcessor scriptProcessor;
	
	private enum StoreModes{
		INSERT,
		UPDATE,
		SAVE
	}

	@Autowired
	public SearchBoxOperationsImpl(SearchBoxConfig config, JedisConnectionPool jedisConnectionPool,
			QueryContainer queryContainer) throws Exception {

		this.config = config;
		this.queryContainer = queryContainer;

		scriptProcessor = ScriptProcessor.create(config, jedisConnectionPool);
		modelDataRedisCaller = ModelDataProcessor.create(config, jedisConnectionPool, scriptProcessor);
		searchDataRedisCaller = SearchDataProcessor.create(config, jedisConnectionPool, scriptProcessor);

	}
	
	@Override
	@CacheEvict(cacheNames={
					"search_query"
					, "search_query_name"
					, "searchByField"
					, "searchByFieldPerfix"
					, "searchByFieldPattern"}, allEntries=true)
	public <T> void insert(T object) throws SearchBoxOperationsException {
		saveModel(object, StoreModes.INSERT);
	}

	@Override
	@CacheEvict(cacheNames={
			"search_query"
			, "search_query_name"
			, "searchByField"
			, "searchByFieldPerfix"
			, "searchByFieldPattern"}, allEntries=true)
	public <T> void update(T object) throws SearchBoxOperationsException {
		saveModel(object, StoreModes.UPDATE);
	}

	@Override
	@CacheEvict(cacheNames={
			"search_query"
			, "search_query_name"
			, "searchByField"
			, "searchByFieldPerfix"
			, "searchByFieldPattern"}, allEntries=true)
	public <T> void save(T object) throws SearchBoxOperationsException {
		saveModel(object, StoreModes.SAVE);
	}
	
	private <T> void saveModel(T object, StoreModes storeModes) throws SearchBoxOperationsException{
		
		try {

			Class<?> cls = object.getClass();

			String tableName = cls.getSimpleName();

			Object id = null;
			Id id_annotation = null;

			for (Field field : cls.getDeclaredFields()) {

				field.setAccessible(true);
				id = field.get(object);
				field.setAccessible(false);
				id_annotation = field.getAnnotation(Id.class);

				if (id_annotation != null) {
					
					if (id == null)
						throw new Exception(ErrorMessage.id_field_null);
					
					
					ObjectMapper objectMapper = null;
					String jsonObject = null;
					
					switch (storeModes) {
					case INSERT:
						
						if(modelDataRedisCaller.getModelById(tableName, String.valueOf(id)) != null){
							throw new SearchResourceDuplicate();
						}

						objectMapper = new ObjectMapper();
						jsonObject = objectMapper.writeValueAsString(object);

						modelDataRedisCaller.storeModel(tableName, String.valueOf(id), jsonObject);
						
						break;
						
					case UPDATE:
						
						if(modelDataRedisCaller.getModelById(tableName, String.valueOf(id)) == null){
							throw new SearchResourceNotFound();
						}
						
						modelDataRedisCaller.removeModelById(tableName, String.valueOf(id));

						objectMapper = new ObjectMapper();
						jsonObject = objectMapper.writeValueAsString(object);

						modelDataRedisCaller.storeModel(tableName, String.valueOf(id), jsonObject);
						
						break;
						
					case SAVE:
						
						modelDataRedisCaller.removeModelById(tableName, String.valueOf(id));

						objectMapper = new ObjectMapper();
						jsonObject = objectMapper.writeValueAsString(object);

						modelDataRedisCaller.storeModel(tableName, String.valueOf(id), jsonObject);
						break;
						
					}
					
					break;
				}
			}
			
			if(id_annotation == null)
				throw new Exception(ErrorMessage.annotation_id_not_found);

			if(id == null)
				throw new Exception(ErrorMessage.id_field_null);
			
			for (Field field : cls.getDeclaredFields()) {

				Index index_annotation = field.getAnnotation(Index.class);

				if (index_annotation != null) {

					field.setAccessible(true);
					Object val = field.get(object);
					
					if(StringUtils.isEmpty(val)) {
						field.setAccessible(false);
						continue;
					}
					
					if(val instanceof Collection){
						
						for(Object v : (Collection<?>)val){
							searchDataRedisCaller.prepareSearchData(tableName, field.getName(), v.toString(), id.toString());
						}
						
					}else{
						searchDataRedisCaller.prepareSearchData(tableName, field.getName(), val.toString(), id.toString());
					}
					
					field.setAccessible(false);

				}
			}

		} catch (Exception e) {
			throw new SearchBoxOperationsException(e.getMessage(), e);
		}
		
	}

	@Override
	@CacheEvict(cacheNames={
			"search_query"
			, "search_query_name"
			, "searchByField"
			, "searchByFieldPerfix"
			, "searchByFieldPattern"}, allEntries=true)
	public <T> void delete(T object) throws SearchBoxOperationsException {

		try {
			Class<?> cls = object.getClass();

			Object id = null;

			for (Field field : cls.getDeclaredFields()) {

				field.setAccessible(true);
				id = field.get(object);
				field.setAccessible(false);
				Id id_annotation = field.getAnnotation(Id.class);

				if (id_annotation != null) {
					delete(cls, id);
					break;
				}
			}
		} catch (Exception e) {
			throw new SearchBoxOperationsException(e.getMessage(), e);
		}

	}

	@Override
	@CacheEvict(cacheNames={
			"search_query"
			, "search_query_name"
			, "searchByField"
			, "searchByFieldPerfix"
			, "searchByFieldPattern"}, allEntries=true)
	public <T> void delete(Class<T> cls, Object id) throws SearchBoxOperationsException {

		try {
			String tableName = cls.getSimpleName();
			modelDataRedisCaller.removeModelById(tableName, String.valueOf(id));
		} catch (Exception e) {
			throw new SearchBoxOperationsException(e.getMessage(), e);
		}
	}

	@Override
	@Cacheable("search_query")
	public <T> SearchResult<T> search(Class<T> cls, Query query) throws SearchBoxOperationsException {
		return search(cls, query, null);
	}

	@Override
	@Cacheable("search_query")
	public <T> SearchResult<T> search(Class<T> cls, Query query, Page page) throws SearchBoxOperationsException {

		try {
			
			double start_time = getCurrentTimeMillis();
			
			String modelName = cls.getSimpleName();
			
			String script = query.getQueryString();
			List<String> keys = query.getCriteria().getCriteriaChain();

			List<String> argv = new ArrayList<>();
			prepareArgv(argv, cls, page, query.getProjection());
			
			Object evalResult = scriptProcessor.runScript(script, keys, argv);

			return prepareResult(query.getQueryName(), modelName, query, start_time, evalResult);

		} catch (Exception e) {
			throw new SearchBoxOperationsException(e.getMessage(), e);
		}

	}
	
	@Override
	public <T> SearchResult<T> search(Class<T> cls, String queryJson) throws SearchBoxOperationsException {
		return search(cls, queryJson, null);
	}

	@Override
	public <T> SearchResult<T> search(Class<T> cls, String queryJson, Page page) throws SearchBoxOperationsException {
	
		try{
			JsonQueryParser jsonQueryParser = new JsonQueryParser();
			ObjectMapper objectMapper = new ObjectMapper();
			JsonNode jsonNode = objectMapper.readTree(queryJson);
			Criteria criteria = jsonQueryParser.process(jsonNode);
			Query query = new Query(criteria);
			return search(cls, query, page);
		}catch(Exception e){
			throw new SearchBoxOperationsException(e);
		}
		
	}

	@Override
	@Cacheable("search_query_name")
	public <T> SearchResult<T> search(String queryName) throws SearchBoxOperationsException {
		return search(queryName, new HashMap<>());
	}

	@Override
	@Cacheable("search_query_name")
	public <T> SearchResult<T> search(String queryName, Map<String, Object> inputParms) throws SearchBoxOperationsException {
		return search(queryName, inputParms, null);
	}

	@Override
	@Cacheable("search_query_name")
	public <T> SearchResult<T> search(String queryName, Map<String, Object> inputParms, Page page) throws SearchBoxOperationsException {
		try {
			
			double start_time = getCurrentTimeMillis();
			
			QueryHolder queryHolder = queryContainer.getQuery(queryName);

			if (queryHolder == null)
				throw new QueryFunctionNotFoundException(queryName);

			Class<?> modelClass = queryHolder.getModelType();
			Query query = queryHolder.getQuery();

			String modelName = modelClass.getSimpleName();

			List<String> keys;
			if (inputParms.isEmpty()) {
				keys = query.getCriteria().getCriteriaChain();
			} else {
				keys = prepareKeys(queryName, query.getCriteria().getCriteriaChain(), inputParms);
			}

			List<String> argv = new ArrayList<>();
			prepareArgv(argv, modelClass, page, query.getProjection());
			
			Object evalResult = scriptProcessor.runScriptByEvalsha(queryName, keys, argv);
			
			return prepareResult(queryName, modelName, query, start_time, evalResult);
			
		} catch (Exception e) {
			throw new SearchBoxOperationsException(e.getMessage(), e);
		}

	}

	private List<String> prepareKeys(String queryName, List<String> keys, Map<String, Object> inputParms)
			throws Exception {

		List<String> notFoundParms = new ArrayList<>();
		List<String> _k = new ArrayList<>();

		ObjectMapper objectMapper = new ObjectMapper();

		JsonNode json_input_parms = objectMapper.readTree(objectMapper.writeValueAsString(inputParms));

		for (String k : keys) {

			Map<String, Object> new_m = new HashMap<>();

			JsonNode json_k = objectMapper.readTree(k);

			JsonNode json_lua_input_parm_key = json_k.get(lua_input_parm_key);
			JsonNode json_lua_input_parm_value = json_k.get(lua_input_parm_value);

			if (json_lua_input_parm_key != null && json_lua_input_parm_value != null) {

				// key
				String key_txt = json_lua_input_parm_key.asText();
				if (key_txt.startsWith("@")) {

					String orgi_key = key_txt.substring(1);
					JsonNode json_in = json_input_parms.get(orgi_key);

					if (json_in != null)
						new_m.put(lua_input_parm_key, json_in.asText());
					else
						notFoundParms.add(orgi_key);

				} else {
					new_m.put(lua_input_parm_key, key_txt);
				}
				// key

				// value
				String value_txt = json_lua_input_parm_value.asText();
				if (value_txt.startsWith("@")) {

					String orgi_key = value_txt.substring(1);
					JsonNode json_in = json_input_parms.get(orgi_key);

					if (json_in != null)
						new_m.put(lua_input_parm_value, json_in.asText());
					else
						notFoundParms.add(orgi_key);

				} else {
					new_m.put(lua_input_parm_value, value_txt);
				}
				// value

				_k.add(objectMapper.writeValueAsString(new_m));

			} else {
				_k.add(k);
			}

		}

		if (notFoundParms.size() != 0)
			throw new QueryFunctionParmsException(queryName, notFoundParms);

		return _k;

	}
	
	private void prepareArgv(List<String> argv, Class<?> modelClass, Page page, Projection projection){
		
		argv.add(config.getAppname()); // app name -> ARGV[1]
		argv.add(modelClass.getSimpleName()); // model name -> ARGV[2]
		argv.add(String.valueOf(config.getPage().getLength())); // page length -> ARGV[3]

		Page defaultPage = new Page();

		if (page == null) {
			argv.add(String.valueOf(defaultPage.getPageNo()));
		} else {
			argv.add(String.valueOf(page.getPageNo()));
		}
		
		extractHideFields(argv, modelClass, projection);
		
	}
	
	private void extractHideFields(List<String> argv, Class<?> modelClass, Projection projection){
		ObjectMapper objectMapper = new ObjectMapper();
		
		Map<String, Integer> hideFields = projection.getHidefields();
		Map<String, Integer> showfields = projection.getShowfields();
		
		if(hideFields != null){
			try {
				argv.add(objectMapper.writeValueAsString(hideFields));
			} catch (Exception e) {
				
			}
		}else if(showfields != null){
			
			hideFields = new HashMap<>();
			
			for(Field field : modelClass.getDeclaredFields()){
				
				try{
					//check field is annotated by Index
					field.getAnnotation(Index.class);
					
					//check this field is not mentions as showFields. 
					//If not this field must be hide 
					String fieldName = field.getName();
					
					if(!showfields.containsKey(fieldName))
						hideFields.put(fieldName, 1);
						
					
				}catch(NullPointerException e){
				}
			}
			
			try {
				
				if(!hideFields.isEmpty())
					argv.add(objectMapper.writeValueAsString(hideFields));
				
			} catch (Exception e) {
			}
			
		}
	
	}
	
	
	private <T> SearchResult<T> prepareResult(String queryName, String modelName, Query query, double start_time, Object evalResult) throws Exception{
		
		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode evalResultJsonObject = objectMapper.readTree(evalResult.toString());

		int pageLength = evalResultJsonObject.get("pageLength").asInt();
		int pagesCount = evalResultJsonObject.get("pagesCount").asInt();
		int pageNo = evalResultJsonObject.get("pageNo").asInt();
		int documentsCount = evalResultJsonObject.get("documentsCount").asInt();
		
		JsonNode resultJsonNode = evalResultJsonObject.get("result");

		List<T> result = new LinkedList<>();

		if (resultJsonNode != null) {
			Iterator<JsonNode> iterator = resultJsonNode.iterator();
			while (iterator.hasNext()) {
				String jsonStr = iterator.next().asText();
				result.add(objectMapper.readValue(jsonStr, new TypeReference<T>() {
				}));
			}
		}
		
		/**
		 * ---Sort---
		 */
		@SuppressWarnings("unchecked")
		Sort<T> sort = query.getSort();
		if(sort != null) sort.run(result);
		/**
		 * ---Sort---
		 */
		
		double end_time = getCurrentTimeMillis();
		
		double time_diff = getTimeDifference(start_time, end_time);

		return new SearchResult<T>(modelName, result, new Page(pageLength, pagesCount, pageNo), documentsCount, queryName, prepareTimeDifference(time_diff));		
		
	}

	@Override
	@Cacheable("searchByField")
	public <T> SearchResult<T> searchByField(Class<T> cls, String field, Object searchValue) throws SearchBoxOperationsException {
		return searchByField(cls, field, searchValue, new Page());
	}

	@Override
	@Cacheable("searchByField")
	public <T> SearchResult<T> searchByField(Class<T> cls, String field, Object searchValue, Page page)
			throws SearchBoxOperationsException {
		
		Criteria criteria = Criteria.where(field).is(searchValue);
		Query query = new Query(criteria);
		return search(cls, query, page);
		
	}

	@Override
	@Cacheable("searchByFieldPerfix")
	public <T> SearchResult<T> searchByFieldPerfix(Class<T> cls, String field, Object searchPrefix) throws SearchBoxOperationsException {
		return searchByFieldPerfix(cls, field, searchPrefix, new Page(), false);
	}

	@Override
	@Cacheable("searchByFieldPerfix")
	public <T> SearchResult<T> searchByFieldPerfix(Class<T> cls, String field, Object searchPrefix, boolean allWords)
			throws SearchBoxOperationsException {
		return searchByFieldPerfix(cls, field, searchPrefix, new Page(), allWords);
	}

	@Override
	@Cacheable("searchByFieldPerfix")
	public <T> SearchResult<T> searchByFieldPerfix(Class<T> cls, String field, Object searchPrefix, Page page)
			throws SearchBoxOperationsException {
		return searchByFieldPerfix(cls, field, searchPrefix, page, false);
	}

	@Override
	@Cacheable("searchByFieldPerfix")
	public <T> SearchResult<T> searchByFieldPerfix(Class<T> cls, String field, Object searchPrefix, Page page,
			boolean allWords) throws SearchBoxOperationsException {
		
		Criteria criteria = Criteria.where(field).searchByPrefix(String.valueOf(searchPrefix));
		Query query = new Query(criteria);
		return search(cls, query, page);

	}
	
	@Override
	@Cacheable("searchByFieldPattern")
	public <T> SearchResult<T> searchByFieldPattern(Class<T> cls, String field, String pattern) throws SearchBoxOperationsException {
		return searchByFieldPattern(cls, field, pattern, new Page());
	}

	@Override
	@Cacheable("searchByFieldPattern")
	public <T> SearchResult<T> searchByFieldPattern(Class<T> cls, String field, String pattern, Page page)
			throws SearchBoxOperationsException {
		
		Criteria criteria = Criteria.where(field).searchByPattern(pattern);
		Query query = new Query(criteria);
		return search(cls, query, page);
		
	}

	@Override
	public Object runScriptByEvalsha(String name, List<String> keys, List<String> argv) throws SearchBoxOperationsException {

		try {
			return scriptProcessor.runScriptByEvalsha(name, keys, argv);
		} catch (Exception e) {
			throw new SearchBoxOperationsException(e.getMessage(), e);
		}
	}

	@Override
	public String saveScript(String name, String script) throws SearchBoxOperationsException {

		try {
			return scriptProcessor.saveScript(name, script);
		} catch (Exception e) {
			throw new SearchBoxOperationsException(e.getMessage(), e);
		}
	}

	/**
	 * remove all elements related to this app
	 */
	@Override
	public void clearAllForApp() throws SearchBoxOperationsException {

		try {
			modelDataRedisCaller.clearAllForApp();
		} catch (Exception e) {
			throw new SearchBoxOperationsException(e.getMessage(), e);
		}
	}
	
	private double getCurrentTimeMillis(){
		return System.nanoTime()/1e6;
	}
	
	private double getTimeDifference(double start_time, double end_time){
		return end_time - start_time;
	}
	
	private String prepareTimeDifference(double time_diff){
		return (int) time_diff + " ms";
	}

}
