package net.morimekta.util.index;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Arrays;

import net.iharder.xmlizable.Base64;
import net.morimekta.util.std.STD;


/**
 * General Index class for indexing any size number in a "reverse" consistent manner.
 *  All indices are considered big endian (first byte is most significant), and are
 *  not re-aligned for arithmetics (they are aligned on _most_ significant _byte_
 *  (not bit)).
 *  
 * Class is set as final as it is not supposed to be alteres or subclassed.
 * 
 * @author Stein Eldar Johnsen
 */
public final class Index implements Comparable, Serializable {
    /**
     * Index is not to be instansiated from any other place than serializable and
     * from the IndexFactories.
     *
     */
    protected Index() {
    }
    /**
     * Serializable version UID.
     */
    private static final long serialVersionUID = 3258697593587381560L;
    /**
     * IndexFactory for this index type.
     */
    private transient IndexFactory    factory;
    /**
     * Byted containing the Index data.
     */
    private byte[]                    bytes;
    
    /**
     * Gets an instance of an IndexFactory that will make indices like this. 
     * 
     * @return
     *   IndexFactory for same type of indices.
     */
    public final IndexFactory getFactory() {
        return factory;
    }
    
    /**
     * Method for setting the IndexFactory for the Index. This factory will
     *  produce indices of the same type as the base type of this index.
     * 
     * @param fac
     *    Factory of the index.
     */
    public final void setFactory(IndexFactory fac) {
        factory = fac;
    }
    
    /**
     * Get the byte array with the index data.
     * 
     * @return
     *   Byte array of index data.
     */
    public final byte[]  getBytes() {
        return bytes;
    }
    
    /**
     * Protected method for the IndexFactory. Sets the byte value of the Index.
     * 
     * @param b
     *     Bytes of the new 
     */
    protected final void setBytes(byte[] b) {
        bytes = b;
    }
    
    /**
     * Adds the numerical equivalent of this index with the numerical equivalent of arg0.
     * Leaves the source indices unchanged.
     * 
     * @param idx
     *    Index to add.
     * @return
     *    Sum of the two additives.
     */
    public final Index   add(Index idx) {
        return add(idx.bytes);
    }
    
    /**
     * Protected add method...
     * 
     * @param b
     *     Byte value to add.
     * @return
     *     The new index.
     */
    protected final Index add(byte[] b){
        Index ret = new Index();
        ret.bytes = STD.stradd(bytes, b);
        return ret;
    }
    
    /**
     * Substracts the numerical equivalent of an index from the value of this index.
     *  Leaves the source indices inchanged.
     * 
     * @param idx
     *   Index to subtract from this.
     * @return
     *   Subtracted Index.
     */
    public final Index   sub(Index idx) {
        return sub(idx.bytes);
    }
    
    /**
     * Subtract a byte array index value from this index. Does not change
     *  this object.
     * @param idx
     *    Index to subtract from this.
     * @return
     *    Result index.
     */
    protected Index sub(byte[] idx){
        Index ret = new Index();
        ret.bytes = STD.strsub(bytes, idx);
        return ret;
    }

    /**
     * Generates a hexadecimal string representing the Index.
     * 
     * @return
     *   Hexadecimal String.
     */
    public final String  toHexString(){
        return STD.strhex(getBytes());
    }

    /**
     * Returns true if the two hash values are identical.
     * @param o
     *   Object to compare with.
     * @return
     *   True if equivalent, false otherwise.
     */
    public final boolean equals(Object o){
        if( Index.class.isAssignableFrom(o.getClass()) )
            return compareTo((Index) o) == 0;
        else
            return false; // all objects are not equal if not compatible.
    }
    
    /**
     * Compares the index numerically with another object.
     * 
     * @param o
     *   Index to compare with.
     * @return
     *   Positive if this index is "larger" than idx, 0 if they are equivalent and 
     *   negative otherwise. If the object is not assignable to Index, return -1.
     */
    public final int compareTo(Object  o){
        if( Index.class.isAssignableFrom(o.getClass()) )
            return compareTo((Index) o);
        else
            return -1; // all objects are "smaller" than this if not compatible.
    }
    
    /**
     * get the standard hashCode from the index data. Hashcodes the data array, and
     * is thus <em>not</em> linear equivalent to the index.
     * 
     * @return Hashed array as integer.
     */
    public final int hashCode(){
        return Arrays.hashCode(bytes);
    }

    /**
     * Generates a byte64 encoded String representing the Index.
     * 
     * @return
     *   The byte64 encoded String.
     */
    public final String  toBase64String(){
        return Base64.encodeBytes(getBytes());
    }
    
    /**
     * get a string that represents the index that the class is capable of rebuilding 
     * the index from. Normally a
     *  
     * @return
     *   String representing the index.
     */
    public final String  toString(){
        return toBase64String();
    }
    
    /**
     * Checks if the index in numerically equal to another index.
     * 
     * @param idx
     *   Index to compare with.
     * @return
     *   True if the indices are equivalent.
     */
    public final boolean equals(Index idx){
        return compareTo(idx) ==0;
    }
    
    /**
     * Compares the index numerically with another index.
     * 
     * @param idx
     *   Index to compare with.
     * @return
     *   Positive if this index is "larger" than idx, 0 if they are equivalent and 
     *   negative otherwise.
     */
    public final int     compareTo(Index idx){
        return STD.strcmp(getBytes(), idx.getBytes());
    }
    
    /**
     * Checks if the index are "between" two ither indices. That is this index is in the
     * circular interval from (including) the 'from' index to (but not including) to 'to' index.
     * 
     * @param from
     *   Index of starting point of range.
     * @param to
     *   Index of ending point of range.
     * @return
     *   True if this is 'between' 'from' and 'to'. False otherwise.
     */
    public final boolean between(Index from, Index to){ // from <= this < to
        return STD.between(from.getBytes(), getBytes(), to.getBytes());
    }
    
    /**
     * Read object for serializable.
     * 
     * @param in 
     *     ObjectInputStream to read from.
     * @throws IOException 
     *     If ReadObject cannot read the object from the stream.
     * @throws ClassNotFoundException
     *     If the class is wrong or the ClassLoader cannot instansiate the class.
     * @see Serializable
     */
    private final void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        factory = SHA1Factory.getInstance(); // default factory...
    }
    
}
