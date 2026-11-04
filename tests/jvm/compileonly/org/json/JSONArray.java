// Compile-time signatures of the current Android org.json, whose keys() is generic. The harness runs on
// the AOSP implementation from deps.lock, which has the same method descriptors.
package org.json;
public class JSONArray {
public JSONArray() {}
public JSONArray(java.util.Collection c) {}
public JSONArray(String s) throws JSONException {}
public int length() { throw e(); }
public JSONArray put(boolean v) { throw e(); }
public JSONArray put(int v) { throw e(); }
public JSONArray put(long v) { throw e(); }
public JSONArray put(Object v) { throw e(); }
public Object get(int i) throws JSONException { throw e(); }
public Object opt(int i) { throw e(); }
public int getInt(int i) throws JSONException { throw e(); }
public long getLong(int i) throws JSONException { throw e(); }
public String getString(int i) throws JSONException { throw e(); }
public JSONObject getJSONObject(int i) throws JSONException { throw e(); }
private static UnsupportedOperationException e() { return new UnsupportedOperationException(); }
}
