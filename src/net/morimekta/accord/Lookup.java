/*
 * Created on Apr 29, 2005
 */
package net.morimekta.accord;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.LinkedList;

import net.morimekta.accord.tables.LookupTable;
import net.morimekta.net.Message;
import net.morimekta.net.MessageService;
import net.morimekta.net.MessageSocket;
import net.morimekta.net.Location;
import net.morimekta.util.index.Index;
import net.morimekta.util.index.IndexFactory;
import net.morimekta.util.index.SHA1Factory;
import net.morimekta.util.std.Log;
import net.morimekta.util.std.Options;
import net.morimekta.util.std.STD;
import net.morimekta.util.std.Log.Level;

/**
 * Lookup service for Accord. Implements both lookup requester and participant protocol.
 * The class is both usable for active and passive nodes, but some of the methods have
 * unspecified behaviour when working in a passive environment. <br>
 * <br>
 * <H1> Lookup Service Provider </H1>
 * 
 * The various lookup methods have a distinct way of behaviour as laid out here.
 * <br>
 * <ul>
 *   <li><em>--timeout</em> - Timeout of lookup procedure.</li>
 *   <li><em>--msg-timeout</em> - Timeout of a single lookup message.<li>
 *   <li><em>--iter</em> - Iterate restriction. (Index lookup only)</li>
 * </ul>
 * 
 * <H2> Lookup based on Index </H2>
 * 
 * There are four basic iterative restriction modes.
 * <ul>
 * <li><em>unsafe</em> Iterate on all results. </li>
 * <li><em>safe</em> Iterate on nodes tagged as "safe" in the lookuptable. </li>
 * <li><em>neighbour</em> Iterate on self and neighbours. </li>
 * <li><em>default</em> Use default setting.</li>
 * <li><em>no_neighbor</em> Iterate on all except neighbours. </li>
 * <li><em>no_safe</em> Iterate on all except neighbours and safe. </li>
 * <li><em>self</em> Iterate on self only (no recursion). </li>
 * </ul>
 * <br>
 * To iterate is to return the result of the local lookup instead of forwarding it to the
 * one that more likely know where the final result node is. 
 * Using the iter argument to its full potensial is not hard.
 * 
 * <H2> Table Lookups </H2>
 * 
 * There are two methods of looking up OverlayLinks from the overlay tables in remote Accord nodes.
 * But they are all a variant of the same procedure, as they invoke the same remote operation
 * (lookup/table). Both methods work the same way, but the <code>lookup_table</code> method will
 * only ask for a single lookup, and decompose the result list and extract the Location given.<br>
 * <ul>
 * <li><code>Location Lookup#lookup_table(location, tableref, [options])</code><br>
 *      Looks up a single location from table with "table:ref".<sup>1</sup></li>
 * <li><code>List Lookup#lookup_tables(location, queryline, [options])</code><br>
 *      Looks up from tables with a "queryline" <sup>1</sup></li>
 * </ul>
 * 
 * Lookup with a complex table query, and returning is a list of strings. <br>
 * 
 * <H3> Query table and operand options </H3>
 * 
 * The general syntax of the query is "<code>[table[:op]* ]*</code>".<br>
 * <br>
 * TODO: Add Lookup#route(Index,Message)
 * 
 * @author Stein Eldar Johnsen
 * @see net.morimekta.accord.tables.OverlayContainer
 * @see net.morimekta.util.index.Index
 */
public class Lookup implements MessageService {
    protected enum Op {
        index,
        index_res,
        table,
        table_res,
    }
    
    protected enum Iter {
        // iterate on "safer"
        unsafe,      // l, n, s, u  - pure iterative
        safe,        // l, n, s,[u]
        neighbor,    // l, n,[s, u]
        self,        // l,[n, s, u] - pure recursive.
        // undefined, load from config.
        undefined,   // choose default.
        // recurse on "safer"
        no_neighbor, // l,[n],s, u
        no_safe,     // l,[n, s],u
        // do not iterate (none) = self
        // iterate on all (all)  = unsafe
    }
    
    private String         op_string      = "t,timeout,integer\n" +
                                            "m,msg-timeout,integer";
    private LocalOpts      op             = new LocalOpts();
    private String         index_string   = "i,iter,string\n" +
                                            "o,origin,string\n" +
                                            "I,index,string";
    private IndexOpts      index          = new IndexOpts();
    
    private int            qid = 0;
    
    private class AtomicQuery {
        int qid;
        int opid;
        String query;
        public String toString(){
            return qid+","+opid+" "+query;
        }
    }
    public class QueryResult {
        public int      quid  = 0;
        public int      opid  = 0;
        public String   query = null;
        public int      idx   = -2;
        public Location loc   = null;
        public QueryResult(String result_line) throws IllegalArgumentException {
            if( result_line == null )
                throw new IllegalArgumentException("result_line is null");
            String[] words = result_line.split(" ");
            if( words.length < 3 )
                throw new IllegalArgumentException("result_line has not enough arguments");
            // [0] = quid,opid
            // [1] = query
            // [2] = loc
            if( "null".equals(words[2]) ) loc = null;
            else {
                try {
                    idx = Integer.parseInt(words[2]);
                } catch ( NumberFormatException e) {
                    try {
                        loc = new Location(words[2]);
                    } catch (UnknownHostException e1) {
                        throw new IllegalArgumentException("no valid Location or index");
                    }
                }
            }
            
            query = words[1];
            words = words[0].split(",");
            if( words.length < 2 )
                throw new IllegalArgumentException("illegal quid,opid string");
            try {
                quid = Integer.parseInt(words[0]);
                opid = Integer.parseInt(words[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("query or operation ID not an integer");
            }
        }
        public String toString(){
            return quid+","+opid+" "+query+" = "+(idx==-2 ? loc.toString() : ""+idx );
        }
    }
    private class LocalOpts extends Options {
        long            timeout;
        long            msg_timeout;
        public LocalOpts(){
            super(op_string);
        }
        protected LocalOpts(String extra){
            super(op_string+"\n"+extra);
        }
        public void parse(char op, String arg) {
            try {
                switch (op) {
                    case 't':
                        timeout = Long.parseLong(arg);
                        break;
                    case 'm':
                        msg_timeout = Long.parseLong(arg);
                        break;
                    default:
                        throw new IllegalArgumentException(arg+" is not a valid Lookup Option");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(arg+" is not a number");
            }
        }
    }
    private class IndexOpts extends LocalOpts { 
        Iter     iter;
        Index    idx;
        Location origin;
        public   IndexOpts() { super( index_string ); }
        public void parse( char op, String arg ){
            switch( op ){
                case 'i':
                    try {
                        iter = Iter.valueOf(arg);
                    } catch( Exception e ) {
                        iter = Iter.undefined;
                    }
                    break;
                case 'o':
                    try {
                        origin = new Location(arg);
                    } catch (UnknownHostException e) {
                        // ignore.
                    }
                    break;
                case 'I':
                    idx = factory.getIndex(arg);
                    break;
                case '?':
                    log.log("IndexOpts: Unknown Operation "+arg, Level.high);
                    break;
                default:
                    try {
                        super.parse(op, arg);
                    } catch( IllegalArgumentException e ) {
                        log.log("IndexOpts: "+e.getMessage(), Level.low);
                        throw e;
                    }
                    break;
            }
        }
    }
    
    private LookupTable    table;
    private MessageSocket  socket;
    private IndexFactory   factory;
    private Log            log;
    
    /**
     * Create a lookupservice working on the LookupTable _ft, and using ServiceSocket sock to send
     * requests and receive responses. Uses MessageService interface to call lookup from remote
     * Location.
     * 
     * @param _log LogFile to log events to.
     * @param _ft LookupTable to look up in.
     * @param sock ServiceSocket to respond to lookups with.
     */
    public Lookup( Log _log, LookupTable _ft, MessageSocket sock ) {
        log = _log;
        table = _ft;
        socket = sock;
        if( _ft != null )
            factory = _ft.getMe().getIndex().getFactory();
        else
            factory = SHA1Factory.getInstance(); // we use what we have.
    }
    
    public String getServiceName() {
        return "lookup";
    }
    
    /**
     * Invokes lookup from index.
     * 
     * @param msg
     *   Message with the index to look up.
     * @see Lookup#invoke(Message)
     * @see Lookup#lookup(Index, String, Location)
     */
    private void invoke_index( Message msg ){
        Iter       mode     = Iter.undefined;
        Location   owner    = null;
        Location   origin     = null;
        Index      idx;
        boolean    is_safe,
                   reply      = false;
        long       pred_index,
                   succ_index;
        Iter       iter;
        
        try {
            {
                String[] argument = STD.splitString(msg.getMessage());
                if( argument.length < 2 ) {
                    log.log("index.invoke "+msg.getSender()+" invalid option string", Level.warning);
                    return;
                }
                argument[0] = null; // "index"
                
                synchronized ( index ) {
                    index.origin = null;
                    index.iter   = Iter.undefined;
                    index.idx    = null;
                    try {
                        index.run(argument);
                    } catch (IllegalArgumentException e) {
                        //e.printStackTrace();
                        log.log("index.invoke illegal argument in operions: "+e.getMessage(),
                                Level.warning);
                    }
                    origin = index.origin;
                    iter   = index.iter;
                    idx    = index.idx;
                }
                /*  */ if( idx     == null ) {
                    log.log("index.invoke "+msg.getSender()+" no index", Level.warning);
                    return;
                } else if( origin  == null ) {
                    log.log("index.invoke "+msg.getSender()+" no origin", Level.warning);
                    return;
                }
                if( iter == Iter.undefined ) iter = Conf.lookup__iterate;
                log.log("index.invoke "+origin+" "+idx+" "+iter, Level.low);
            }
            //log.log("Index is: "+idx.toHexString()+", iter is "+iter, Level.low);
            
            /*
             * START CONCURRENCY CONTROL
             */
            synchronized ( table ) {
                // find the owner.
                owner      = table.owner_of( idx );
                // get some other info.
                pred_index = table.preds().indexOf( owner );
                succ_index = table.succs().indexOf( owner );
                is_safe    = table.is_safe( owner );
            }
            
            /* DEBUG *
            if( owner == null ) {
                System.out.println("what the heck!!!");
            }
            /* DEBUG */
            /*
             * END CONCURRENCY CONTROL
             */
            
            //log.log("Owner is: "+owner, Level.low);
            //log.log("preds("+pred_index+"), succs("+succ_index+"). consistent: "+consistent, Level.low);
            /*
             * Determine "mode" and form of action (forward or reply)
             */
            if ( owner.equals( table.getMe() ) ) {
                mode  = Iter.self;
                reply = true; // always reply on self.
            } else if ( pred_index == 0 || succ_index == 0 ) {
                mode  = Iter.neighbor;
                // iterate on "neighbour" and higher
                reply = ( iter == Iter.neighbor ) ||
                        ( iter == Iter.safe ) ||
                        ( iter == Iter.unsafe );
            } else if ( is_safe ) {
                mode  = Iter.safe;
                // iterate if iter in safe or all
                reply = ( iter == Iter.safe ) ||
                        ( iter == Iter.unsafe ) ||
                        ( iter == Iter.no_neighbor);
            } else {
                mode  = Iter.unsafe;
                // iterate on succ with index < iter (because of prop of is_safe)
                // and "iterate on all".
                reply = ( iter == Iter.unsafe ) ||
                        ( iter == Iter.no_neighbor ) ||
                        ( iter == Iter.no_safe );
            }
            /*
             * Send message or response to "whom it may concern".
             */
            if ( ! reply ) {
                socket.send( owner, msg );
                log.log("index.forward "+owner+" "+mode, Level.low);
            } else {
                Message response = new Message( 0, msg.getFromTicket(), 0,
                        Op.index_res.toString(),
                        idx.toBase64String() + " " + owner + " " + mode);
                socket.send( origin, response );
                log.log("index.reply "+owner+" "+mode, Level.low);
            }
        } finally {
            //log.log("Index: Cleaning up.", Level.low);
        }
    }
    
    /**
     * Invokes Lookup in table reference.
     * 
     * @param msg
     *   Message with lookup query.
     * @see Lookup#invoke(Message)
     * @see Lookup#lookup_tables(Location, String, String)
     */
    private void invoke_table( Message msg ){
        LinkedList<AtomicQuery>  lookups;
        String      reply_string = "";
        AtomicQuery atomic;
        Message     reply;
        /*
         * Decompose table query to single table lookups.
         */
        lookups = decompose( msg.getMessage() );
        // we have the ops, lets actually do them and compose the reply.
        //log.log("Table: Invoking with "+lookups.size()+" queries.", Level.low);
        //log.log(msg.getMessage().replaceFirst(" ", ".invoke "), Level.low);

        for ( Iterator<AtomicQuery> i = lookups.iterator(); i.hasNext(); ) {
            atomic = i.next();
            Location l = table.table_at( atomic.query );
            reply_string += atomic + " " + ( l == null ? "null" : l.toString() ) + "\n";
        }
        
        //log.log("table.reply "+msg.getSender(), Level.low);
        // compose reply message and reply.
        reply = new Message(
                0, msg.getFromTicket(), 0,
                Op.table_res.toString(), reply_string);
        socket.send( msg.getSender(), reply );
    } // invoke table.
    
    /**
     * Lookup participant protocol. Note that index lookup are always single but table lookups takes multiple lookups.
     * 
     * @param msg lookup message to ...
     * @see Lookup#lookup_tables(Location, String, String)
     * @see Lookup#lookup(Index, String, Location)
     * @see Lookup#invoke_index(Message)
     * @see Lookup#invoke_table(Message)
     */
    public void invoke( Message msg ) {
        String   tmp = msg.getMessage();
        Location loc = table.getLocation(msg.getSender());
        if( loc != null ) loc.setTimestamp(System.currentTimeMillis());
        
        /**/ if ( tmp.startsWith( Op.index.toString() ) ) invoke_index( msg );
        else if ( tmp.startsWith( Op.table.toString() ) ) invoke_table( msg );
        else log.log("invalid lookup operation", Level.warning);
        
    }// invoke "lookup"
    
    /**
     * Simple table lookup query.
     * 
     * @param ask Host to query. (Location)
     * @param queryline Single queryline (QL)
     * @param options Query Options (OPT)
     * @return List of String (RE)
     * @throws SocketTimeoutException 
     * @throws IllegalArgumentException 
     */
    public LinkedList<QueryResult> lookup_tables( Location ask, String queryline, String options )
    throws SocketTimeoutException,
    IllegalArgumentException {
        long       timeout      = 0;
        long       msg_timeout  = 0;
        int        local_ticket = 0;
        String[]   entries;
        Message    in, out;
        LinkedList<QueryResult> result_list = new LinkedList<QueryResult>();
        /*
         * CHECK OPTIONS: - decompose (and set method attributes and query flags)
         */
        synchronized ( op ) {
            // default values.
            op.timeout     = Conf.lookup__timeout;
            op.msg_timeout = Conf.msg_timeout;
            // parse options.
            op.run(options);
            // store.
            timeout        = op.timeout;
            msg_timeout    = op.msg_timeout;
        }
        
        try {
            //log.log("Lookup/Tables: Initiating Table Lookup", Level.medium);
            //log.log("Lookup/Tables: Asking "+ask+" query "+queryline, Level.low);
            /*
             * EXECUTE QUERY: - execute : execute query. while( not all results ) send LM to location collect result message -
             * decompose : build result list (RL) from message.
             */
            // execute
            local_ticket = socket.requestTicket();
            in  = null;
            out = new Message(
                    local_ticket, 0, 0,
                    getServiceName(), Op.table+" " + queryline + "\n");
            timeout = System.currentTimeMillis() + timeout;
            while( in == null ) {
                try {
                    socket.send(ask, out);
                    in = socket.receive(local_ticket, msg_timeout);
                    if( Op.table_res.toString().equals(in.getOperation()) && in.getMessage() != null) {
                        // read result list.
                        entries = in.getMessage().split("\n");
                        for( int i = 0; i < entries.length; i++ ) {
                            if( entries[i] == null ) continue;
                            try {
                                result_list.addLast( new QueryResult(entries[i]) );
                            } catch( IllegalArgumentException iae ) {
                                // unable to make a query result from line.
                                // possible to repair result? Ignore for now.
                                //log.log("Lookup/Tables: IllegalArgumentException; "+iae.getMessage(), Level.medium);
                                //log.log("Lookup/Tables: Unable to parse query result \""+entries[i]+"\"", Level.medium);
                            }
                        }
                    } else throw new SocketTimeoutException();
                } catch( SocketTimeoutException ste ) {
                    if( System.currentTimeMillis() > timeout ){
                        throw new SocketTimeoutException("lookup timed out");
                    }
                    //log.log("Timeout/problem, trying again", Level.low);
                    in = null;
                }
            }
            //log.log("Lookup/Tables: Returning list of "+result_list.size()+" entries.", Level.low);
            return result_list;
        } finally {
            //log.log("Lookup/Tables: Cleaning up.", Level.medium);
            if( local_ticket > 0 ) socket.freeTicket(local_ticket);
        }
    }
    
    /**
     * Looks up a signel table reference to location.
     * 
     * @param ask Location/Host to ask.
     * @param tabref Table Reference "table:index"
     * @param opts Lookup Options.
     * @return Location if found, or null of no such entry exists.
     * @throws SocketTimeoutException
     * @throws IllegalArgumentException
     */
    public Location lookup_table( Location ask, String tabref, String opts)
            throws SocketTimeoutException, IllegalArgumentException {
        LinkedList<AtomicQuery> qlist  = decompose("arg0 "+tabref);
        LinkedList<QueryResult> rlist  = null;
        AtomicQuery             query  = null;
        
        //log.log("Lookup/Table: Asking "+ask+" query "+tabref, Level.low);
        // check query
        if( qlist.size() != 1 ) throw new IllegalArgumentException("tabref ("+tabref+") must be exactly 1 atomic query, was "+qlist.size());
        query = qlist.get(0);
        if( query.query.contains("size") ) throw new IllegalArgumentException("tabref can not be a size query");
        
        // invoke lookup.
        rlist = lookup_tables(ask, tabref, opts);
        
        // check result.
        if( rlist.size() < 1 ) throw new SocketTimeoutException("empty result set");
        //log.log("Lookup/Table: Returning location "+rlist.get(0).loc, Level.low);
        return rlist.get(0).loc;
    }
    
    /**
     * Lookup based on an index.
     * 
     * @param idx 
     *    Index to look up.
     * @return
     *   Location found.
     * @throws SocketTimeoutException 
     * @throws IllegalArgumentException 
     * @see Lookup#lookup(Index, String, Location)
     */
    public Location lookup( Index idx )
            throws SocketTimeoutException, IllegalArgumentException {
        return lookup( idx, null, null );
    }
    /**
     * Lookup based on an index.
     * 
     * @param idx index to look up (Index).
     * @param opts
     *   Remote lookup options.
     * @return
     *   Location found.
     * @throws IllegalArgumentException 
     * @throws SocketTimeoutException 
     * @see Lookup#lookup(Index, String, Location)
     */
    public Location lookup( Index idx, String opts )
            throws IllegalArgumentException, SocketTimeoutException {
        return lookup( idx, opts, null );
    }
    
    /**
     * Lookup based on an index and an "ask first node".
     * 
     * @param idx index to look up (Index).
     * @param opts
     *   Remote lookup options. If null, ignore.
     * @param ask
     *   Ask this host first when looking up. If null, ignore.
     * @return
     *   Location found.
     * @throws IllegalArgumentException 
     * @throws SocketTimeoutException 
     */
    public Location lookup( Index idx, String opts, Location ask )
            throws IllegalArgumentException, SocketTimeoutException {
        /*
         * Quick fix when searching for an index I owns.
         */
        if( ask == null && (ask = table.owner_of( idx )).equals(table.getMe())) return table.getMe();

        LinkedList<Location> search_stack = new LinkedList<Location>();
        int        ticket       = 0; 
        Location   ret          = null;
        Message    in           = null;
        Message    out;
        Iter       remiter;
        long       timeout;
        long       msg_timeout;
        long       chk_time;
        /*
         * Check remote options.
         */
        synchronized ( index ) {
            index.iter        = Iter.undefined; // Default.
            index.timeout     = Conf.lookup__timeout;
            index.msg_timeout = Conf.msg_timeout;
            index.run(opts);
            remiter     = index.iter;
            timeout     = index.timeout;
            msg_timeout = index.msg_timeout;
        }
        // check for defaults.
        if( remiter == Iter.undefined ) remiter = Conf.lookup__iterate;
        
        /*
         * Build lookup message.
         */
        search_stack.addLast( ask );
        
        ticket      = socket.requestTicket();
        out         = new Message(
                ticket, 0, 0,
                getServiceName(),
                "index --index "+idx.toBase64String()+
                    " --origin "+table.getMe()+(opts==null?"":" "+opts) );
        log.log("lookup.index: starting ticket="+ticket+" iter="+remiter, Level.medium);
        /*
         * sends and receives...
         */
        try {
            chk_time = System.currentTimeMillis();
            timeout  = chk_time + timeout;
            chk_time = chk_time + Conf.msg_timeout;
            socket.send( search_stack.getFirst(), out );
            
            while( in == null ){
                try {
                    //log.log("Lookup/Index: Asking "+search_stack.getFirst()+" for "+idx.toHexString(), Level.low);
                    in = socket.receive( ticket, msg_timeout );
                    //log.log("Lookup/Index: Got response from "+in.getSender(), Level.low);
                    if( Op.index_res.toString().equals(in.getOperation()) && in.getMessage() != null ){
                        // some variables we need for determining result.
                        //log.log("Lookup/Index: Response is an index response...", Level.low);
                        String[] parts;
                        Index    check;
                        boolean  reply = false; // just in case we dont know ...
                        /*
                         * Find out what mode the return was in, set ret if we are satisfied, else set own to
                         *  return of last initiate_lookup, and loop.
                         */
                        parts = (new String(in.getMessage())).split(" "); // there should be no newlines here.
                        if( parts.length < 3 )
                            throw new IndexOutOfBoundsException("not enough arguments to determine safety");
                        
                        try {
                            reply = Iter.valueOf(parts[2]) == Iter.self;
                        } catch (Exception e) {
                            reply = false;
                        }

                        /*
                         * Read the response Location itself...
                         */
                        try{
                            ret = new Location( parts[1] );
                        }catch (UnknownHostException uhe){
                            throw new IndexOutOfBoundsException(uhe.getMessage());
                        }
                        /*
                         * Read check index value.
                         */
                        check = factory.getIndex(parts[0]);
                        if( check == null ){
                            throw new IndexOutOfBoundsException(
                            "unverifiable message; it gave invalid index string");
                        } else {
                            // check ??? Make it an option...
                            //log.log("Lookup/Index: I am supposed to check index and search index...", Level.low);
                        }
                        /*
                         * Determine action: return or loop.
                         */
                        if( reply ) {
                            //log.log("Lookup/Index: Got good enough result.", Level.low);
                            continue; // satisfies criteria.
                        } else
                            //log.log("Lookup/Index: Not good enough... Trying more lookups.", Level.low);
                        
                        // else remake and resend!
                        search_stack.addFirst(ret);
                        ret = null; // push on the "send attribs"
                        in  = null; // loop criteria.
                        // loop to resend...
                    } else {
                        // phony message, drop it and continue.
                        throw new IndexOutOfBoundsException("phony message");
                    }
                } catch ( SocketTimeoutException ste ) {
                    log.log("lookup.index: Timeout "+(System.currentTimeMillis()-chk_time)+" "+ste.getMessage(), Level.low); /**/
                    if( timeout < System.currentTimeMillis() ){
                        log.log("lookup.index: final timeout, lookup failed", Level.medium);
                        //log.log("Lookup/Index: Final SocketTimeout, breaking.", Level.medium);
                        //log.log("Lookup/Index: Lookup time = "+(System.currentTimeMillis()-chk_time),Level.low);
                        throw new SocketTimeoutException( "lookup timed out" );
                    }
                    // remove non-working lookup...
                    if( !search_stack.isEmpty() ) {
                        search_stack.removeFirst();
                    }
                    
                    if ( search_stack.isEmpty() ) {
                        if( ask == null ) {
                            //log.log("Lookup/Index: Adding owner_of to search_stack.", Level.low);
                            search_stack.addFirst(table.owner_of( idx ));
                        } else {
                            //log.log("Lookup/Index: Re-Adding ask to search_stack!", Level.low);
                            search_stack.addFirst(ask);
                        }
                    }
                    socket.send( search_stack.getFirst(), out );
                    
                    msg_timeout = Conf.msg_timeout;
                    ret = null;
                    in  = null; // anyway.
                } catch ( IndexOutOfBoundsException ioobe ){
                    log.log("lookup.index: Exception: "+ioobe.getMessage(), Level.medium);
                    if( timeout < System.currentTimeMillis() ){
                        //log.log("Lookup/Index: Lookup time = "+(System.currentTimeMillis()-chk_time),Level.low);
                        throw new SocketTimeoutException( ioobe.getMessage() );
                    }
                    msg_timeout = chk_time - System.currentTimeMillis();
                    if( msg_timeout <= 0 ){
                        chk_time    = System.currentTimeMillis()+ Conf.msg_timeout;
                        msg_timeout = Conf.msg_timeout;
                    }
                    ret = null;
                    in  = null; // just loop.
                }
            }// loop.
            
            //log.log("Lookup/Index: Returning "+ret, Level.low);
            //log.log("Lookup/Index: Lookup time = "+(System.currentTimeMillis()-chk_time),Level.low);
            return ret;
        } catch( IndexOutOfBoundsException ioobe ) {
            // no available ticket...
            throw new SocketTimeoutException(ioobe.getMessage());
        } finally {
            log.log("lookup.index: ending", Level.low);
            //log.log("Lookup/Index: Ending and cleaning up...", Level.low);
            if( ticket > 0 ) socket.freeTicket( ticket );
        }
    }
    
    /**
     * Decompose cuery into atomic lookup entries.
     * 
     * @param query
     *  Query to decompose
     * @return
     *  LinkedList of atomic queries.
     */
    private LinkedList<AtomicQuery> decompose( String query ) {
        LinkedList<AtomicQuery> lookups = new LinkedList<AtomicQuery>();
        AtomicQuery aq = null;
        String[] cqq = query.split( "\n" );
        // just in case there is a newline that may interfere with the decomposition.
        if( cqq.length < 1 ) return lookups;
        String[] cql = cqq[0].split( " " );
        String[] ops;
        String table;
        /*
         * Decompose table query to a list of atomic queries.
         */
        for ( int i = 0; i < cql.length; i++ ) {
            ops = cql[i].split( ":" );
            if ( ops.length > 1 ) {
                table = ops[0];
                // ... may have several
                for ( int j = 1; j < ops.length; j++ ) {
                    /*  */ if ( ops[j] == null ){
                        // double colon ... ignore.
                    } else {
                        // single index or operation. (size, idx, etc.)
                        aq = new AtomicQuery();
                        aq.qid  = i;
                        aq.opid = j;
                        aq.query = table + ":" + ops[j];
                        lookups.addLast( aq );
                    } // if-else "sequence"
                }// for j : indexes
            }// if "long enough"
        }// for i : 0 .. argument.length-1
        
        return lookups;
    }
    
}
