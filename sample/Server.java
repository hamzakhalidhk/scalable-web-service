import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.Naming;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The Server class to connect with the cloud and launch and handle the main VM, 
 * front tiers and middle tiers.
 *
 * @author Hamza Khalid
 */
public class Server extends UnicastRemoteObject implements MainVMInterface {

    private static LinkedBlockingQueue<Cloud.FrontEndOps.Request> requestsBlockingQueue = new LinkedBlockingQueue<>();
    private static ConcurrentHashMap<Integer, Integer> tiersMap = new ConcurrentHashMap<>();
    
    private static int PORT;
    private static ServerLib SL;
    private static Registry registry;
    /**
    * Keeps the count of the total middle tier VMs.
    */
    private static int MIDDLE_TIERS = 1;
    /**
    * The maximum number of middle tiers allowed.
    */
    private static final int MAX_MIDDLE_TIERS = 10;
    /**
    * The registry host IP address.
    */
    private static final String REGISTRY_HOST = "//127.0.0.1:";
    /**
    * The main virtual machine identifier.
    */
    private static final int MAIN_VM = 1;
    /**
    * The front tier identifier.
    */
    private static final int FRONT_TIER = 1;
    /**
    * The middle tier identifier.
    */
    private static final int MIDDLE_TIER = 2;
    /**
    * The port number of the registry.
    */
    private static final int REGISTRY_PORT = 1357;
    /**
    * The maximum size a VM can have of the server load queue.
    */
    private static final int SL_QUEUE_MAX_SIZE = 3;
    /**
    * The maximum size of the centralized requests queue can have.
    */
    private static final int REQUESTS_QUEUE_MAX_SIZE = 7;
    /**
    * The time after which a middle tier is added (after the change is detected).
    */
    private static final int MIDTIER_ADD_FREQUENCY = 2;
    /**
    * The time after which a middle tiers is destroyed (after the change is detected).
    */
    private static final int MIDTIER_DESTROY_FREQUENCY = 3;
    /**
    * The interarrival cutoff time.
    */
    private static final int INTERARRIVAL_CUTOFF = 2900;
    /**
    * The time conversion factor used to convert nano seconds to milliseconds.
    */
    private static final double TIME_CONV_FACTOR = 1000000.0;

    public Server() throws RemoteException {
        super();
    }

    public static void main ( String args[] ) throws Exception, RemoteException {
        // Cloud class will start one instance of this Server intially [runs as separate process]
        // It starts another for every startVM call [each a seperate process]
        // Server will be provided 3 command line arguments
        if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
        
        // Get the VM id for this instance of Server.
        int myVMid = Integer.parseInt(args[2]);
        // Get the port number to use with RMI.
        PORT = Integer.parseInt(args[1]);

        // Initialize ServerLib.  Almost all server and cloud operations are done 
        // through the methods of this class.  Please refer to the html documentation in ../doc
        SL = new ServerLib(args[0], PORT);

        if (myVMid == MAIN_VM) {
            
            try {
                // Create an instance of Server for the main VM.
                Server mainVM = new Server();
                // Initialize tiersMap to keep track of front and middle tiers.
                tiersMap = new ConcurrentHashMap<>(); 
                // Create registry and bind main server to the registry.
                registry = LocateRegistry.createRegistry(1357);
                registry.bind(REGISTRY_HOST + PORT + "/" + 1, mainVM);

                // Enter the main server handler.
                mainServerHandler(mainVM);
                
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(0);
            } 

            
        } else { // If the current VM is not the main VM, it will either be a front tier or the middle tier VM.

            try {
                // Get the registry to look up the main VM
                Registry reg = LocateRegistry.getRegistry(REGISTRY_PORT);
                Server routineServer = new Server();
                MainVMInterface mainDataServer = (MainVMInterface) reg.lookup(REGISTRY_HOST + args[1] + "/1");

                // Get the tier type of a VM from the main VM and launch either the frontTierHandler or the 
                // middleTierHandler based on the tier type.
                int tierType = mainDataServer.getTierType(myVMid);
                if (tierType == FRONT_TIER) {
                    frontTierHandler(mainDataServer, myVMid);
                } else if (tierType == MIDDLE_TIER) {
                    middleTierHandler(mainDataServer, myVMid);
                }

            } catch (Exception e) {
                e.printStackTrace();
                System.exit(0);
            }

        }

    
    }

    /**
    * This method launches virtual machines (VMs) by scaling out the server load based on the input parameters.
    * @param mainServer the interface for the main server to which the VMs will be connected.
    * @param frontTiers the number of front tiers to be launched.
    * @param middleTiers the number of middle tiers to be launched.
    * @throws RemoteException if a communication-related exception occurs during the remote method call.
    */
    public void launchVMs(MainVMInterface mainServer, int frontTiers, int middleTiers) throws RemoteException {
        
        MIDDLE_TIERS += middleTiers;

        for (int i = 0; i < frontTiers; i++) {
            scaleOut(mainServer, 1);
        }

        if (MIDDLE_TIERS < MAX_MIDDLE_TIERS) {  
            for (int i = 0; i < middleTiers; i++) {
                scaleOut(mainServer, 2);
            }
        }       
        
    }

    /**
    * This method handles the main VM requests and launches initial VMs to handle traffic loads.
    * It also drops requests while initial VMs are booting, and enters the front tier handler when
    * the initial VMs are booted.
    * @param mainServer main VM that has managerial responsibilites to scale out or scale back 
    *                   the system.
    * @throws Exception if there is an error while processing requests.
    */
    public static void mainServerHandler(Server mainServer) throws Exception {
        
        // Get the start time t0 to calculate interarrival at the very beginning.
        long t0Time = System.nanoTime();
        // Scale out a middle tier right off the bat.
        scaleOut(mainServer, MIDDLE_TIER); 
        // Register with load balancer so client connections are sent to the main server.
        SL.register_frontend();
        
        // Accept the first connection, get the time t1, and calculate the time of the 
        // arrival of the first request.
        ServerLib.Handle h1 = SL.acceptConnection();
        long t1Time = System.nanoTime();

        double interarrivalRate = 0.0;
        // If the arrival of the request is too slow, parse and process this first request.
        if ((t1Time - t0Time)/ TIME_CONV_FACTOR > INTERARRIVAL_CUTOFF) {
            // Set the inter-arrival rate.
            interarrivalRate = (t1Time - t0Time)/ TIME_CONV_FACTOR;
            Cloud.FrontEndOps.Request r1 = SL.parseRequest(h1);
            SL.processRequest(r1);
        }

        // Now, accept the second connection, get the time t2, and calculate the time of the
        // arrival of the second request.
        ServerLib.Handle h2 = SL.acceptConnection();
        long t2Time = System.nanoTime();
        // If the interarrival is not set that means the request arrival rate is fast, and the
        // first request is not processed in the last if block.
        if (interarrivalRate == 0.0) {
            // Set the inter-arrival rate.
            interarrivalRate =  (t2Time - t1Time)/ TIME_CONV_FACTOR;
        }

        // Drop the first two requests used to calculate the initial inter-arrival rate of the 
        // requests (in case the arrival rate is fast -- above INTERARRIVAL_CUTOFF).
        if (interarrivalRate < INTERARRIVAL_CUTOFF) {
            SL.dropHead();
            SL.dropHead();

        } else { 
            // In case the arrival rate is fast -- below INTERARRIVAL_CUTOFF, let the main server
            // parse and process the requests while the initial VMs are Booting.
            Cloud.FrontEndOps.Request r2 = SL.parseRequest(h2);
            mainServer.addRequest(r2);
            
            // If the interarrival rate is too slow, we are assigning mix responsibilities (parse
            // and process) to the main VM while the initial VMs are booting.
            parseAndProcessRequests(mainServer);

            // Enter the front tier handler when the initial VMs are booted. This means that
            // that the mainVM will now only parse the requests and will stop processing requests.
            frontTierHandler(mainServer, FRONT_TIER);
        }
        
        // We will be at this point when the inter-arrival rate is below INTERARRIVAL_CUTOFF.
        // Launch initial VMs.
        launchInitialVMs(mainServer, interarrivalRate);
        
        // Drop requests while the initial VMs are booting.
        while (SL.getStatusVM(2) != Cloud.CloudOps.VMStatus.Running) { 
            if (interarrivalRate < SL_QUEUE_MAX_SIZE) {
                SL.dropHead();
            }
        }
        // Enter the front tier handler when the initial VMs are booted. This means that
        // that the mainVM will now only parse the requests and will stop processing requests.
        frontTierHandler(mainServer, FRONT_TIER);

    }

    /**
    * Parses and processes requests from queue at the main server, when the initial middle tier VMs are booting.
    * @param mainServer the server from which to fetch the requests to parse and process.
    * @throws Exception if there is an error while processing requests.
    */
    private static void parseAndProcessRequests(Server mainServer) throws Exception {

        while (SL.getStatusVM(MIDDLE_TIER) != Cloud.CloudOps.VMStatus.Running) { 
            if (mainServer.getQeueueLength() != 0) {
            Cloud.FrontEndOps.Request r = mainServer.getNextRequest();
                SL.processRequest(r);
            } else {
                mainServer.addRequest(SL.getNextRequest());
            }
        } 
    }

    /**
    * Launches initial VMs for the main server based on the specified interarrival rate.
    * @param mainServer the server from which to launch the initial VMs.
    * @param interarrivalRate the interarrival rate of requests.
    * @throws RemoteException if there is an error communicating with the server.
    */
    private static void launchInitialVMs(Server mainServer, double interarrivalRate) throws RemoteException {

        if (interarrivalRate <= 140) {
            mainServer.launchVMs(mainServer, 1, 7);
        } else if (interarrivalRate <= 350) {
            mainServer.launchVMs(mainServer, 0, 5);
        } else if (interarrivalRate <= 670) {
            mainServer.launchVMs(mainServer, 0, 3);
        } else {
            mainServer.launchVMs(mainServer, 0, 1);
        } 
    }

    /**
    * Scales out the system by starting a new VM and adding it to the specified tier in the map held by the main server.
    * @param mainServer the main server having the map to store the new VM information.
    * @param tierType the type of tier -- front tier or the middle tier. 
    * @throws RemoteException if there is an error communicating with the server.
    */
    public static void scaleOut(MainVMInterface mainServer, int tierType) throws RemoteException {
        int tierID = SL.startVM();
        mainServer.addTier(tierID, tierType);
    }

    /**
    * Registers a VM with the server, gets and parses requests, and puts the requests in the central
    * requests blocking queue.
    * 
    * @param mainDataServer the main server having the system's central requests blocking queue.
    * @param vmId An integer representing the ID of the virtual machine (VM) being handled.
    * @throws RemoteException if a remote communication error occurs.
    */
    public static void frontTierHandler(MainVMInterface mainDataServer, int vmId) throws RemoteException {

        // Registers the front-end tier with the Server Lib to start getting requests.
        SL.register_frontend();
        int count = 0;

        while (true) {
             // Drops the oldest request in the SL queue if the queue length exceeds the maximum size.
            while (SL.getQueueLength() > SL_QUEUE_MAX_SIZE) {
                SL.dropHead();
            }
            
            // Retrieves the length of the central request queue from the main data server.
            int queLen = (int) mainDataServer.getQeueueLength();

            // Launches a new middle-tier VM if the request queue on the main data server 
            // exceeds the maximum size.
            if (vmId == MAIN_VM && queLen >= REQUESTS_QUEUE_MAX_SIZE) {
                count++;
                if (count == MIDTIER_ADD_FREQUENCY) {
                    count = 0;
                    mainDataServer.launchVMs(mainDataServer, 0, 1);
                }
            }

            mainDataServer.addRequest(SL.getNextRequest());

        }

    }

    /**
    * Fetches the next request from the system's central request blocking queue and process it.
    * @param mainDataServer the main data server that has the central request blocking queue.
    * @param vmId the ID of the current middle tier virtual machine.
    * @throws RemoteException if there is an error in the remote method invocation.
    */
    public static void middleTierHandler(MainVMInterface mainDataServer, int vmId) throws RemoteException {

        int count = 0;

        while (true) {
            // Gets next request from the system's central request blocking queue. 
            Cloud.FrontEndOps.Request req = mainDataServer.getNextRequest();
            // If this VM doesn't receive anything MIDTIER_DESTROY_FREQUENCY (2 in 
            // this case) times in a row, scale back.
            if (req != null) {
                count = 0;
                // If it receives the request, process it.
                SL.processRequest(req);
            } else {
                count++;
                if (count == MIDTIER_DESTROY_FREQUENCY) {
                    // Destroying the VM
                    mainDataServer.destroyVM(vmId);
                    SL.endVM(vmId);
                    count = 0;
                }
            }

        }

    }

    /**
    * Removes the virtual machine with the given ID from the tiersMap.
    * @param vmId the ID of the virtual machine to be removed.
    * @throws RemoteException if there is an error in the remote method invocation.
    */
    public void destroyVM(int vmId) throws RemoteException {
        tiersMap.remove(vmId);
    } 

    /**
    * Retrieves the next request from the blocking queue, waiting for up to 600 milliseconds if necessary for a 
    * request to become available.
    * 
    * @return the next incoming request in the central request blocking queue, or null if no request is available 
    * within the allotted time.
    * @throws RemoteException if there is a communication-related issue during the method call.
    */
    public Cloud.FrontEndOps.Request getNextRequest() throws RemoteException {

        Cloud.FrontEndOps.Request request = null;
        try {
            // Retrieves the next request from the blocking queue
            request = requestsBlockingQueue.poll(600, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(0);
        }

        return request;
        
    }

    /**
    * Returns the length of the central requests blocking queue.
    * @return the length of the central requests blocking queue
    * @throws RemoteException if there is a communication error.
    */
    public long getQeueueLength() throws RemoteException { 
        return requestsBlockingQueue.size();
    }

    /**
    * Adds a request to the requests blocking queue.
    * @param request the request to be added
    * @throws RemoteException if there is a problem with the remote method invocation.
    */
    public void addRequest(Cloud.FrontEndOps.Request request) throws RemoteException {
        try {
            requestsBlockingQueue.put(request);
        } catch (Exception e) {
            e.printStackTrace();
        } 
        
    }

    /**
    * Returns the type of the tier associated with the given VM ID.
    * @param vmId the ID of the virtual machine whose tier type is to be returned
    * @return the type of the tier associated with the given VM ID, or -1 if the ID is not found
    * @throws RemoteException if a communication error occurs during the remote method invocation
    */
    public int getTierType(int vmId) throws RemoteException {
        if (tiersMap.containsKey(vmId)) {
            return tiersMap.get(vmId);
        } 
        return -1;
    }

    /**
    * Adds a new tier to the tiersMap with the given vmId and tierType.
    * @param vmId the identifier of the virtual machine
    * @param tierType the type of the tier to be added
    * @throws RemoteException if a remote communication error occurs during the method call
    */
    public void addTier(int vmId, int tierType) throws RemoteException {
        tiersMap.put(vmId, tierType);
    }

}

