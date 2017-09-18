/*
 * Created on Apr 28, 2005
 */
package net.morimekta.accord.tables;

import java.net.SocketTimeoutException;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.ListIterator;

import net.morimekta.accord.Conf;
import net.morimekta.net.Location;
import net.morimekta.util.std.Log.Level;

/**
 * Successor List
 * @see net.morimekta.accord.tables.OverlayContainer
 * @author Stein Eldar Johnsen
 */
public class SuccList extends OverlayContainer {
    private static final long serialVersionUID = 3258412815798055735L;

    /**
     * Create a new SuccList.
     * 
     * @param own
     *    Owner for base calculations.
     */
    public SuccList( Location own) {
        super(own, Sorting.ASCENDING);
    }
    
    public SuccList( OverlayContainer cont ) {
        super(cont);
        sort = Sorting.ASCENDING;
    }
    
    @Override
    public void stabilizeConcurrent( ) {
        try {
            if( size() > 0 ) {
                int version = version();
                Iterator<Location> iter = this.iterator();
                Location loc;
                iter.next();
                while( iter.hasNext() ) {
                    loc = iter.next();
                    if( loc != null && 
                        loc.getTimestamp()+Conf.stabilizer__alive_timeout > System.currentTimeMillis()) {
                        // check for activity...
                        long ping = socket.ping(
                                loc,
                                Conf.stabilizer__ping_timeout,
                                Conf.stabilizer__alive_ping_count);
                        if( ping < 0 ) {
                            iter.remove();
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
    public void stabilizeBackoff() {
        // TODO Find a way to "remember" i-am-alive fix-length...
        if( size() > Conf.stabilizer__min_succ ) {
            ListIterator<Location> cur = listIterator(Conf.stabilizer__min_succ);
            Location insert, loc, next;
            while( cur.hasNext() ) {
                loc = cur.next();
                // check for dead nodes...
                if( loc.getTimestamp() < ( System.currentTimeMillis() - Conf.stabilizer__alive_timeout ) ) {
                    if( socket.ping( loc, Conf.stabilizer__ping_timeout, Conf.stabilizer__alive_ping_count) < 0 ) {
                        // ping timeout...
                        cur.remove();
                        loc = cur.previous();
                        setStable(false);
                    } else {
                        loc.setTimestamp(System.currentTimeMillis());
                    }
                }
                // lets check for insertions...
                if( cur.hasNext() ) {
                    try {
                        insert = lookup.lookup_table(loc, "succ:0", null);
                        loc.setTimestamp(System.currentTimeMillis());
                        
                        next   = getLocation(insert);
                        if( next != null ) insert = next;
                        next   = get(cur.nextIndex());
                        
                        if( insert.equals(next) ) {
                            // no change...
                        } else if (insert.getIndex().between(loc.getIndex(),next.getIndex())) {
                            // insert...
                            cur.add(insert);
                            cur.previous(); // check the new ...
                            setStable(false);
                        } else {
                            // remove some nodes !!!
                            while( cur.hasNext() ) {
                                loc = cur.next();
                                if( !loc.equals(insert) &&
                                    loc.getIndex().between(sortBase.getIndex(),insert.getIndex()) ) {
                                    cur.remove();
                                }
                            }
                            if( !loc.equals(insert) ) {
                                cur.add(insert);
                            }
                            setStable(false);
                        }
                    } catch (SocketTimeoutException e) {
                        // ignore.
                    } catch (IllegalArgumentException e) {
                        assert false : "PredList - Illegal argument to lookup_table.";
                    }
                } // if point in adding nodes (has more)
            } // while locations ...
        } // no locations, ignore.
    } 

    @Override
    public void stabilizeRebuild(int len) {
        Location loc, loc2;
        // TODO Auto-generated method stub
        if( size() > 0 && socket != null && log != null && lookup != null ) {
            // succs.
            while( size() < len ){
                //log.log("backoff.rebuild: Trying to increase succs 1 from size "+size(), Level.low);
                
                loc = get(-1);
                if( loc == null ) break;
                else {
                    long   version = version();
                    try {
                        loc = lookup.lookup_table( loc, "succ:0", "" );
                        
                        // avoids duplicate locations.
                        if( ( loc2 = getLocation(loc) ) != null ){
                            loc = loc2;
                        }

                        synchronized ( this ) {
                            if( version != version() ) break;
                            else {
                                log.log("backoff.rebuild: inserting succ["+size()+"] "+loc+".", Level.low);
                                add(loc);
                            }
                        }
                    } catch ( SocketTimeoutException e ) {
                        log.log("backoff.rebuild: Unable to contact last succ.", Level.low);
                        setStable(false);
                        break;
                    } catch ( IllegalArgumentException e ) {
                        assert false : "IllegalArgumentException in SuccList.stabilizeRebuild(int)";
                        break;
                    }
                }
            }
        }
    }

}
