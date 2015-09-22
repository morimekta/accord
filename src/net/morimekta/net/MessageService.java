package net.morimekta.net;

/**
 * General service for ServiceSocket. For external use call
 * <b> my_socket.register(my_service) </b>
 * to make available. Services dependent on the fix-new messages
 * must be registered _before_ the node is connected to the ring.
 * 
 * @author Stein Eldar
 */
public interface MessageService {
    /**
     * Invoke the service with message msg.
     * @param msg message to send to service.
     */
    public void   invoke(Message msg);
    /**
     * Returns the name of the service. This is used for registering and
     *  service identification in ServiceSocket.
     * @return name of service.
     */
    public String getServiceName();
}
