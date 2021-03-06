package com.breeze.test;

import java.util.Map;

import northwind.model.Role;
import junit.framework.TestCase;




import com.breeze.util.JsonGson;

@SuppressWarnings("unused")
public class JsonTest extends TestCase {
	
	protected void setUp() throws Exception {
		super.setUp();
 
	}
	
	public void testDeserializeEnum() {
	    String json = "{ id:-1.0, ts:null, name:'TEMPb5hbK', roleType:'Restricted'}";
	    Object roleObj =  JsonGson.fromJson(Role.class, json);
	    Role role = (Role) roleObj;
	}

	public void testDeserializeMap() {
	     String json = "{ freight: { '>': 100}, rowVersion: { lt: 10}, shippedDate: '2015-02-09T00:00:00' }";
	     Map map = JsonGson.fromJson(json);
	     Map freightMap = (Map) map.get("freight");
	     double freightVal = (double) freightMap.get(">");
	     assertTrue(freightVal == 100.0);
	     
	     Map rowVersionMap = (Map) map.get("rowVersion");
	     Object val = rowVersionMap.get("lt");
	     double rowVersionVal = (double) val;
	     assertTrue((int) rowVersionVal == 10);
	     
	     Double rowVersionVal2 = (Double) val;
	     assertTrue(rowVersionVal2.intValue() == 10);
	     
	     
	     Map foo = (Map) map.get("foo");
	     assertTrue(foo == null);
	     Integer fooInt = (Integer) map.get("foo");
	     assertTrue(fooInt == null);
	     
	}
	
	public void testDeserialize2() {
		String jsFrom = "resourceName: 'foo'";
		// String jsWhere = "'where': { 'freight': { '>': 100}, 'rowVersion': { 'lt': 10}, 'shippedDate': '2015-02-09T00:00:00' }";
		String jsWhere = "where: { freight: { '>': 100}, rowVersion: { lt: 10}, shippedDate: '2015-02-09T00:00:00' }";
		String json = "{" + jsFrom + "," + jsWhere + "}";
		Map map = JsonGson.fromJson(json);
		assertTrue(map != null);
	}
	
	
	public void testDeserializeArray() {
	     String json = " { where: [ { freight: { '>': 100}}, { rowVersion: { lt: 10} }] }";
	     Map map = JsonGson.fromJson(json);
//	     Map freightMap = (Map) map.get("freight");
//	     double freightVal = (double) freightMap.get(">");
//	     assertTrue(freightVal == 100.0);
//	     
//	     Map rowVersionMap = (Map) map.get("rowVersion");
//	     Object val = rowVersionMap.get("lt");
//	     double rowVersionVal = (double) val;
//	     assertTrue((int) rowVersionVal == 10);
//	     
//	     Double rowVersionVal2 = (Double) val;
//	     assertTrue(rowVersionVal2.intValue() == 10);
//	     
//	     
//	     Map foo = (Map) map.get("foo");
//	     assertTrue(foo == null);
//	     Integer fooInt = (Integer) map.get("foo");
//	     assertTrue(fooInt == null);
	     
	}
}
