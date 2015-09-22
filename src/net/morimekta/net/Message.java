package net.morimekta.net;

import java.io.IOException;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.util.Arrays;

import net.morimekta.util.std.STD;

/**
 * Message contains a general message with an attached
 * sequence number, and original sender, enabling SCL to
 * foreward messages without forgetting who sent the first
 * message.
 * 
 * @author Stein Eldar Johnsen
 * 
 */
public class Message implements Comparable<Message>, Serializable {
    /**
     * Serializable version UID.
     */
    private static final long serialVersionUID = 3256438092897400885L;
    /**
     * Options byte is 8 bits in which various optiops can be set.
     * The option for a ping message. (last bit set to 1)
     */
    public static final int   PING         =  1   ; //  ... 0001 // this is a ping message.
    /**
     * Option for a pong message. (second last bit set to 1).
     */
    public static final int   PONG         =  1<<1; //  ... 0010 // this is a pong reply.
    
    /**
     * Header size. Not accounting for operation string length.
     */
    public static final int   header_size  =  4;
    
    /**
     * Ticket of the sending host.
     */
    transient protected int         fromTicket; // senders return ticket number.
    
    /**
     * Ticket of the receiving host.
     */
    transient protected int         toTicket;   // receivers Ticket number for the request.
    
    /**
     * Options int, using | to add, & to check.
     */
    transient protected int         opts;       // message options...
    
    /**
     * Operation string, optional.
     */
    transient protected String      operation;
    
    /**
     * Message body.
     */
    transient protected byte[]      message;
    transient protected String      messageString = null;
    
    /**
     * Link to the sender of the message.
     *  Serializable.
     */
    protected Location sender;
    
    /**
     * Message Header Structure:
     *<ul>
     *<li> ft     = from ticket (ticket of sending message-socket)</li>
     *<li> tt     = to ticket (ticket of receiving message-socket)</li>
     *<li> op     = options, all negative option values are reserved ServiceSocket...</li>
     *<li> ol     = operation length.</li>
     *</ul> 
     *<br>
     *<code><pre>
     * [ft][tt][op][ol]
     * [(operation)... 
     *   ...  message ]
     *</pre></code>
     *
     * Serializable.
     */
    private byte[]         data;    // the fullsize message.
    
    /**
     * Create a message for Serializeable.
     *
     */
    private Message() {
        // "ignore" for now... data not yet read.
    }
    
    /**
     * Creates a clone (copy) of given Message.
     * 
     * @param msg
     *    Message to copy.
     */
    public Message( Message msg ) {
        this(msg.fromTicket, msg.toTicket, msg.opts, msg.operation, msg.message );
        sender = msg.sender;
    }
    
    /**
     * Create an empty message (no operation and no message).
     * 
     * @param from_ticket
     *    Source ticket number.
     * @param to_ticket
     *    Destination ticket number.
     * @param options
     *    Options (most likely socket management operaiton number).
     */
    public Message(
            int    from_ticket,
            int    to_ticket,
            int    options ) {
        this(from_ticket,to_ticket,options,null,(byte[])null);
    }
    
    /**
     * Create a new Ticket.
     * 
     * @param from_ticket
     *    Source / origin ticket number.
     * @param to_ticket
     *    Destination / receiver ticket number.
     * @param options
     *    Options.
     * @param operation
     *    Operation string (if appliccable).
     * @param message
     *    Message string.
     */
    public Message(
            int    from_ticket,
            int    to_ticket,
            int    options,
            String operation,
            String message) {
        this(from_ticket, to_ticket, options, operation, message.getBytes());
    }
    
    /**
     * Create a new Message.
     * 
     * @param from_ticket
     *    Source ticket number.
     * @param to_ticket
     *    Destination ticket number.
     * @param options
     *    Mesasge Options.
     * @param operation
     *    Operation String (if appliccable).
     * @param message
     *    Message data.
     */
    public Message(
            int    from_ticket,
            int    to_ticket,
            int    options,
            String operation,
            byte[] message ) {
        this.fromTicket = from_ticket;
        this.toTicket   = to_ticket;
        this.opts       = options;
        this.operation  = operation;
        this.message    = message;
        
        if( message != null )
            data       = new byte[message.length+header_size+(operation!=null?operation.length():0)];
        else
            data       = new byte[4];
        
        data[0] =  STD.int2byte(from_ticket);
        data[1] =  STD.int2byte(to_ticket);
        data[2] =  STD.int2byte(options);
        data[3] =
            ( operation != null ) ?
            STD.int2byte(operation.getBytes().length) :
            0 ;
        // read from operation and message.
        if( operation != null ) {
            STD.strcpy(operation.getBytes(), data, header_size);
        }
        if( message != null )
            STD.strcpy( message, data, header_size+(operation==null?0:operation.getBytes().length));
        
        // local sender ...
        sender = null;
        if( message != null )
            messageString = new String(message);
    }
    
    /**
     * Creates a message from an incoming Datagram Packet. For MessageSocket only.
     * 
     * @param in
     *    Packet to create message from.
     */
    protected Message(DatagramPacket in) {
        int  p_len = in.getLength();
        // sender of packet.
        // content
        data       = new byte[p_len];
        STD.strcpy(in.getData(), data);
        /*
         * [ft][tt][op][ol]  0 -  3
         * [  operation      4 - 
         *    message ... ]    - header_size + op_len + msg_len -1
         */
        fromTicket = STD.byte2int(data[0]);
        toTicket   = STD.byte2int(data[1]);
        opts       = STD.byte2int(data[2]);
        int ol     = STD.byte2int(data[3]);
        if( ol > 0 ) {
            operation = new String(STD.substr(data,  header_size, ol));
        } else operation = null;
        message       = STD.substr(data, header_size+ol, data.length-(header_size+ol));
        messageString = new String(message);
    }
    
    /**
     * Compare Message to another Message.
     * @param o Object to compare to.
     * @return >0 if this is greater, 0 if equal and negative otherwise.
     */
    public int  compareTo(Message o){
        return STD.strcmp(data, o.data);
    }
    
    /**
     * Checks if message is identical to another object.
     * @param o Object to check against.
     * @return 
     *     true if equivalent.
     */
    public boolean equals(Object  o){
        if( o.getClass().isAssignableFrom(this.getClass()))
            return equals((Message) o);
        else
            return false;
    }
    
    /**
     * Checks if message is identical to another message.
     * @param m 
     *   Mesage to compare to.
     * @return
     *   True if the message origin, operation and message are equal.
     */
    public boolean equals(Message m){
        return compareTo(m) == 0;
    }
    
    /**
     * Creates a string represenation of Message<br>
     * <b>Note:</b> This generates a multiline string, and cannot be used for
     * rebuilding the message.
     * @return 
     *     String of Message.
     */
    public String toString(){
        String   ret = "Message[sender="+sender;
        ret         += ",ticket{from="+fromTicket+",to="+toTicket;
        ret         += "},options="+opts+"]";
        return ret;
    }
    
    /**
     * Returns hashCode of message content (not including metadata).
     * @return 
     *     Hash code value.
     */
    public int    hashCode(){
        return Arrays.hashCode(data);
    }

    /**
     * @return Returns the packet data.
     */
    public byte[] getData() {
        return data;
    }
    
    /**
     * gets the size of the header of the message in bytes
     * @return bytes in message header.
     */
    public static int getHeaderSize(){
        return header_size;
    }
    
    /**
     * @return Returns the sender's ticket number.
     */
    public int getFromTicket() {
        return fromTicket;
    }
    
    /**
     * @return Returns the receiver's ticket number.
     */
    public int getToTicket() {
        return toTicket;
    }
    
	/**
	 * Sets the receiver's ticket number.
     * @param _t ticket number.
	 */
    public void setToTicket(int _t){
    	toTicket = _t;
        STD.strcpy(STD.i2a(toTicket, 1), data, 1);
    }
    
    /**
     * Sets the sender's ticket..
     * @param _t ticket number.
     */
    public void setFromTicket(int _t){
        fromTicket = _t;
        STD.strcpy(STD.i2a(fromTicket, 1), data, 0);
    }
    
    /**
     * @return Returns the Options int.
     */
	public int getOptions() {
		return opts;
	}
	
    /**
     * @return the DatagramPakcet representing the Message.
     */
    public DatagramPacket getPacket() {
        return new DatagramPacket(data, data.length);
    }
    
	/**
	 * @return Returns the sender.
	 */
	public Location getSender() {
		return sender;
	}
    
    /**
     * Get the operation.
     * 
     * @return
     *    Operation String.
     */
    public String getOperation() {
        return operation;
    }
    /**
     * Get he message data.
     * 
     * @return
     *    Message data byte-array.
     */
    public byte[] getMessageBytes() {
        return message;
    }
    
    /**
     * Get the message String.
     * 
     * @return
     *    String of the message.
     */
    public String getMessage() {
        return messageString;
    }
    /**
     * Clone the message.
     * @return 
     *  Clone of this message.
     */
    protected Message clone() {
        return new Message(this);
    }
    
    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        /*
         * Reads:
         *  - sender.
         *  - data.
         */
        in.defaultReadObject();
        /*
         * [ft][tt][op][ol]  0 -  3
         * [  operation      4 - 
         *    message ... ]    - header_size + op_len + msg_len -1
         */
        fromTicket = STD.byte2int(data[0]);
        toTicket   = STD.byte2int(data[1]);
        opts       = STD.byte2int(data[2]);
        int ol     = STD.byte2int(data[3]);
        if( ol > 0 ) {
            operation = new String(STD.substr(data,  header_size, ol));
        } else operation = null;
        message    = STD.substr(data, header_size+ol, data.length-(header_size+ol));
        messageString = new String(message);
    }
}
