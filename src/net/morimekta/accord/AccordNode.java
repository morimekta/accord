/*
 * Created on May 2, 2005
 */
package net.morimekta.accord;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedList;

import net.morimekta.accord.Lookup.QueryResult;
import net.morimekta.accord.tables.LookupTable;
import net.morimekta.net.Message;
import net.morimekta.net.MessageService;
import net.morimekta.net.MessageSocket;
import net.morimekta.net.Location;
import net.morimekta.util.index.Index;
import net.morimekta.util.std.Log;
import net.morimekta.util.std.Log.Level;

/**
 * TODO: Make Type Descrption.
 * 
 * @author Stein Eldar Johnsen
 */
public class AccordNode {
    private static Conf conf; // => to non-static ???
    /*
     * 
     */
    private Log         log;
    private LookupTable table;
    private MessageSocket socket;
    /*
     * 
     */
    private IAmAlive    iamalive;
    private Lookup      lookup;
    private Membership  membership;
    private Stabilizer  stabilizer;
    
    /**
     * Creates an AccordNode on a random port.
     *
     */
    public AccordNode() {
        this(-1);
    }
    
    /**
     * Creates an AccordNode on the local port port.
     * 
     * @param port
     *    Prefferred port numbrt of the Node.
     */
    public AccordNode(int port) {
        try {
            socket = new MessageSocket(port>=0?port:Conf.port);
            log    = new Log(socket.getLocation().toString()+".log");
            log.setLoggingLevel(Conf.logging);
            log.setVerboseLevel(Conf.verbose);
            table  = new LookupTable(socket.getLocation());
            // if table should load from file/state, do it here.
            lookup = new Lookup(log, table, socket);
            table.setLog(log);
            table.setSocket(socket);
            table.setLookup(lookup);
            
            membership = new Membership(log, table, lookup, socket);
            stabilizer = new Stabilizer(log, table, lookup, socket);
            iamalive   = new IAmAlive  (log, table, membership, socket);
            
            socket.register(iamalive);
            socket.register(membership);
            socket.register(lookup);
            
            stabilizer.start();
            iamalive.start();
            
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    /**
     * 
     * @param logging 
     * @see net.morimekta.util.std.Log#setLoggingLevel(net.morimekta.util.std.Log.Level)
     */
    public void setLoggingLevel(Level logging) {
        log.setLoggingLevel(logging);
    }

    /* (non-Javadoc)
     * @see net.morimekta.util.std.Log#setVerboseLevel(net.morimekta.util.std.Log.Level)
     */
    public void setVerboseLevel(Level verbose) {
        log.setVerboseLevel(verbose);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.Lookup#lookup(net.morimekta.util.index.Index)
     */
    public Location lookup(Index idx) throws SocketTimeoutException, IllegalArgumentException {
        return lookup.lookup(idx);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.Lookup#lookup(net.morimekta.util.index.Index, java.lang.String)
     */
    public Location lookup(Index idx, String opts) throws IllegalArgumentException, SocketTimeoutException {
        return lookup.lookup(idx, opts);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.Lookup#lookup(net.morimekta.util.index.Index, java.lang.String, net.morimekta.net.Location)
     */
    public Location lookup(Index idx, String opts, Location ask) throws IllegalArgumentException, SocketTimeoutException {
        return lookup.lookup(idx, opts, ask);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.Lookup#lookup_table(net.morimekta.net.Location, java.lang.String, java.lang.String)
     */
    public Location lookup_table(Location ask, String tabref, String opts) throws SocketTimeoutException, IllegalArgumentException {
        return lookup.lookup_table(ask, tabref, opts);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.Lookup#lookup_tables(net.morimekta.net.Location, java.lang.String, java.lang.String)
     */
    public LinkedList<QueryResult> lookup_tables(Location ask, String queryline, String options) throws SocketTimeoutException, IllegalArgumentException {
        return lookup.lookup_tables(ask, queryline, options);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.Membership#connect(net.morimekta.net.Location)
     */
    public boolean connect(Location to) {
        return membership.connect(to);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.Membership#disconnect()
     */
    public boolean disconnect() {
        return membership.disconnect();
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#freeTicket(int)
     */
    public synchronized boolean freeTicket(int ticket) {
        return socket.freeTicket(ticket);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#freeTickets()
     */
    public synchronized int freeTickets() {
        return socket.freeTickets();
    }

    /**
     * Gets the Location to the local node.
     * 
     * @return Location to the local node.
     * @see net.morimekta.net.MessageSocket#getLocation()
     */
    public Location getLocation() {
        return socket.getLocation();
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#getMaxPacketSize()
     */
    public int getMaxPacketSize() {
        return socket.getMaxPacketSize();
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#getMessageTimeout()
     */
    public int getMessageTimeout() {
        return socket.getMessageTimeout();
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#getService(java.lang.String)
     */
    public synchronized MessageService getService(String name) {
        return socket.getService(name);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#ping(net.morimekta.net.Location, long, int)
     */
    public long ping(Location to, long timeout, int tries) {
        return socket.ping(to, timeout, tries);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#receive(int)
     */
    public Message receive(int _ticket) throws SocketTimeoutException {
        return socket.receive(_ticket);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#receive(int, long)
     */
    public Message receive(int ticket, long timeout) throws SocketTimeoutException {
        return socket.receive(ticket, timeout);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#register(net.morimekta.net.MessageService)
     */
    public synchronized boolean register(MessageService service) {
        return socket.register(service);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#requestTicket()
     */
    public synchronized int requestTicket() throws IndexOutOfBoundsException {
        return socket.requestTicket();
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#send(net.morimekta.net.Location, net.morimekta.net.Message)
     */
    public boolean send(Location to, Message msg) {
        return socket.send(to, msg);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#setMaxPacketSize(int)
     */
    public void setMaxPacketSize(int _max_packet_size) {
        socket.setMaxPacketSize(_max_packet_size);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#setMessageTimeout(int)
     */
    public void setMessageTimeout(int to) {
        socket.setMessageTimeout(to);
    }

    /* (non-Javadoc)
     * @see net.morimekta.net.MessageSocket#unregister(java.lang.String)
     */
    public synchronized MessageService unregister(String name) {
        return socket.unregister(name);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.Stabilizer#is_stable()
     */
    public boolean is_stable() {
        return stabilizer.is_stable();
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.tables.LookupTable#contains(net.morimekta.net.Location)
     */
    public boolean contains(Location host) {
        return table.contains(host);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.tables.LookupTable#getLocation(net.morimekta.net.Location)
     */
    public Location getLocation(Location hl) {
        return table.getLocation(hl);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.tables.LookupTable#owner_of(net.morimekta.util.index.Index)
     */
    public Location owner_of(Index index) {
        return table.owner_of(index);
    }

    /* (non-Javadoc)
     * @see net.morimekta.accord.tables.LookupTable#table_at(java.lang.String)
     */
    public Location table_at(String table_idx) {
        return table.table_at(table_idx);
    }
    
    @Override
    public String toString() {
        return socket.toString();
    }
    
}
