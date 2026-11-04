// Compile-time signatures of the current Android org.json, whose keys() is generic. The harness runs on
// the AOSP implementation from deps.lock, which has the same method descriptors.
package org.json;
public class JSONObject {
public static final Object NULL = null;
public JSONObject() {}
public JSONObject(java.util.Map m) {}
public JSONObject(String s) throws JSONException {}
public int length() { throw e(); }
public JSONObject put(String n, boolean v) throws JSONException { throw e(); }
public JSONObject put(String n, double v) throws JSONException { throw e(); }
public JSONObject put(String n, int v) throws JSONException { throw e(); }
public JSONObject put(String n, long v) throws JSONException { throw e(); }
public JSONObject put(String n, Object v) throws JSONException { throw e(); }
public Object remove(String n) { throw e(); }
public boolean isNull(String n) { throw e(); }
public boolean has(String n) { throw e(); }
public Object get(String n) throws JSONException { throw e(); }
public Object opt(String n) { throw e(); }
public boolean getBoolean(String n) throws JSONException { throw e(); }
public boolean optBoolean(String n) { throw e(); }
public boolean optBoolean(String n, boolean f) { throw e(); }
public int getInt(String n) throws JSONException { throw e(); }
public int optInt(String n) { throw e(); }
public int optInt(String n, int f) { throw e(); }
public long getLong(String n) throws JSONException { throw e(); }
public long optLong(String n) { throw e(); }
public long optLong(String n, long f) { throw e(); }
public String getString(String n) throws JSONException { throw e(); }
public String optString(String n) { throw e(); }
public String optString(String n, String f) { throw e(); }
public JSONArray getJSONArray(String n) throws JSONException { throw e(); }
public JSONArray optJSONArray(String n) { throw e(); }
public JSONObject getJSONObject(String n) throws JSONException { throw e(); }
public JSONObject optJSONObject(String n) { throw e(); }
public java.util.Iterator<String> keys() { throw e(); }
public JSONArray names() { throw e(); }
public String toString(int i) throws JSONException { throw e(); }
private static UnsupportedOperationException e() { return new UnsupportedOperationException(); }
}
