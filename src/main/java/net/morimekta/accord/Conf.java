/*
 * Created on Apr 29, 2005
 */
package net.morimekta.accord;

import net.morimekta.accord.Lookup.Iter;
import net.morimekta.util.std.Config;
import net.morimekta.util.std.Log.Level;

public class Conf extends Config {
    /*
     * GLOBAL
     */
    public volatile static int   msg_timeout = 150; // "default"
    public volatile static Level logging = Level.all;
    public volatile static Level verbose = Level.high;
    public volatile static int   port;
    
    /*
     * I-AM-ALIVE
     */
    public volatile static int  iamalive__cycle_time;
    public volatile static long iamalive__leave_timeout;
    
    /*
     * LOOKUP
     */
    public volatile static int  lookup__timeout = 300;
    public volatile static Iter lookup__iterate = Iter.no_safe;
    
    /*
     * MEMBERSHIP
     */
    public volatile static long membership__connect_msg_timeout;
    public volatile static long membership__connect_timeout;
    public volatile static long membership__joinpred_timeout;
    public volatile static long membership__joinpred_msg_timeout;
    
    /*
     * STABILIZER (Tables)
     */
    public volatile static int    stabilizer__alive_timeout;
    public volatile static int    stabilizer__alive_ping_count;
    public volatile static long   stabilizer__ping_timeout;
    public volatile static int    stabilizer__min_succ;
    public volatile static double stabilizer__succ_ratio;
    public volatile static long   stabilizer__concurrent;
    public volatile static long   stabilizer__backoff;
    public volatile static long   stabilizer__cycle_time;
    public volatile static int    stabilizer__rebalance_maxiter;
}
