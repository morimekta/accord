package net.morimekta.net;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;

import net.morimekta.util.index.Index;
import net.morimekta.util.index.SHA1Factory;
import net.morimekta.util.std.STD;

/**
 * General Location class. Contains the InetAddress and port number of a host, and
 * some practical methods needed for an overlay link (mostly converting to and
 * from strings and various forms of data), and simple indexing.
 * 
 * @author Stein Eldar Johnsen
 */
public class Location extends InetSocketAddress implements Comparable<Location>, Serializable {
    /**
     * Generated Serial Version UID. 
     */
    private static final long serialVersionUID = 3545234730439358008L;

    /**
     * InetAddress for the getLocalAddress method. Contains the ip-address externally
     * viewed to the local host.
     */
    private static ArrayList<InetAddress> localAddresses = null;

    /**
     * Known activity timestamp.
     */
    private long timestamp = 0;
    
    /**
     * Index of host in ring.
     */
    private Index index;

    /**
     * Prevent no-argument instantiation.
     *
     */
    private Location() {
        super(0);
    }
    /**
     * Copy Constructor.
     * 
     * @param host host to copy.
     */
    public Location(Location host) {
        super(host.getAddress(), host.getPort());
        index = host.index;
    }
    
    /**
     * Get the Index of the Location.
     * 
     * @return
     *    Index of the overlay link.
     */
    public final Index getIndex() {
        return index;
    }
    
    /**
     * Set the Index.
     * 
     * @param idx
     *     New Index of the link.
     */
    private final void setIndex(Index idx) {
        index = idx;
    }
    
    /**
     * The method that calculates the index
     * @throws UnknownHostException 
     *
     */
    private void calculateIndex() throws UnknownHostException {
        String tmp = toString();
        try {
            // Default is SHA1Index.
            if( getAddress() == null || isUnresolved() ) {
                throw new Exception("unable to resolve host");
            }
            setIndex(SHA1Factory.getInstance().getIndexOf(tmp));
        } catch( Exception e ) {
            // and we forwards the stack-trace so we dont looses the original ...
            UnknownHostException n = new UnknownHostException("unable to generate index from \""+tmp+"\"");
            n.initCause(e);
            
            throw n;
        }
    }

    /**
     * Create a HostLink from InetAddress and port number.
     * 
     * @param _addr
     *    InetAddress (IP) of host.
     * @param _port
     *    Port number to host/service.
     * @throws UnknownHostException 
     */
    public Location(InetAddress _addr, int _port) throws UnknownHostException {
        super(_addr, _port);
        calculateIndex();
    }
    
    /**
     * Create a Location from a host:port string.
     * 
     * @param host_port to host service.
     * @throws UnknownHostException 
     */
    public Location(String host_port) throws UnknownHostException {
        this(getHostnameFrom(host_port), getPortFrom(host_port));
    }
    
    /**
     * Create a Location from a host string and a port number.
     * 
     * @param host
     *     Host name or ip address in string format.
     * @param port
     *     Port number.
     * @throws UnknownHostException
     */
    public Location(String host, int port) throws UnknownHostException {
        super(host, port);
        if( getAddress() == null || isUnresolved() )
            throw new UnknownHostException("unable to resolve host");
        calculateIndex();
    }
    
    /**
     * Checks if the Location equals this Location.
     * 
     * @param loc Location to compare to.
     * @return true if equal, false otherwise.
     */
    public final boolean equals(Location loc) {
        return (super.equals((InetSocketAddress) loc));
    }
    
    /**
     * Compare the host to another HostLink.
     * 
     * @param loc HostLink to compare to.
     * @return >0 if this is "greater", 0 if equal, and negative otherwise.
     */
    public final int compareTo(Location loc) {
        int dA = STD.strcmp(loc.getAddress().getAddress(), getAddress().getAddress());
        return (dA == 0 ? getPort() - loc.getPort() : dA);
    }

    /**
     * make a String representation of the host. This string may be
     *  used both ways; so <code>new Location(ol.toString())</code> will be the
     *  same as <code>new Location(ol)</code>.
     * @return String representaiton of Location
     */
    public String toString() {
        if( getAddress() == null || isUnresolved() ) {
            throw new RuntimeException("Location has no valid address");
        } else {
            String[] tmp = getAddress().toString().split("/");
            return tmp[1]+":"+getPort();
        }
    }
    
    /**
     * Returns the InetSocketAddress toString() string.
     * 
     * @return
     *    Hostname/IP:port string.
     * @see InetSocketAddress#toString()
     */
    public final String toSocketString() {
        return super.toString();
    }
    
    /**
     * Parses the host:port string and fetches the host number part from the string. If there
     *  is more than one ':', use the first part as host and the second part as port, and ignore
     *  the rest. If there is no ':' treat the whole string as host. If the string is empty or
     *  only contains no "host part", it returns "0.0.0.0".
     * 
     * @param str
     *    Host:Port string.
     * @return
     *    Hostname or IP part of string.
     */
    private static final String getHostnameFrom(String str) {
        String[] tmp = str.split(":");
        if( tmp.length > 0 && tmp[0] != null ) return tmp[0];
        else                                   return "0.0.0.0";
    }
    
    /**
     * Parses the host:port string and fetches the port number part from the string. If there
     *  is more than one ':', use the first part as host and the second part as port, and ignore
     *  the rest.
     *  
     * @param str
     *    Host:Port string.
     * @return
     *    Port number generated from string.
     */
    private static final int getPortFrom(String str) {
        String[] tmp = str.split(":");
        int      ret = 0;
        if( tmp.length > 1 ) {
            try {
                ret = Integer.parseInt(tmp[1]);
            } catch( NumberFormatException e ) {
                // ignore ...
                ret = -1; // returns invalid port number !!!
            }
        }
        
        return ret;
    }
    
    /**
     * get the local ip address (as viewed from the outside).
     * 
     * @return InetAddress of localhost
     */
    public static final synchronized InetAddress getLocalAddress() {
        if (localAddresses == null) initializeLocalAddresses();
        return localAddresses.get(0);
    }

    /**
     * Initialize the list of local addresses to check "isLocalAddress" against.
     * 
     */
    private static synchronized final void initializeLocalAddresses() {
        try {
            ArrayList<InetAddress> addresses = new ArrayList<InetAddress>();
            
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();

                //System.out.println("NIC: " + nic.getName());
                Enumeration<InetAddress> ias = nic.getInetAddresses();
                
                while (ias.hasMoreElements()) {
                    InetAddress ia = ias.nextElement();
                    if (Inet4Address.class.isAssignableFrom(ia.getClass())) {
                        //System.out.println("\t- ip: " + ia.toString());
                        
                        if (ia.isLoopbackAddress()) {
                            // local ip address except "localhost" / "loopback".
                            addresses.add(ia);
                        } else {
                            addresses.add(0, ia); // keep all non-localhost ip-addresses  first...
                        }
                    }
                }
            }
            localAddresses = addresses;
        } catch (SocketException e) {
            System.err.println(e.getMessage());
        }
    }
    
    
    /**
     * Get the timestamp of the link.
     * @return
     *     Timestamp value.
     */
    public long getTimestamp() {
        return timestamp;
    }

    
    /**
     * Set the timestamp of the link to a new value.
     * @param _timestamp
     */
    public void setTimestamp(long _timestamp) {
        timestamp = _timestamp;
    }

}
