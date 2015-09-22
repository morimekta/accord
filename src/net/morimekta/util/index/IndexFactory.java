package net.morimekta.util.index;

import net.iharder.xmlizable.Base64;
import net.morimekta.util.std.STD;

/**
 * Factory for Indexes.
 * 
 * @see net.morimekta.util.index.Index
 * @author Stein Eldar Johnsen
 */
public abstract class IndexFactory {
    /**
     * gets the index of string.
     * 
     * @param str the string to index
     * @return the index
     */
    public abstract Index getIndexOf(String str);
    
    /**
     * gets the index of the array.
     * 
     * @param a array to index
     * @return the index
     */
    public abstract Index getIndexOf(byte[] a);
    
    /**
     * get the index represented by the encoded data in str.
     * 
     * @param str string-encoded data
     * @return the index
     */
    public final Index getIndex(String str){
        return getIndex(Base64.decode(str));
    }
    
    /**
     * get the index representing the byte array equivalent.
     * 
     * @param a
     *   Array representing index
     * @return
     *  The index
     */
    public final Index getIndex(byte[] a) {
        if( a == null ) return null;
        Index ret = new Index();
        ret.setBytes(STD.strdup(a));
        ret.setFactory(this);
        return ret;
    }
    
    /**
     * get I<sub>max</sub>+1 >> n or I<sub>max</sub>.
     * 
     * @param n bits to shift.
     * @return the index.
     */
    public abstract Index getImaxRshN(int n);
}
