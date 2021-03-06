package com.feup.sdis.messages.requests;

import com.feup.sdis.chord.Chord;
import com.feup.sdis.chord.SocketAddress;
import com.feup.sdis.messages.Status;
import com.feup.sdis.messages.responses.DeleteResponse;
import com.feup.sdis.messages.responses.Response;
import com.feup.sdis.model.PeerInfo;
import com.feup.sdis.model.Store;
import com.feup.sdis.model.StoredChunkInfo;
import com.feup.sdis.peer.Constants;
import com.feup.sdis.peer.MessageHandler;
import com.feup.sdis.peer.Peer;

import java.io.File;

public class DeleteRequest extends Request {
    protected final String fileID;
    protected final int chunkNo;
    protected final int replNo;

    public DeleteRequest(String fileID, int chunkNo, int replNo) {
        this.fileID = fileID;
        this.chunkNo = chunkNo;
        this.replNo = replNo;
    }

    protected DeleteResponse deleteChunk() {
        final Store store = Store.instance();
        final String chunkID = StoredChunkInfo.getChunkID(fileID, chunkNo);
        System.out.println("> DELETE: peer " + Peer.addressInfo + " received request (" + fileID + "," + chunkNo + "," + replNo + ")");

        final PeerInfo chunkOwnerInfo = store.getReplCount().getPeerAddress(chunkID, replNo);

        if (chunkOwnerInfo == null) {
            System.out.println("> DELETE: redirect address is null for chunk " + chunkNo + " of file " + fileID + ", replNo = " + replNo);
            return new DeleteResponse(Status.FILE_NOT_FOUND, fileID, chunkNo, replNo);
        }

        final SocketAddress chunkOwner = chunkOwnerInfo.getAddress();
        if (!store.getStoredFiles().containsKey(chunkID) || !chunkOwner.equals(Peer.addressInfo)) { // must delete in redirects

            System.out.println("> DELETE: Redirect to " + chunkOwner + " - " + chunkID + " rep " + replNo);

            final DeleteRequest deleteRequest = new DeleteRequest(fileID, chunkNo, replNo);
            final DeleteResponse deleteResponse = MessageHandler.sendMessage(deleteRequest, Chord.chordInstance.getSuccessor());

            if (deleteResponse == null) {
                System.out.println("> DELETE: Received null for chunk " + chunkID + ", replNo=" + replNo);
                return new DeleteResponse(Status.CONNECTION_ERROR, fileID, chunkNo, replNo);
            }

            Status responseStatus = deleteResponse.getStatus();
            if (responseStatus != Status.SUCCESS) {
                System.out.println("> DELETE: Error deleting file " + chunkID + ", replNo=" + replNo + ". Received " + responseStatus);
                return new DeleteResponse(responseStatus, fileID, chunkNo, replNo);
            }

            System.out.println("> DELETE: Deleted chunk " + chunkID + ", replNo=" + replNo);
            store.getReplCount().removeRepDegree(chunkID, replNo);
            return new DeleteResponse(Status.SUCCESS, fileID, chunkNo, replNo);
        }

        // this peer has the chunk
        final StoredChunkInfo storedChunkInfo = store.getStoredFiles().get(chunkID);
        store.getReplCount().removeRepDegree(chunkID, replNo);
        if(!storedChunkInfo.pendingDeletion())
            store.decrementSpace(storedChunkInfo.getChunkSize());

        return null;
    }

    @Override
    public Response handle() {
        final Response errorResponse = this.deleteChunk();
        if (errorResponse != null)
            return errorResponse;
        final String chunkID = StoredChunkInfo.getChunkID(fileID, chunkNo);
        Store.instance().getStoredFiles().remove(chunkID);
        Status returnStatus = Status.SUCCESS;

        final File fileToDelete = new File(Constants.backupFolder + chunkID);
        if (!fileToDelete.exists()) {
            System.out.println("> DELETE: Could not find chunk " + chunkID + " on disk");
            returnStatus = Status.FILE_NOT_FOUND;
        }
        else if (!fileToDelete.delete()) {
            System.out.println("> DELETE: Failed to delete chunk " + chunkID);
            returnStatus = Status.FILE_NOT_DELETED;
        }

        return new DeleteResponse(returnStatus, fileID, chunkNo, replNo);
    }

    @Override
    public SocketAddress getConnection() {
        return null;
    }

    @Override
    public String toString() {
        return "DeleteRequest{" +
                "fileID='" + fileID + '\'' +
                ", chunkNo=" + chunkNo +
                ", replNo=" + replNo +
                '}';
    }
}
