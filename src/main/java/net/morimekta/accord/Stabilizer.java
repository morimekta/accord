package net.morimekta.accord;

import java.net.SocketTimeoutException;

import net.morimekta.accord.tables.LookupTable;
import net.morimekta.net.MessageSocket;
import net.morimekta.net.Location;
import net.morimekta.util.index.Index;
import net.morimekta.util.index.IndexFactory;
import net.morimekta.util.std.Log;
import net.morimekta.util.std.STD;
import net.morimekta.util.std.Log.Level;

/**
 * Stabilize-Manager made to stabilize a set of overlay tables in Accord.
 * 
 * Stabilizes predecessor, successor and finger table through a set of
 *  stability rules.<br>
 * <code><pre>
 * Counting Rules: (Calculated, not "checked") for a given N:
 * [1] tmp = (( N - min_succ ) * stable_succ_ratio )
 *     FC  = ( tmp > 0 ) ? ( N - min_succ - tmp ) : 0
 * [2] NC  = NC = N - FC
 * 
 * No Redundancy rule:
 * [3] FC = 0   : succ(NC) <= pred(NC) < me
 *     FC > 0   : me <= succ(NC) < finger(FC)
 *     
 * Completeness Rule:
 * [4] FC = 0   : NC  < min_succ : pred(NC)          <= succ(NC).succ(0)  < me
 *                NC >= min_succ : (FH(2)+(Imax>>3)) <= succs(NC)         < me
 *     FC > 0   : FH(FC+1) <= succ(nc) < finger(NC)
 * </pre></code><br>
 * With the simple "search" rule of if rule [3] is broken, decrease N, if rule [4] is
 * broken, increase N, else its OK.
 * 
 * @author Stein Eldar Johnsen
 */
public class Stabilizer {
    private final int         CORRECT                   = 0;    // ok.
    private final int         CSIZE                     = -1;   // break means too small tables to check.
    private final int         CRULE_3                   = 3;    // break means too large
    private final int         CRULE_4                   = 4;    // break means too small

    private long              concurrent_timeout        = 0;
    private long              backoff_timeout           = 0;
    private volatile boolean  is_stable                 = true;
    private volatile boolean  cont_cycle                = true;
    private int               lastN;
    
    private LookupTable       table;
    private Lookup            lookup;
    private IndexFactory      factory;
    private MessageSocket     socket;
    private Membership        membership;
    private Log               log;
    
    private Index             border_index;
    private Thread            stabilizer = new Thread() {
        public void run() {
            try {
                runLoop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };
    
    /**
     * Creates a stabilizer thread object, but does not start it.
     * @param _log 
     *     Logger to use.
     * @param _ft
     *     FingerTable to stabilize
     * @param _l
     *     LookupService to assist in localization of links.
     * @param _s 
     */
    public Stabilizer( Log _log, LookupTable _ft, Lookup _l, MessageSocket _s ) {
        log          = _log;
        table        = _ft;
        lookup       = _l;
        factory      = _ft.getMe().getIndex().getFactory();
        socket       = _s;
        
        border_index =
            _ft.getMe().getIndex().add(
                    factory.getImaxRshN(2).add(
                            factory.getIndex(STD.RshN(STD.zeroes(1), 3))));
        
        // start initializing.
        lastN         = _ft.succs().size() + _ft.fingers().size();
        check_stability();
        stabilizer.setDaemon(true);
        stabilizer.setName(table.getMe()+ ".Stabilizer" );
        //stabilizer.start();
    }
    
    /**
     * Checks stability if the lookuptable. If table is stable, it is marked stable, else it is marked unstable. Stable
     * means all locations in the table are reachable, and pred-list and succ-list are sequential with no "holes".
     * 
     * @return 
     *    True of stable.
     */
    private boolean check_stability() {
        /*
         * If any of the tables contain "dead" hosts (not alive), it is considered unstable. And as the tables are
         * marked unstable as nodes are marked not alive, we can just check the tables' stability.
         */
        is_stable = table.preds().isStable()
                 && table.succs().isStable()
                 && table.fingers().isStable();
        return is_stable;
    }
    
    /**
     * Finger Count, number of fingers accordingly to N.
     * @param n N
     * 
     * @return the optimal finger count.
     */
    private int FC(int n) {
        return n - NC(n);
    }
    
    /**
     * Neighbour Count, the number of neighbours (succs or preds).
     * @param n N
     * 
     * @return the optimal neighbour count.
     */
    private int NC(int n) {
        int    min   = Conf.stabilizer__min_succ;   // 3
        double ratio = Conf.stabilizer__succ_ratio; // 0.33
        if( n > min ) {
            return min + (int)( (n-min) * ratio );
        } else return n;
    }
    
    /**
     * Runs the Stabilizer event loop
     */
    private void runLoop() {
        log.log("Thread is started.", Level.high);
        
        synchronized ( this ) {
            while ( cont_cycle ) {
                try {
                    /*
                     * This should theoretically be the responsibility of iamalive or to interconnect these in some way.
                     * Note: Do not check the nodes too often, as it could congest the system (response problems) after
                     * a while... Still iamalive should not be prevented full operating because of updating
                     * neighbours...
                     */
                    if ( !is_stable ||
                         ( concurrent_timeout == 0 ) ||
                         ( concurrent_timeout < System.currentTimeMillis() ) ) {
                        stabilize_concurrent();
                        concurrent_timeout = System.currentTimeMillis() + Conf.stabilizer__concurrent;
                        
                        if ( !is_stable ||
                             ( backoff_timeout == 0 ) ||
                             ( backoff_timeout < System.currentTimeMillis() ) ) {
                            stabilize_backoff();
                            backoff_timeout = System.currentTimeMillis() + Conf.stabilizer__backoff;
                        }
                    }
                    // source of unfreed tickets was the ping system.
                    //log.log("Freeing "+socket.freeTickets()+" unfree'd locks.", Level.medium);
                    
                    // sleep for Config.stabilizer__cycle_time milliseconds.
                    wait( Conf.stabilizer__cycle_time );
                } catch ( InterruptedException e ) {
                    log.log("Interrupted while waiting.", Level.low);
                    // loop, just without the rest of the wait.
                } // try
            } // while
        } // sync
        
        log.log("Thread is stopped.", Level.high);
    } // run()
    
    /**
     * Start the Stabilizer.
     *
     */
    public synchronized void start() {
        try {
            stabilizer.start();
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
        }
    }
    /**
     * Stop the Stabilizer.
     *
     */
    public synchronized void stop() {
        try {
            if( cont_cycle ) {
                cont_cycle = false;
                stabilizer.interrupt();
                stabilizer.join();
            }
        } catch (InterruptedException e) {
            System.err.println("Join on Stabilizer interrupted.");
        }
    }
    
    /**
     * Tries to tell whether or not the list or table is stable. This should give a quite accurate view of whether or
     * not stabilize_backoff() must be run. Returning true will often mean the stabilize_backoff will not be run.
     * 
     * @return True if relative certain the list is stable.
     */
    public boolean is_stable() {
        return is_stable;
    }

    /**
     * Stabilize the most crucial part of the list. This will check and update the nodes in the list that the system is
     * wideliy dependent on.
     */
    private void stabilize_concurrent() {
        table.preds().stabilizeConcurrent();
        table.succs().stabilizeConcurrent();
        table.fingers().stabilizeConcurrent();
    }

    /**
     * Stabilize the part of the list that is not as crucial for the workings of the system as the concurrent part. Will
     * be run lots less than stabilize_concurrent().
     */
    private void stabilize_backoff() {
        if( table.succs().size() > 0 && table.preds().size() > 0 ) {
            log.log("backoff.start", Level.low);
            // first stabilize succs. ** ( succs have higher precedence than preds ) **
            table.preds().stabilizeBackoff();
            table.succs().stabilizeBackoff();
            table.fingers().stabilizeBackoff();
            // rebalance fingers/neighours
            stabilize_rebalance();
            check_stability();
            log.log("backoff.end "+table, Level.low);
        } else {
            log.log("backoff trivial tables", Level.low);
        }
    }
    
    /**
     * Rebalances the fingers and succ/pred lists after general stabilizing procedure.
     */
    private void stabilize_rebalance() {
        // select pre-N.
        // check status of NC, FC.
        
        // while unstable & less count
        //   check_stab
        //   rebuild
        //
        
        Location pred_last   = null,
                 succ_last   = null;
        int      pred_size   = 0,
                 succ_size   = 0,
                 finger_size = 0;
        try {
            log.log("backoff.rebalance.start", Level.medium);
            synchronized( table.succs() ){
                // synchronizes on entire table... prevents partial join _between_ locks.
                succ_size = table.succs().size();
                if(succ_size>0) succ_last = table.succs().get(-1);
            }
            synchronized ( table.preds() ) {
                pred_size = table.preds().size();
                if(pred_size>0) pred_last = table.preds().get(-1);
            }
            /*  */ if( pred_size==0 && succ_size==0 ) {
                log.log("backoff.rebalance: trivial tables.", Level.low);
                is_stable = true;
                return;
            } else if( succ_size==0 ) {
                // severily broken tables!
                log.log("backoff.rebalance: severily broken tables, no succs", Level.error);
                is_stable = false;
                throw new IllegalStateException("broken succs");
            } else if( pred_size==0 ) {
                // moderately broken tables, may be fixed...
                log.log("backoff.rebalance: broken tables, no preds", Level.warning);
                is_stable = false;
                throw new IllegalStateException("broken preds");
            } else {
                // we have both preds and succs...
                if( succ_size == pred_size ) {
                    // same size lists...
                    if( succ_size < Conf.stabilizer__min_succ ) {
                        if( succ_last.getIndex().between(pred_last.getIndex(), table.getMe().getIndex()) ){
                            // embracing tables covering _all_ nodes.
                            if( pred_last.getIndex().between(table.getMe().getIndex(), succ_last.getIndex()) ) {
                                // they embrace too much, remove one from each...
                                log.log("backoff.rebalance: Over-Embracing tables at length "+ succ_size, Level.low);
                                synchronized( table ) {
                                    table.preds().crop(succ_size -1); // safety against i-am--alive change.
                                    table.succs().crop(succ_size -1);
                                }
                                setStable(false);
                                return;
                            } else {
                                log.log("backoff.rebalance: Exact Embracing tables at length "+ succ_size, Level.low);
                                // perfec' its balanced!
                                setStable(true);
                                return;
                            }
                        } else {
                            if( lookup.lookup_table(succ_last, "succ:0", "").equals(pred_last) ) {
                                // semiembracing tables, OK.
                                log.log("backoff.rebalance: Semiembracing tables.", Level.low);
                                setStable(true);
                                return;
                            } else {
                                // insufficient tables, increase.
                                log.log("backoff.rebalance: Insufficient tables, inc by one.", Level.low);
                                succ_size++;
                                table.succs().stabilizeRebuild(succ_size);
                                table.preds().stabilizeRebuild(succ_size);
//                              table.fingers().stabilizeRebuild(0); // already empty.
                                setStable(false);
                                return;
                            }
                        }
                    } else if( succ_size == Conf.stabilizer__min_succ ) {
                        // exact size match... do a "validity" check.
                        if( succ_last.getIndex().between(
                                table.getMe().getIndex(),
                                border_index)){
                            // me < succ(last) <= me+(max>>2)+(max>>3) ... we need fingers!
                            log.log("backoff.rebalance: Unsuficient coverage of table, get fingers.", Level.low);
                        } else {
                            // we cover more than half ++ of table... OK.
                            log.log("backoff.rebalance: Coving 3/4 + of ring.", Level.low);
                            setStable(true);
                            return;
                        }
                    } else {
                        // tables are larger... go to "fingers".
                    }
                } else {
                    // lists are of different length ...
                    log.log("backoff.rebalance: Unequal succs ("+ succ_size+") and preds ("+pred_size+"), rebuilding preds.", Level.low);
                    synchronized ( table ) {
                        table.succs().stabilizeRebuild(succ_size);
                        table.preds().stabilizeRebuild(succ_size);
//                      table.fingers().stabilizeRebuild(0); // already empty.
                        table.preds().crop(succ_size); // just in case ?
                    }
                    if( table.succs().size() < Conf.stabilizer__min_succ ) {
                        setStable(false);
                        return;
                    }
                    // else rebalance fingers...
                }
            }
            
            ////////////////////////////////
            // REBALANCE FINGERS to SUCCS //
            ////////////////////////////////
            
            int     count       = 0;
            int     N           = lastN;
            
            //( while counting rule is not ok )
            while( count++ < Conf.stabilizer__rebalance_maxiter ){
                // add to tables if needed.
                table.succs().stabilizeRebuild(NC(N));
                table.preds().stabilizeRebuild(NC(N));
                table.fingers().stabilizeRebuild(FC(N)); // already empty.
                
                // if N nok, decrease.
                switch( stabilize_test_correctness(N) ) {
                    case CSIZE:        break;  // too small tables, rebuild again...
                    case CRULE_3: N--; break;  // too much redundancy...
                    case CRULE_4: N++; break;  // incomplete tables...
                    default: {
                        // TO-DO: crop tables down to N spec'ed size.
                        table.preds().crop(NC(N));
                        table.succs().crop(NC(N));
                        table.fingers().crop(FC(N));
                        succ_size   = N;
                        finger_size = 0; // fakes working sizes...
                        return ; // break + break ...
                    }
                }
            }
        } catch( IndexOutOfBoundsException e ) {
            // no content in preds and/or succs. Assume we have the trivial state (alone).
            setStable(false);
            return ;
        } catch (SocketTimeoutException e) {
            // not able to contact succ_last.
            setStable(false);
            return ;
        } catch (IllegalArgumentException e) {
            log.log("Rebalance: Illegal Argument to lookup_table.", Level.error);
            e.printStackTrace();
            System.exit(0);
            return ;
        } finally {
            lastN = succ_size + finger_size;
            log.log("Rebalance: Finished.", Level.medium);
        }
    }
        
    /**
     * Checking if the tables are accordingly to the stability rules (except the last) for a given N.
     * @param n
     *   N - as in the basic stabilization rules.
     * @return
     *   CORRECT if the tables fulfill all the rules relative to N, CSMALL if there are problems with the
     *   table sizes, and CRULE_N otherwise (broke on correctness rule N).
     */
    private int stabilize_test_correctness(int n){
        if( n < 1 ){
            return CRULE_4;
        }
        /*
         * Counting Rules: (Calculated, not "checked") for a given N:
         * [1] tmp = (( N - min_succ ) * stable_succ_ratio )
         *     FC  = ( tmp > 0 ) ? ( N - min_succ - tmp ) : 0
         * [2] NC  = NC = N - FC
         * 
         * No Redundancy rule*:
         * [3] FC = 0   : succ(NC) <= pred(NC) < me
         *     FC > 0   : me <= succ(NC) < finger(FC)
         *   
         * Completeness Rule:
         * [4] FC = 0   : NC  < min_succ  : pred(NC)          <= succ(NC).succ(0)  < me
         *                NC >= min_succ :  (FH(2)+(Imax>>3)) <= succs(NC)         < me
         *     FC > 0   : FH(FC+1) <= succ(nc) < finger(NC)
         */
        Location tmp;
        int fc = FC(n);    // [1]
        int nc = NC(n);    // [2]
        try {
            //log.log("Testing N("+n+"): NC = "+nc+" FC = "+fc+".", Level.low); /**/
            
            // [3] No Redundancy Rule
            if( fc == 0 ){
                // must check that the tables does not wrap around more than 1 element...
                if( table.succs().get(nc-1).getIndex().between(
                        table.preds().get(nc-1).getIndex(),
                        table.getMe().getIndex() ) ){
                    // they wrap...
                    if( table.preds().get(nc-1).equals(table.succs().get(nc-1)) ) {
                        // last is the same on the end. Its OK.
                    } else {
                        log.log("backoff.correctness: broke [3].1", Level.low); /**/
                        return CRULE_3;
                    }
                } else {
                    // they dont wrap... => no redundancy.
                }
            } else {
                if( ! table.succs().get(nc -1).getIndex().between(
                        table.getMe().getIndex(),
                        table.fingers().get(fc -1).getIndex()) ){
                    log.log("backoff.correctness: broke [3].2", Level.low); /**/
                    return CRULE_3;
                }
            }
            
            // [4] Completeness Test.
            if( fc == 0 ){
                if( nc < Conf.stabilizer__min_succ ) {
                    // nc is small, must preserve as much as possible of tables.
                    // nc < min_succ, it must wrap or almost wrap...
                    tmp = lookup.lookup_table(
                            table.succs().get(nc -1),
                            "succ:0", "");
                    if( tmp == null ) throw new SocketTimeoutException("succs(nc) has bas tables.");
                    if( tmp.getIndex().between(
                            table.preds().get(nc-1).getIndex(),
                            table.getMe().getIndex()) ) {
                        // Succ(nc).next is OK.
                    } else {
                        log.log("backoff.correctness: broke [4].1-1", Level.low);
                        return CRULE_4;
                    }
                } else {
                    // nc is larger, must have a more relazed criteria.
                    // nc >= min_succ, Imax>>2 <= succs(nc) < me
                    if( table.succs().get(nc-1).getIndex().between(
                            border_index,
                            table.getMe().getIndex()) ) {
                        // succ is relative to next Finger. OK.
                    } else {
                        log.log("backoff.correctness: broke [4].1-2", Level.low);
                        return CRULE_4;
                    }
                }
            } else {
                if( table.getMe().getIndex().add( factory.getImaxRshN( n+1 ) ).between(
                        table.succs().get(nc-1).getIndex(),
                        table.fingers().get(fc-1).getIndex()) ){
                    log.log("backoff.correctness: broke [4].2", Level.low); /**/
                    return CRULE_4;
                }
            }
            
            log.log("backoff.correctness: success N="+n, Level.low);
            return CORRECT;
        } catch ( IndexOutOfBoundsException ioobe ) {
            log.log("backoff.correctness: broke on too small tables.", Level.medium); /**/
            return CSIZE;
        } catch ( SocketTimeoutException e ) {
            log.log("backoff.correctness: broke on SocketTimeoutException: "+e.getMessage(), Level.medium); /**/
            return CSIZE;
        } catch ( IllegalArgumentException e ) {
            System.err.println("backoff.correctness: IllegalArgumentException "+e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return CSIZE;
        } catch ( NullPointerException e ) {
            System.err.println("backoff.correctness: NullPointerException "+e.getMessage());
            return CSIZE;
        }
    }
    
    /**
     * Set wether the tables the stabilizer is stabilizing is considered stable.
     * 
     * @param stable Boolean true or false.
     */
    public void setStable( boolean stable ) {
        is_stable = stable;
    }
}
