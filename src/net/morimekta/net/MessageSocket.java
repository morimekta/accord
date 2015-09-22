package net.morimekta.net;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ConcurrentModificationException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * General Messaging Socket.
 * 
 * Hides the notion of "ordinary" DatagramPackets.
 * but packs the messages in the Message class, which contains more information
 * needed to make safer transmission.
 * <br><br>
 * Uses also MessageServices to invoke services listening to the socket.
 * 
 * @author Stein Eldar Johnsen
 */
public class MessageSocket {
    /**
     * Ticket space of 0..255.
     */
    public static final int TICKET_COUNT        = 256;
    /**
     * Default Message timeout in milliseconds.
     * Set to 3*60*1000 millis = 3 minutes.
     */
    private int             msg_timeout         = 3*60*1000;
    /**
     * Default maximum packet size, default to 1024 bytes.
     */
    private int             max_packet_size     = 1024;
    /**
     * Default maximum thread count. Defaults to 10.
     */
    private int             max_thread_count    = 10;
    
    /**
     * DatagramSocket object.
     */
    private DatagramSocket  socket;
    
    /**
     * The local locaiton, used for self reference.
     */
    private Location        location;
        
    private Thread[] ticket_map = new Thread[TICKET_COUNT];
    private int      last_ticket = 0;
    
    private static Message ping = new Message(0, 0, Message.PING);
    private static Message pong = new Message(0, 0, Message.PONG);
    
    /**
     * Operate switch.
     */
    private volatile boolean operate = true;
    /**
     * Running socket thread (daemon).
     */
    private Thread           running = new Thread() {
        public void run(){
            try {
                receiveLoop();
            } catch( Exception e ) {
                System.err.println("Unhandled Exception in MessageSocket: "+e.getMessage());
                e.printStackTrace();
            }
        }
        
    };
    
    // HashMap is faster than TreeMap when there is a large amount of services (20+).
    private Hashtable<String,MessageService> services = new Hashtable<String,MessageService>();
    // Message queue, linked list have better insert and remove effectivity, using Iterator.
    private LinkedList<Message>              queue    = new LinkedList<Message>();
    
    private long  invoke_count = 0;
    
    private class ServiceInvoker extends Thread {
        public MessageService service;
        public Message        message;
        
        public void run() {
            try {
                /* DEBUG *
                System.out.println(" --- "+getHost()+" SERVICE "+service.getServiceName()+" STARTED");
                /* DEBUG */
                service.invoke(message);
            } catch( Exception e ) {
                System.err.println("Exception: "+e.getMessage());
                e.printStackTrace();
                // handle anything?
            } finally {
                // clean up anything ?
            }
        }
    }
    
    /*
     * THREAD CONTROL
     */
    /**
     * The main receive-message loop.
     *
     */
    private void receiveLoop() {
        DatagramPacket p = new DatagramPacket(new byte[max_packet_size], max_packet_size);
        Message msg;
        String  op;
        MessageService serv;
        ServiceInvoker inv;
        byte[]         buffer = new byte[max_packet_size];
        while( operate ) {
            try {
                if( max_packet_size != buffer.length ) 
                    buffer = new byte[max_packet_size];
                
                socket.receive(p);
                
                msg = new Message(p);
                
                // mark for activity ! We have received a valid message.
                location.setTimestamp(System.currentTimeMillis());
                
                // no need to make new packet if timeout... 
                p = new DatagramPacket(new byte[max_packet_size], max_packet_size);
                
                if( ( ( msg.opts & Message.PING ) > 0 ) && msg.fromTicket > 0 ) {
                    /* DEBUG *
                    System.out.println(" --- "+getHost()+" PONG TO "+msg.getSender());
                    /* DEBUG */ 
                    
                    pong.setToTicket(msg.fromTicket);
                    send( msg.sender, pong );
                } else if( msg.toTicket > 0 ) {
                    /* DEBUG *
                    System.out.println(" --- "+getHost()+" MESSAGE TO "+msg.getSender()+"-"+msg.toTicket);
                    /* DEBUG */ 
                    // prevent ticket mess...
                    synchronized ( this ) {
                        // only receive requested messages! This prevents "phony" messages
                        // from previous sessions to interfere with new sessions.
                        if  (   msg.toTicket < ticket_map.length &&
                                ticket_map[msg.toTicket] != null   ) {
                            queue.addLast( msg );
                            notifyAll();
                        }
                    }
                } else {
                    if( ( op = msg.operation ) != null ) {
                        /* DEBUG *
                        System.out.println(" --- "+getHost()+" SERVICE "+msg.operation);
                        /* DEBUG */ 
                        // a message 
                        serv = services.get(op);
                        
                        if( serv != null ) {
                            inv = new ServiceInvoker();
                            inv.message = msg;
                            inv.service = serv;
                            inv.setName(getLocation()+"."+serv.getServiceName()+"#"+(++invoke_count));
                            inv.start();
                        } else {
                            /* DEBUG *
                            System.out.println(" --- "+getHost()+" NO SUCH SERVICE SERVICE "+msg.operation);
                            /* DEBUG */ 
                        }
                    } else {
                        /* DEBUG *
                        System.out.println(" --- "+getHost()+" INVALID MESSAGE ");
                        /* DEBUG */ 
                    }
                }
                
                System.gc(); // is not so slow ...
            } catch (SocketTimeoutException e) {
                // loop...
            } catch (UnknownHostException e ) {
                System.err.println("MessageSocket: Unable to generate Message:");
                System.err.println("             : "+e.getMessage());
            } catch (IOException e) {
                System.err.println("MessageSocket: Unable to receive Message.");
            }
        }
    }
    
    /**
     * Creates a bare socket with a vacant port number.
     * @throws SocketException 
     */
    public MessageSocket() throws SocketException {
        this(-1);
    }
    /**
     * Creates a socket with a pre-assignes port number.
     * @param port desired port number of socket.
     * @throws SocketException 
     */
    public MessageSocket(int port) throws SocketException {
        if( port >= 0 ){
            socket = new DatagramSocket(port);
        } else {
            socket = new DatagramSocket();
        }
        
        ticket_map[0] = running;
        try {
            location = new Location(Location.getLocalAddress(), socket.getLocalPort());
        } catch( Exception e ) {
            System.err.println("Unhandled Exception in Location<init>(addr,port):");
            System.err.println(e.getClass().getSimpleName()+": "+e.getMessage());
            e.printStackTrace();
            
            SocketException err =  new SocketException("Unable to generate socket location");
            err.initCause(e);
            throw err;
        }
        running.setDaemon(true);
        running.setName(toString()+".Socket");
        running.start();
    }
	
    /*
     * SENDING AND RECEIVING
     */
    /**
     * Receive a message with the given ticket number, using the default timeout.
     * 
     * @param ticket
     *   Ticket number of requested message.
     * @return
     *   Message received.
     * @throws SocketTimeoutException
     *   Thrown if timeout limit exceeded.
     * @see MessageSocket#receive(int, Location, long)
     */
    public Message receive(int ticket) throws SocketTimeoutException {
        return receive(ticket, null, msg_timeout);
    }
    
    /**
     * Receive a message on local ticket and from a specified sender.
     * 
     * @param ticket
     *   Ticket to receive message on.
     * @param from
     *   Sender of Message.
     * @return
     *   Message received.
     * @throws SocketTimeoutException
     * @see MessageSocket#receive(int, Location, long)
     */
    public Message receive(int ticket, Location from) throws SocketTimeoutException {
        return receive(ticket, from, msg_timeout);
    }
    
    /**
     * Receive a message on ticket with a specified timeout.
     * 
     * @param ticket
     *   Ticket to receive on.
     * @param timeout
     *   Timeout in milliseconds.
     * @return
     *   Message received.
     * @throws SocketTimeoutException
     * @see MessageSocket#receive(int, Location, long)
     */
    public Message receive(int ticket, long timeout) throws SocketTimeoutException {
        return receive(ticket, null, timeout);
    }
    
    /**
     * Receive a message with the given ticket number and with the given receive timeout.
     * 
     * @param ticket
     *     Number to request, if 0 accept all not requested.
     * @param from 
     *     Host to receive message from only. If set to null, ignore.
     * @param timeout
     *     Timeout of receive request, negative never gives up, 0 never waits (checks
     *     if incoming message and returns ot throws exception).
     * @return
     *      Message received.
     * @throws SocketTimeoutException
     *      If timeout limit exceeded.
     */
    public synchronized Message receive(int ticket, Location from, long timeout) throws SocketTimeoutException {
        long to, rest;
        
        if( timeout < 0 ) to = -1;
        else              to = System.currentTimeMillis() + timeout ;
        
        for( ;; ){
            try{
                for( Message msg : queue ) {
                    if( msg.getToTicket() == ticket &&
                            (from == null || from.equals(msg.sender)) ) {
                        queue.remove(msg);
                        return msg;
                    }
                }
                
                // not found...
                
                if( to > 0 && to < System.currentTimeMillis() ) {
                    throw new SocketTimeoutException("message receive timeout");
                } else if( to > 0 ) {
                    rest = to - System.currentTimeMillis();
                    if( rest > 0 ) {
                        wait(rest);
                    } else {
                        throw new SocketTimeoutException("message receive timeout");
                    }
                } else {
                    // no timeout limit.
                    wait();
                }
            } catch( InterruptedException e ){
                // loop.
            } catch( ConcurrentModificationException e ) {
                // loop.
            }
        }
    }
    
    /**
     * Send a message to a host.
     * 
     * @param to
     *     Location to recieve the packet.
     * @param msg
     *     Message to send.
     * @return
     *     True if no send exception thrown.
     */
    public boolean send(Location to, Message msg){
        if( msg == null || to == null ) return false;
        try{
            if( location.equals(to) && msg.getToTicket() == 0 ) {
                synchronized ( this ) {
                    queue.addLast(msg);
                    notifyAll();
                    return true;
                }
            }
            /*
             * Evades "cannot assign requested address" when address is
             *  localhost by "remote" ip.
             */
        	InetAddress ip;
            if( to.getAddress().isAnyLocalAddress() ) {
                ip = InetAddress.getLocalHost(); // loopback address...
            } else {
                ip = to.getAddress();
            }
            
            DatagramPacket p = msg.getPacket();
            p.setAddress(ip);
            p.setPort(to.getPort());
            socket.send(p);
            /* DEBUG *
            System.out.println(" --- "+getHost()+" SENDING MESSAGE TO "+ ip+":"+to.getPort());
            /* DEBUG */ 
            return true;
        } catch( IOException e ){
            //assert false : "MessageSocket.send(): "+e.getMessage();
            return false;
        }
    }
    
    /*
     * TICKETS
     */
    /**
     * Request a ticket number.
     * @return assigned ticket number. Or -1 if there are none available.
     * @throws IndexOutOfBoundsException 
     */
    public synchronized int requestTicket() throws IndexOutOfBoundsException {
        last_ticket++; // ignore the last one...
        int ticket = 0;
        for( int i = 0; i < TICKET_COUNT; i++ ) {
            if( ticket_map[((i+last_ticket)%TICKET_COUNT)] == null ) {
                ticket = ((i+last_ticket)%TICKET_COUNT);
                last_ticket = ticket;
                break;
            }
        }
        
        if( ticket_map[ticket] != null ) {
            throw new IndexOutOfBoundsException("out of free ticket numbers");
        }
        
        // ticket is OK.
        ticket_map[ticket] = Thread.currentThread();
        return ticket;
    }
    /**
     * Free a certain ticket for further requests.
     * 
     * @param ticket number to free.
     * @return true if freed, false otherwise.
     */
    public synchronized boolean freeTicket(int ticket){
        if( Thread.currentThread().equals(ticket_map[ticket]) ){
            ticket_map[ticket] = null;
            
            // clear away "stale" messages. This prevents a lot of
            // phony stuff later on.
            Iterator<Message> iter = queue.iterator();
            while( iter.hasNext() ) {
                Message msg = iter.next();
                if( msg.getToTicket() == ticket ) iter.remove();
            }
            return true;
        } else
            return false;
    }
    
    /**
     * Free all tockets held by this thread.
     * 
     * Note that this method is very insecure if run in nesten methods where some calling
     *  methods have requested ticket. In this case those tickets will _also_ be freed!
     *  
     * @return
     *   Number of free'd tickets.
     */
    public synchronized int     freeTickets() {
        Thread tr = Thread.currentThread();
        int    ret = 0;
        // never free ticket [0] !!!
        for( int i = 1; i < ticket_map.length; i++ ) {
            if( tr.equals(ticket_map[i]) ) {
                freeTicket(i);
                ret++;
            }
        }
        return ret;
    }
    
    
    
    /*
     * PING PONG
     */
    /**
     * Send a ping, and wait for reply.
     * 
     * @param to
     *     Host to ping
     * @param timeout 
     *     Total ping timeout.
     * @param tries 
     *     Number of messages to send in time span.
     * @return milliseconds from send to reply received.
     */
    public long ping(Location to, long timeout, int tries) {
        /* System.err.println("Initializing ping."); /**/
        Message        msg;
        int            ticket = 0;
        Message        response = null;
        long           start, ptimeout, mtimeout;
        // you need to have a special implementation
        //  of the server side of the ping...
        try{
            ticket = requestTicket();
            msg = new Message(ping);
            msg.setFromTicket(ticket);

            start = System.currentTimeMillis();
            ptimeout = start + timeout;
            mtimeout = timeout/tries;
            
            send(to, msg);
            while( response == null ) {
                try {
                    response = receive(ticket, mtimeout);
                    if( response.sender.equals(to) ) {
                        if( (response.opts & Message.PONG) > 0 ) return System.currentTimeMillis() - start;
                    } else response = null; // loop.
                } catch ( SocketTimeoutException e) {
                    if( ptimeout < System.currentTimeMillis() ) {
                        return -1; // unreachable.
                    }
                    send(to, msg);
                }
            }
            return -1; // some error has broken the loop.
        } finally {
            if( ticket > 0 ) freeTicket(ticket);
        }
    }
    
    /*
     * SERVICE INTERFACE
     */
    /**
     * Tries to register a service. If a service of that name already exists, abort.
     * 
     * @param service
     *      MessageService to register.
     * @return
     *      True if registration was successfull, false otherwise.
     */
    public synchronized boolean register(MessageService service){
        if( services.containsKey(service.getServiceName()) ){
            return false;
        } else {
            services.put(service.getServiceName(), service);
            return true;
        }
    }
    
    /**
     * Retrieves a MessageService from the services list.
     * 
     * @param name
     *    Name of service to retrieve.
     * @return
     *    MessageService retrieved.
     */
    public synchronized MessageService getService(String name){
        return (MessageService) services.get(name);
    }
    
    
    /**
     * Unregister given service.
     * 
     * @param name
     *    Name of service to unregister.
     * @return
     *    MessageService unregisteres or null if not in list.
     */
    public synchronized MessageService unregister(String name){
        return (MessageService) services.remove(name);
    }
    

    /*
     * ATTRIBUTES
     */
    /**
     * Get an Location representing the socket.
     * 
     * @return
     *     Location representing socket.
     */
    public Location getLocation(){
        return location;
    }
    
    /**
     * Set new max packet size limit.
     * 
     * @param _max_packet_size
     *    New size.
     */
    public void setMaxPacketSize(int _max_packet_size) {
        max_packet_size = _max_packet_size;
    }
    
    /**
     * Maximum packet size.
     * 
     * @return
     *    Size of the largest packet size.
     */
    public int  getMaxPacketSize() {
        return max_packet_size;
    }
    
    /**
     * Set the default message timeout.
     * 
     * @param to
     *    New timeout value (ms).
     */
    public void setMessageTimeout(int to) {
        msg_timeout = to;
    }
    
    /**
     * Get the message timeout.
     * 
     * @return
     *    Default message timeout (ms).
     */
    public int getMessageTimeout() {
        return msg_timeout;
    }
    
    /*
     * STRING AND PRINTING
     */
    /**
     * @return String describing socket (ip:port).
     */
    public String toString(){
        return getLocation().toString();
    }
    
    /**
     * Closes the socket.
     */
    public void close() {
        System.err.println(" - NO CLOSING OPERATION! -");
        operate = false;
        if( socket.isBound() ) {
            socket.close();
        }
    }
    
    @Override
    protected void finalize() {
        close();
    }
}
