package com.breeze.save;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.breeze.metadata.AutoGeneratedKeyType;
import com.breeze.metadata.Metadata;
import com.breeze.metadata.MetadataHelper;
import com.breeze.util.JsonGson;

public class SaveWorkState {

    // input
    private ContextProvider _contextProvider;
    private SaveOptions _saveOptions;
    private List<Map> _entityMaps;
    // calculated during beforeSaveEntity impl
    private Map<Class, List<EntityInfo>> _saveMap;
    private List<EntityInfo> _entitiesWithAutoGeneratedKeys;
    // output
    private List<KeyMapping> _keyMappings;
    private List<EntityError> _entityErrors;   

    public SaveWorkState(Map saveBundle) {
        this._saveOptions = new SaveOptions((Map) saveBundle.get("saveOptions"));
        this._entityMaps = (List<Map>) saveBundle.get("entities");
 
        this._saveMap = new HashMap<Class, List<EntityInfo>>();
        this._entitiesWithAutoGeneratedKeys = new ArrayList<EntityInfo>();
    }  
    
    public SaveOptions getSaveOptions() {
        return _saveOptions;
    }
    
    public Metadata getMetadata() {
        return _contextProvider.getMetadata();
    }
    
    public List<EntityInfo> getEntitiesWithAutoGeneratedKeys() {
        return _entitiesWithAutoGeneratedKeys;
    }
    
    public Map<Class, List<EntityInfo>> getSaveMap() {
        return _saveMap;
    }
    
    public void setContextProvider(ContextProvider contextProvider) {
        _contextProvider = contextProvider;
    }
    
    public void setKeyMappings(List<KeyMapping> keyMappings) {
        _keyMappings = keyMappings;
    }
    
    public void setEntityErrors(List<EntityError> entityErrors) {
        _entityErrors = entityErrors;
    }


    /** Build the saveMap, and call context.beforeSaveEntity/ies */
    protected void beforeSave() throws EntityErrorsException {
        for (Object o : _entityMaps) {
            EntityInfo entityInfo = createEntityInfoFromJson((Map) o);

            // don't put it in the saveMap if it was rejected by
            // beforeSaveEntity
            if (beforeSaveEntity(entityInfo)) {
                addToSaveMap(entityInfo);
            }
        }

        _saveMap = beforeSaveEntities(_saveMap);

    }
    
    protected void afterSave() throws EntityErrorsException {
        afterSaveEntities(_saveMap, _keyMappings);
    }
    
    // methods to override 
    /**
     * Allow subclasses to process each entity before it gets included in the saveMap.
     * Called when each EntityInfo is materialized (before beforeSaveEntities is called).
     * Base implementation always returns true.
     * @param entityInfo
     * @return true if the entity should be included in the saveMap, false if not.  
     */
    protected boolean beforeSaveEntity(EntityInfo entityInfo) throws EntityErrorsException {
        return true;
    }

    /**
     * Allow subclasses to process the saveMap before the entities are saved.
     * @param saveMap all entities that will be saved
     * @return saveMap, which may have entities added, changed, or removed.
     */
    protected Map<Class, List<EntityInfo>> beforeSaveEntities(
            Map<Class, List<EntityInfo>> saveMap) throws EntityErrorsException {
        return saveMap;
    }

    /**
     * Allows subclasses to process entities before they are saved.  This method is called
     * after BeforeSaveEntities(saveMap), and before any session.Save methods are called.
     * The foreign-key associations on the entities have been resolved, relating the entities
     * to each other, and attaching proxies for other many-to-one associations.
     * 
     * @param entitiesToPersist List of entities in the order they will be saved
     * @return The same entitiesToPersist.  Overrides of this method may modify the list.
     */
    public List<EntityInfo> beforeSaveEntityGraph(
            List<EntityInfo> entitiesToPersist) throws EntityErrorsException {
        return entitiesToPersist;
    }

    
    /**
     * Allow subclasses to process the saveMap after entities are saved (and temporary keys replaced)
     * @param saveMap all entities which have been saved
     * @param keyMappings mapping of temporary keys to real keys
     */
    protected void afterSaveEntities(Map<Class, List<EntityInfo>> saveMap, List<KeyMapping> keyMappings) throws EntityErrorsException {
    }
    
    /**
     * Allows subclasses to plug in their own exception handling.  
     * This method is called when saveChangesCore throws an exception.
     * Subclass implementations of this method should either:
     *  1. Throw an exception
     *  2. Return false (exception not handled)
     *  3. Return true (exception handled) and modify the SaveWorkState accordingly.
     * Base implementation returns false (exception not handled).
     * @param e Exception that was thrown by saveChangesCore
     * @return true (exception handled) or false (exception not handled)
     */
    public boolean handleException(Exception e) {
        return false;
    }

    public void addToSaveMap(EntityInfo entityInfo) {
        Class clazz = entityInfo.entity.getClass();

        List<EntityInfo> entityInfos = _saveMap.get(clazz);
        if (entityInfos == null) {
            entityInfos = new ArrayList<EntityInfo>();
            _saveMap.put(clazz, entityInfos);
        }
        if (entityInfo.entityType.getAutoGeneratedKeyType() != AutoGeneratedKeyType.None) {
            _entitiesWithAutoGeneratedKeys.add(entityInfo);
        };
        entityInfos.add(entityInfo);
    }
    
    public EntityInfo createEntityInfoForEntity(Object entity, EntityState entityState) {
        EntityInfo info = new EntityInfo();

        info.entity = entity;
        info.entityType = getMetadata().getEntityTypeForClass(entity.getClass());
        info.entityState = entityState;
        return info;
    }
    
    /**
     * Populate a new SaveResult with the entities and keyMappings. If there are
     * entityErrors, populate it with those instead.
     */
    public SaveResult toSaveResult() {
        if (_entityErrors != null) {
            return new SaveResult(_entityErrors);
        } else {
            List<Object> entities = new ArrayList<Object>();
            for (List<EntityInfo> infos : _saveMap.values()) {
                for (EntityInfo info : infos) {
                    entities.add(info.entity);
                }
            }
            return new SaveResult(entities, _keyMappings);
        }
    }



    /**
     * @param map
     *            raw name-value pairs from JSON
     * @return populated EntityInfo
     */
    private EntityInfo createEntityInfoFromJson(Map map) {
        EntityInfo info = new EntityInfo();

        Map aspect = (Map) map.get("entityAspect");
        map.remove("entityAspect");

        String entityTypeName = (String) aspect.get("entityTypeName");
        Class type = MetadataHelper.lookupClass(entityTypeName);
        info.entityType = getMetadata().getEntityType(entityTypeName);
        info.entity = JsonGson.fromMap(type, map);

        info.entityState = EntityState.valueOf((String) aspect
                .get("entityState"));
        info.originalValuesMap = (Map) aspect.get("originalValuesMap");
        info.unmappedValuesMap = (Map) aspect.get("unmappedValuesMap");
        // AutoGeneratedKey info from the client isn't needed because we have the metadata already on the server.  
//        Map autoKey = (Map) aspect.get("autoGeneratedKey");
//        if (autoKey != null) {
//            info.autoGeneratedKey = new AutoGeneratedKey(info.entity,
//                    (String) autoKey.get("propertyName"),
//                    (String) autoKey.get("autoGeneratedKeyType"));
//        }
        return info;
    }


}
