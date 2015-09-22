/*
 * Created on Apr 28, 2005
 */
package net.morimekta.accord.tables;
/*
 * Created on Apr 28, 2005
 */
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Vector;

import net.morimekta.accord.Lookup;
import net.morimekta.net.Location;
import net.morimekta.net.MessageSocket;
import net.morimekta.util.index.Index;
import net.morimekta.util.std.Log;

public abstract class OverlayContainer extends Vector<Location> {
    
    /**
     * Sorting order of an OverlayContainer.
     * 
     * @author Stein Eldar Johnsen
     */
    public enum Sorting {
        /**
         * Container is sorted in ascending order.
         */
        ASCENDING,
        /**
         * Container is sorted in descending order.
         */
        DESCENDING,
        /**
         * Container have no sorting order. It is unsorted.
         */
        UNSORTED
    }
    
    protected boolean       stable   = true;
    protected Sorting       sort     = Sorting.UNSORTED;
    protected Location      sortBase = null;
    
    protected transient MessageSocket socket = null;
    protected transient Log           log    = null;
    protected transient Lookup        lookup = null;
    
    /**
     * Overrides the vector get method, and adds negative indices.
     * 
     * @param idx vector position of Location.
     * @return 
     *    Location on index.
     * 
     * @see Vector#get(int)
     */
    @Override
    public synchronized Location get(int idx) {
        if( idx < 0 ) return super.get( idx+size() );
        else          return super.get( idx );
    }
    
    /**
     * Overrides the vector remove method, and adds negative indices.
     * 
     * @param idx
     *     Index of element to remove.
     * @return
     *     Location Removed.
     * @see Vector#remove(int)
     */
    public synchronized Location remove(int idx) {
        if( idx < 0 ) return super.remove( idx+size() );
        else          return super.remove( idx );
    }
    
    /**
     * Set size and trim if and only if new size is smaller than old size.
     * 
     * @param size
     *     New mazimum size of container.
     */
    public synchronized void crop( int size ) {
        if( size < size() ) {
            setSize(size);
            trimToSize();
        }
        // else ignore: dont increase size of container.
    }
    
    /**
     * Replace the links from index off, to (but not including) off+len with
     * all the links in given collection.
     * 
     * If Collection is not big enough to fill,
     * The rest of the length will be removed, and if it is too lerge, the 'overflow'
     * overlaylinks will be inserted.
     * 
     * @param off
     *    Offset to start replace.
     * @param len
     *    Number of OverlayLinks to replace.
     * @param arr
     *    Collection of replacements.
     */
    public void replace( int off, int len, Collection<Location> arr ) {
        if( off < 0 || len < 0 || off+len >= size() ) 
            throw new IndexOutOfBoundsException();
        for( int i = 0; i < len; i++ ) remove(off);
        addAll(off, arr);
    }
    
    /**
     * Creates a new OverlayContainer.
     * 
     * @param own
     *     Sorting base of the container.
     * @param srt
     *     Sorting of the container.
     */
    public OverlayContainer(Location own, Sorting srt) {
        super();
        sortBase = own;
        sort     = srt;
    }
    
    /**
     * Create a copy of the OverlayContainer.
     * 
     * @param base
     */
    public OverlayContainer(OverlayContainer base) {
        super(base);
        sortBase = base.sortBase;
        sort     = base.sort;
        
        log      = base.log;
        socket   = base.socket;
        lookup   = base.lookup;
    }
    
    /**
     * Checks if the container is stable.
     * 
     * @return
     *     Returns true if the container os considered 'stable'.
     */
    public boolean isStable() {
        return stable;
    }
    
    /**
     * Sets the stability of the container.
     * 
     * @param s
     *    New stability value of the container.
     */
    public void    setStable(boolean s) {
        stable = s;
    }
    
    /**
     * Get the sorting order of the collection.
     * 
     * The sorting order is NOT possible to
     * change after creation.
     * @return 
     *     Sorting of list.
     */
    public Sorting getSorting() {
        return sort;
    }
    
    /**
     * Add an Location in a "sorted" manner.
     * 
     * @param loc
     *     Location to add.
     * @return
     *     True if able to add the Location. False if the index is already taken
     *     (even if by the same object).
     */
    public synchronized boolean addSorted(Location loc) {
        if( sort == Sorting.ASCENDING ) {
            for( int i = 0; i < size(); i++ ) {
                if( loc.getIndex().between(sortBase.getIndex(), get(i).getIndex()) ) {
                    if( loc.getIndex().equals(get(i).getIndex()) ) return false;
                    else {
                        add(i, loc);
                        return true;
                    }
                }
            }
            // not added in 
            add(loc);
            return true;
        } else if ( sort == Sorting.DESCENDING ) {
            for( int i = 0; i < size(); i++ ) {
                if( loc.getIndex().between(get(i).getIndex(), sortBase.getIndex()) ) {
                    if( loc.getIndex().equals(get(i).getIndex()) ) return false;
                    else {
                        add(i, loc);
                        return true;
                    }
                }
            }
            // not added in 
            add(loc);
            return true;
        } else {
            add(0, loc); // unsorted...
            return true;
        }
    }
    
    /**
     * Adds all elements of a collection with the addSorted method. Note that this
     * can be rather slow if the two collections are rather large.
     * 
     * @param coll
     *     Collection to add elements from.
     * @return
     *     Number of added elements.
     */
    public synchronized int     addAllSorted(Collection<Location> coll) {
        int count = 0;
        for( Location loc : coll ) {
            if( addSorted(loc) ) count++;
        }
        return count;
    }
        
    /**
     * Finds the owner of an index relative to the sortBase's index.
     * 
     * @param idx
     *     Index to find owner of.
     * @return
     *     Owner if found, or null if "owned by sortBase".
     */
    public synchronized Location ownerOf(Index idx) {
        if( sort == Sorting.UNSORTED ) {
            // unsorted, just get a "random" Location.
            if( size() > 0 ) return get(0);
            else             return null;
        }
        // if sorted
        Location loc;
        for( int i = 0 ; i < size() ; i-- ) {
            // for successor list only... (may add toe table with this later)
            if( sort == Sorting.ASCENDING ) loc = get(-1-i); // since we starts with [size-1]
            // for predecessor list and finger table.
            else                            loc = get(i);
            
            if( ( loc != null ) &&
                ( idx.between(loc.getIndex(), sortBase.getIndex()) ) ) {
                return loc;
            }
        }
        return null; // not found.
    }
    
    /**
     * Checks if the index is (thought to be) owned by an Location within the container.
     * 
     * @param idx
     *    Index to check.
     * @return
     *    True if it beleives the index is owned here.
     */
    public synchronized boolean containsOwnerOf(Index idx) {
        if( size() < 1 ) return false;
        /*  */ if( sort == Sorting.ASCENDING ) {
            return idx.between(get(0).getIndex(),        sortBase.getIndex());
        } else if ( sort == Sorting.DESCENDING ){
            return idx.between(get(size()-1).getIndex(), sortBase.getIndex());
        } else {
            return false;
        }
    }
    
    /**
     * Return an array of OverlayLinks with length maximum of i, but still as
     *  large as possible, and no larger than the array size.
     * 
     * @param i
     *     Length of prefferred array.
     * @return
     *     Array of OverlayLinks.
     */
    public synchronized Location[] toArray(int i) {
        if( i >= size() )
            return (Location[]) toArray();
        else {
            Location[] ret = new Location[i];
            return toArray(ret);
        }
    }
    
    /**
     * Reads a string and generates the container from that string.
     * 
     * @param from
     *      String to build vector from.
     */
    public synchronized void fromString( String from ) {
        // remove the leading [ and the ending ] from the list string.
        String[] locs = from.substring(1, from.length()-1).split(",");
        LinkedList<Location> list = new LinkedList<Location>();
        try {
            for( int i = 0; i < locs.length; i++ ) {
                list.addLast(new Location(locs[i]));
            }
        } catch (UnknownHostException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        removeAllElements();
        addAll(list);
    }
    
    public String toString() {
        String tmp = super.toString();
        tmp = tmp.replaceAll(" ", "");
        return tmp;
    }
    
    /**
     * Get the Location object that represents the Location hl.
     * 
     * @param hl
     *     Location to look for.
     * @return
     *     Location if found, null otherwise.
     */
    public synchronized Location getLocation(Location hl) {
        for( Location l : this ) {
            if( l != null && l.equals(hl) ) return l;
        }
        return null;
    }
    
    
    public synchronized Location getLocation( InetAddress ia, int port ) {
        for( Location l : this ) {
            if( l != null && l.getAddress().equals(ia) && l.getPort() == port ) return l;
        }
        return null;
    }
    
    /**
     * Get the version count of the container.
     * 
     * @return
     *     Version number.
     */
    public synchronized int version() {
        return this.modCount;
    }
    
    /**
     * Stabilize imminent "problems" such as dead nodes.
     *
     */
    public abstract void stabilizeConcurrent();
    
    /**
     * Stabilize less important changes, like table "holes" (succ/pred lists) or
     * Enclosing to better owner (fingers).
     * 
     */
    public abstract void stabilizeBackoff();
    
    /**
     * Rebuilds the container to have a minimum of 'len' elements.
     * 
     * @param len
     *    Wanted size of the container.
     */
    public abstract void stabilizeRebuild(int len);
    
    /**
     * Get the Log.
     * 
     * @return
     *    Log.
     */
    public Log getLog() {
        return log;
    }
    
    /**
     *  Set the Log.
     *  
     * @param log
     *    Log to set.
     */
    public void setLog(Log log) {
        this.log = log;
    }
    
    /**
     * Get the Lookup.
     * 
     * @return
     *    The Lookup.
     */
    public Lookup getLookup() {
        return lookup;
    }
    
    /**
     * Set the lookup.
     * 
     * @param lookup
     *     The Lookup.
     */
    public void setLookup(Lookup lookup) {
        this.lookup = lookup;
    }
    
    /**
     * Get the socket.
     * 
     * @return
     *    The socket.
     */
    public MessageSocket getSocket() {
        return socket;
    }
    
    /**
     * set the Socket.
     * 
     * @param socket
     *    The socket.
     */
    public void setSocket(MessageSocket socket) {
        this.socket = socket;
    }
    
}
