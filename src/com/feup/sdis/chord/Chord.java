package com.feup.sdis.chord;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.feup.sdis.actions.BSDispatcher;
import com.feup.sdis.messages.Status;
import com.feup.sdis.messages.requests.BatchRequest;
import com.feup.sdis.messages.requests.Request;
import com.feup.sdis.messages.requests.chord.*;
import com.feup.sdis.messages.responses.BatchResponse;
import com.feup.sdis.messages.responses.chord.*;
import com.feup.sdis.model.ChunkTransfer;
import com.feup.sdis.model.PeerInfo;
import com.feup.sdis.model.Store;
import com.feup.sdis.model.StoredChunkInfo;
import com.feup.sdis.peer.MessageHandler;
import com.feup.sdis.peer.Peer;


public class Chord {

    // Constants
    private static final BigInteger B = BigInteger.ONE.shiftLeft(64); // 2^64
    private static final BigInteger L = BigInteger.valueOf(Long.MAX_VALUE);
    private static final boolean DEBUG_MODE = true;
    private static final int FINGER_TABLE_SIZE = 8;
    private static final int FIX_FINGERS_INTERVAL_MS = 500;
    private static final int STABILIZE_INTERVAL_MS = 500;
    private static final int CHECK_PREDECESSOR_INTERVAL_MS = 500;
    private static final int SUCCESSOR_LIST_SIZE = 3;
    private static final ScheduledThreadPoolExecutor periodicThreadPool = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(3);

    public static Chord chordInstance;
    private final AtomicReferenceArray<SocketAddress> fingerTable;
    private final AtomicReferenceArray<SocketAddress> successorList;
    private final UUID[] stepValues;
    private final AtomicReference<SocketAddress> self;
    private final AtomicReference<SocketAddress> predecessor = new AtomicReference<>();
    private int next = 1;
    private boolean initialized = false;

    // create
    public Chord(SocketAddress self) {
        this.self = new AtomicReference<>(self);
        final var fingerTable = new SocketAddress[FINGER_TABLE_SIZE];
        final var successorList = new SocketAddress[SUCCESSOR_LIST_SIZE];
        this.stepValues = new UUID[FINGER_TABLE_SIZE];
        Arrays.fill(fingerTable, this.self.get());
        Arrays.fill(successorList, this.self.get());
        this.fingerTable = new AtomicReferenceArray<>(fingerTable);
        this.successorList = new AtomicReferenceArray<>(successorList);
        this.initKeyLookupSteps();

        self.setPeerID(Chord.normalizeToSize(self.getPeerID(), FINGER_TABLE_SIZE));
    }

    // join
    public Chord(SocketAddress self, SocketAddress node) throws Exception {
        this(self);

        node.setPeerID(Chord.normalizeToSize(node.getPeerID(), FINGER_TABLE_SIZE));

        ClosestPrecedingResponse res = MessageHandler.sendMessage(new ClosestPrecedingRequest(self), node);

        if (res == null || res.getStatus() == Status.ERROR)
            throw new Exception("Could not create chord peer");

        this.setSuccessor(res.getAddress());
    }

    public AtomicReferenceArray<SocketAddress> getSuccessorList(){

        return this.successorList;
    }

    public SocketAddress getSuccessor() {

        return this.fingerTable.get(0);
    }

    public void shutdown(){

        periodicThreadPool.shutdown();
    }

    private synchronized void setSuccessor(SocketAddress newSuccessor) {

        if (DEBUG_MODE)
            System.out.println("> CHORD: A Successor updated to " + newSuccessor);
        this.fingerTable.set(0, newSuccessor);
        this.successorList.set(0, newSuccessor);
    }

    private SocketAddress getBestMatch(AtomicReferenceArray<SocketAddress> arr, UUID key){

        for (int i = arr.length() - 1; i >= 0; i--)
            if (this.betweenTwoKeys(self.get().getPeerID(), key, arr.get(i).getPeerID(), false, false))
                return arr.get(i);
            
        return null;
    }


    public synchronized SocketAddress closestPrecedingNode(UUID key) {

        SocketAddress bestMatchFingerTable = getBestMatch(this.fingerTable, key);
        SocketAddress bestMatchSuccTable = getBestMatch(this.successorList, key);

        // Check successor list

        if(bestMatchFingerTable == null ||  bestMatchSuccTable == null)
            return self.get();

        // Return best match
        return compareDistanceToKey(bestMatchFingerTable.getPeerID(), bestMatchSuccTable.getPeerID(), key) <= 0 ? bestMatchFingerTable : bestMatchSuccTable;
    }

    public SocketAddress lookup(String chunkID, int repDegree) {

        return this.findSuccessor(generateKey(chunkID, repDegree));
    }

    public UUID generateKey(String chunkID, int repDegree) {
        return normalizeToSize(UUID.nameUUIDFromBytes(StoredChunkInfo.getChunkID(chunkID, repDegree).getBytes()), FINGER_TABLE_SIZE);
    }

    private SocketAddress queryPeersForSuccessorOf(UUID key) {

        // Ask the closest match to find key's successor
        // If the designated peer does not answer find the next closest match

        while (true) {
            SocketAddress cpn = this.closestPrecedingNode(key);

            if(cpn.equals(self.get()))
                break;

            FindSuccessorResponse res = MessageHandler.sendMessage(new FindSuccessorRequest(key), cpn);

            if ((res != null) && (res.getStatus() != Status.ERROR))
                return res.getAddress();

            for(int i = 0; i < FINGER_TABLE_SIZE; i++)
                if(this.fingerTable.get(i).equals(cpn))
                    this.fingerTable.set(i, self.get());
            
            if(DEBUG_MODE)
                System.out.println("> CHORD: find successor failed, trying again.");
        }

        if (DEBUG_MODE)
            System.out.println("> CHORD: find successor failed, could not recover.");

        return self.get();
    }

    public SocketAddress findSuccessor(UUID key) {

        // The current peer is the closest preceding node from key
        if (this.betweenTwoKeys(this.self.get().getPeerID(), this.getSuccessor().getPeerID(), key, false, true))
            return this.getSuccessor();

        return this.queryPeersForSuccessorOf(key);
    }

    private BatchResponse querySuccessorForStabilization() {

        BatchResponse batchResponses = null;

        for(int i = 0; i< this.successorList.length(); i++){

            // Send a batch-request containing a perceived predecessor and r-list request
            GetPredecessorRequest getPredReq = new GetPredecessorRequest();
            ReconcileSuccessorListRequest recSucReq = new ReconcileSuccessorListRequest();
            Request[] requestList = {getPredReq, recSucReq};
            batchResponses = MessageHandler.sendMessage(new BatchRequest(requestList), this.getSuccessor());

            // Check for errors on the responses
            if (batchResponses == null || batchResponses.getStatus() == Status.ERROR) {

                if(i != this.successorList.length() - 1){

                    this.setSuccessor(this.successorList.get(i + 1));

                    if (this.DEBUG_MODE)
                        System.out.println("> CHORD: Stabilization failed. Trying next successor from list.");
                }
                else{
                    this.setSuccessor(self.get());
                }
            } else {
                break;
            }
        }

        return batchResponses;
    }

    private void stabilize() {

        if (this.getSuccessor() == this.self.get()) {

            // Successor is self
            SocketAddress successorsPerceivedPredecessorAddr = this.predecessor.get();

            if (successorsPerceivedPredecessorAddr != null) {

                // If the successor is self, and the predecessor is a different peer, the successor should now be that peer
                this.setSuccessor(successorsPerceivedPredecessorAddr);

            }

            return;
        }

        BatchResponse batchResponses = this.querySuccessorForStabilization();

        if (batchResponses == null) {

            if (DEBUG_MODE)
                System.out.println("> CHORD: Stabilization failed. Could not recover.");

            return;
        }

        GetPredecessorResponse successorsPerceivedPredecessor = (GetPredecessorResponse) batchResponses.getResponses()[0];
        ReconcileSuccessorListResponse successorsSuccList = (ReconcileSuccessorListResponse) batchResponses.getResponses()[1];

        // Update successor list
        AtomicReferenceArray<SocketAddress> newSuccList = successorsSuccList.getSuccessorList();
        for(int i = 1; i < SUCCESSOR_LIST_SIZE -1; i++){
            this.successorList.getAndSet(i, newSuccList.get(i));
        }

        SocketAddress successorsPerceivedPredecessorAddr = successorsPerceivedPredecessor.getAddress();

        // Successor has no predecessor, notify him
        if (successorsPerceivedPredecessorAddr == null) {
            NotifyResponse res = MessageHandler.sendMessage(new NotifyRequest(self.get()), this.getSuccessor());

            if ((res == null || res.getStatus() == Status.ERROR) && DEBUG_MODE)
                System.out.println("> CHORD: Stabilization failed (notify on successor).");

            return;
        }

        // No need to notify self
        UUID successorsPerceivedPredecessorID = successorsPerceivedPredecessorAddr.getPeerID();
        if(successorsPerceivedPredecessorID.equals(self.get().getPeerID()))
            return;

        // Update the successor
        if (this.betweenTwoKeys(this.self.get().getPeerID(), this.getSuccessor().getPeerID(), successorsPerceivedPredecessorID,
                false, false)) {

            this.setSuccessor(successorsPerceivedPredecessorAddr);

        }

        NotifyResponse res = MessageHandler.sendMessage(new NotifyRequest(self.get()), this.getSuccessor());

        if ((res == null || res.getStatus() == Status.ERROR) && DEBUG_MODE)
            System.out.println("> CHORD: Stabilization failed (notify on successor ).");
    }

    public void retrieveOwnedChunks(SocketAddress peer, UUID predecessor) {
        final TransferChunksResponse transferChunksResponse = MessageHandler.sendMessage(
                new TransferChunksRequest(this.self.get().getPeerID(), predecessor), peer);

        if (transferChunksResponse == null) {
            System.out.println("Error retrieving chunks from " + peer);
            return;
        }

        for (ChunkTransfer chunkTransfer : transferChunksResponse.getChunkTransfers()) {
            BSDispatcher.servicePool.execute(() -> {
                if (Store.instance().incrementSpace(chunkTransfer.getChunkSize())) {
                    final TakeChunkResponse response = MessageHandler.sendMessage(chunkTransfer.getRequest(), peer);
                    if (response == null || response.getStatus() != Status.SUCCESS) {
                        System.out.println("TransferChunk: Error in response!");
                    }

                    final StoredChunkInfo chunkInfo = new StoredChunkInfo(response.getFileID(), response.getDesiredReplDegree(), response.getChunkNo(),
                            response.getData().length, response.getnChunks(), response.getOriginalFileName(), response.getInitiatorPeer());

                    try {
                        chunkInfo.storeFile(response.getData());
                        Store.instance().getStoredFiles().put(chunkInfo.getChunkID(), chunkInfo);
                        Store.instance().getReplCount().addNewID(chunkInfo.getChunkID(),
                                new PeerInfo(Peer.addressInfo, chunkTransfer.getChunkSize()), response.getReplNo());
                        System.out.println("TransferChunk: sucessfully retrieved " + chunkInfo.getChunkID() + " from " + peer);
                    } catch (IOException e) {
                        System.out.println("TransferChunk: Failed to store chunk");
                    }
                }

            });
        }

        transferChunksResponse.getRedirects().forEach((redirect) ->{
                    System.out.println("Got new redirect to " + StoredChunkInfo.getChunkID(redirect.getKey(), redirect.getValue().getKey()) + " for peer " + redirect.getValue().getValue().getAddress());
                    Store.instance().getReplCount().addNewID(redirect.getKey(), redirect.getValue().getValue(), redirect.getValue().getKey());
                });
    }

    public boolean notify(SocketAddress newPred) {

        UUID candidateID = newPred.getPeerID();

        if (this.predecessor.get() == null || this.betweenTwoKeys(this.predecessor.get().getPeerID(), this.self.get().getPeerID(),
                candidateID, false, false)) {

            if (DEBUG_MODE)
                System.out.println("> CHORD: Predecessor updated to " + newPred);
            this.predecessor.set(newPred);

            if (!initialized) {
                initialized = true;
                this.retrieveOwnedChunks(successorList.get(0), predecessor.get().getPeerID());
                this.retrieveOwnedChunks(predecessor.get(), predecessor.get().getPeerID());
            }

            return true;
        }

        return false;
    }

    private void fixFingers() {

        UUID neededID = this.stepValues[next];
        SocketAddress newFinger = this.findSuccessor(neededID);

        if (this.fingerTable.get(next).equals(newFinger)) {

            next = (next + 1) % FINGER_TABLE_SIZE;
            return;
        }
        if (DEBUG_MODE)
            System.out.println("> CHORD: Fixing fingers was " + this.fingerTable.get(next) + " (next = " + next + ")");

        this.fingerTable.set(next, newFinger);

        if (DEBUG_MODE)
            System.out.println("> CHORD: Added " + newFinger);

        next = (next + 1) % FINGER_TABLE_SIZE;

    }


    public SocketAddress getPredecessor() {
        return predecessor.get();
    }

    private void setPredecessor(SocketAddress predecessor) {
        this.predecessor.set(predecessor);
    }

    public void checkPredecessor() {

        if(this.predecessor.get() == null)
            return;

        PingResponse res = MessageHandler.sendMessage(new PingRequest(), predecessor.get());

        if (res == null || res.getStatus().equals(Status.ERROR)) {

            this.predecessor.set(null);
            if(DEBUG_MODE)
                System.out.println("> CHORD: Predecessor failed");
        }

    }

    public void initThreads() {

        Runnable t1 = () -> {
            try {
                Chord.chordInstance.stabilize();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        };
        Runnable t2 = () -> {
            try {
                Chord.chordInstance.fixFingers();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        };
        Runnable t3 = () -> {
            try {
                Chord.chordInstance.checkPredecessor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        };

        // The threads are started with a delay to avoid them running at the same time
        periodicThreadPool.scheduleAtFixedRate(t1, 0, STABILIZE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        periodicThreadPool.scheduleAtFixedRate(t2, 200, FIX_FINGERS_INTERVAL_MS, TimeUnit.MILLISECONDS);
        periodicThreadPool.scheduleAtFixedRate(t3, 400, CHECK_PREDECESSOR_INTERVAL_MS, TimeUnit.MILLISECONDS);

    }

    private void initKeyLookupSteps() {

        BigInteger maxV = new BigInteger(String.valueOf(2)).pow(FINGER_TABLE_SIZE);

        for (int i = 0; i < FINGER_TABLE_SIZE; i++) {
            int step = (int) Math.pow(2, i);
            BigInteger nextVal = Chord.convertToBigInteger(this.self.get().getPeerID());
            nextVal = nextVal.add(new BigInteger(String.valueOf(step + ""))).mod(maxV);
            UUID neededID = Chord.convertFromBigInteger(nextVal);
            this.stepValues[i] = neededID;
        }
    }

    /*
     * HELPER FUNCTIONS
     */

    public boolean betweenTwoKeys(UUID lowerBound, UUID upperBound, UUID key, boolean closedLeft, boolean closedRight) {

        if ((closedLeft && key.equals(lowerBound)) || (closedRight && key.equals(upperBound)))
            return true;

        if ((!closedLeft && key.equals(lowerBound)) || (!closedRight && key.equals(upperBound)))
            return false;

        // Whole circle is valid
        if (upperBound.equals(lowerBound) && !key.equals(lowerBound))
            return true;

        return (lowerBound.compareTo(upperBound) < 0) ? (lowerBound.compareTo(key) < 0) && (key.compareTo(upperBound) < 0)
                : !((upperBound.compareTo(key) < 0) && (key.compareTo(lowerBound) < 0));
    }

    public static int compareDistanceToKey(UUID a, UUID b, UUID c) {

        BigInteger aInt = convertToBigInteger(a);
        BigInteger bInt = convertToBigInteger(b);
        BigInteger cInt = convertToBigInteger(c);
        BigInteger acDif = aInt.subtract(cInt).abs();
        BigInteger bcDif = bInt.subtract(cInt).abs();

        return acDif.compareTo(bcDif);
    }


    public static BigInteger convertToBigInteger(UUID id) {
        BigInteger lo = BigInteger.valueOf(id.getLeastSignificantBits());
        BigInteger hi = BigInteger.valueOf(id.getMostSignificantBits());

        // If any of lo/hi parts is negative interpret as unsigned

        if (hi.signum() < 0)
            hi = hi.add(B);

        if (lo.signum() < 0)
            lo = lo.add(B);

        return lo.add(hi.multiply(B));
    }

    public static UUID convertFromBigInteger(BigInteger x) {
        BigInteger[] parts = x.divideAndRemainder(B);
        BigInteger hi = parts[0];
        BigInteger lo = parts[1];

        if (L.compareTo(lo) < 0)
            lo = lo.subtract(B);

        if (L.compareTo(hi) < 0)
            hi = hi.subtract(B);

        return new UUID(hi.longValueExact(), lo.longValueExact());
    }

    public static UUID normalizeToSize(UUID id, int bits) {
        BigInteger maxV = new BigInteger(String.valueOf(2));
        maxV = maxV.pow(bits);
        BigInteger val = Chord.convertToBigInteger(id);
        val = val.mod(maxV);

        return Chord.convertFromBigInteger(val);

    }

    public SocketAddress getSelf() {
        return self.get();
    }

    public String state() {

        StringBuilder message = new StringBuilder("Chord state: \n");
        message.append("  - Self" + "\n");
        message.append("  > ").append(self).append("\n");
        message.append("  - Predecessor" + "\n");
        message.append("  > ").append(predecessor).append("\n");
        message.append("  - Successor" + "\n");
        message.append("  > ").append(this.getSuccessor()).append("\n");
        message.append("  - Finger table" + "\n");
        for (int i = 0; i < this.fingerTable.length(); i++) {
            message.append("  > entry #").append(i).append(" - ").append(this.fingerTable.get(i)).append("\n");
        }
        message.append("  - Successor list" + "\n");
        for (int i = 0; i < this.successorList.length(); i++) {
            message.append("  > entry #").append(i).append(" - ").append(this.successorList.get(i)).append("\n");
        }


        return message.toString();
    }
}