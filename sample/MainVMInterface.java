
import java.rmi.Remote;
import java.rmi.RemoteException;

/**
* The MainVMInterface interface defines the methods that the Server class
* should implement to interact with the Cloud system. This interface extends 
* the Remote interface to support remote method invocation.
*
* @author Hamza Khalid
*/
interface MainVMInterface extends Remote { 
    /**
     * Returns the next request from the system's central requests blocking queue 
     * in the main VM.
     *
     * @return the next request from the requests blocking queue
     * @throws RemoteException if a communication error occurs
     */
    public Cloud.FrontEndOps.Request getNextRequest() throws RemoteException;
    /**
     * Adds a new request to the system's central requests blocking queue 
     *
     * @param request the request to add
     * @throws RemoteException if a communication error occurs
     */
    public void addRequest(Cloud.FrontEndOps.Request request) throws RemoteException;
    /**
     * Returns the tier type (front tier or middle tier) of a given VM.
     *
     * @param vmId the ID of the VM
     * @return the tier type of the VM, or -1 if the VM ID is not found
     * @throws RemoteException if a communication error occurs
     */
    public int getTierType(int vmId) throws RemoteException;
    /**
     * Adds a new VM to the system with the specified tier type.
     *
     * @param vmId the ID of the VM to add
     * @param tierType the tier type of the VM to add
     * @throws RemoteException if a communication error occurs
     */
    public void addTier(int vmId, int tierType) throws RemoteException;
    /**
     * Returns the current length of the system's central requests blocking queue 
     *
     * @return the length of the request queue
     * @throws RemoteException if a communication error occurs
     */
    public long getQeueueLength() throws RemoteException;
    /**
     * Destroys a VM with the specified ID.
     *
     * @param vmId the ID of the VM to destroy
     * @throws RemoteException if a communication error occurs
     */
    public void destroyVM(int vmId) throws RemoteException;
    /**
     * Launches new VMs with the specified number of front and middle tiers.
     *
     * @param mainServer the main server to launch the VMs from
     * @param frontTiers the number of front tiers to launch
     * @param middleTiers the number of middle tiers to launch
     * @throws RemoteException if a communication error occurs
     */
    public void launchVMs(MainVMInterface mainServer, int frontTiers, int middleTiers) throws RemoteException;
    
}