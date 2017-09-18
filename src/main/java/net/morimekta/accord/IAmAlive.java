package net.morimekta.accord;

import java.net.UnknownHostException;
import java.util.LinkedList;

import net.morimekta.accord.tables.LookupTable;
import net.morimekta.accord.tables.OverlayContainer;
import net.morimekta.accord.tables.PredList;
import net.morimekta.accord.tables.SuccList;
import net.morimekta.accord.tables.OverlayContainer.Sorting;
import net.morimekta.net.Location;
import net.morimekta.net.Message;
import net.morimekta.net.MessageService;
import net.morimekta.net.MessageSocket;
import net.morimekta.util.std.Log;
import net.morimekta.util.std.Options;
import net.morimekta.util.std.Log.Level;
/**
 * The I-Am-Alive protocol, both participant and initiator / thread.
 * 
 * @author Stein Eldar Johnsen
 */
public class IAmAlive implements MessageService {
    private Log                 log;
    private volatile long       pred_ver; // last version of the pred list.
    private volatile long       pred_iaa_ver = 0; // last version received from pred(0).
    private volatile int        pred_count = 0;
    private volatile long       succ_ver; // last version of the succ list.
    private volatile long       succ_iaa_ver = 0; // last version received from succ(0).
    private volatile int        succ_count = 0;
    private LookupTable         table; // used for getLocation and as mutex.
    private Location            me;
    private Membership          membership;
    private OverlayContainer    pred;
    private OverlayContainer    succ;
    private MessageSocket       socket;
    private Message             alive2pred;
    private Message             alive2succ;
    private volatile boolean    work;
    
    private class IAmAliveOptions extends Options {
        long version;
        public IAmAliveOptions(String _opts) { super(_opts); }
        public void parse(char op, String arg) {
            switch( op ) {
            case 'v':
                try {
                    version = Long.parseLong(arg);
                } catch (NumberFormatException e) {
                    version = -1;
                }
                break;
            default:
                break;
            }
        }
    }
    private static String optline="v,version,int";
    private IAmAliveOptions opts = new IAmAliveOptions(optline);
    private Thread daemon = new Thread() {
        public void run() {
            try {
                runLoop();
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    };
    /**
     * Builds the alive messages:<ul>
     * <li><code> i-am-alive \0 </code>
     * <li><code> [--version version]</code>
     * <li><code> 1 host1:port</code>
     * <li><code> 2 host2:port</code>
     * </ul>
     * 
     * @param force
     *     Fortce the method to build, even if versions are Ok,
     */
    private void build_messages(boolean force){
        String   str;
        
        if( force ) {
            pred_ver = -1;
            succ_ver = -1;
        }
        
        // alive-to-succ
        if( pred_ver != pred.version() ){
            synchronized ( pred ) {
                pred_ver = pred.version();
                str = "--version " + pred_ver +"\n";
                for( Location cur : pred ) {
                    str += cur + "\n";
                }
            }
            alive2succ = new Message(0, 0, 0, getServiceName(), str);
        }
        // alive-to-pred
        if( pred_ver != pred.version() ){
            synchronized ( succ ) {
                succ_ver = succ.version();
                str = "--version " + succ_ver +"\n";
                for( Location cur : succ ) {
                    str += cur + "\n";
                }
            }
            alive2pred = new Message(0, 0, 0, getServiceName(), str);
        }
        // done.
    }
    
    /**
     * Create an IAmAlive service provider.
     * 
     * @param _log
     *   Log to log on.
     * @param _lt
     *   Lookup Table to work on.
     * @param _mm
     *   MembershipManager to notice inactivity to.
     * @param _sock
     *   Socket to send and receive messages on.
     */
    public IAmAlive(Log _log, LookupTable _lt, Membership _mm, MessageSocket _sock){
        log         = _log;
        me          = _lt.getMe();
        table       = _lt;
        pred        = _lt.preds();
        succ        = _lt.succs();
        pred_ver    = -1;
        succ_ver    = -1;
        socket      = _sock;
        work        = true;
        membership  = _mm;
        
        build_messages(true);
        
        daemon.setDaemon(true);
        daemon.setName(me+".IAmAlive");
    }
    
    /**
     * Runs the I-Am-Alive protocol.
     */
    private synchronized void runLoop(){
        boolean  pred_unstable,
                 succ_unstable;
        Location succ0 = null,
                 pred0 = null;
        long     stat_timeout = 0;
        log.log("starting thread", Level.high);
        
        while( work ){
            try{
                pred_unstable = false;
                succ_unstable = false;
                //log.log("Looping.",LogFile.INFO);
                /*
                 * Check for too long timeout periods.
                 * No point in managing empty tables!
                 */
                synchronized ( pred ) {
                    if( ( pred.size() > 0 ) &&
                        (( pred.get(0).getTimestamp() + Conf.iamalive__leave_timeout ) < System.currentTimeMillis()) ){
                        pred0 = pred.get(0);
                        log.log("unstable pred:0 "+pred0, Level.medium);
                        pred.setStable(false);
                        pred_unstable = true;
                    }
                }
                synchronized ( succ ) {
                    if( ( succ.size() > 0 ) &&
                        (( succ.get(0).getTimestamp() + Conf.iamalive__leave_timeout ) < System.currentTimeMillis()) ){
                        succ0 = succ.get(0);
                        log.log("unstable succ:0 "+succ0, Level.medium);
                        succ.setStable(false);
                        succ_unstable = true;
                    }
                }
                
                if( succ_unstable ){
                    membership.checkLeave(succ0);
                }
                if( pred_unstable ){
                    // do nothing !!! let the node's parent (pred) to take care of it.
                }
                
                /*
                 * We need to rebuild messages if the table
                 * is modified since last build.
                 */
                build_messages(false);
                
                /*
                 * Send i-am-alive messages.
                 */
                synchronized ( pred ) {
                    if( pred.size() > 0 ){
                    	socket.send(pred.get(0), alive2pred);
                    }
                }
                
                synchronized ( succ ) {
                    if( succ.size() > 0 ){
                    	socket.send(succ.get(0), alive2succ);
                    }
                }
                
                if( stat_timeout < System.currentTimeMillis() ) {
                    stat_timeout = System.currentTimeMillis()+Conf.iamalive__cycle_time*30;
                    log.log("i-am-alives: succ="+succ_count+" pred="+pred_count, Level.low);
                    succ_count = 0;
                    pred_count = 0;
                }
                
                
                wait( Conf.iamalive__cycle_time );
            } catch( InterruptedException e ){
                // just loop.
                log.log("Thread Interrupted.", Level.high);
            }
        }
        
        log.log("Thread is stopped.", Level.high);
    }
    
    /**
     * Start the IAmAlive Service Thread
     *
     */
    public synchronized void start() {
        if( !daemon.isAlive() ) {
            work = true;
            daemon.start();
        }
    }
    
    /**
     * Stop the IAA Thread.
     *
     */
    public synchronized void stop(){
    	try {
            if( work ) {
                work = false;
                daemon.interrupt();
                daemon.join();
            }
		} catch (InterruptedException e) {
			System.err.println("Join on IAmAlive interrupted.");
		}
    }
    
    public String getServiceName(){
        return "i-am-alive";
    }

    public synchronized void invoke( Message msg ) {
        Location loc;
        //Location cur;
        Location in;
        boolean  is_pred0 = false;
        boolean  is_succ0 = false;
        long     version;

        String[] lines;
        
        /*
         * For now, we dont care wether or not the node is the "first" of
         * anything...
         */
        if( (loc = table.getLocation(msg.getSender())) == null ) {
            log.log("unknown host "+msg.getSender(), Level.low);
            return;
        }
        // have no idea who's talking to me...
        // set the location as active in FingerTable.
        loc.setTimestamp(System.currentTimeMillis());
        // check if first of anything...
        if( pred.indexOf(loc) == 0 ) {
            is_pred0 = true;
            pred_count++;
        }
        if( succ.indexOf(loc) == 0 ) {
            is_succ0 = true;
            succ_count++;
        }
        
        lines = msg.getMessage().split("\n");
        opts.version = -1; // no attached version should not go through...
        opts.run(lines[0]);
        version = opts.version;
        
        if( version == -1 ) {
            log.log("version not given from "+msg.getSender(), Level.warning);
        }
        
        // nothing to use the first line for ... yet...
        // check for the need for iterating...
        if( is_pred0 && is_succ0 ){
            //log.log("Got I-Am-Alive from sole neighbour: "+loc, Level.low);
        } else if( is_pred0 || is_succ0 ){
            if( is_pred0 ) {
                //log.log("Got I-Am-Alive from pred[0] = "+loc, Level.low);
                if( pred_iaa_ver == version || version == 0 ) return; // ignore and stop.
                else pred_iaa_ver = version; // update.
            } else { // is_succ0.
                //log.log("Got I-Am-Alive from succ[0] = "+loc, Level.low);
                if( succ_iaa_ver == version || version == 0 ) return; // same for succ.
                else succ_iaa_ver = version; // update.
            }
            
            LinkedList<Location> incoming = new LinkedList<Location>();
            
            for( int i = 1; i < lines.length; i++ ) {
                try {
                    in    = new Location(lines[i]);  // host:port (Location)
                    loc   = table.getLocation(in);      // check if we already know this location in our tables.
                    if( loc == null )  loc = in;        // lets use the incoming locaiton...
                    if( loc.equals(me) ) break;         // self or no location... done !!!.
                    incoming.addLast(loc);
                } catch (UnknownHostException e) {
                    continue;
                }
            }
            // replace locations in between ... but keep our last
            if( incoming.size() < 1 ) return;
            else {
                OverlayContainer   local  = ( is_pred0 ? pred : succ );
                OverlayContainer   remove = ( is_pred0 ? new PredList(local) : new SuccList(local) );
                remove.removeAll(incoming); // local but not incoming.
                incoming.removeAll(local);  // no point in adding existing elements.
                Location           last  = local.get(-1);
                // remove all removes "after" last local.
                // These would not be in "incoming" anyway...
                while( remove.size() > 0 && 
                       ( local.getSorting() == Sorting.DESCENDING ?
                         remove.get(-1).getIndex().between(me.getIndex(), last.getIndex()) :
                         remove.get(-1).getIndex().between(last.getIndex(), me.getIndex()) ) ) {
                    remove.remove(-1);
                }
                // remove all incoming "after" last local.
                // this prevents ever-growing neighbor lists.
                while( incoming.size() > 0 &&
                       ( local.getSorting() == Sorting.DESCENDING ?
                         incoming.getLast().getIndex().between(me.getIndex(), last.getIndex()) :
                         incoming.getLast().getIndex().between(last.getIndex(), me.getIndex()) ) ) {
                    incoming.removeLast();
                }
                
                if( remove.size() > 0 || incoming.size() > 0 ) {
                    synchronized ( local ) {
                        // remove all before last that is not in message.
                        local.removeAll(remove);
                        // add from message ... within the limits of the list.
                        local.addAllSorted(incoming);
                    }
                }
                // done.
            }
        } else {
            // not twosome ...
            log.log("Got I-Am-Alive from non-neighbor node: "+loc, Level.low);
        }
    } // invoke
}
