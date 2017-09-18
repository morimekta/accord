package net.morimekta.util.std;

import java.util.ArrayList;

/**
 * @author Stein Eldar Johnsen
 */
public class STD {
    /**
     * Prevents instantiation.
     *
     */
    private STD() {}
    /**
     * Converts byte[4] to integer.
     * @param b byte array
     * @return integer
     */
    public static int     byte2int(byte b){
        return b < 0 ? 0x100+b : b;
    }
    
    /**
     * Converts integer to byte [4]
     * @param i integer to convert
     * @return byte array of int.
     */
    public static byte    int2byte(int i){
        i = i % 0x100;
        return (byte)( i < 0x80 ? i : i - 0x100 );
    }
    
    /**
     * Single byte add overrun calculation.
     * @param a first byte to add.
     * @param b secong byte to add.
     * @param over overrun from previous adds (usually 0 or 1).
     * @return result overrun.
     */
    private static int     byte_aor(byte a, byte b, int over){
        return int2byte((byte2int(a) + byte2int(b) + over) / 0x100);
    }
    
    /**
     * Single byte add giving byte result.
     * @param a first byte.
     * @param b second byte.
     * @param over overrun from previous adds.
     * @return a+b % 0x100.
     */
    private static byte    byte_add(byte a, byte b, int over){
        return int2byte((byte2int(a) + byte2int(b) + over) % 0x100);
    }
    
    /**
     * Returns the "negative" of a single unsigned byte.
     * 
     * @param a
     *     Byte to negate.
     * @return
     *     Negated byte.
     */
    private static byte    byte_neg(byte a){
        return int2byte(1+(a ^ 0xFF)); // a XOR 0xFF + 1
    }

    /**
     * Adds together two byte arrays with an initial overrun.
     * 3.may.2005 Added byte array length checking...
     * 
     * @param a
     *     First byte array.
     * @param b
     *     Second byte array.
     * @param c
     *     Initial overrun.
     * @return
     *     Sum of the two byte arrays.
     */
    private static byte[]  bytea_add(byte[] a, byte[] b, int c){
        if( b.length < a.length )      b = strcpy(b, zeroes(a.length));
        else if( b.length > a.length ) a = strcpy(a, zeroes(b.length));
        
        byte[] res = new byte[a.length];
        int overrun = c;
        for( int i = a.length-1; i >= 0; --i ){
                // We add [ 0 <== 40 ] and propagates overrun.
                res[i]  = byte_add(a[i], b[i], overrun);
                overrun = byte_aor(a[i], b[i], overrun);
        }
        return res;

    }
    
    /**
     * Calculates the negative of the numerical value representing the byte array,
     *  and returns it as a byte array.
     *  
     * @param a
     *   Array to negave
     * @return
     *   Negative of a.
     */
    private static byte[]  bytea_neg(byte[] a){
        byte[] ret = new byte[a.length];
        for(int i = 0; i < a.length; ++i){
            ret[i] = int2byte(a[i] ^ 0xFF);
        }
        return bytea_add(ret, zeroes(ret.length), 1);
    }
    
    /**
     * Calculates the two-character String representing the byte in hexadecimal.
     * 
     * @param i
     *    Byte to hex.
     * @return
     *    Hex String.
     */
    private static String  byte2hex(byte i){
        int I = (i < 0 ? 0x100+i : i);
        String ret = Integer.toHexString(I);
        if( ret.length() < 2 ) ret = "0" + ret;
        return ret;
    }

    /* 
     * Some methods used on the hashes and with the hashes.
     */
    /**
     * Generates the hexadecimal string representing a.
     * 
     * @param a
     *    Array to hex.
     * @return
     *    Hexadecimal string.
     */
    public  static String   strhex(byte[] a){
        String s = "";
        for(int i = 0; i < a.length; ++i){
            s += byte2hex( a[i] );
        }
        return s;
    }
    
    /**
     * Duplicates the byte array.
     * 
     * @param h0
     *    Array to duplicate.
     * @return
     *    Duplicated array, identical to h0.
     */
    public  static byte[]   strdup(byte[] h0){
        byte[] ret = new byte[h0.length];
        strcpy(h0, ret);
        return ret;
    }
    
    /**
     * Copies the content of a byte array into another byte array.
     * 
     * @param h0
     *    Copy from this array.
     * @param h1
     *    Copy into this.
     * @return
     *    h1 after copy.
     */
    public  static byte[]   strcpy(byte[] h0, byte[] h1){
        return strcpy(h0, h1, 0);
    }
    
    /**
     * Copies the content from a byte array into another array with an initial offset.
     * 
     * @param h0
     *    Copy from this array.
     * @param h1
     *    And into this.
     * @param off
     *    With this initial offset in h1.
     * @return
     *    h1 after copy.
     */
    public  static byte[]   strcpy(byte[] h0, byte[] h1, int off){
        /*int count = (h0.length > (h1.length - off) ? 
                    (h1.length - off) :
                     h0.length );
        for(int i = 0; i < count; ++i){
            h1[i+off] = h0[i];
        }
        return h1; */
        return strcpy(h0, 0, h1, off, h1.length);
    }
    
    /**
     * Copies the content from a byte array into another array with an initial offsets in both arrays, and a maximum
     * copy length.
     * 
     * @param h0     Array to copy from.
     * @param h0off  Offset in h0 to start reading.
     * @param h1     Array to copy to.
     * @param h1off  Offset in h1 to start writing.
     * @param len    Max length to copy.
     * @return       h1 as after copy.
     */
    public static byte[] strcpy(byte[] h0, int h0off, byte[] h1, int h1off, int len){
        // find the longest copyable sequence.
        int count = ( (h0.length - h0off ) > (h1.length - h1off) ? 
                      (h1.length - h1off ) :
                      (h0.length - h0off ) );
        // select the smallest of the two.
        if( count > len ) count = len;
        // copy the bytes.
        for(int i = 0; i < count; ++i){
            h1[i+h1off] = h0[i+h0off];
        }
        // return the array with the result.
        return h1;
    }
    
    /**
     * Returns h0 - h1, that is positive is h0 is bigger, 0 if
     *  equal, and negative otherwise. Mark that the arrays are "equivalent" if
     *  the first N bytes are equal, and the rest are zeroes.
     *  
     * @param h0
     *   First array
     * @param h1
     *   Second array
     * @return
     *   Negative if h1 is "bigger" than h0, positive if 
     *   h0 is bigger, and zero if equivalent.
     */
    public  static int      strcmp(byte[] h0, byte[] h1){
        byte[] tmp0 = (h0.length < h1.length ? strcpy(h0, zeroes(h1.length)) : h0 );
        byte[] tmp1 = (h0.length > h1.length ? strcpy(h1, zeroes(h0.length)) : h1 );
        
        int ret = 0;
        for(int i = 0; i < tmp0.length; ++i ){
            ret = byte2int(tmp0[i]) - byte2int(tmp1[i]);
            if(ret != 0) return ret;
        }
        return 0;
    }
    
    /**
     * Concatenates two byte arrays into a single array.
     * 
     * @param h0 first array.
     * @param h1 second array.
     * @return concatenated: h0 . h1 .
     */
    public  static byte[]   strcat(byte[] h0, byte[] h1){
        return strcat(h0, h1, h1.length);
    }
    
    /**
     * Concatenates two byte arrays into a single array with a total length of h0.length + len.
     * 
     * @param h0 first array.
     * @param h1 second array.
     * @param len
     *   How much of h1 to include in the concatenation,
     *   if longer than h1, add zeroes at the end of the array.
     * @return concatenated: h0 . h1 .
     */
    public  static byte[]   strcat(byte[] h0, byte[] h1, int len){
        byte[] ret = new byte[h0.length + len];
        strcpy(h0, ret, 0);
        strcpy(h1, ret, h0.length);
        return ret;
    }
    
    /**
     * generates the subarray from position off and the length len from str.
     * 
     * @param str source byte array.
     * @param off offset in str to start at.
     * @param len length of substring.
     * @return substring.
     */
    public  static byte[]   substr(byte[] str, int off, int len){
        byte[] ret = new byte[len];
        
        for(int i = 0; i < len; i++ ){
            ret[i] = str[i+off];
        }
        return ret;
    }
    
    /**
     * Add the numerical values of the two byte arrays.
     * 
     * @param h0 first array.
     * @param h1 second array.
     * @return result sum of the two.
     */
    public  static byte[]   stradd(byte[] h0, byte[] h1){
        return bytea_add(h0, h1, 0);
    }
    
    /**
     * Subtract the numerical value of a byte array from another byte array.
     * 
     * @param h0 first array.
     * @param h1 second array.
     * @return h0 - h1.
     */
    public  static byte[]   strsub(byte[] h0, byte[] h1){
        return bytea_add(h0, bytea_neg(h1), 0);
    }
    
    /**
     * Converts a test string representation of an IP address into a byte[4] representing the IP.
     * 
     * @param ip Source string of IP.
     * @return byte[4] of IP, { 0,0,0,0 } if not a valid IP.
     */
    public  static byte[]   str2ip(String ip){
        String[] tmp = ip.split(".");
        if( tmp.length != 4 ) return new byte[]{ 0,0,0,0 };
        else {
            try{
                byte[] tmpip = new byte[4];
                for(int i = 0; i < 4; i++){
                    try {
                        tmpip[i] = Byte.parseByte( tmp[i] );
                    } catch ( NumberFormatException nfe ) {
                        // not an IP...
                        return new byte[]{ 0,0,0,0 };
                    }
                }
                return tmpip;
            } catch( NumberFormatException e ){
                return new byte[]{ 0,0,0,0 };
            }
        }
    }
    
    /**
     * Converts a byte array into an IP string. Since it does no length check, it may generate phony
     * ip strings.
     * 
     * @param ip IP address to convert to string.
     * @return String of IP.
     */
    public  static String   ip2str(byte[] ip) {
        String ret = "";
        
        for(int i = 0; i < ip.length; i++) {
            ret += byte2int(ip[i]);
            if( i < ip.length -1 ) ret += ".";
        }
        
        return ret;
    }
    
    /**
     * Converts a long int into a byte array representation with specified length.
     * 
     * @param num Number to convert.
     * @param len Length of desired byte array.
     * @return byte array.
     */
    public  static byte[]   i2a(long num, int len){
        byte[] ret = new byte[len];
        
        for(int i = len-1; i >= 0; i--){
            int tmp = (int) num % 0x100;
            ret[i] = (byte) ( tmp >= 0x80 ? tmp-0x100 : tmp );
            num /= 0x100;
        }
        
        return ret;
    }
    
    /**
     * Converts a byte array into an integer (long).
     * 
     * @param a array to convert.
     * @return long int representing array.
     */
    public  static long     a2i(byte[] a){
        long ret = 0;
        
        for( int i = 0; i < a.length; i++){
            ret *= 0x100;
            ret += ( a[i] < 0 ? a[i]+0x100 : a[i] );
        }
        
        return ret;
    }
    
    /**
     * Checks if the values of the byte arrays h0, h1 and h2 have
     * the property of h1 being between h0 and h2 or equal to h0.
     * 
     * If the arrays are of different lengths, the shorter arrays are padded
     * with zeroes to compensate regarding substract array length and compare.
     * 
     * @param h0 Lower bound
     * @param h1 Between
     * @param h2 Upper Bound
     * @return True if (h1-h0) is less than (h2-h0)
     */
    public static boolean between( byte[] h0, byte[] h1, byte[] h2 ) {
        // - second comes naturally if the two arrays are of the
        // same length.
        int len = h0.length > h1.length ? h0.length : h1.length;
        len     = len > h2.length       ? len       : h2.length;
        
        if ( h0.length < len )
            h0 = strcat( h0, zeroes( len - h0.length) );
        if ( h1.length < len )
            h1 = strcat( h1, zeroes( len - h0.length) );
        if ( h2.length < len )
            h2 = strcat( h2, zeroes( len - h0.length) );
        
        byte[] rel1 = strsub( h1, h0 );
        byte[] rel2 = strsub( h2, h0 );
        return strcmp( rel1, rel2 ) < 0;
    }
    
    /**
     * Generates a byte array of N zero bytes.
     * 
     * @param n number of bytes.
     * @return Byte array result.
     */
    public final static byte[] zeroes(int n){
        byte[] ret = new byte[n];
        for( int i = 0; i < ret.length; i++ ){
            ret[i] = 0;
        }
        return ret;
    }
    
    /**
     * Left Circular Bitwise Shift of byte array.
     * 
     * @param a Array to shift, is unmodified.
     * @param n Bits to shift.
     * @return Shifted byte array.
     */
    public static byte[]   LshN(byte[] a, int n){
        return RshN(a, -n);
    }
    /**
     * Right Circular Bitwise Shift of byte array.
     * 
     * @param a Array to shift, is unmodified.
     * @param n Bits to shift.
     * @return Shifted byte array.
     */
    public static byte[]   RshN(byte[] a, int n){
        while( n < 0 )
            n += (8*a.length);
        n  = n % (8*a.length);
        if( n==0 ) return strdup(a);
        
        byte[] ret = new byte[a.length];
        if( n < 8 ){
            // shift some bits only
            // first shift from last byte to first byte.
            int ai  = byte2int(a[0])          >>    n;
            int ain = byte2int(a[a.length-1]) << (8-n);
            ret[0] = int2byte(( ai | ain ) & 0xFF);
            // then shift from byte i-1 to byte i.
            for(int i = 1; i < a.length; i++){
                ai  = byte2int(a[i])   >>    n;
                ain = byte2int(a[i-1]) << (8-n);
                ret[i] = int2byte(( ai | ain ) & 0xFF);
            }
        } else {
            // shift first bits, then bytes.
            a = RshN(a, n%8);
            int sh_bytes = a.length - n/8;
            
            // set each byte to the "old" byte in a.
            for(int i = 0; i < a.length; i++){
                ret[i] = a[(i+sh_bytes) % a.length];
            }
        }
        return ret;
    }
    
    /**
     * Checks if the string is not "final", meaning it should be appended to.
     * 
     * @param str
     *     String to check for finality.
     * @return
     *     True if the string is <i>not</i> finalized (i.e. is escaped).
     */
    private static boolean stringIsFinal(String str){
        if( ( str.startsWith("\"") && str.endsWith("\"") ) ||
            ( str.startsWith("\'") && str.endsWith("\'") ) ) {
            int c = 0;
            for( int i = str.length()-2; i > 0; i--){
                if( str.charAt(i) == '\\' ) ++c;
                else break;
            }
            return (c % 2) == 0; // if even number it is final.
        } else {
            // if begin quote marks, it is not final.
            if( str.startsWith("\"") || str.startsWith("\'") ) return false;
            // no begin quote marks. It is final.
            else return true;
        }
    }
    
    
    /**
     * Split a string by means of "quotation". Aquoted sentence with spaces is treated as a
     * single part, and given its own string. All spaces are kept within the quoted string.
     * 
     * @param args
     *     String to split.
     * @return
     *     Array of string parts.
     */
    public static String[] splitString(String args){
        ArrayList<String> list = new ArrayList<String>();
        String[] array = args.split("[\\s]");
        args = args.replaceAll("[\\S]", ""); // remove all non-spaces from the string.
        /*
         * array[0]+args.charAt(0)+array[1] ...
         */
        // composite strings with spaces.
        for( int i = 1; i < array.length; ++i ){
            // check if we can merge i-1 and i. because the space is marked with "\"
            // or it is quoted (and not ended quoting).
            if( array[i-1] != null &&
                !stringIsFinal(array[i-1]) ){
                array[i] = array[i-1] + args.charAt(i-1) + ( array[i] == null ? "" : array[i] );
                array[i-1] = null; // remove the "old" entry.
                
                // remove quoting.
                if( array[i].startsWith("\"") && array[i].endsWith("\"") || 
                    array[i].startsWith("\'") && array[i].endsWith("\'") )
                    array[i] = array[i].substring(1, array[i].length()-1);
                // TODO: remove escaping...
                // make escaped character the "right" character...
            }
        }
        for( int i = 0; i < array.length ; i++ ){
            if( array[i] != null && array[i].length() > 0 ){
                // TO-DO: remove escaping or 1 level of quoting.
                list.add(array[i]);
            }
        }
        
        return list.toArray(new String[list.size()]);
    }
    
    /**
     * Assemble an array of strings to a quoted string separating original strings, not 
     * separate words from the given strings.
     * 
     * @param array
     *    Array to assemble.
     * @return
     *    Assembled string.
     */
    public static String assembleString(String[] array){
        String ret = "";
        for( int i = 0; i < array.length; i++){
            if( array[i] != null &&
            	array[i].length() > 0 ){
                // add quotes if needed.
                if( array[i].split("[\\s]", 2).length > 1 ){
                    array[i] = "\""+array[i]+"\"";
                }
                ret += (ret.length()>0?" ":"")+array[i];
            }
        }
        return ret;
    }

    
}
