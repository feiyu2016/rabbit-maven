package rabbit.util;

// $Id: Config.java,v 1.7 2005/08/03 17:00:55 robo Exp $

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

/** a class to handle configs for different things. */
public abstract class Config {
    private final Map<String, SProperties> configs;

    protected Map<String, SProperties> getConfigs(){
        return configs;
    }

    /** create an empty Config (has only section "" with no data in it)
     */
    public Config () {
        configs = new HashMap<>();
        final SProperties current = new SProperties (); // the main thing.
        configs.put("", current);
        populateConfigs(configs);
    }
    /** Called during constructor to populate the configs object */
    protected abstract void populateConfigs(final Map<String, SProperties> configs);

    /** get the available sections
     * @return an Enumeration of the available sections (including the empty section).
     */
    public Collection<String> getSections () {
        return configs.keySet ();
    }

    /** get the properties for a given section
     * @param sectionName the section we want properties for.
     * @return a SProperties if section exist or null.
     */
    public SProperties getProperties (final String sectionName) {
        return configs.get (sectionName);
    }

    /** set the properties for a given section
     * @param sectionName the section we want to set the properties for.
     * @param prop the SProperties for the sections
     */
    public void setProperties (final String sectionName, final SProperties prop) {
        configs.put (sectionName, prop);
    }

    /** get a property for given key in specified section
     * @param section the section we should look in.
     * @param key the key we want a value for.
     * @return a string if section + key is set, null otherwise
     */
    public String getProperty (final String section, final String key) {
        return getProperty (section, key, null);
    }

    /** get a property for given key in specified section
     * @param section the section we should look in.
     * @param key the key we want a value for.
     * @param defaultstring the string to use if no value is found.
     * @return a string if section + key is set, null otherwise
     */
    public String getProperty (final String section, final String key, final String defaultstring) {
        final SProperties p = getProperties (section);
        if (p != null) {
            final String s = p.get (key);
            if (s != null) {
                return s;
            }
        }
        return defaultstring;
    }

    /** set a property for given section.
     * @param section the section we should look in.
     * @param key the key.
     * @param value the value.
     */
    public void setProperty (final String section, final String key, final String value) {
        SProperties p = getProperties (section);
        if (p == null) {
            p = new SProperties ();
            configs.put (section, p);
        }
        p.put (key, value);
    }

    /** Get a string describing this Config
     */
    @Override public String toString () {
        final StringBuilder res = new StringBuilder ();
        for (String section : configs.keySet ()) {
            res.append ('[');
            res.append (section);
            res.append (']');
            res.append ('\n');
            final SProperties pr = configs.get (section);
            for (Map.Entry<String, String> me : pr.entrySet ()) {
                final String key = me.getKey ();
                String value = me.getValue ();
                if (value == null) {
                    value = "";
                }
                final StringTokenizer st = new StringTokenizer (key, "=", true);
                while (st.hasMoreTokens ()) {
                    final String token = st.nextToken ();
                    if (token.equals ("=")) {
                        res.append('\\');
                    }
                    res.append (token);
                }
                res.append ('=');
                res.append (value);
                res.append ('\n');
            }
            res.append ('\n');
        }
        return res.toString ();
    }

    /** Merge this config with another one.
     *  that for every section/key in either of the two configs do:
     *  if this Config has the value use it otherwise use others value.
     * @param other the Config to merge with.
     */
    public void merge (final Config other) {
        for (String section : other.getSections ()) {
            final SProperties p = other.getProperties (section);
            if (p == null) {
                continue;
            }
            for (Map.Entry<String, String> me : p.entrySet ()) {
                final String key = me.getKey ();
                final String value = me.getValue ();
                final String merged = getProperty (section, key, value);
                setProperty (section, key, merged);
            }
        }
    }
}
