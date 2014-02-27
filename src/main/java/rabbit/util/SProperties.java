package rabbit.util;

import java.util.HashMap;

/** A simple string properties class. 
 */
public class SProperties extends HashMap<String, String> {
    private static final long serialVersionUID = 20050430;

    /** Get the property for a given key
     * @param key the property to get
     * @return the property or null if the key does not exists in
     *         this properties.
     */
    public String getProperty (final String key) {
	return get (key);
    }

    /** Get the property for a given key
     * @param key the property to get
     * @param defaultValue the value to use if the key was not found
     *        or if the value was null.
     * @return the property value
     */
    public String getProperty (final String key, final String defaultValue) {
	final String val = get (key);
	if (val == null)
	    return defaultValue;
	return val;
    }
	
	/** Overridden so if we add a null value, it actually removes it
	 *  from the hashmap
	 *  @param key the property to get
	 *  @param value the value to set, or null to remove a value
	 *  @return the previous value, if any
	 */
	public String put (final String key, final String value) {
		if(value == null){
			return remove(key);
		}else{
			return super.put(key, value);
		}
	}
}
