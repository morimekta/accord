/*
 * Created on 27.jan.2005
 */
package net.morimekta.util.std;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/**
 * Simple config file reader.
 * 
 * Follows the principle of:
 * <pre>
 *    key name = value
 * </pre>
 * and
 * <pre>
 *    [section name]
 * </pre>
 * 
 * This example would also represent the static value
 * <pre>
 * MyClass {
 *   public static Type section_name__key_name = value;
 * }
 * </pre>
 * 
 * or if no section name is given:
 * <pre>
 *   public static Type key_name = value;
 * </pre>
 * 
 * The keys and sections must still be named according to
 * java field naming scheme, but may contain spaces
 * which will be replaced with a single underscore ("_",
 * and only one regardless of the number of spaces).
 * 
 * @author Stein Eldar Johnsen
 */
public class Config {
    
    /**
     * Protected constructor prevents the instansiation of a pure config-object.
     *
     */
	protected Config(){
		// defeats local instantiation.
	}
    
    /**
     * Loads from the given file and into this class. This does not guarantee that it works,
     * as the class needs to conform to the name-rules of config files.
     * 
     * @param filename
     *     File to load configs from.
     * @throws FileNotFoundException
     *     If no such file excists.
     * @throws IllegalArgumentException
     *     If there is a parse problem with the config file content or
     *     fields.
     */
    public synchronized void loadFile(String filename)
            throws FileNotFoundException, IllegalArgumentException {
        loadFile(filename, getClass(), this);
    }
    
    /**
     * Loads a config-file into a class or object. Both is needed in case of static variables
     * and static classes.
     * 
     * @param filename
     *     File to load config from.
     * @param into
     *     Class to load into.
     * @param object
     *     Object to load into if there is non-static fields to load.
     * @throws FileNotFoundException
     *     If no such file exists.
     * @throws IllegalArgumentException
     *     If there is a parse problem with the config file content or
     *     fields.
     */
    public static synchronized void loadFile(String filename, Class<?> into, Object object )
            throws FileNotFoundException, IllegalArgumentException {
        File                file;
        FileReader          read;
        BufferedReader      buffer;
        
        file    = new File(filename);
        read    = new FileReader(file);
        buffer  = new BufferedReader(read);
        
        int      l, c;
        String   line;
        String[] words;
        String[] tmp;
        String   section;
        String   seckey;
        
        try {
            section = "";
            l = 0;
            while( ( line = buffer.readLine() ) != null ){
                l++;
                
                words = line.split("#", 2); // remove comments...
                if( words.length == 0 || words[0] == null ) continue;
                line  = words[0];
                
                words = line.split("=", 2); // split on first "equals".
                if( words.length == 0 ) continue; // empty...
                else {
                    if( words[0] == null ) {
                        throw new IllegalArgumentException(filename+": illegal line syntax: "+l);
                    } else {
                        // we have a "before"
                        words[0] = words[0].replaceAll("[\\s]", " "); // normalize on whitespace.
                        // and reduce multiple spaces to one space
                        c = words[0].length()+1;
                        while( words[0].length() < c ){
                            c = words[0].length();
                            words[0] = words[0].replaceAll("  ", " ");
                        }
                        // remove starting and ending whitespace...
                        if( Character.isWhitespace(words[0].charAt(0)) )
                            words[0] = words[0].substring(1); // remove first char.
                        if( Character.isWhitespace(words[0].charAt(words[0].length()-1)) )
                            words[0] = words[0].substring(0, words[0].length()-1); // remove last char.

                    }
                }
                // if there is an "other" side of the "=".
                if( words.length == 2 ) {
                    if( words[1] == null ) {
                        throw new IllegalArgumentException(filename+": illegal line syntax: "+l);
                    } else {
                        tmp = STD.splitString(words[1]);
                        if( tmp.length == 0 ) {
                            throw new IllegalArgumentException(filename+": illegal config line syntax: "+l);
                        } else if( tmp.length > 1 ){
                            throw new IllegalArgumentException(filename+": multiple values at line: "+l);
                        } else {
                            // we have a value...
                            words[1] = tmp[0];
                        }
                    }
                }
                
                // parse the line contents.
                
                if( words.length == 1 ) {
                    // lets first assume its a [section] line...
                    // if we find "["
                    if((c = words[0].indexOf("["))>=0) {
                        int j = words[0].indexOf("]");
                        if( j > (c+1) ){
                            section = words[0].substring(c+1, j);
                            if( Character.isWhitespace(section.charAt(0)) )
                                section = section.substring(1); // remove first char.
                            if( Character.isWhitespace(section.charAt(section.length()-1)) )
                                section = section.substring(0, section.length()-1); // remove last char.
                        } else {
                            throw new IllegalArgumentException(filename+": illegal section marker on line "+l);
                        }
                    } else {
                        if( words[0].length() == 0 ||
                            words[0].charAt(0) == ' ') continue; // only spaces
                        else // weird line...
                            throw new IllegalArgumentException(filename+": illegal config line syntax: "+l);
                    }
                    // end [section]
                } else {
                    // we have an "=", its a key-value line... (and both are non-null)
                    seckey = (section!=null?section+"  ":"")+words[0];
                    // replace all whitespace in sec_key with underscore.
                    seckey = seckey.replaceAll(" ", "_");
                    storeKeyValue(seckey, words[1], into, object);
                }
            }
        } catch(NullPointerException npe){
            throw new IllegalArgumentException("no class to store into");
        } catch(IOException ioe){
            throw new FileNotFoundException("io exception masking");
        } finally {
            try {
                read.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Tries to store a key=value pair into the class or object's field.
     * @param seckey
     *    Section and key in one string. This have to be pre-parsed to confirm with the naming
     *    convention of the config class.
     * @param value
     *    Value string to be stored.
     * @param into
     *    Class to store into.
     * @param object
     *    Object to store into if there is non-static fields.
     * @throws IllegalArgumentException
     *    If there is a parsing or storing problem.
     */
    private static synchronized void storeKeyValue(String seckey, String value, Class<?> into, Object object)
            throws IllegalArgumentException {
        // long_key consists of section name/key name ...
        Field          field       = null;
        Class<?>       type        = null;
        String         tmp;
        try {
            field    = into.getDeclaredField(seckey);
            type     = field.getType();
            if( type.isEnum()) {
                tmp = value.replaceAll("-", "_"); // - is invalid enum stuff... replace with _.
                field.set(object, Enum.valueOf((Class<? extends Enum>) type, tmp));
            }
            else if( type.isAssignableFrom(Integer.TYPE) )   field.setInt(object, Integer.parseInt(value));
            else if( type.isAssignableFrom(Long.TYPE) )     field.setLong(object, Long.parseLong(value));
            else if( type.isAssignableFrom(Float.TYPE) )   field.setFloat(object, Float.parseFloat(value));
            else if( type.isAssignableFrom(Double.TYPE) ) field.setDouble(object, Double.parseDouble(value));
            else if( type.isAssignableFrom(Byte.TYPE) )     field.setByte(object, Byte.parseByte(value));
            else if( type.isAssignableFrom(String.class) )      field.set(object, value);
            else if( type.isAssignableFrom(Character.TYPE) ) {
                tmp = value;
                if( tmp.length() == 2 ) {
                    tmp = tmp.replaceAll("\\t", "\t");
                    tmp = tmp.replaceAll("\\n", "\n");
                    tmp = tmp.replaceAll("\\r", "\r");
                    tmp = tmp.replaceAll("\\\"", "\"");
                    tmp = tmp.replaceAll("\\\'", "\'");
                }
                if( tmp.length() != 1 ) throw new NumberFormatException();
                field.setChar(object, tmp.charAt(0));
            }
            else if( type.isAssignableFrom(Boolean.TYPE) ){
                tmp = value.toLowerCase();
                tmp = tmp.replace("yes", "true");
                tmp = tmp.replace("on",  "true");
                tmp = tmp.replace("1",   "true");
                field.setBoolean(object, Boolean.valueOf(tmp));
            } else {
                // "unknown" type, lets try a default from String constructor.
                field.set(object, type.getConstructor(String.class).newInstance(value));
            }
        } catch ( SecurityException e ) {
            throw new IllegalArgumentException(
                    "unsupported option "+into.getName()+"."+seckey+
                    ", security protected");
        } catch ( NoSuchFieldException e ) {
            throw new IllegalArgumentException(
                    "unsupported option "+into.getName()+"."+seckey+
                    ", no such field"); /**/
        } catch ( NumberFormatException e ) {
            throw new IllegalArgumentException(
                    "illegal number format on "+into.getName()+"."+seckey+
                    ", "+value+" is not "+(type.isAssignableFrom(Integer.TYPE)?"an ":"a ")+type.getName()); /**/
        } catch ( IllegalArgumentException e ) {
            //System.err.println("IllegalArgumentException "+e.getMessage());
            if( e.getMessage().startsWith("No enum") ) {
                throw new IllegalArgumentException(
                        "unsupported enum value on field "+into.getName()+
                        ", value \""+value+"\" not valid");
            }

            throw new IllegalArgumentException(
                    "unsupported option field "+into.getName()+"."+seckey+
                    ", illegal argument when setting value"); /**/
        } catch ( IllegalAccessException e ) {
            throw new IllegalArgumentException(
                    "unsupported option field "+into.getName()+"."+seckey+
                    ", access denied"); /**/
        } catch ( NoSuchMethodException e ) {
            throw new IllegalArgumentException(
                    "unsupported field type "+field.getClass().getName()+
                    ", no suitable constructor"); /**/
        } catch ( InstantiationException e ) {
            throw new IllegalArgumentException(
                    "unsupported field type "+field.getClass().getName()+
                    ", instantiation error"); /**/
        } catch ( InvocationTargetException e ) {
            throw new IllegalArgumentException(
                    "unsupported field type "+field.getClass().getName()+
                    ", unable to invocate constructor"); /**/
        } catch( EnumConstantNotPresentException e ) {
            System.err.println("EnumConstantNotPresentException "+e.getMessage());
            throw new IllegalArgumentException(
                    "unsupported enum value on field "+into.getName()+
                    ", constant \""+value+"\" not present");
        } catch ( Exception e ) {
            throw new IllegalArgumentException(
                    "unsupported field type "+field.getClass().getName()+
                    ", unknown error in setting value"); /**/
        }
    }
}
