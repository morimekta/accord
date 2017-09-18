package net.morimekta.util.index;

import java.security.MessageDigest;

import net.morimekta.util.std.STD;

/**
 * IndexFactory for SHA1 type Index.
 * 
 * @author Stein Eldar
 */
public class SHA1Factory extends IndexFactory {
    private static SHA1Factory instance;
    private static final int length = 20;
    
    private MessageDigest mac;
    
    /**
     * Deny all instantiation. Only use getInstance().
     *
     */
    private SHA1Factory() {
        try{
            mac = MessageDigest.getInstance("SHA-1");
        } catch( Exception e ){
            System.err.println(e.getMessage());
            System.exit(-1);
        }
    }
    
    /**
     * Instansiates the SHA1Factory simpletons.
     * 
     * @return
     *    The instansiated SHA1Factory.
     */
    public static synchronized IndexFactory getInstance(){
        if( instance == null ) instance = new SHA1Factory();
        return instance;
    }
    
    /* (non-Javadoc)
     * @see lib.IndexFactory#getIndexOf(byte[])
     */
    public Index getIndexOf(byte[] a) {
        Index ret = new Index();
        ret.setFactory(this);
        // and digest...
        if( a == null || a.length == 0 ) {
            // no value... no hash.
            ret.setBytes(STD.zeroes(length));
        } else {
            ret.setBytes(mac.digest(a));
        }
        return ret;
    }
    
    /* (non-Javadoc)
     * @see lib.IndexFactory#getIndexOf(String)
     */
    public Index getIndexOf(String a) {
        if( a == null || a.length() == 0 ) return getIndexOf((byte[])null);
        else                               return getIndexOf(a.getBytes());
    }

    /* (non-Javadoc)
     * @see lib.IndexFactory#getImaxRshN(int)
     */
    public Index getImaxRshN(int n) {
        Index ret = new Index();
        ret.setBytes(HmaxRshN(n));
        ret.setFactory(instance);
        return ret;
    }
    
    /**
     * Calculates H_{max} >> n (Right Circular Shift), or gives
     *  zero ( H_{max} mod H_{max} = zero) if n is 0.
     * @param n Bits to shift.
     * @return Shifted byte array.
     */
    protected static byte[]   HmaxRshN(int n){
        if( n < 0 ) {
            n = (length*8)+n+1;
        }
        /* 
         * H_{max} >> n
         */
        byte[] tmp;
        tmp    = STD.zeroes(length+1);
        tmp[0] = 0x01; // set the first "invisible" bit to 1.
        tmp    = STD.RshN(tmp, n);
        return STD.substr(tmp, 1, length);
    }
}
