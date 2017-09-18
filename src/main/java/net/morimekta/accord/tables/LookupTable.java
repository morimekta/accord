package net.morimekta.accord.tables;

import java.net.InetAddress;
import java.net.UnknownHostException;

import net.morimekta.accord.Lookup;
import net.morimekta.net.Location;
import net.morimekta.net.MessageSocket;
import net.morimekta.util.index.Index;
import net.morimekta.util.std.Log;

/*
 *  - Users of lookuptable:
 *Read-Write:
 *      IAmAlive          - called from - ServiceSocket.run()
 *                        - and         - IAmAlive thread,
 *      Membership - called from - ServiceSocket.run()
 *                        - and         - Accord (initializer#connect()).
 *      Stabilizer        - called from - Stabilizer thread.
 *Read-Only:
 *      Lookup     - called from - Accord (externally#lookup())
 *                        -             - ServiceSocket.run()
 *                        -             - Accord through MM (initializer#connect())
 *                        -             - Accord (indirect) Other thread.
 * 
 *  ** cannot collide as they are called from the same thread. But we have four threads here:
 * 
 *     Thread:            Class:        :Pri:    Description:
 *  - ServiceSocket    - MembershipMan. : 1 :
 *                       Lookup  : 0 :
 *                       IAmAlive       : 3 :  * Syncronized on IAmAlive too.
 *  - IAmAlive.run()   - IAmAlive       : 2 :  * Syncronized on IAmAlive too.
 *  - Stabilizer.run() - Stabilizer.
 *                       #concurrent()  : 2 :
 *                       #backoff()     : 1 :
 *  - Initiator        - MembershipMan. : 2 :
 *  - (indirect)       - Lookup  : 0 :
 * 
 * Priority Levels: (CC = Concurrency Control)
 *  - 0: Read Only, READ 0;              - Lock tables for near-atomic reads (at, size+at).
 *                                       - Versioning for read-read CC.
 *  - 1: ReadWrite, READ 0, READWRITE 0; - Lock LT for VC + near-atomic read-writes.
 *                                       - Versioning for read-write CC.
 *  - 2: ReadWrite, READ 1, READWRITE 1; - Lock LT for simple dependent read-writes.
 *                                       - Lock tables for simple dependent reads.
 *  - 3: ReadWrite, READ 2, READWRITE 2; - Lock LT throughout transaction.
 */
/**
 * LookupTable class made from the ground up. It is only a wrapper for
 *  the finger table and its components. LookupTable as a system is
 *  not able to work on its own, but needs an ADN to forward operations
 *  to it. This class does no self maintainance... <br>
 * <br>
 * 
 * @author Stein Eldar Johnsen
 * @see net.morimekta.accord.tables.OverlayContainer
 */
public class LookupTable {
    // The tables in descending sorted order!
	private   OverlayContainer preds;   // predlist
	private   OverlayContainer succs;   // succlist
	private   OverlayContainer fingers; // fingertable
    private   Location         me;      // reference back to "me"
    
	public    OverlayContainer preds(){  return preds;}
	public    OverlayContainer succs(){  return succs;}
	public    OverlayContainer fingers(){return fingers;}
    /**
     * @return the Location of "this" node.
     */
    public Location getMe() {
        return me;
    }
	 
    /**
     * Creates an empty fingertable.
     * 
     * @param _me HostLink of "this" locale.
     */
    public LookupTable(Location _me){
        preds   = new PredList   (_me);
        succs   = new SuccList   (_me);
        fingers = new FingerTable(_me);
        me      = _me;
    }
    
    /**
     * Boolean check if a node is considered "safe".
     * 
     * @param h
     *    Location to check.
     * @return
     *    True if safe, false otherwise.
     */
    public boolean    is_safe    (Location h){
    	if( me.equals(h) )                    return true;
    	synchronized ( preds ) {
    		if( preds.isStable()  &&
    			preds.contains(h) )           return true;
		}
    	synchronized ( succs ) {
    		if( succs.isStable()  &&
    			succs.contains(h) &&
				( succs.size() == 0 ||
				  !succs.get(-1).equals(h) ) ) return true;
		}
    	return false;
    }
    
    /**
     * Wether the LookupTable contains an Location representing the host.
     * 
     * @param host to look for.
     * @return true if found.
     */
    public boolean    contains   (Location host){
        return me.equals(host)                   ||
		       preds.getLocation(host)   != null ||
               succs.getLocation(host)   != null ||
               fingers.getLocation(host) != null;
    }
    
    /**
     * Make a string representation of the LookupTable. Not appliccable for
     * remaking the table.
     * NOTE: ONLY TO BE USED IN DEBUGGING; LOCKING PREVENTS CONCURRENCY
     * @return String of LookupTable.
     */
    public String     toString(){
        return "LookupTable[preds="+preds+";me="+me+";succs="+succs+";fingers="+fingers+"]";
    }
    
    /**
     * Reads the tables from a String, and replaces all content.
     *  
     * @param from
     *     String to parse and build tables from.
     */
    public void       fromString(String from) {
        String[] tmpA = from.substring(11, from.length()-1).split(";");
        String   tmpP = tmpA[0].split("=")[1];
        String   tmpM = tmpA[1].split("=")[1];
        String   tmpS = tmpA[2].split("=")[1];
        String   tmpF = tmpA[3].split("=")[1];
        try {
            me = new Location(tmpM);
        } catch (UnknownHostException e) {
            return;
        }
        preds.fromString(tmpP);
        succs.fromString(tmpS);
        fingers.fromString(tmpF);
    }
    
    /**
     * Retrieves the relative owner (host responsible) of hash in the
     * finger table.
     * 
     * @param index to find relative owner of.
     * @return Location found.
     */
    public Location owner_of(Index index){
        if( preds.containsOwnerOf(index) ) {
            return preds.ownerOf(index);
        } else if( fingers.containsOwnerOf(index) ) {
            return fingers.ownerOf(index);
        } else if( succs.containsOwnerOf(index) ) {
            return succs.ownerOf(index);
        } else {
            return me;
        }
    }
    
    /**
     * Fetch a Location from a String with table:index.
     * 
     * @param table_idx string representation of table lookup as "table:idx".
     * @return Location found.
     */
    public Location table_at(String table_idx) {
        String[]         args;
        OverlayContainer table;
        int              idx;
        try{
            args = table_idx.split(":");
            /**/ if( "pred"  .equals(args[0]) ) table = preds;
            else if( "succ"  .equals(args[0]) ) table = succs;
            else if( "finger".equals(args[0]) ) table = fingers;
            else{
                throw new IllegalArgumentException(
                        "No Such Table \""+args[0]+"\"");
            }
            if(      "first" .equals(args[1]) ) idx =  0;
            else if( "last"  .equals(args[1]) ) idx = -1;
            else {
                idx = Integer.parseInt(args[1]);
            }
            // negative index counts from the end of table. that code is in OverlayTable.
            return table.get(idx);
        } catch( IndexOutOfBoundsException ioobe ){
            // args empty, no table:index, empty table, index outside table...
            return null;
        } catch( NumberFormatException nfe ){
            // unable to determine index.
            return null;
        }
    }
    
    // @Implement
    public Location   getLocation(Location hl){
        Location ret = null;
        if( me.equals(hl) ) {
            return me;
        } else {
            if( ret == null ) {
                ret = preds.getLocation(hl);
                if( ret == null ) {
                    ret = succs.getLocation(hl);
                    if( ret == null ) {
                        ret = fingers.getLocation(hl);
                    }
                }
            }
        }
        return ret;
    }
    
    // @Implement
    public Location   getLocation( InetAddress ia, int port ) {
        Location ret = fingers.getLocation(ia,port);
        if( ret != null ) return ret;
        ret          = preds.getLocation(ia,port);
        if( ret != null ) return ret;
        ret          = preds.getLocation(ia,port);
        return ret;
    }
    
    /**
     * Set the logger for all the tables.
     * 
     * @param log
     *    Log to set.
     */
    public void setLog(Log log) {
        preds.setLog(log);
        succs.setLog(log);
        fingers.setLog(log);
    }
    
    /**
     * Set the Lookup for the tables.
     * 
     * @param lookup
     *     Lookup to set.
     */
    public void setLookup(Lookup lookup) {
        preds.setLookup(lookup);
        succs.setLookup(lookup);
        fingers.setLookup(lookup);
    }
    
    /**
     * Set the socket of the tables.
     * 
     * @param socket
     *    The Socket to set.
     */
    public void setSocket(MessageSocket socket) {
        preds.setSocket(socket);
        succs.setSocket(socket);
        fingers.setSocket(socket);
    }
    
    
}
