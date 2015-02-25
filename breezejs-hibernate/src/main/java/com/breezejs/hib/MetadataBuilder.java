package com.breezejs.hib;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.*;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.id.Assigned;
import org.hibernate.id.ForeignGenerator;
import org.hibernate.id.IdentifierGenerator;
import org.hibernate.id.IdentityGenerator;
import org.hibernate.mapping.*;
import org.hibernate.metadata.*;
import org.hibernate.persister.collection.AbstractCollectionPersister;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.Joinable;
import org.hibernate.service.internal.SessionFactoryServiceRegistryImpl;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.hibernate.tuple.entity.EntityMetamodel;
import org.hibernate.type.*;

import com.breezejs.metadata.Metadata;

/**
 * Builds a data structure containing the metadata required by Breeze.
 * 
 * @see http://www.breezejs.com/documentation/breeze-metadata-format
 * @author Steve
 *
 */
public class MetadataBuilder {

    private SessionFactory _sessionFactory;
    private Configuration _configuration;
    private Metadata _map;
    private List<HashMap<String, Object>> _typeList;
    private HashMap<String, Object> _resourceMap;
    private HashSet<String> _typeNames;
    private HashMap<String, String> _fkMap;

    public MetadataBuilder(SessionFactory sessionFactory,
            Configuration configuration) {
        _sessionFactory = sessionFactory;
        _configuration = configuration;
    }

    public MetadataBuilder(SessionFactory sessionFactory) {
        _sessionFactory = sessionFactory;
        _configuration = getConfigurationFromRegistry(sessionFactory);
    }

    /**
     * Extract the Configuration from the ServiceRegistry exposed by
     * SessionFactoryImpl. Works in Hibernate 4.3, but will probably break in a
     * future version as they keep trying to make the configuration harder to
     * access (for some reason). Hopefully they will provide a interface to get
     * the full mapping data again.
     * 
     * @param sessionFactory
     */
    Configuration getConfigurationFromRegistry(SessionFactory sessionFactory) {
        ServiceRegistryImplementor serviceRegistry = ((SessionFactoryImplementor) _sessionFactory)
                .getServiceRegistry();

        SessionFactoryServiceRegistryImpl impl = (SessionFactoryServiceRegistryImpl) serviceRegistry;
        Configuration cfg = null;

        try {
            Field configurationField = SessionFactoryServiceRegistryImpl.class
                    .getDeclaredField("configuration");
            configurationField.setAccessible(true);
            Object configurationObject = configurationField.get(impl);
            cfg = (Configuration) configurationObject;

        } catch (Exception e) {
            e.printStackTrace();
        }
        if (cfg == null) {
            throw new RuntimeException(
                    "Unable to get the Configuration from the service registry.  Please provide the Configuration in the constructor.");
        }

        return cfg;
    }

    /**
     * Build the Breeze metadata as a nested HashMap. The result can be
     * converted to JSON and sent to the Breeze client.
     */
    public Metadata buildMetadata() {
        initMap();

        Map<String, ClassMetadata> classMeta = _sessionFactory
                .getAllClassMetadata();
        // Map<String, ICollectionMetadata> collectionMeta =
        // _sessionFactory.GetAllCollectionMetadata();

        for (ClassMetadata meta : classMeta.values()) {
            addClass(meta);
        }

        return _map;
    }

    /**
     * Populate the metadata header.
     */
    void initMap() {
        _map = new Metadata();
        _typeList = new ArrayList<HashMap<String, Object>>();
        _typeNames = new HashSet<String>();
        _resourceMap = new HashMap<String, Object>();
        _fkMap = new HashMap<String, String>();
        _map.put("localQueryComparisonOptions", "caseInsensitiveSQL");
        _map.put("structuralTypes", _typeList);
        _map.put("resourceEntityTypeMap", _resourceMap);
        _map.foreignKeyMap = _fkMap;
    }

    /**
     * Add the metadata for an entity.
     * 
     * @param meta
     */
    void addClass(ClassMetadata meta) {
        Class type = meta.getMappedClass();

        String classKey = getEntityTypeName(type);
        HashMap<String, Object> cmap = new LinkedHashMap<String, Object>();
        _typeList.add(cmap);

        cmap.put("shortName", type.getSimpleName());
        cmap.put("namespace", type.getPackage().getName());

        EntityMetamodel metaModel = ((EntityPersister) meta)
                .getEntityMetamodel();
        String superTypeName = metaModel.getSuperclass();
        if (superTypeName != null) {
            ClassMetadata superMeta = _sessionFactory
                    .getClassMetadata(superTypeName);
            if (superMeta != null) {
                Class superClass = superMeta.getMappedClass();
                cmap.put("baseTypeName", getEntityTypeName(superClass));
            }
        }

        String genType = "None";
        if (meta instanceof EntityPersister) {
            EntityPersister entityPersister = (EntityPersister) meta;
            // multipart keys can never have an AutoGeneratedKeyType
            if (entityPersister.hasIdentifierProperty()) {
                IdentifierGenerator generator = entityPersister != null ? entityPersister
                        .getIdentifierGenerator() : null;
                if (generator != null) {
                    if (generator instanceof IdentityGenerator)
                        genType = "Identity";
                    else if (generator instanceof Assigned
                            || generator instanceof ForeignGenerator)
                        genType = "None";
                    else
                        genType = "KeyGenerator";
                    // TODO find the real generator
                }
            }
        }
        cmap.put("autoGeneratedKeyType", genType);

        String resourceName = pluralize(type.getSimpleName()); // TODO find the
                                                               // real name
        cmap.put("defaultResourceName", resourceName);
        _resourceMap.put(resourceName, classKey);

        ArrayList<HashMap<String, Object>> dataArrayList = new ArrayList<HashMap<String, Object>>();
        cmap.put("dataProperties", dataArrayList);
        ArrayList<HashMap<String, Object>> navArrayList = new ArrayList<HashMap<String, Object>>();
        cmap.put("navigationProperties", navArrayList);

        addClassProperties(meta, dataArrayList, navArrayList);
    }

    /**
     * Add the properties for an entity.
     * 
     * @param meta
     * @param pClass
     * @param dataArrayList
     *            - will be populated with the data properties of the entity
     * @param navArrayList
     *            - will be populated with the navigation properties of the
     *            entity
     */
    void addClassProperties(ClassMetadata meta,
            ArrayList<HashMap<String, Object>> dataArrayList,
            ArrayList<HashMap<String, Object>> navArrayList) {
        // maps column names to their related data properties. Used in
        // MakeAssociationProperty to convert FK column names to entity property
        // names.
        HashMap<String, HashMap<String, Object>> relatedDataPropertyMap = new HashMap<String, HashMap<String, Object>>();

        AbstractEntityPersister persister = (AbstractEntityPersister) meta;
        Class type = meta.getMappedClass();
        HashSet<String> inheritedProperties = getSuperProperties(persister);

        String[] propNames = meta.getPropertyNames();
        Type[] propTypes = meta.getPropertyTypes();

        PersistentClass persistentClass = _configuration.getClassMapping(type
                .getName());

        boolean[] propNull = meta.getPropertyNullability();
        for (int i = 0; i < propNames.length; i++) {
            String propName = propNames[i];
            if (inheritedProperties.contains(propName))
                continue; // skip property defined on superclass

            Type propType = propTypes[i];
            if (!propType.isAssociationType()) // skip association types until
                                               // we handle all the data types,
                                               // so the relatedDataPropertyMap
                                               // will be populated.
            {
                ArrayList<Selectable> propColumns = getColumns(persistentClass
                        .getProperty(propName));
                if (propType.isComponentType()) {
                    // complex type
                    ComponentType compType = (ComponentType) propType;
                    String complexTypeName = addComponent(compType, propColumns);
                    HashMap<String, Object> compMap = new HashMap<String, Object>();
                    compMap.put("nameOnServer", propName);
                    compMap.put("complexTypeName", complexTypeName);
                    compMap.put("isNullable", propNull[i]);
                    dataArrayList.add(compMap);
                } else {
                    // data property
                    boolean isKey = meta.hasNaturalIdentifier()
                            && contains(meta.getNaturalIdentifierProperties(),
                                    i);
                    boolean isVersion = meta.isVersioned()
                            && i == meta.getVersionProperty();

                    Column col = (Column) propColumns.get(0);
                    HashMap<String, Object> dmap = makeDataProperty(propName,
                            propType, col, propNull[i], isKey, isVersion);
                    dataArrayList.add(dmap);

                    String columnNameString = getPropertyColumnNames(persister,
                            propName, propType);
                    relatedDataPropertyMap.put(columnNameString, dmap);
                }
            }
        }

        // Hibernate identifiers are excluded from the list of data properties,
        // so we have to add them separately
        if (meta.hasIdentifierProperty()
                && !inheritedProperties.contains(meta
                        .getIdentifierPropertyName())) {
            Property property = persistentClass.getProperty(meta
                    .getIdentifierPropertyName());
            ArrayList<Selectable> propColumns = getColumns(property);
            Column col = (Column) propColumns.get(0);

            HashMap<String, Object> dmap = makeDataProperty(
                    meta.getIdentifierPropertyName(), meta.getIdentifierType(),
                    col, false, true, false);
            dataArrayList.add(0, dmap);

            String columnNameString = getPropertyColumnNames(persister,
                    meta.getIdentifierPropertyName(), meta.getIdentifierType());
            relatedDataPropertyMap.put(columnNameString, dmap);
        } else if (meta.getIdentifierType() != null
                && meta.getIdentifierType().isComponentType()) {
            // composite key is a ComponentType
            ComponentType compType = (ComponentType) meta.getIdentifierType();

            // check that the component belongs to this class, not a superclass
            if (compType.getReturnedClass() == type
                    || meta.getIdentifierPropertyName() == null
                    || !inheritedProperties.contains(meta
                            .getIdentifierPropertyName())) {
                String[] compNames = compType.getPropertyNames();
                for (int i = 0; i < compNames.length; i++) {
                    String compName = compNames[i];

                    Type propType = compType.getSubtypes()[i];
                    if (!propType.isAssociationType()) {
                        Property property = persistentClass
                                .getProperty(compName);
                        ArrayList<Selectable> propColumns = getColumns(property);
                        Column col = (Column) propColumns.get(0);
                        HashMap<String, Object> dmap = makeDataProperty(
                                compName, propType, col,
                                compType.getPropertyNullability()[i], true,
                                false);
                        dataArrayList.add(0, dmap);
                    } else {
                        String propColumnNames = getPropertyColumnNames(
                                persister, compName, propType);

                        HashMap<String, Object> assProp = makeAssociationProperty(
                                type, (AssociationType) propType, compName,
                                propColumnNames, relatedDataPropertyMap, true);
                        navArrayList.add(assProp);
                    }
                }
            }
        }

        // We do the association properties after the data properties, so we can
        // do the foreign key lookups
        for (int i = 0; i < propNames.length; i++) {
            String propName = propNames[i];
            if (inheritedProperties.contains(propName))
                continue; // skip property defined on superclass

            Type propType = propTypes[i];
            if (propType.isAssociationType()) {
                // navigation property
                String propColumnNames = getPropertyColumnNames(persister,
                        propName, propType);
                HashMap<String, Object> assProp = makeAssociationProperty(type,
                        (AssociationType) propType, propName, propColumnNames,
                        relatedDataPropertyMap, false);
                navArrayList.add(assProp);
            }
        }
    }

    /**
     * Return names of all properties that are defined in the mapped ancestors
     * of the given persister. Note that unmapped superclasses are deliberately
     * ignored, because they shouldn't affect the metadata.
     * 
     * @param persister
     * @return set of property names. Empty if the persister doesn't have a
     *         superclass.
     */
    HashSet<String> getSuperProperties(AbstractEntityPersister persister) {
        HashSet<String> set = new HashSet<String>();
        String superClassName = persister.getMappedSuperclass();
        if (superClassName == null)
            return set;

        ClassMetadata superMeta = _sessionFactory
                .getClassMetadata(superClassName);
        if (superMeta == null)
            return set;

        String[] superProps = superMeta.getPropertyNames();
        set.addAll(Arrays.asList(superProps));
        set.add(superMeta.getIdentifierPropertyName());
        return set;
    }

    ArrayList<Selectable> getColumns(Property pClassProp) {
        Iterator iter = pClassProp.getColumnIterator();
        ArrayList<Selectable> list = new ArrayList<Selectable>();
        while (iter.hasNext())
            list.add((Selectable) iter.next());
        return list;
    }

    boolean contains(int[] array, int x) {
        for (int j = 0; j < array.length; j++) {
            if (array[j] == x)
                return true;
        }
        return false;
    }

    boolean contains(String[] array, String x) {
        for (int j = 0; j < array.length; j++) {
            if (array[j].equals(x))
                return true;
        }
        return false;
    }

    /**
     * Adds a complex type definition
     * 
     * @param compType
     *            - The complex type
     * @param propColumns
     *            - The columns which the complex type spans. These are used to
     *            get length and defaultValues.
     * @return The class name and namespace of the component.
     */
    String addComponent(ComponentType compType, List<Selectable> propColumns) {
        Class type = compType.getReturnedClass();

        // "Location:#com.breezejs.model"
        String classKey = getEntityTypeName(type);
        if (_typeNames.contains(classKey)) {
            // Only add a complex type definition once.
            return classKey;
        }

        HashMap<String, Object> cmap = new LinkedHashMap<String, Object>();
        _typeList.add(0, cmap);
        _typeNames.add(classKey);

        cmap.put("shortName", type.getSimpleName());
        cmap.put("namespace", type.getPackage().getName());
        cmap.put("isComplexType", true);

        ArrayList<HashMap<String, Object>> dataArrayList = new ArrayList<HashMap<String, Object>>();
        cmap.put("dataProperties", dataArrayList);

        String[] propNames = compType.getPropertyNames();
        Type[] propTypes = compType.getSubtypes();
        boolean[] propNull = compType.getPropertyNullability();

        int colIndex = 0;
        for (int i = 0; i < propNames.length; i++) {
            Type propType = propTypes[i];
            String propName = propNames[i];
            if (propType.isComponentType()) {
                // nested complex type
                ComponentType compType2 = (ComponentType) propType;
                int span = compType2.getColumnSpan((Mapping) _sessionFactory);
                List<Selectable> subColumns = propColumns.subList(colIndex,
                        colIndex + span);
                String complexTypeName = addComponent(compType2, subColumns);
                HashMap<String, Object> compMap = new HashMap<String, Object>();
                compMap.put("nameOnServer", propName);
                compMap.put("complexTypeName", complexTypeName);
                compMap.put("isNullable", propNull[i]);
                dataArrayList.add(compMap);
                colIndex += span;
            } else {
                // data property
                Column col = (Column) propColumns.get(colIndex);
                HashMap<String, Object> dmap = makeDataProperty(propName,
                        propType, col, propNull[i], false, false);
                dataArrayList.add(dmap);
                colIndex++;
            }
        }
        return classKey;
    }

    /**
     * Make data property metadata for the entity
     * 
     * @param propName
     *            - name of the property on the server
     * @param type
     *            - data type of the property, e.g. Int32
     * @param col
     *            - the Column for this property; used for length and default
     *            value
     * @param isNullable
     *            - whether the property is nullable in the database
     * @param isKey
     *            - true if this property is part of the key for the entity
     * @param isVersion
     *            - true if this property contains the version of the entity
     *            (for a concurrency strategy)
     * @return data property definition
     */
    private HashMap<String, Object> makeDataProperty(String propName,
            Type type, Column col, boolean isNullable, boolean isKey,
            boolean isVersion) {
        String newType = BreezeTypeMap.get(type.getName().toLowerCase());
        String typeName = newType != null ? newType : type.getName();

        HashMap<String, Object> dmap = new LinkedHashMap<String, Object>();
        dmap.put("nameOnServer", propName);
        dmap.put("dataType", typeName);
        dmap.put("isNullable", isNullable);

        if (col != null && col.getDefaultValue() != null) {
            dmap.put("defaultValue", col.getDefaultValue());
        }
        if (isKey) {
            dmap.put("isPartOfKey", true);
        }
        if (isVersion) {
            dmap.put("concurrencyMode", "Fixed");
        }

        ArrayList<HashMap<String, String>> validators = new ArrayList<HashMap<String, String>>();

        if (!isNullable) {
            validators.add(newMap("name", "required"));
        }
        if ((!NoLength.contains(typeName)) && col != null && col.getLength() > 0) {
            dmap.put("maxLength", col.getLength());
            validators.add(newMap("maxLength",
                    Integer.toString(col.getLength()), "name", "maxLength"));
        }

        String validationType = ValidationTypeMap.get(typeName);
        if (validationType != null) {
            validators.add(newMap("name", validationType));
        }

        if (!validators.isEmpty())
            dmap.put("validators", validators);

        return dmap;
    }

    /**
     * Make a HashMap populated with the given key and value.
     * 
     * @param key
     * @param value
     * @return
     */
    HashMap<String, String> newMap(String key, String value) {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put(key, value);
        return map;
    }

    HashMap<String, String> newMap(String key, String value, String key2,
            String value2) {
        HashMap<String, String> map = newMap(key, value);
        map.put(key2, value2);
        return map;
    }

    /**
     * Make association property metadata for the entity. Also populates the
     * _fkMap which is used for related-entity fixup when saving.
     * 
     * @param containingType
     * @param propType
     * @param propName
     * @param columnNames
     * @param pClass
     * @param relatedDataPropertyMap
     * @param isKey
     * @return association property definition
     */
    private HashMap<String, Object> makeAssociationProperty(
            Class containingType, AssociationType propType, String propName,
            String columnNames,
            HashMap<String, HashMap<String, Object>> relatedDataPropertyMap,
            boolean isKey) {
        HashMap<String, Object> nmap = new LinkedHashMap<String, Object>();
        nmap.put("nameOnServer", propName);

        Class relatedEntityType = getEntityType(propType);
        nmap.put("entityTypeName", getEntityTypeName(relatedEntityType));
        nmap.put("isScalar", !propType.isCollectionType());

        // the associationName must be the same at both ends of the association.
        nmap.put(
                "associationName",
                getAssociationName(containingType.getSimpleName(),
                        relatedEntityType.getSimpleName(), columnNames));

        if (propType.isCollectionType()) {
            // inverse foreign key
            Joinable joinable = propType
                    .getAssociatedJoinable((SessionFactoryImplementor) this._sessionFactory);
            if (joinable instanceof AbstractCollectionPersister) {
                // many-to-many relationships do not have a direct connection on
                // the client or in metadata
                AbstractEntityPersister elementPersister = (AbstractEntityPersister) ((AbstractCollectionPersister) joinable)
                        .getElementPersister();
                if (elementPersister != null) {
                    String joinProp = getPropertyNameForColumn(
                            elementPersister, columnNames);
                    if (joinProp != null) {
                        nmap.put("invForeignKeyNamesOnServer",
                                new String[] { joinProp });
                    }
                }
            }
        } else {
            // Not a collection type - a many-to-one or one-to-one association
            // Look up the related foreign key name using the column name
            String fkName = null;
            Map<String, Object> relatedDataProperty = relatedDataPropertyMap
                    .get(columnNames);

            if (relatedDataProperty != null) {
                fkName = (String) relatedDataProperty.get("nameOnServer");
                if (propType.getForeignKeyDirection() == ForeignKeyDirection.FOREIGN_KEY_FROM_PARENT) {
                    nmap.put("foreignKeyNamesOnServer", new String[] { fkName });
                } else {
                    nmap.put("invForeignKeyNamesOnServer",
                            new String[] { fkName });
                }
            }

            // For many-to-one and one-to-one associations, save the
            // relationship in _fkMap for re-establishing relationships during
            // save
            String entityRelationship = containingType.getName() + '.'
                    + propName;
            if (relatedDataProperty != null) {
                _fkMap.put(entityRelationship, fkName);
                if (isKey) {
                    if (!relatedDataProperty.containsKey("isPartOfKey")) {
                        relatedDataProperty.put("isPartOfKey", true);
                    }
                }
            } else {
                nmap.put("foreignKeyNamesOnServer", columnNames);
                nmap.put("ERROR", "Could not find matching fk for property "
                        + entityRelationship);
                _fkMap.put(entityRelationship, columnNames);
                throw new IllegalArgumentException(
                        "Could not find matching fk for property "
                                + entityRelationship);
            }
        }

        return nmap;
    }

    /**
     * Get the type name in the form "Order:#northwind.model"
     * 
     * @param clazz
     * @return
     */
    String getEntityTypeName(Class clazz) {
        // return clazz.getName();
        return clazz.getSimpleName() + ":#" + clazz.getPackage().getName();
    }

    /**
     * Get the column names for a given property as a comma-delimited String of
     * unbracketed, lowercase names. For a collection property, the column name
     * is the inverse foreign key (i.e. the column on the other table that
     * points back to the persister's table)
     */
    String getPropertyColumnNames(AbstractEntityPersister persister,
            String propertyName, Type propType) {
        String[] propColumnNames = null;
        if (propType.isCollectionType()) {
            propColumnNames = ((CollectionType) propType)
                    .getAssociatedJoinable(
                            (SessionFactoryImplementor) this._sessionFactory)
                    .getKeyColumnNames();
        } else {
            propColumnNames = persister.getPropertyColumnNames(propertyName);
        }
        if (propColumnNames == null || propColumnNames.length == 0) {
            // this happens when the property is part of the key
            propColumnNames = persister.getKeyColumnNames();
        }
        return catColumnNames(propColumnNames);
    }

    /**
     * Gets the simple (non-Entity) property that has the given columns
     * 
     * @param persister
     * @param columnNames
     *            - Comma-delimited column name string
     * @return
     */
    String getPropertyNameForColumn(AbstractEntityPersister persister,
            String columnNames) {
        String[] propNames = persister.getPropertyNames();
        Type[] propTypes = persister.getPropertyTypes();
        for (int i = 0; i < propNames.length; i++) {
            String propName = propNames[i];
            Type propType = propTypes[i];
            if (propType.isAssociationType())
                continue;
            String[] columnArray = persister.getPropertyColumnNames(i);
            String columns = catColumnNames(columnArray);
            if (columns == columnNames)
                return propName;
        }
        return persister.getIdentifierPropertyName();
    }

    /**
     * Unbrackets the column names and concatenates them into a comma-delimited
     * string
     */
    String catColumnNames(String[] columnNames) {
        StringBuilder sb = new StringBuilder();
        for (String s : columnNames) {
            if (sb.length() > 0)
                sb.append(',');
            sb.append(unBracket(s));
        }
        return sb.toString().toLowerCase();
    }

    /**
     * Get the column name without square brackets or quotes around it. E.g.
     * "[OrderID]" -> OrderID Because sometimes Hibernate gives us brackets, and
     * sometimes it doesn't. Double-quotes happen with SQL CE. Backticks happen
     * with MySQL.
     */
    String unBracket(String name) {
        name = (name.charAt(0) == '[') ? name.substring(1, name.length() - 1)
                : name;
        name = (name.charAt(0) == '"') ? name.substring(1, name.length() - 1)
                : name;
        name = (name.charAt(0) == '`') ? name.substring(1, name.length() - 1)
                : name;
        return name;
    }

    /**
     * Get the Breeze name of the entity type. For collections, Breeze expects
     * the name of the element type.
     * 
     * @param propType
     * @return
     */
    Class getEntityType(AssociationType propType) {
        if (!propType.isCollectionType())
            return propType.getReturnedClass();
        CollectionType collType = (CollectionType) propType;

        Type elementType = collType
                .getElementType((SessionFactoryImplementor) _sessionFactory);
        return elementType.getReturnedClass();
    }

    /**
     * Lame pluralizer. Assumes we just need to add a suffix.
     */
    String pluralize(String s) {
        if (s == null || s.isEmpty())
            return s;
        int last = s.length() - 1;
        char c = s.charAt(last);
        switch (c) {
        case 'y':
            return s.substring(0, last) + "ies";
        default:
            return s + 's';
        }
    }

    /**
     * Creates an association name from two entity names. For consistency, puts
     * the entity names in alphabetical order.
     * 
     * @param name1
     * @param name2
     * @param columnNames
     *            - name of the column(s) on the child entity
     * @return
     */
    String getAssociationName(String name1, String name2, String columnNames) {
        columnNames = columnNames.replace(',', '_');
        if (name1.compareTo(name2) < 0)
            return ASSN + name1 + '_' + name2 + '_' + columnNames;
        else
            return ASSN + name2 + '_' + name1 + '_' + columnNames;
    }

    static final String ASSN = "AN_";

    // Map of Hibernate datatype to Breeze datatype.
    static HashMap<String, String> BreezeTypeMap;

    // Map of data type to Breeze validation type
    static HashMap<String, String> ValidationTypeMap;

    // Set of Breeze types which don't need a maxlength validation
    static HashSet<String> NoLength;

    static {
        BreezeTypeMap = new HashMap<String, String>();
        BreezeTypeMap.put("byte[]", "Binary");
        BreezeTypeMap.put("binary", "Binary");
        BreezeTypeMap.put("binaryblob", "Binary");
        BreezeTypeMap.put("blob", "Binary");
        BreezeTypeMap.put("timestamp", "DateTime");
        BreezeTypeMap.put("timeastimespan", "Time");
        BreezeTypeMap.put("short", "Int16");
        BreezeTypeMap.put("integer", "Int32");
        BreezeTypeMap.put("long", "Int64");
        BreezeTypeMap.put("boolean", "Boolean");
        BreezeTypeMap.put("byte", "Byte");
        BreezeTypeMap.put("datetime", "DateTime");
        BreezeTypeMap.put("date", "DateTime");
        BreezeTypeMap.put("datetimeoffset", "DateTimeOffset");
        BreezeTypeMap.put("big_decimal", "Decimal");
        BreezeTypeMap.put("double", "Double");
        BreezeTypeMap.put("float", "Single");
        BreezeTypeMap.put("uuid", "Guid");
        BreezeTypeMap.put("uuid-char", "Guid");
        BreezeTypeMap.put("uuid-binary", "Guid");
        BreezeTypeMap.put("string", "String");
        BreezeTypeMap.put("time", "Time");

        NoLength = new HashSet<String>();
        NoLength.add("Byte");
        NoLength.add("Binary");
        NoLength.add("Int16");
        NoLength.add("Int32");
        NoLength.add("Int64");
        NoLength.add("DateTime");
        NoLength.add("DateTimeOffset");
        NoLength.add("Time");
        NoLength.add("Boolean");
        NoLength.add("Guid");
        NoLength.add("Double");
        NoLength.add("Single");
        NoLength.add("Decimal");

        ValidationTypeMap = new HashMap<String, String>();
        ValidationTypeMap.put("Boolean", "bool");
        ValidationTypeMap.put("Byte", "byte");
        ValidationTypeMap.put("DateTime", "date");
        ValidationTypeMap.put("DateTimeOffset", "date");
        ValidationTypeMap.put("Decimal", "number");
        ValidationTypeMap.put("Double", "number");
        ValidationTypeMap.put("Single", "number");
        ValidationTypeMap.put("Guid", "guid");
        ValidationTypeMap.put("Int16", "int16");
        ValidationTypeMap.put("Int32", "int32");
        ValidationTypeMap.put("Int64", "int64");
        ValidationTypeMap.put("Float", "number");
        // ValidationTypeMap.put("String", "string");
        ValidationTypeMap.put("Time", "duration");

    }

}
