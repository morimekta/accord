/*
 * Created on Apr 28, 2005
 */
package net.morimekta.accord.tables;

import java.net.SocketTimeoutException;
import java.util.ConcurrentModificationException;
import java.util.ListIterator;

import net.morimekta.accord.Conf;
import net.morimekta.net.Location;
import net.morimekta.util.index.Index;
import net.morimekta.util.index.IndexFactory;
import net.morimekta.util.std.Log.Level;

/**
 * Finger Table
 * @see net.morimekta.accord.tables.OverlayContainer
 * @author morimekta
 */
public class FingerTable extends OverlayContainer {
    private static final long serialVersionUID = 4049353123799577139L;
    private transient IndexFactory factory = null;
    
    /**
     * Returns the hash value finger "i" should be the owner of. In an ideal finger-table each finger(i) should be the
     * the owner of fingerhash(i).
     * 
     * @param i
     *    table index of finger.
     * @return
     *    Index that the i'th finger should own.
     */
    public Index fingerIndex(int i) {
        return factory.getImaxRshN(i+1).add(sortBase.getIndex());
    }
    
    /**
     * Creates a FingerTable.
     * 
     * @param own
     *    Owner of the table. Sets the sorting base.
     */
    public FingerTable( Location own ) {
        super(own, Sorting.DESCENDING);
        factory = sortBase.getIndex().getFactory();
    }
    
    @Override
    public void stabilizeConcurrent( ) {
        try {
            if( size() > 0 ) {
                int version = version();
                ListIterator<Location> iter = this.listIterator();
                Location loc;
                iter.next();
                while( iter.hasNext() ) {
                    loc = iter.next();
                    if( loc == null ) setStable(false);
                    else if( loc.getTimestamp()+Conf.stabilizer__alive_timeout > System.currentTimeMillis()) {
                        // check for activity...
                        long ping = -1;
                        socket.ping(loc, Conf.stabilizer__ping_timeout, Conf.stabilizer__alive_ping_count);
                        if( ping < 0 ) {
                            iter.set(null);
                            setStable(false);
                        } else {
                            loc.setTimestamp(System.currentTimeMillis());
                        }
                    }
                }
                synchronized ( this ) {
                    if( version == version() ) {
                        setStable(true);
                    }
                }
            } else {
                setStable(true);
                // empty list is always stable...
            }
        } catch( ConcurrentModificationException e ) {
            setStable(false);
        }
    }

    @Override
    // TODO: Check this !!
    public void stabilizeBackoff() {
        ListIterator<Location> iter = listIterator(size());
        Location cur, insert;
        
        // iterate reverse order...
        while( iter.hasPrevious() ) {
            cur = iter.previous();
            if( cur == null ) {
                // no cur, just replace with a new node.
                try {
                    insert = lookup.lookup(fingerIndex(iter.nextIndex()+1));
                    insert.setTimestamp(System.currentTimeMillis());
                    iter.set(insert);
                    setStable(false);
                } catch (SocketTimeoutException e) {
                    // ignore ...
                } catch (IllegalArgumentException e) {
                    assert false : "FingerTable: IllegalArgument from lookup ...";
                }
            } else {
                // cur exists.
                if( (cur.getTimestamp()+Conf.stabilizer__alive_timeout) > System.currentTimeMillis() ) {
                    long ping = socket.ping(
                            cur,
                            Conf.stabilizer__ping_timeout,
                            Conf.stabilizer__alive_ping_count);
                    if( ping < 0 ) {
                        iter.set(null);
                        iter.next();
                        // try again ...
                        continue;
                    } else {
                        cur.setTimestamp(System.currentTimeMillis());
                    }
                    // we have an active current :)
                    try {
                        insert = lookup.lookup(fingerIndex(iter.nextIndex()+1), null, cur);
                        if( !cur.equals(insert) ) {
                            cur = getLocation(insert);
                            if( cur != null ) {
                                insert = cur;
                            }
                            iter.set(insert);
                            setStable(false);
                        }
                    } catch (SocketTimeoutException e) {
                        // gah ... ignore.
                    } catch (IllegalArgumentException e) {
                        assert false : "FingerTable: IllegalArgument from lookup ...";
                    }
                }
            }
        }
    }

    @Override
    public void stabilizeRebuild(int len) {
        if( log == null || lookup == null ) return;
        Location loc, loc2;
        Index       f_idx;
        long        version;
        // fingers.
        while( size() < len ){
            try {
                version = version();
                f_idx   = fingerIndex(size());
                loc     = lookup.lookup( f_idx, null, null);
                if( ( loc2 = getLocation(loc) ) != null ){
                    loc = loc2;
                }
                
                synchronized ( this ) {
                    if( version != version() ) break; // Concurrency-Control.
                    else {
                        log.log("backoff.rebuild: inserting finger["+size()+"] "+loc, Level.low);
                        add(loc);
                    }
                }
                
            } catch ( IllegalArgumentException e ) {
                assert false : "IllegalArgumentException in FingerTable.stabilizeRebuild(int)";
                break;
            } catch (SocketTimeoutException e) {
                log.log("backoff.rebuild: Unable to locate next finger", Level.low);
                setStable(false);
                break;
            }
        }
    }

}
