// Compile-time signatures of the current Android org.json, whose keys() is generic. The harness runs on
// the AOSP implementation from deps.lock, which has the same method descriptors.
package org.json;
public class JSONException extends Exception { public JSONException(String s) { super(s); } }
