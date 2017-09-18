package net.morimekta.accord;


import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import net.morimekta.accord.tables.LookupTable;
import net.morimekta.net.Location;
import net.morimekta.net.Message;
import net.morimekta.net.MessageService;
import net.morimekta.net.MessageSocket;
import net.morimekta.util.index.Index;
import net.morimekta.util.std.Log;
import net.morimekta.util.std.Options;
import net.morimekta.util.std.STD;
import net.morimekta.util.std.Log.Level;

/**
 * Membership manages the join and leave protocols.
 * 
 * This class only contains the "change" protocols, not any monitoring.
 * 
 * @author Stein Eldar Johnsen
 */
class Membership implements MessageService {
    private void prepare( Oper op ) {
        log.log("Prepare: "+op, Level.low);
    }
    private void notify( Oper op, Oper res, String msg ) {
        log.log("Notify: "+op+" resulted in "+res+" with message: "+msg, Level.low);
    }
    
    // Exceptional Exit States.
    private abstract class ExitState   extends Exception {
        private static final long serialVersionUID = 1L;
        public ExitState(String exitMsg){
            super(exitMsg);
        }
    }
    private class          AckState    extends ExitState {
        private static final long serialVersionUID = 2L;
        public AckState(String ackMsg){
            super(ackMsg);
        }
    }
    private class          AbortState  extends ExitState {
        private static final long serialVersionUID = 3L;
        public AbortState(String abortMsg){
            super(abortMsg);
        }
    }
    private class          CommitState extends ExitState {
        private static final long serialVersionUID = 1L;
        public CommitState(String exitMsg) {
            super(exitMsg);
        }        
    }
    
    
    private String         join_string = 
            "h,host,string\n" +
            "m,msg,string\n" +
            "s,succs,string";
    private String         leave_string =
            "h,host,string\n" +
            "M,mypred,string\n" +
            "m,msg,string\n" +
            "c,check\n" +
            "n,no-check\n" +
            "r,respond\n" +
            "N,no-response";
    private JoinOptions    join        = new JoinOptions();
    private LeaveOptions   leave       = new LeaveOptions();
    
    private class JoinOptions extends Options{
        Location host;
        String   msg;
        String   succ;
        JoinOptions(){ super(join_string); }
        public void parse( char op, String arg ) {
            switch ( op ) {
                case 'h':
                    try {
                        host = new Location(arg);
                    } catch (UnknownHostException e) {
                        // ignore.
                    }
                    break;
                case 'm':
                    msg = arg;
                    break;
                case 's':
                	succ = arg;
                	break;
                case '?':
                default:
                    break;
            }
        }
    }
    private class LeaveOptions extends Options{
        Location host;
        Location mypred;
        String   msg;
        boolean  check;
        boolean  respond;
        public LeaveOptions() { super(leave_string); }
        public void parse( char op, String arg ) {
            switch ( op ) {
            case 'h':
                try {
                    host = new Location(arg);
                } catch (UnknownHostException e) {
                    // ignore.
                }
                break;
            case 'M':
                try {
                    mypred = new Location(arg);
                } catch (UnknownHostException e) {
                    // ignore.
                }
                break;
            case 'm':
                msg = arg;
                break;
            case 'c':
                check = true;
                break;
            case 'n':
                check = false;
                break;
            case 'r':
                respond = true;
                break;
            case 'N':
                respond = false;
                break;
            case '?':
            default:
                break;
            }
        }
    }
    
    /*
     * MEMBERSHIP MANAGEMENT CONCURRENCY CONTROL FOR SUCCESSOR
     */
    private volatile     Thread   mm_succ_thread = null;
    private volatile     Location mm_succ_loc    = null;
    private volatile     int      mm_succ_ticket = 0;
    private volatile     Oper     mm_succ_op     = Oper.ready; // no oper...
    
    /**
     * Begin the Membership Management for this' successor.
     * 
     * @param host
     *     The host in question.
     * @param ticket
     *     The ticket number the session is using.
     * @param op
     *     The operation the session is working on.
     * @throws AckState
     *     If the same host is conducting the same operation on this manager.
     * @throws AbortState 
     *     If this manager is working on a different succ, or a different
     */
    private synchronized void     mm_succ_begin(Location host, int ticket, Oper op) throws AckState, AbortState {
        if( host == null || ticket <= 0 || ticket > 255 )
            throw new IllegalArgumentException("illegal host or ticket");
        if( mm_succ_thread == null ) {
            mm_succ_thread = Thread.currentThread();
            mm_succ_loc    = host;
            mm_succ_ticket = ticket;
            mm_succ_op     = op;
        } else {
            log.log("Unable to start mm_succ session.", Level.medium);
            System.err.println(" - Unable to start mm_succ session.");
            if( host.equals(mm_succ_loc) && op == mm_succ_op ) {
                throw new AckState(Ack.initiated+" --ticket "+mm_succ_ticket);
            } else {
                throw new AbortState(Abort.concurrent_conflict+" --host "+mm_succ_loc+" --ticket "+mm_succ_ticket);
            }
        }
    }
    
    /**
     * End the locking membership management of this' successor. If there is no
     * such session, ignore.
     * 
     * @see Membership#mm_succ_begin(Location,int,Oper)
     */
    private synchronized void     mm_succ_end() {
        if( Thread.currentThread() == mm_succ_thread ) {
            mm_succ_thread = null;
            mm_succ_loc    = null;
            mm_succ_ticket = 0;
            mm_succ_op     = Oper.ready;
        } else {
            log.log("Ending not started mm_succ session.", Level.medium);
            System.err.println(" - Ending non-started mm_succ session.");
        }
    }
    
    
    /*
     * MEMBERSHIP MANAGEMENT CONCURRENCY CONTROL FOR PREDECESSOR
     */
    private volatile     Thread   mm_pred_thread = null;
    private volatile     Location mm_pred_loc    = null;
    private volatile     int      mm_pred_ticket = 0;
    private volatile     Oper     mm_pred_op     = Oper.ready; // no oper...
    
    /**
     * Begin the Membership Management for this' predecessor.
     * 
     * @param host
     *     The host in question.
     * @param ticket
     *     The ticket number the session is using.
     * @param op
     *     The operation the session is working on.
     * @throws AckState
     *     If the same host is conducting the same operation on this manager.
     * @throws AbortState 
     *     If this manager is working on a different succ, or a different
     * @see Membership#mm_succ_begin(Location, int, Oper)
     */
    private synchronized void     mm_pred_begin(Location host, int ticket, Oper op) throws AckState, AbortState {
        if( host == null || ticket <= 0 || ticket > 255 )
            throw new IllegalArgumentException("illegal host or ticket");
        if( mm_pred_thread == null ) {
            mm_pred_thread = Thread.currentThread();
            mm_pred_loc    = host;
            mm_pred_ticket = ticket;
            mm_pred_op     = op;
        } else {
            log.log("Unable to start mm_pred session.", Level.medium);
            if( host.equals(mm_pred_loc) && op == mm_pred_op ) {
                throw new AckState(Ack.initiated+" --ticket "+mm_pred_ticket);
            } else {
                throw new AbortState(Abort.concurrent_conflict+" --host "+mm_pred_loc+" --ticket "+mm_pred_ticket);
            }
        }
    }
    
    /**
     * End the locking membership management of this' predecessor. If there is no
     * such session, ignore.
     * 
     * @see Membership#mm_pred_begin(Location,int,Oper)
     */
    private synchronized void     mm_pred_end() {
        if( Thread.currentThread() == mm_pred_thread ) {
            mm_pred_thread = null;
            mm_pred_loc    = null;
            mm_pred_ticket = 0;
            mm_pred_op     = Oper.ready;
        } else {
            log.log("Ending not started mm_pred session.", Level.medium);
        }
    }
    
    /**
     * Operations for Concurrency Control, logging and messages.
     * 
     * The first two (connect, disconnect) are "meta"-operations, as
     * they are only used for logging.  The next four are the
     * basic perations (join and leave for succ and pred).
     * The last four are in-session operations.
     * 
     * @author Stein Eldar Johnsen
     */
    private enum Oper {
        // "meta" operations for prepare/notify.
        connect,
        disconnect,
        // Basic Operations
        join,
        join_pred,
        leave,
        leave_pred,
        // Session Operations
        ack,
        abort,
        ready,
        commit,
    }
    
    /**
     * Acknowledge Messages.
     * 
     * @author Stein Eldar Johnsen
     */
    private enum Ack {
        confirm,
        initiated,
        table_safe,
    }
    
    /**
     * Abort Messages
     * 
     * @author Stein Eldar Johnsen
     */
    private enum Abort {
        concurrent_conflict,
        index_collision,
        wrong_host,
        timeout,
        internal,
        cascading,
    }
        
    private static final String OPERATION        = "membership";
    
    private Log           log;
    private LookupTable   table;
    private Lookup        lookup;
    private MessageSocket socket;        
    
    /**
     * Create a new MembershipManager object.
     * 
     * @param _log
     *   Log to log on.
     * @param _table
     *   LookupTable to manage.
     * @param _lookup
     *   Lookup service to look up neighbors.
     * @param _socket
     *   Socket to send and receive messages.
     */
    public Membership(Log _log, LookupTable _table, Lookup _lookup, MessageSocket _socket) {
        log    = _log;
        table  = _table;
        lookup = _lookup;
        socket = _socket;
    }
    
    /**
     * Server side Join-Pred protocol implementation.
     * 
     * @param msg
     *   Join-Pred Message from Join master with joiner and arguments.
     */
    private void joinPred( Message msg ){
        // initialize variables.
        Message  in, out;
        Location joiner;
        String   join_args;
        int      local_ticket = 0;
        long     timeout;
        {
            String[] ops;
            ops = STD.splitString(msg.getMessage());
            ops[0] = null; // ignore "join-pred"
            join_args = STD.assembleString(ops);
            synchronized ( join ) {
                join.host = null;
                join.run(ops);
                joiner = join.host;
            }
        }
        try {
            log.log("JoinPred: Initializing with joiner "+joiner+".", Level.high);
            local_ticket = socket.requestTicket();
            mm_pred_begin(joiner, local_ticket, Oper.join_pred);
            // check validity of joiner. (but should be validated by "master".)
            try {
                synchronized( table.preds() ){
                    if( joiner.equals(table.preds().get(0)) ){
                        throw new AckState(Ack.confirm.toString());
                    } else if(!joiner.getIndex().between(
                            table.preds().get(0).getIndex(),
                            table.getMe().getIndex())) {
                        throw new AbortState(Abort.wrong_host.toString());
                    }
                }
            } catch ( IndexOutOfBoundsException ioobe ) {
                // We are alone, but was called uppon regarding JoinPred anyway!!!
                throw new AbortState(Abort.internal+" --msg \"index out of bounds\"");
            }
            // Joiner is OK. Lets PREPARE and send READY.
            prepare(Oper.join_pred);
            in = null;
            out = new Message(
                    local_ticket, msg.getFromTicket(), 0,
                    Oper.ready.toString(), join_args);
            timeout = System.currentTimeMillis() + Conf.membership__joinpred_timeout;
            while( in == null ) {
                try {
                    // send VOTE-COMMIT / READY meaage, and wait for reply.
                    socket.send(msg.getSender(), out);
                    in = socket.receive(local_ticket, msg.getSender());
                    /**/ if( Oper.commit.toString().equals(in.getOperation()) ); // continue ...
                    else if( Oper.abort.toString().equals(in.getOperation()) ) {
                        notify(Oper.join_pred, Oper.abort, in.getMessage());
                        log.log("JoinPred: Got abort from master, abort with no response.", Level.medium);
                        return; // no response.
                    } else throw new SocketTimeoutException(); // check for timeout and loop.
                } catch (SocketTimeoutException e) {
                    if( System.currentTimeMillis() > timeout ) {
                        notify(Oper.join_pred, Oper.abort, Abort.timeout.toString());
                        throw new AbortState(Abort.timeout.toString());
                    }
                    in = null; // loop.
                }
            }
            // we got COMMIT.
            synchronized ( table ) {
                table.preds().add(0, joiner);
            }
            notify(Oper.join_pred, Oper.commit, "");
            in = null;
            out = new Message(
                    local_ticket, msg.getFromTicket(), 0,
                    Oper.ack.toString(), Ack.confirm.toString());
            try {
                while( in == null ) {
                    socket.send(msg.getSender(), out);
                    in = socket.receive(local_ticket, msg.getSender(), Conf.membership__connect_msg_timeout*3);
                    /**/ if( !Oper.ack.toString().equals(in.getOperation()) )   in = null; // ignore non-ack msgs.
                    else if( !in.getMessage().startsWith(Oper.ack.toString()) ) in = null;   // and non-ack-ack.
                }
                log.log("JoinPred: Got Ack-Ack on Ack-Commit.", Level.low);
            } catch( SocketTimeoutException ste ) {
                // leave clean.
                log.log("JoinPred: No response from join master! Maybe the message was lost.", Level.low);
            }
            log.log("JoinPred: Committed and finished Acking.", Level.low);
        } catch ( AckState ack ) {
            out = new Message(
                    mm_pred_ticket, msg.getFromTicket(), 0,
                    Oper.ack.toString(), ack.getMessage());
            socket.send(msg.getSender(), out);
            log.log("JoinPred: Acked with message \""+ack.getMessage()+"\"", Level.medium);
        } catch ( AbortState abort ) {
            out = new Message(
                    0, msg.getFromTicket(), 0,
                    Oper.abort.toString(), abort.getMessage());
            socket.send(msg.getSender(), out);
            log.log("JoinPred: Acked with message \""+abort.getMessage()+"\"", Level.medium);
        } finally {
            mm_pred_end();
            if( local_ticket > 0 ) socket.freeTicket(local_ticket);
            log.log("JoinPred: Ended and released resources.", Level.high);
        }
    }
    
    /**
     * Server side Join Protocol implementation. This initializes the Join-Pred
     *  protocol, and  makes shure the joiner adds its Overlay Links before the
     *  ring adds the joiner.
     *  
     * @param msg
     *   Join Message from the joiner, with arguments.
     */
    private void join( Message msg ){
        Message  in, out;
        Location joiner;
        String   join_args;
        int      local_ticket  = 0;
        //int      jp_lo_ticket  = 0;
        int      jp_re_ticket  = 0;
        long     timeout       = 0;
        Location jp_host       = null;
        { // initialize arguments.
            String[] ops;
            ops          = STD.splitString(msg.getMessage());
            ops[0]       = null;           // exclude "join"
            join_args    = STD.assembleString(ops);
            synchronized ( join ) {
                join.host = null;
                join.run(ops);
                joiner = join.host;
            }
        }
        try { // main end states.
            log.log("Join: Initiated with joiner "+joiner+".", Level.high);
            local_ticket = socket.requestTicket();
            mm_succ_begin(joiner, local_ticket, Oper.join);
            // if index collision, abort.
            if( table.getMe().getIndex().equals(joiner.getIndex()) ){
                // index collision.
                throw new AbortState(Abort.index_collision.toString());
            } else {
                // if get succ(0), if it exists ++ 
                // ... separate to avoid long locking on succlist.
                // syncing on table to avoid dead-locking.
                synchronized( table ){
                    try { // initiate states.
                        jp_host = table.succs().get(0);
                        if( joiner.getIndex().between(table.getMe().getIndex(), jp_host.getIndex()) ){
                            // is my resp.
                            log.log("Join: succ(0) = "+jp_host+".", Level.low);
                        } else if( joiner.equals(table.succs().get(0)) ){
                            // succ0 == joiner.
                            if( table.preds().indexOf(joiner) == 0 ) {
                                jp_host = table.getMe();
                                // it is only me and him, and he has joined correctly.
                                throw new AckState(Ack.confirm.toString());
                            } else {
                                try {
                                    jp_host = table.succs().get(1);
                                } catch( IndexOutOfBoundsException e ){
                                    jp_host = table.preds().get(-1);
                                    if( jp_host.equals(joiner) ) {
                                        try {
                                            jp_host = table.preds().get(-2);
                                        } catch( IndexOutOfBoundsException ioobe ) {
                                            // should not ever happen!
                                            log.log("Join: Internal error, preds modified...", Level.error);
                                            throw new AbortState(Abort.internal+" --msg \"index out of bounds\"");
                                        }
                                    }
                                }
                                log.log("Join: joiner == succ(0), using "+jp_host+" instead.", Level.low);
                            }
                        } else {
                            throw new AbortState(Abort.wrong_host.toString());
                        }
                    } catch ( IndexOutOfBoundsException e ) {
                        // succ or pred is empty, => we are alone, so just continue.
                        jp_host = null;
                        log.log("Join: no succ(0), cont. with succ0 = null", Level.low);
                    }
                }
            }
            ////////////////////
            // INITIAL STATE. //
            ////////////////////
            // if no jp_host, jump to READY.
            if( jp_host != null ){
                try {
                    log.log("Join: Initialize Join-Pred with "+jp_host, Level.medium);
                    // JP_INIT.
                    in = null;
                    out = new Message(
                            local_ticket, 0, 0,
                            getServiceName(), Oper.join_pred+" "+join_args);
                    
                    log.log("Join: Sending message to "+jp_host+": \n"+out, Level.low);
                    
                    timeout = System.currentTimeMillis() + Conf.membership__joinpred_timeout;
                    socket.send(jp_host, out);
                    while( in == null){
                        try {
                            //log.log("Join: Sending JP message to "+jp_host, Level.low);
                            //socket.send(jp_host, out);
                            //log.log("Join: Receiving JP message on ticket "+local_ticket, Level.low);
                            in = socket.receive(local_ticket, jp_host, Conf.msg_timeout);
                            //log.log("Join: Received JP message on ticket "+local_ticket+"\n"+in, Level.low);
                            /**/ if( Oper.ready.toString() .equals(in.getOperation()) ); // go on.
                            else if( Oper.ack.toString()   .equals(in.getOperation()) ){
                                if( in.getMessage().startsWith(Ack.confirm.toString()) ) {
                                    throw new AckState(in.getMessage());
                                }
                                // else its ACK_INITIATED.
                                in = null; // wait a little more ...
                                continue;  // avoid ticket reading.
                            }
                            // the ABORT is only if jp_host had a known internal
                            // error that made the join impossible.
                            else if( Oper.abort.toString() .equals(in.getOperation()) ) throw new AbortState(in.getMessage());
                            // unknown type of message...
                            else {
                                // throw new SocketTimeoutException();
                                in = null; // loop without sending.
                            }
                            jp_re_ticket = in.getFromTicket();
                            // + break loop.
                        } catch ( SocketTimeoutException e1 ) {
                            if( System.currentTimeMillis() > timeout ){
                                // dont clear jp_re_ticket, we need to jp_abort.
                                throw new AbortState(Abort.timeout.toString());
                            }
                            log.log("Sending message to "+jp_host+": \n"+out, Level.low);
                            socket.send(jp_host, out);
                            in = null; // loop.
                        }
                    }
                } catch( AbortState as ){
                    // if it is already in the ring!
                    if( ( table.succs().size() > 0 ) &&
                        ( table.succs().get(0).equals(joiner) ) ) {

                        // We have to remove the succ(0) because it is already there...
                        log.log("Join: Succ(0) tried to rejoin, and failed! It leaves!", Level.medium);
                        prepare(Oper.leave);
                        table.succs().remove(0);
                        notify(Oper.leave, Oper.commit, as.getMessage());
                        
                        if( table.succs().size() == 0 && table.preds().size() > 0 ) {
                            log.log("Join: Cleanup breaks succ-list!", Level.high);
                            if( table.preds().get(-1).equals(joiner) ) { // we were almost alone...
                                table.preds().remove(-1);
                                log.log("Join: Succ(0) weapped to end of preds.", Level.medium);
                            }
                            if( table.preds().size() > 0 ) {
                                table.succs().add(0, table.preds().get(-1));
                                log.log("Join: We are able to rebuild table from preds!", Level.medium);
                            } else {
                                log.log("Join: We are alone again!", Level.medium);
                            }
                        } // empty succ after clean.
                        
                    } // post-join join-abort.
                    throw as;
                } catch( AckState as ){
                    // if joiner not already in place, add it.
                    if( table.succs().size() == 0 ||
                        !joiner.equals(table.succs().get(0)) ){
                        prepare(Oper.join);
                        
                        table.succs().add(0, joiner);
                        
                        notify(Oper.join, Oper.commit, as.getMessage());
                    }
                    log.log("Join: jp_host has confirmed host, ack to joiner.", Level.medium);
                    throw new AckState(Ack.confirm+" --host "+jp_host+" --msg \""+as.getMessage()+"\"");
                }
            } // end if there is a succ0.
            //////////////////
            // READY STATE. //
            //////////////////
            prepare(Oper.join);
            out = new Message(
                    local_ticket, msg.getFromTicket(), 0,
                    Oper.ready.toString(),
                    	"--host "+(jp_host==null?table.getMe():jp_host)+
                    	" --succ "+table.succs());
            in = null;
            timeout = System.currentTimeMillis() + Conf.membership__connect_timeout;
            socket.send(msg.getSender(), out);
            while(in == null){
                try { // READY LOOP.
                    //socket.send(msg.getOrigin(), out);
                    in = socket.receive(local_ticket, msg.getSender(), Conf.msg_timeout);
                    if( !Oper.commit.toString().equals(in.getOperation()) ) throw new SocketTimeoutException();
                    
                    synchronized( table ){
                        // if empty tables (alone) or jot yet joined, add it.
                        if( table.succs().size() == 0 || !table.succs().get(0).equals(joiner)) {
                            table.succs().add(0, joiner);
                        }
                    }
                    // believe it or not, the loop is over...
                    try { // COMMIT & JP_COMMIT
                        // If we were alone: Just add (both) & Ack.
                        if( jp_host == null ) {
                        	log.log("Join: Lonly commit, add pred and ack.", Level.low);
                            synchronized( table ){
                            	table.preds().add(0, joiner);
                            }
                            throw new AckState(Oper.commit+" --host "+table.getMe());
                        }
                        // starting JP_COMMIT
                        log.log("Join: Committing to jp_host.", Level.low);
                        in = null;
                        out = new Message(
                                local_ticket, jp_re_ticket, 0,
                                Oper.commit.toString(), join_args );
                        socket.send(jp_host, out);
                        while( in == null ){
                            try {
                                //socket.send(jp_host, out);
                                in = socket.receive(local_ticket, jp_host, Conf.msg_timeout);
                                if( !Oper.ack.toString().equals(in.getOperation()) )
                                    throw new SocketTimeoutException();
                                throw new AckState(
                                        Ack.confirm+" --host "+
                                        jp_host+" --msg \""+in.getMessage()+"\"");
                                // MARK: COMMIT ENDS HERE !!!
                            } catch( SocketTimeoutException ste ){
                                // JP_COMMIT TIMEOUT
                                if( System.currentTimeMillis() > timeout ){
                                    log.log("Join: JoinPred host timed out, checking state.",
                                            Level.high);
                                    try{
                                        Location ret = lookup.lookup_table(jp_host, "pred:0", "");
                                        if( ret.equals(joiner) ){
                                            log.log("Join: JoinPred tables are OK." +
                                                    " Lets ack commit.", Level.high);
                                            // its OK, lets finish the ordinary way.
                                            throw new AckState(Ack.confirm+" --host "+jp_host+
                                                    " --msg \""+Ack.table_safe+"\"");
                                        } else throw new SocketTimeoutException();
                                    } catch( SocketTimeoutException e ){
                                        log.log("Join: jp_commit check timed out," +
                                                " making unsafe join!", Level.warning);
                                        // ... shall we test ???
                                        // We must ack join,
                                        // because jp_host may have accepted join, but we dont know
                                        throw new AckState(Ack.confirm+" --host "+jp_host+
                                                " --msg \""+Abort.timeout+"\"");
                                    }
                                }
                                socket.send(jp_host, out);
                                in = null; // loop jp_commit.
                            }
                        }
                    } finally { // END OF COMMIT ...
                        // we were in commit mode anyway.
                        notify(Oper.join, Oper.commit, Ack.table_safe.toString());
                    }
                    // END OF READY
                } catch( SocketTimeoutException ste ) {
                    // WAIT FOR COMMIT / READY TIMED OUT.
                    if( System.currentTimeMillis() > timeout ){
                        // no response from JOINER, lets abort both ways.
                        log.log("Join: joiner timed out, aborting", Level.high);
                        throw new AbortState(Abort.timeout.toString());                        
                    }
                    socket.send(msg.getSender(), out);

                    in = null; // loop
                }
                // messaging try/catch
            } // send/recv loop
            log.log("Join: Ended with no exit state!", Level.warning);
            // BEGINNING END STATES!
        } catch( AckState as ) {
            out = new Message(
                    local_ticket, msg.getFromTicket(), 0,
                    Oper.ack.toString(), as.getMessage());
            socket.send(msg.getSender(), out);
            log.log("Join: Acked with message \""+as.getMessage()+"\"", Level.high);
        } catch ( AbortState as ) {
            out = new Message(
                    local_ticket, msg.getFromTicket(), 0,
                    Oper.abort.toString(), as.getMessage());
            socket.send(msg.getSender(), out);
            if( jp_re_ticket > 0){ // jp_abort.
                out.setToTicket(jp_re_ticket);
                socket.send(jp_host, out);
            }
            log.log("Join: Aborted with message \""+as.getMessage()+"\"", Level.high);
        } finally {
            mm_succ_end();
            if( local_ticket > 0 ) socket.freeTicket(local_ticket);
            log.log("Join: ended and released resources.", Level.low);
        }
    }
    
    /**
     * Connects the node to a ring.
     * 
     * @param to Host to connect to.
     * @return true if the local node joined the ring successfully.
     */
    public boolean connect( Location to ) {
        disconnect();
        
        int local_ticket = 0, remote_ticket = 0;
        long timeout;
        Message in, out;
        Location pred0 = null, succ0 = null;
        String join_args, msg;
        Index pred0_ownz = table.getMe().getIndex().sub(table.getMe().getIndex().getFactory().getImaxRshN(-1));
        String succ_lst = null;
        Location ask = null;
        try {
            ask = to;
            log.log("Connect: Initializing with host: "+to, Level.high);
            local_ticket = socket.requestTicket();
            mm_succ_begin(table.getMe(), local_ticket, Oper.connect);
            mm_pred_begin(table.getMe(), local_ticket, Oper.connect);

            log.log("Connect: My Index:   "+table.getMe().getIndex().toHexString(), Level.low);
            log.log("Connect: Pred0-ownz: "+pred0_ownz.toHexString(), Level.low);

            join_args    = "--host "+table.getMe();
            out     = new Message(
                    local_ticket, 0, 0,
            		getServiceName(), Oper.join.toString()+" "+join_args);
            in = null;
            pred0 = initPred0(pred0_ownz, ask, Conf.membership__connect_timeout);
            
            timeout = System.currentTimeMillis() + Conf.membership__connect_timeout;
            socket.send(pred0, out);
            while( in == null ){
                try {
                    // pred0 is OK, lets start the join.
                    in = socket.receive(local_ticket, pred0);
                    
                    /**/ if( Oper.ready.toString().equals(in.getOperation())) ; // let loop break.
                    else if( Oper.abort.toString().equals(in.getOperation())) {
                        /*  */ if( in.getMessage().startsWith(Abort.concurrent_conflict.toString()) ) {
                            synchronized( this ) {
                                try {
                                    // wait to try to let it "go away"...
                                    wait(Conf.msg_timeout);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                in = null; // loop.
                            }
                        } else if( in.getMessage().contains(Abort.wrong_host.toString()) ) {
                            // if wrong-host, initPred0();
                            pred0 = initPred0( pred0_ownz, pred0, Conf.membership__connect_timeout );
                        } else {
                            log.log("Connect: Aborting with message from pred0.", Level.medium);
                            throw new AbortState(in.getMessage());
                        }
                    }
                    else if( Oper.ack.toString()  .equals(in.getOperation())) {
                        if( in.getMessage().startsWith(Ack.confirm.toString()) ) {
                            // we are already in the ring.
                            // get succ0 from message.
                            synchronized ( join ) {
                                join.msg  = null;
                                join.host = null;
                                join.succ = null;
                                join.run(in.getMessage());
                                succ0    = join.host;
                                msg      = join.msg;
                                succ_lst = join.succ;
                            }
                            if( succ0 == null && succ_lst == null ) {
                                log.log("Connect: Ack/Confirm but Aborting with no succ0 and no succ_lst", Level.warning);
                                throw new AbortState(Abort.internal+" --msg \"no succ and succ_lst (1)\"");
                            }
                            prepare(Oper.connect);
                            
                            table.preds().add(0, pred0);
                            
                            if( succ_lst != null ) 
                                table.succs().fromString(succ_lst);
                            else
                                table.succs().add(0, succ0);
                            
                            throw new CommitState(msg==null?in.getMessage():msg);
                        } else throw new NullPointerException();
                    } else {
                        // throw new SocketTimeoutException(); // unknown message.
                        in = null; // loop and recv new message.
                        continue;
                    }
                    synchronized ( join ) {
                        join.msg  = null;
                        join.host = null;
                        join.succ = null;
                        join.run(in.getMessage());
                        succ0    = join.host;
                        msg      = join.msg;
                        succ_lst = join.succ;
                    }
                    if( succ0 == null ) throw new AbortState(Abort.internal+" --msg \"no succ and no succ_lst (2)\"");
                    remote_ticket = in.getFromTicket();
                    // leave loop.
                } catch ( NullPointerException e ) {
                    if( System.currentTimeMillis() > timeout ) {
                        throw new AbortState(Abort.timeout.toString());
                    }
                    // no resend!
                    in = null; // loop.
                } catch ( SocketTimeoutException e ) {
                    if( System.currentTimeMillis() > timeout ) {
                        throw new AbortState(Abort.timeout.toString());
                    }
                    socket.send(pred0, out);

                    in = null; // loop.
                }
            } // initialize loop.
            //
            //  PRED(0) is READY, lets commit.
            //
            try {
                prepare( Oper.connect );
                synchronized ( table ) {
                    table.preds().add(0, pred0);
                    if( succ_lst != null )
                        table.succs().fromString(succ_lst);
                    else
                        table.succs().add(0, succ0);
                }
                
                out = new Message(
                        local_ticket, remote_ticket, 0,
                        Oper.commit.toString(), join_args );
                in = null;
                // may result in some weirdness...
                timeout = System.currentTimeMillis() + Conf.membership__connect_timeout;
                while( in == null ) {
                    try {
                        socket.send(pred0, out);
                        in = socket.receive(local_ticket, pred0);
                        if( Oper.ack.toString().equals(in.getOperation()) ) {
                            log.log("Connect: Join CONFIRMED by pred0.", Level.low);
                            throw new CommitState(in.getMessage());
                        } else throw new SocketTimeoutException();
                    } catch( SocketTimeoutException ste ) {
                        if( System.currentTimeMillis() > timeout ) {
                            try {
                                Location ret = lookup.lookup_table( pred0, "succ:0",
                                        "--timeout "+Conf.membership__connect_msg_timeout);
                                if( table.getMe().equals(ret) ){
                                    log.log("Connect: Connect Timeout, but we are in the ring, COMMIT.", Level.low);
                                    throw new CommitState(Ack.table_safe.toString());
                                } else throw new SocketTimeoutException();
                            } catch (SocketTimeoutException e) {
                                table.preds().setSize(0);
                                table.succs().setSize(0);
                                table.fingers().setSize(0);
                                log.log("Connect: Connect Timeout, Quick ABORT.", Level.low);
                                throw new AbortState(Abort.timeout.toString());
                            } catch (IllegalArgumentException iae) {
                                log.log("Connect: Illegal Argument: "+iae.getMessage(), Level.error);
                                throw new AbortState(Abort.internal+" --msg \"Illegal Argument in lookup-table\"");
                            }
                        }
                        in = null; // loop.
                    }
                }
            } catch( AbortState as ) {
                notify( Oper.connect, Oper.abort, as.getMessage() );
                throw as;
            }
            log.log("Connect: Returning with no message.", Level.medium);
        } catch (CommitState cs) {
            if( succ_lst != null ) // in case list and not host.
                succ0 = table.succs().get(0);
            pred0.setTimestamp(System.currentTimeMillis()); // make sure it is max updated on commit.
            succ0.setTimestamp(System.currentTimeMillis());
            notify( Oper.connect, Oper.commit, cs.getMessage() );
            log.log("Connect: Commiting connect with pred0: "+pred0+" and succ0: "+succ0+".", Level.medium);
            return true;
        } catch (AckState as) {
            log.log("Connect: Connecting concurrently.", Level.medium);
            return false;
        } catch (AbortState as) {
            log.log("Connect: Connect Aborted with message: "+as.getMessage(), Level.medium);
            return false;
        } finally {
            if( local_ticket > 0 ) socket.freeTicket(local_ticket);
            mm_pred_end();
            mm_succ_end();
            log.log("Connect: Ended and cleaned up.", Level.high);
        }
        return false; // juust in case...
    }
    
    /**
     * Finds a pred[0] it can use for the sake of the connect.
     * 
     * @param pred0_ownz
     *    Index the pred[0] positively owns (a teeny bit smaller than mine...)
     * @param ask
     *    Check this node first, needed because we dont have working lookup-tables.
     * @param timeout
     *    Timeout for locating a useable pred[0] from 'ask' in milliseconds.
     * @return
     *    Location prepresenting pred[0].
     * @throws AbortState
     *    If there is Timeout or my Index is occupised by others.
     * @throws CommitState
     *    If I am already in the ring with 'ask'.
     */
    private Location initPred0( Index pred0_ownz, Location ask, long timeout )
            throws AbortState, CommitState {
        Location pred0 = null,
                 succ0;
        
        long too = System.currentTimeMillis() + timeout;
        
        while( pred0 == null ) {
            try {
                // find pred0
                log.log("Connect-Pred0: Initializing pred0, aksing "+ask, Level.low);
                pred0 = lookup.lookup(
                        table.getMe().getIndex(),
                        "", ask );
                if( !pred0.equals(ask) ) {
                    ask = pred0;
                }
                
                if( pred0.equals(table.getMe()) ){
                    // we are already in the ring, lets finalize and quit.
                    log.log("Connect-Pred0: We are already in the ring...", Level.low);
                    //while(  )
                    pred0 = lookup.lookup( pred0_ownz, null, ask );
                    log.log("Connect-Pred0: Looking up succ0 from succ:1", Level.low);
                    succ0 = lookup.lookup_table( pred0, "succ:1", "");
                    if( succ0 == null ){
                        log.log("Connect-Pred0: Lookup Failed... Looking up succ0 from pred:-1", Level.low);
                        succ0 = lookup.lookup_table( pred0, "pred:-1", "");
                        if( succ0 == null ){
                            throw new AbortState("ring error: unable to locate succ(0).");
                        }
                    }
                    log.log("Connect-Pred0: Using succ(0) "+succ0+" instead.", Level.low);
                    prepare(Oper.connect);
                    synchronized ( table ) {
                        table.preds().add(0, pred0);
                        table.succs().add(0, succ0);
                    }
                    throw new CommitState("--msg \"post insert commit\"");
                } else if( pred0.getIndex().equals(table.getMe().getIndex()) ){
                    throw new AbortState(Abort.index_collision.toString());
                }
                // Else its OK, end loop.
                log.log("Connect-Pred0: Returning pred0 "+pred0, Level.low);
            } catch( IllegalArgumentException e ) {
                assert false: "Illegal Argument in InitPred0";
            } catch( SocketTimeoutException e ) {
                if( too < System.currentTimeMillis() ) {
                    log.log("Connect-Pred0: Unable to locate pred0: "+e.getMessage(), Level.error);
                    throw new AbortState(Abort.internal+" --msg \"unable to locate pred0\"");
                }
                pred0 = null;
            }
        } // lookup loop.
        return pred0;
    }
    
    /*
     * @see MessageService#invoke(Message)
     */
    public void invoke( Message msg ) {
        /* DEBUG */
        System.out.println(
                " - "+table.getMe()+": got membership from "+msg.getSender()+"\n" +
                " - "+msg.getMessage());
        /* DEBUG */
        
        try {
            /**/ if ( msg.getMessage() == null || msg.getMessage().length() == 0)
                log.log("Empty MM Message...", Level.warning);
            else if ( msg.getMessage().startsWith( Oper.join      +" " ) ) join( msg );
            else if ( msg.getMessage().startsWith( Oper.join_pred +" " ) ) joinPred( msg );
            else if ( msg.getMessage().startsWith( Oper.leave     +" " ) ) leave( msg );
            else if ( msg.getMessage().startsWith( Oper.leave_pred+" " ) ) leavePred( msg );
            else log.log("Unknown membership operation: "+
                    msg.getMessage().split(" ")[0], Level.warning);
        } catch( Exception e ) {
            log.log("Unhandled exception with message "+e.getMessage(), Level.warning);
            e.printStackTrace();
        } finally {
            log.log("Updated Result Tables: " + table, Level.low );
        }
    }
    
    /**
     * LeavePred protocol method.
     * 
     * @param msg
     *     Message from leave/master with leave-host and arguments.
     */
    private void leavePred( Message msg ) {
        Location leaver, pnew;
        Message out;
        {
            String[] opt = STD.splitString(msg.getMessage());
            opt[0] = null;
            synchronized ( leave ) {
                leave.host    = null;
                leave.run(opt);
                leaver        = leave.host;
            }
        }
        
        try {
            if( leaver == null ) throw new AbortState("No leaver in leave-pred");
            
            mm_pred_begin(leaver, 0, Oper.leave_pred);
            
            try {
                log.log("LeavePred: Initialized with leaver "+leaver, Level.low);
                if( leaver.equals(table.preds().get(0)) ) {
                    prepare(Oper.leave_pred);
                    try {
                        synchronized ( table ) {
                            table.preds().remove(0);
                            if( table.preds().size() == 0 ) {
                                pnew = table.getLocation(msg.getSender());
                                if( pnew == null ) pnew = msg.getSender();
                                table.preds().add(0, pnew);
                            }
                        }
                    } catch (IndexOutOfBoundsException e) {
                        notify(Oper.leave_pred, Oper.abort, Abort.internal.toString());
                        throw e;
                    }
                    notify(Oper.leave_pred, Oper.commit, "");
                } else if( leaver.getIndex().between(table.preds().get(0).getIndex(), table.getMe().getIndex()) ) {
                    // already OK.
                } else throw new AbortState("--mypred "+table.preds().get(0)+" --msg \""+Abort.wrong_host+"\"");
                throw new AckState(Ack.confirm.toString());
            } catch( IndexOutOfBoundsException e ) {
                throw new AbortState(Abort.internal+"--msg \"index out of bounds\"");
            }
        } catch (AbortState as) {
            log.log("LeavePred: Aborted with message "+as.getMessage(), Level.low);
            out = new Message(
                    0, msg.getFromTicket(), 0,
                    Oper.abort.toString(), as.getMessage());
            socket.send(msg.getSender(), out);
        } catch (AckState as) {
            log.log("LeavePred: Acked with message "+as.getMessage(), Level.low);
            out = new Message(
                    0, msg.getFromTicket(), 0,
                    Oper.ack.toString(), as.getMessage());
            socket.send(msg.getSender(), out);
        } finally {
            mm_pred_end();
            log.log("LeavePred: Ended and cleaned up.", Level.low);
        }
    }
    
    /**
     * Sub-method of leave. Checks on succ[1], and calls its lavePred
     * service invocation.
     *  
     * @param leaver
     *     Node to leave.
     * @param leave_opts
     *     Leave Options
     * @throws AckState
     *     AckState if leave is acknowledged.
     * @throws AbortState
     *     AbortState if leave was aborted by succ[1] or timed out.
     */
    private void leave_me( Location leaver, String leave_opts ) 
            throws AckState, AbortState {
        int      local_ticket = 0;
        long     timeout;
        Location succ1, chksucc;
        Message  out, in = null;
        try {
            log.log("LeaveMe: Init with leaver "+leaver, Level.medium);
            local_ticket = socket.requestTicket();
            mm_succ_begin(leaver, local_ticket, Oper.leave);
            try {
                succ1 = table.succs().get(1);
            } catch( IndexOutOfBoundsException e ) {
                succ1 = table.preds().get(-1);
            }
            try {
                prepare(Oper.leave);
                out = new Message(
                        local_ticket, 0, 0,
                        getServiceName(), Oper.leave_pred+" "+leave_opts);
                timeout = System.currentTimeMillis() + Conf.membership__joinpred_timeout;
                socket.send(succ1, out);
                while( in == null ) {
                    try {
                        in = socket.receive(local_ticket, succ1);
                        if( Oper.ack.toString().equals(in.getOperation()) ) {
                            if( in.getMessage().startsWith(Ack.confirm.toString()) ) {
                                synchronized( table.succs() ) {
                                    table.succs().remove(0);
                                }
                                throw new AckState(in.getMessage());
                            } else throw new SocketTimeoutException(); // ack_initialized...
                        } else if(Oper.abort.toString().equals(in.getOperation())) {
                            synchronized(leave_opts) {
                                leave.mypred = null;
                                leave.run(in.getMessage());
                                chksucc = leave.mypred;
                            }
                            if( chksucc != null && 
                                chksucc.getIndex().between(leaver.getIndex(),succ1.getIndex()) ) {
                                // Addition between succ0 (leaver) and succ1 I did'nt know about!
                                // we must retry with the new succ1...
                                synchronized( table.succs() ) {
                                    if( table.succs().get(1).equals(succ1) ) {
                                        // no changes here... we can add the "new" succ1.
                                        table.succs().add(1, chksucc);
                                        succ1 = chksucc;
                                    }
                                }
                                throw new SocketTimeoutException(); // for the check and loop.
                            }
                            throw new AbortState(in.getMessage());
                        } else {
                            // unknown message.
                            //throw new SocketTimeoutException();
                            in = null; // loop.
                        }
                    } catch (SocketTimeoutException e) {
                        if( System.currentTimeMillis() > timeout ) {
                            throw new AbortState(Abort.timeout+" --msg \"succ[1] not responding\"");
                        }
                        socket.send(succ1, out);
                        in = null;
                    }
                }
            } catch( IndexOutOfBoundsException e ) {
                log.log("LeaveMe: Aborted with IndexOutOfBoundsException...", Level.medium);
                throw new AbortState(Abort.internal+" --msg \"index out of bounds\"");
            }
        } catch( AbortState as ) {
            notify(Oper.leave, Oper.abort, as.getMessage());
            throw as;
        } catch( AckState cs ) {
            notify(Oper.leave, Oper.commit, cs.getMessage());
            throw cs;
        } finally {
            mm_succ_end();
            if( local_ticket > 0 ) socket.freeTicket(local_ticket);
            log.log("LeaveMe: Ended and cleaned up.", Level.medium);
        }
    }
    
    /**
     * Invoke the leave protocol. Note that this does not <i>nessecarilly</i>
     * remove a node from the ring. This is for the master side checking its
     * succ(0).
     * 
     * @param msg 
     *     Message from I-Am-Alive* or leave-host with arguments. 
     */
    private void leave( Message msg ){
        
        Location leaver, tmp;
        Message  out;
        String   leave_opts;
        boolean  check, respond;
        {
            String[] opt = STD.splitString(msg.getMessage());
            opt[0] = null; // remove "leave".
            leave_opts = STD.assembleString(opt);
            synchronized ( leave ) {
                leave.check   = false;
                leave.host    = null;
                leave.respond = false;
                leave.run(opt);
                check    = leave.check;
                leaver   = leave.host;
                respond  = leave.respond;
            }
            if( ( tmp = table.getLocation(leaver))!=null ) {
                leaver = tmp;
            }
                    
        }
        try { // end states...
            try { // exception translation and fullfillment.
                log.log("Leave: Initializing with leaver "+leaver, Level.high);
                
                // check if "my responsibility" ...
                if( leaver.equals(table.succs().get(0)) || 
                    leaver.getIndex().between(
                              table.getMe().getIndex(),
                              table.succs().get(0).getIndex()) ){
                    if( check ) {
                        try {
                            if( table.getMe().equals(lookup.lookup_table(leaver, "pred:0", "")) &&
                                lookup.lookup_table(leaver, "succ:0", "") != null ) {
                                leaver.setTimestamp(System.currentTimeMillis());
                                throw new AbortState(Ack.table_safe.toString());
                            } // else leave me...
                        } catch (SocketTimeoutException e) {
                            // leave me...
                        }
                    }
                    log.log("Leave: Starting LeaveMe.", Level.low);
                    leave_me(leaver, leave_opts);
                } else throw new AbortState(Abort.wrong_host.toString());
            } catch( IndexOutOfBoundsException e ) {
                throw new AbortState(Abort.internal+" --msg \"index out of bounds\"");
            } catch( NullPointerException e ) {
                e.printStackTrace();
                throw new AbortState(Abort.internal+" --msg \"null-pointer\"");
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                throw new AbortState(Abort.internal+" --msg \"illegal argument\"");
            }
            log.log("Leave: Ended without state!", Level.warning);
        } catch (AckState as) {
            log.log("Leave: Acked with message "+as.getMessage(), Level.high);
            if( respond && ( msg.getFromTicket() > 0 ) ) {
                out = new Message(
                        0, msg.getFromTicket(), 0,
                        Oper.ack.toString(), as.getMessage());
                socket.send(msg.getSender(), out);
            }
        } catch (AbortState as) {
            log.log("Leave: Aborted with message "+as.getMessage(), Level.high);
            if( respond && ( msg.getFromTicket() > 0 ) ) {
                out = new Message(
                        0, msg.getFromTicket(), 0,
                        Oper.abort.toString(), as.getMessage());
                socket.send(msg.getSender(), out);
            }
        }
    }
    
    /**
     * Disconnect form the current ring.
     * 
     * @return
     *   True if the node was connected to a ring, and then disconnected.
     */
    public boolean disconnect(){
        if( table.succs().isEmpty() ) {
            table.preds().clear();
            table.fingers().clear();
            return false; // no ring to leave.
        }
        int     local_ticket = 0;
        long    timeout;
        Message out, in = null;
        String  leave_opts = "--host "+table.getMe()+" --no-check --respond";
        try {
            local_ticket = socket.requestTicket();
            mm_succ_begin(table.getMe(), local_ticket, Oper.disconnect);
            mm_pred_begin(table.getMe(), local_ticket, Oper.disconnect);
            
            try {
                prepare(Oper.disconnect);
                out = new Message(
                        local_ticket, 0, 0,
                        getServiceName(), Oper.leave+" "+leave_opts);
                timeout = System.currentTimeMillis() + Conf.membership__connect_timeout;
                socket.send(table.preds().get(0), out);
                while( in == null ) {
                    try {
                        in = socket.receive(local_ticket, table.preds().get(0));
                        /*  */ if( Oper.ack.toString().equals(in.getOperation()) ) {
                            if( in.getMessage().startsWith(Ack.confirm.toString()) || 
                                in.getMessage().startsWith(Ack.table_safe.toString())) {
                                throw new AckState(in.getMessage()); 
                            }
                        } else if( Oper.abort.toString().equals(in.getOperation()) ) {
                            throw new AbortState(in.getMessage());
                        }
                        // No action taken, loop and receive again.
                        in = null;
                    } catch( SocketTimeoutException e ) {
                        if( System.currentTimeMillis() > timeout ) {
                            throw new AckState(Abort.timeout.toString()); // no contact, so we must have lost them!
                        }
                        socket.send(table.preds().get(0), out);
                        in = null; // loop.
                    }
                } // while ...
            } catch( AbortState as ) {
                notify(Oper.disconnect, Oper.abort, as.getMessage());
                throw as;
            } catch( AckState as ) {
                synchronized( table ) {
                    table.fingers().setSize(0);
                    table.preds().setSize(0);
                    table.succs().setSize(0);
                }
                notify(Oper.disconnect, Oper.commit, as.getMessage());
                throw as;
            }
        } catch (AckState e) {
            if( e.getMessage().startsWith(Ack.confirm.toString()) )
                return true;
            else return false;
        } catch (AbortState e) {
            return false;
        } finally {
            mm_succ_end();
            mm_pred_end();
            if( local_ticket > 0 ) socket.freeTicket(local_ticket);
        }
        return false;
    }
    
    /*
     * @see MessageService#getServiceName(Message)
     */
    public String getServiceName() {
        return OPERATION;
    }
    
    /**
     * Checks if a node is a neighbor that is leaving or inaccessible.
     * 
     * @param succ0
     *    Succ[0] that is presumable leaving.
     */
    public void checkLeave(Location succ0) {
        Message msg = new Message(0, 0, 0, getServiceName(), Oper.leave+" --host "+succ0+" --check" );
        socket.send(table.getMe(), msg);
    }
    
    /**
     * Forces a node (neighbor) to leave without question.
     * 
     * @param succ0
     * Succ[0] that is leaving.
     */
    public void forceLeave(Location succ0) {
        Message msg = new Message(0, 0, 0, getServiceName(), Oper.leave+" --host "+succ0+" --no-check" );
        socket.send(table.getMe(), msg);
    }
}
