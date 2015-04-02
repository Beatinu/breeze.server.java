package com.breeze.save;

import java.util.Map;

import com.breeze.metadata.IEntityType;

public class EntityInfo {
	public Object entity;
	public EntityState entityState;
	public Map<String, Object> originalValuesMap;
	public Map<String, Object> unmappedValuesMap;
	public boolean forceUpdate;
	//	// AutoGeneratedKey info from the client isn't needed because we have the metadata already on the server.
	// public AutoGeneratedKey autoGeneratedKey;

	public IEntityType entityType;


}
